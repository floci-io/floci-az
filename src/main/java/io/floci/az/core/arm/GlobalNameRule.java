package io.floci.az.core.arm;

import io.floci.az.core.cloud.CloudCatalog;
import io.floci.az.core.cloud.CloudProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The gate rule for global DNS names ({@link GlobalName}): a PUT of a resource whose type declares one reserves the
 * name in the {@link GlobalNameRegistry} before its handler runs, and is answered with the type's own conflict when
 * another resource owns it. The reservation is committed when the handler answers 2xx and released otherwise; a
 * 2xx DELETE releases the name. Every other request passes: a read or delete through another resource's scope
 * misses that resource's key and is the handler's 404, as before.
 *
 * <p>Only the request that made a reservation releases it on failure: the gate hands each exit hook the call
 * its entry saw, so the reservation's token is kept per call between the two.
 *
 * <p>A path that names no resource group is never a create of a resource-group type, so it passes too, and its
 * handler answers it.
 */
@ApplicationScoped
public class GlobalNameRule implements ArmRequestRule {

    /** Position in the gate: after the location rule and the name syntax rule. */
    public static final int ORDER = 7;

    private final ArmTypeRegistry types;
    private final GlobalNameRegistry names;
    /** The token of each call in flight that reserved a name, by identity: two calls can be equal records. */
    private final Map<ArmCall, Long> reservations = Collections.synchronizedMap(new IdentityHashMap<>());

    @Inject
    public GlobalNameRule(ArmTypeRegistry types, GlobalNameRegistry names) {
        this.types = types;
        this.names = names;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ArmVerdict enter(ArmCall call) {
        Optional<GlobalName> global = globalName(call, "PUT");
        if (global.isEmpty()) {
            return ArmVerdict.proceed();
        }
        GlobalNameRegistry.Reservation reservation =
            names.reserve(cloud(call), global.get().suffix(), call.id().name(), call.id());
        if (reservation.outcome() == GlobalNameRegistry.Outcome.RESERVED) {
            reservations.put(call, reservation.token());
        }
        if (reservation.outcome() != GlobalNameRegistry.Outcome.TAKEN) {
            return ArmVerdict.proceed();
        }
        return ArmVerdict.respond(conflict(global.get(), call, reservation.owner()));
    }

    @Override
    public void exit(ArmCall call, Response response) {
        boolean succeeded = response != null && response.getStatus() / 100 == 2;
        Long token = reservations.remove(call);
        globalName(call, "PUT").ifPresent(global -> {
            if (succeeded) {
                names.commit(cloud(call), global.suffix(), call.id().name(), call.id());
            } else if (token != null) {
                names.releasePending(cloud(call), global.suffix(), call.id().name(), token);
            }
        });
        if (succeeded) {
            globalName(call, "DELETE").ifPresent(global ->
                names.release(cloud(call), global.suffix(), call.id().name(), call.id()));
        }
    }

    /** The global name of the resource {@code call} addresses with {@code method}, when its type declares one. */
    private Optional<GlobalName> globalName(ArmCall call, String method) {
        if (!method.equals(call.method()) || call.type() == null || call.id() == null
                || call.id().subscriptionId() == null || call.id().name() == null || call.id().isCollection()) {
            return Optional.empty();
        }
        return types.type(call.type())
            .filter(type -> type.globalName().declared())
            .filter(type -> type.scope() != ArmScopeKind.RESOURCE_GROUP || call.id().resourceGroup() != null)
            .filter(type -> type.typePath().equalsIgnoreCase(lastTypes(call.id())))
            .map(ArmResourceType::globalName);
    }

    /** The id's type path below its namespace, so an action on a resource ({@code .../listKeys}) does not count. */
    private static String lastTypes(ArmResourceId id) {
        return String.join("/", id.types());
    }

    private static CloudProfile cloud(ArmCall call) {
        return call.scope() == null ? CloudCatalog.forNameOrPublic(CloudCatalog.PUBLIC_CLOUD) : call.scope().cloud();
    }

    private static Response conflict(GlobalName global, ArmCall call, ArmResourceId owner) {
        boolean sameSubscription = owner.subscriptionId() != null
            && owner.subscriptionId().equalsIgnoreCase(call.id().subscriptionId());
        if (sameSubscription && global.sameSubscriptionCode() != null) {
            return ArmErrors.error(global.conflictStatus(), global.sameSubscriptionCode(),
                String.format(global.sameSubscriptionMessage(), call.id().name()));
        }
        return ArmErrors.error(global.conflictStatus(), global.conflictCode(),
            String.format(global.conflictMessage(), call.id().name()));
    }
}
