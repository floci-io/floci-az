package io.floci.az.services.arm;

import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmScopeKind;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmTypeRegistry;
import io.floci.az.core.arm.LocationPolicy;
import io.floci.az.core.directory.ProviderRegistrations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Providers API of a subscription, for every namespace floci-az serves: {@code Providers_List},
 * {@code Providers_Get}, {@code Providers_Register} and {@code Providers_Unregister}.
 *
 * <p>The namespaces and their resource types come from the {@link ArmTypeRegistry}, so a namespace is listed
 * exactly when a service declares it, with Azure's api-versions for each type, newest first. A namespace
 * floci-az does not serve is left out of the list, which azurerm reads as nothing to register, and any other
 * request for it is {@code 404 InvalidResourceNamespace}.
 *
 * <p>Wire contract, Resources spec 2025-04-01, which the 2024-11-01 the az CLI pins and the 2022-09-01 azurerm
 * pins agree with: every operation answers 200 with a {@code Provider} body, which the list wraps in
 * {@code value}. Register and unregister answer the final state at once, never {@code Registering}, and are
 * idempotent. Each type lists the regions it can be created in by display name, as Azure does: every public
 * region for a regional top-level type, {@code global} for a global one, and none for a child or a type with no
 * location of its own. Azure's {@code defaultApiVersion}, {@code apiProfiles}, {@code zoneMappings} and
 * {@code authorizations} are optional and have no local source, so they are omitted.
 */
@ApplicationScoped
public class ProviderService {

    static final String REGISTRATION_REQUIRED = "RegistrationRequired";
    static final String REGISTRATION_FREE = "RegistrationFree";

    private final ArmTypeRegistry types;
    private final ProviderRegistrations registrations;

    @Inject
    public ProviderService(ArmTypeRegistry types, ProviderRegistrations registrations) {
        this.types = types;
        this.registrations = registrations;
    }

    /** {@code GET subscriptions/{sub}/providers}. */
    public Response list(String subscriptionId) {
        return Response.ok(Map.of("value", types.surfaces().stream()
            .map(surface -> body(subscriptionId, surface)).toList())).build();
    }

    /** {@code GET subscriptions/{sub}/providers/{namespace}}. */
    public Response get(String subscriptionId, String namespace) {
        return types.surface(namespace)
            .map(surface -> Response.ok(body(subscriptionId, surface)).build())
            .orElseGet(() -> ArmErrors.invalidResourceNamespace(namespace));
    }

    /** {@code POST subscriptions/{sub}/providers/{namespace}/register}. */
    public Response register(String subscriptionId, String namespace) {
        return types.surface(namespace).map(surface -> {
            registrations.register(subscriptionId, surface.namespace());
            return Response.ok(body(subscriptionId, surface)).build();
        }).orElseGet(() -> ArmErrors.invalidResourceNamespace(namespace));
    }

    /** {@code POST subscriptions/{sub}/providers/{namespace}/unregister}. */
    public Response unregister(String subscriptionId, String namespace) {
        return types.surface(namespace).map(surface -> {
            if (!ProviderRegistrations.isRegistrationFree(surface.namespace())) {
                registrations.unregister(subscriptionId, surface.namespace());
            }
            return Response.ok(body(subscriptionId, surface)).build();
        }).orElseGet(() -> ArmErrors.invalidResourceNamespace(namespace));
    }

    /** The {@code Provider} body, in the key order Azure records it. */
    private Map<String, Object> body(String subscriptionId, ArmSurface surface) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "/subscriptions/" + subscriptionId + "/providers/" + surface.namespace());
        body.put("namespace", surface.namespace());
        body.put("resourceTypes", surface.types().stream().map(this::resourceType).toList());
        body.put("registrationState", registrations.state(subscriptionId, surface.namespace()).wireName());
        body.put("registrationPolicy", ProviderRegistrations.isRegistrationFree(surface.namespace())
            ? REGISTRATION_FREE : REGISTRATION_REQUIRED);
        return body;
    }

    private Map<String, Object> resourceType(ArmResourceType type) {
        List<String> locations = locations(type);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("resourceType", type.typePath());
        entry.put("locations", locations);
        entry.put("apiVersions", types.apiVersions(type));
        entry.put("capabilities", locations.isEmpty() ? "None" : "SupportsTags, SupportsLocation");
        return entry;
    }

    private static List<String> locations(ArmResourceType type) {
        boolean ownLocation = !type.isChild() && type.scope() != ArmScopeKind.EXTENSION
            && type.scope() != ArmScopeKind.TENANT && type.location() != LocationPolicy.NONE;
        if (!ownLocation) {
            return List.of();
        }
        if (type.location() == LocationPolicy.GLOBAL) {
            return List.of("global");
        }
        return AzureLocations.ALL.stream().map(AzureLocations.Location::displayName).toList();
    }
}
