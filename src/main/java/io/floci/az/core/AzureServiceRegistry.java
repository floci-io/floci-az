package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.cosmos.engine.CosmosLifecycleManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class AzureServiceRegistry {

    private final List<AzureServiceHandler> handlers;
    private final EmulatorConfig config;
    private final CosmosLifecycleManager cosmosLifecycleManager;

    @Inject
    AzureServiceRegistry(Instance<AzureServiceHandler> all, EmulatorConfig config,
                         CosmosLifecycleManager cosmosLifecycleManager) {
        this.config = config;
        this.cosmosLifecycleManager = cosmosLifecycleManager;
        this.handlers = new ArrayList<>();
        for (AzureServiceHandler h : all) {
            this.handlers.add(h);
        }
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
            case "servicebus" -> config.services().serviceBus().enabled();
            case "aks"        -> config.services().aks().enabled();
            case "cosmos-mongo", "cosmos-table", "cosmos-cassandra",
                 "cosmos-gremlin", "cosmos-postgresql", "cosmos-nosql" ->
                config.services().cosmos().enabled() &&
                cosmosLifecycleManager.isEnabled(serviceType);
            default           -> true;
        };
    }

    public boolean isKnown(String serviceType) {
        for (AzureServiceHandler h : handlers) {
            if (h.handlesServiceType(serviceType)) return true;
        }
        return false;
    }

    public Optional<AzureServiceHandler> resolve(String serviceType) {
        if (!isEnabled(serviceType)) return Optional.empty();
        // First try exact service type match
        for (AzureServiceHandler handler : handlers) {
            if (handler.getServiceType().equals(serviceType)) {
                return Optional.of(handler);
            }
        }
        // Fallback: use handlesServiceType for handlers that serve multiple service types
        for (AzureServiceHandler handler : handlers) {
            if (handler.handlesServiceType(serviceType)) {
                return Optional.of(handler);
            }
        }
        return Optional.empty();
    }
}
