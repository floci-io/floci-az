package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.cosmos.engine.CosmosLifecycleManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AzureServiceRegistry {

    private final List<AzureServiceHandler> handlers;
    private final EmulatorConfig config;
    private final CosmosLifecycleManager cosmosLifecycleManager;

    /**
     * serviceType → handler, memoised. Handler selection is a pure function of the service type (the
     * sole multi-type handler, {@code CosmosEngineHandler}, matches on the string alone), so the first
     * answer for a given type is the answer forever. Keys are bounded: every service type originates in
     * a routing table or in the blob/queue storage fallback, never in raw request data.
     */
    private final Map<String, Optional<AzureServiceHandler>> handlerByServiceType = new ConcurrentHashMap<>();

    @Inject
    AzureServiceRegistry(Instance<AzureServiceHandler> all, EmulatorConfig config,
                         CosmosLifecycleManager cosmosLifecycleManager) {
        this.config = config;
        this.cosmosLifecycleManager = cosmosLifecycleManager;

        List<AzureServiceHandler> discovered = new ArrayList<>();
        for (AzureServiceHandler h : all) {
            discovered.add(h);
        }
        this.handlers = List.copyOf(discovered);
    }

    public boolean isEnabled(String serviceType) {
        return switch (serviceType) {
            case "blob"      -> config.services().blob().enabled();
            case "queue"     -> config.services().queue().enabled();
            case "table"     -> config.services().table().enabled();
            case "functions"  -> config.services().functions().enabled();
            case "appconfig"  -> config.services().appConfig().enabled();
            case "cosmos"     -> config.services().cosmos().enabled();
            case "keyvault"   -> config.services().keyVault().enabled();
            case "eventhub"   -> config.services().eventHub().enabled();
            case "sql"        -> config.services().sql().enabled();
            case "postgres"   -> config.services().postgres().enabled();
            case "servicebus" -> config.services().serviceBus().enabled();
            case "aks"        -> config.services().aks().enabled();
            case "vm"         -> config.services().vm().enabled();
            case "apim"       -> config.services().apim().enabled();
            case "redis"      -> config.services().redis().enabled();
            case "acr"        -> config.services().acr().enabled();
            case "email"      -> config.services().email().enabled();
            case "monitor"    -> config.services().monitor().enabled();
            case "entra"      -> config.services().entra().enabled();
            case "arm"        -> config.services().arm().enabled();
            case "eventgrid"  -> config.services().eventGrid().enabled();
            case "managedidentity" -> config.services().managedIdentity().enabled();
            case "cosmos-engine" -> config.services().cosmos().enabled();
            case "cosmos-mongo", "cosmos-table", "cosmos-cassandra",
                 "cosmos-gremlin", "cosmos-postgresql", "cosmos-nosql" ->
                config.services().cosmos().enabled() &&
                cosmosLifecycleManager.isEnabled(serviceType);
            default           -> true;

        };
    }

    /** True when some handler implements {@code serviceType}, regardless of whether it is enabled. */
    public boolean isKnown(String serviceType) {
        return lookup(serviceType).isPresent();
    }

    /**
     * The handler for {@code serviceType}, or empty when none is registered <em>or</em> the service is
     * disabled. Callers that need to tell those two cases apart — to answer {@code 503 ServiceDisabled}
     * rather than falling through — must consult {@link #isKnown} and {@link #isEnabled} separately.
     */
    public Optional<AzureServiceHandler> resolve(String serviceType) {
        return isEnabled(serviceType) ? lookup(serviceType) : Optional.empty();
    }

    private Optional<AzureServiceHandler> lookup(String serviceType) {
        return handlerByServiceType.computeIfAbsent(serviceType, this::findHandler);
    }

    private Optional<AzureServiceHandler> findHandler(String serviceType) {
        for (AzureServiceHandler handler : handlers) {
            if (handler.getServiceType().equals(serviceType)) {
                return Optional.of(handler);
            }
        }
        // Handlers that serve several service types (Cosmos engines) only answer handlesServiceType.
        for (AzureServiceHandler handler : handlers) {
            if (handler.handlesServiceType(serviceType)) {
                return Optional.of(handler);
            }
        }
        return Optional.empty();
    }
}
