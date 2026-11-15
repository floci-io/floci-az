package io.floci.az.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AzureServiceRegistry {

    private final List<AzureServiceHandler> handlers;

    /**
     * serviceType → handler, memoised. Handler selection is a pure function of the service type (the
     * sole multi-type handler, {@code CosmosEngineHandler}, matches on the string alone), so the first
     * answer for a given type is the answer forever. Keys are bounded: every service type originates in
     * a routing table or in the blob/queue storage fallback, never in raw request data.
     */
    private final Map<String, Optional<AzureServiceHandler>> handlerByServiceType = new ConcurrentHashMap<>();

    @Inject
    AzureServiceRegistry(Instance<AzureServiceHandler> all) {
        List<AzureServiceHandler> discovered = new ArrayList<>();
        for (AzureServiceHandler h : all) {
            discovered.add(h);
        }
        this.handlers = List.copyOf(discovered);
    }

    /**
     * Whether {@code serviceType} is currently served. Delegates to the owning handler, so enablement
     * lives next to the service rather than in a central switch.
     *
     * <p>Deliberately NOT memoised, unlike {@link #lookup}: Cosmos engine enablement changes while the
     * emulator runs. An unknown service type has no handler and is therefore not enabled — callers that
     * need to tell "unknown" from "disabled" apart use {@link #isKnown}.</p>
     */
    public boolean isEnabled(String serviceType) {
        return lookup(serviceType).map(handler -> handler.enabled(serviceType)).orElse(false);
    }

    /** Every discovered handler, in CDI discovery order. Used to assemble the routing tables. */
    public List<AzureServiceHandler> handlers() {
        return handlers;
    }

    /**
     * Every service type the emulator answers for, sorted, mapped to whether it is enabled right now. The
     * health body ({@code running} / {@code available}) and the {@code /_floci/info} manifest
     * ({@code enabled} / {@code disabled}) both list services from this.
     */
    public Map<String, Boolean> serviceEnablement() {
        Map<String, Boolean> services = new TreeMap<>();
        for (AzureServiceHandler handler : handlers) {
            for (String type : serviceTypes(handler)) {
                services.put(type, isEnabled(type));
            }
        }
        return services;
    }

    /**
     * The service types a handler answers for. A handler whose account routes name other types and never its
     * own (the Cosmos engines: {@code -cosmos-mongo} is {@code cosmos-mongo}) is listed by those types, since
     * its own type routes nothing and each engine is enabled on its own.
     */
    static Set<String> serviceTypes(AzureServiceHandler handler) {
        String own = handler.getServiceType();
        Set<String> routed = new LinkedHashSet<>();
        for (ServiceRoutes.SuffixRoute route : handler.routes().accountSuffixes()) {
            routed.add(route.serviceType());
        }
        if (routed.isEmpty() || routed.contains(own)) {
            return Set.of(own);
        }
        return routed;
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
