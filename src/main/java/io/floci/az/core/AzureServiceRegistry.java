package io.floci.az.core;

import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class AzureServiceRegistry {

    private final Map<String, AzureServiceHandler> handlers;
    private final EmulatorConfig config;

    @Inject
    AzureServiceRegistry(Instance<AzureServiceHandler> all, EmulatorConfig config) {
        this.config = config;
        this.handlers = StreamSupport.stream(all.spliterator(), false)
            .collect(Collectors.toMap(AzureServiceHandler::getServiceType, h -> h));
    }

    public boolean isEnabled(String serviceType) {
        return switch (serviceType) {
            case "blob"      -> config.services().blob().enabled();
            case "queue"     -> config.services().queue().enabled();
            case "table"     -> config.services().table().enabled();
            case "functions"  -> config.services().functions().enabled();
            case "appconfig"  -> config.services().appConfig().enabled();
            case "cosmos"     -> config.services().cosmos().enabled();
            default           -> true;
        };
    }

    public boolean isKnown(String serviceType) {
        return handlers.containsKey(serviceType);
    }

    public Optional<AzureServiceHandler> resolve(String serviceType) {
        if (!isEnabled(serviceType)) return Optional.empty();
        return Optional.ofNullable(handlers.get(serviceType));
    }
}
