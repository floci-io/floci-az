package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies a declarative Service Bus topology at startup from the official emulator's
 * {@code Config.json} format ({@link ServiceBusTopologyFile}). Entities are created through
 * the same {@link ServiceBusHandler} paths the management API uses, so validation, storage,
 * and Artemis provisioning behave exactly as if an SDK had created them.
 *
 * <p>Rule semantics match the official emulator: valid declarations reconcile exactly and
 * remove the implicit {@code $Default} TrueFilter; a rejected replacement preserves the last
 * valid same-named rule, while a subscription without declarations keeps {@code $Default}.
 *
 * <p>Loading is best-effort and never fails startup: an unreadable file is skipped with an
 * {@code ERROR}, an invalid entity is skipped with an {@code ERROR} while the rest of the
 * topology still loads.
 */
@ApplicationScoped
public class ServiceBusTopologyLoader {

    /** Mount path used by the official Service Bus emulator (and targeted by .NET Aspire). */
    static final String OFFICIAL_CONFIG_PATH = "/ServiceBus_Emulator/ConfigFiles/Config.json";

    /** Account the SDK spec paths dispatch under (see {@code AzureRoutingFilter}). */
    private static final String ACCOUNT = "devstoreaccount1";
    private static final String DEFAULT_RULE = "$Default";

    private static final Logger LOG = Logger.getLogger(ServiceBusTopologyLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Per-load application counters, local to one {@link #load()} call. */
    private static final class Tally {
        int namespaces;
        int queues;
        int topics;
        int subscriptions;
        int rules;
    }

    private final EmulatorConfig config;
    private final ServiceBusHandler handler;
    private final ServiceBusNamespaceManager namespaceManager;

    @Inject
    public ServiceBusTopologyLoader(EmulatorConfig config,
                                     ServiceBusHandler handler,
                                     ServiceBusNamespaceManager namespaceManager) {
        this.config = config;
        this.handler = handler;
        this.namespaceManager = namespaceManager;
    }

    public void load() {
        Path file = resolveFile();
        if (file == null) {
            return;
        }
        ServiceBusTopologyFile.Root root;
        try {
            root = MAPPER.readValue(Files.readAllBytes(file), ServiceBusTopologyFile.Root.class);
        } catch (Exception e) {
            LOG.errorf(e, "Could not parse Service Bus topology file '%s' — no topology loaded", file);
            return;
        }
        List<ServiceBusTopologyFile.Namespace> namespaces =
                root == null || root.userConfig() == null || root.userConfig().namespaces() == null
                        ? List.of()
                        : root.userConfig().namespaces();
        if (namespaces.isEmpty()) {
            LOG.warnf("Service Bus topology file '%s' declares no namespaces", file);
            return;
        }

        Tally tally = new Tally();
        boolean configuredPortsAvailable = true;
        for (ServiceBusTopologyFile.Namespace namespace : namespaces) {
            if (namespace == null || namespace.name() == null || namespace.name().isBlank()) {
                LOG.error("Skipping a Service Bus topology namespace without a Name");
                continue;
            }
            boolean useConfiguredPorts = configuredPortsAvailable;
            NamespaceStartResult startResult = startNamespace(namespace.name(), useConfiguredPorts);
            if (!startResult.started()) {
                if (useConfiguredPorts && !startResult.portsReleased()) {
                    configuredPortsAvailable = false;
                }
                continue;
            }
            configuredPortsAvailable = false;
            tally.namespaces++;
            applyNamespace(namespace, tally);
        }
        LOG.infov("Loaded Service Bus topology from {0}: {1} namespace(s), {2} queue(s), "
                        + "{3} topic(s), {4} subscription(s), {5} rule(s)",
                file, tally.namespaces, tally.queues, tally.topics, tally.subscriptions, tally.rules);
    }

    private Path resolveFile() {
        Optional<String> configured = config.services().serviceBus().topologyFile();
        if (configured.isPresent() && !configured.get().isBlank()) {
            Path path = Path.of(configured.get());
            if (!Files.isRegularFile(path)) {
                LOG.errorf("Configured Service Bus topology file '%s' does not exist", path);
                return null;
            }
            return path;
        }
        Path official = Path.of(OFFICIAL_CONFIG_PATH);
        return Files.isRegularFile(official) ? official : null;
    }

    /**
     * The first namespace binds the configured AMQP host ports; further namespaces (the
     * official emulator supports only one) get dynamic ports so they don't collide.
     */
    private NamespaceStartResult startNamespace(String name, boolean useConfiguredPorts) {
        if (namespaceManager.getNamespace(name).isPresent()) {
            return new NamespaceStartResult(true, false);
        }
        EmulatorConfig.ServiceBusConfig sb = config.services().serviceBus();
        if (sb.mocked()) {
            namespaceManager.startMockedNamespace(name);
            return new NamespaceStartResult(true, false);
        }
        try {
            namespaceManager.startNamespace(name,
                    useConfiguredPorts ? sb.amqpPort() : 0,
                    useConfiguredPorts ? sb.amqpTlsPort() : 0);
            return new NamespaceStartResult(true, false);
        } catch (Exception e) {
            LOG.errorf(e, "Could not start Service Bus namespace '%s' from the topology file", name);
            boolean portsReleased = e instanceof ServiceBusNamespaceManager.NamespaceStartException startError
                    && startError.portsReleased();
            return new NamespaceStartResult(false, portsReleased);
        }
    }

    private record NamespaceStartResult(boolean started, boolean portsReleased) {}

    private void applyNamespace(ServiceBusTopologyFile.Namespace namespace, Tally tally) {
        for (ServiceBusTopologyFile.Queue queue : orEmpty(namespace.queues())) {
            if (hasName("queue", queue == null ? null : queue.name()) && applyQueue(namespace.name(), queue)) {
                tally.queues++;
            }
        }
        for (ServiceBusTopologyFile.Topic topic : orEmpty(namespace.topics())) {
            if (!hasName("topic", topic == null ? null : topic.name())) {
                continue;
            }
            if (applyTopic(namespace.name(), topic)) {
                tally.topics++;
                for (ServiceBusTopologyFile.Subscription subscription : orEmpty(topic.subscriptions())) {
                    if (hasName("subscription", subscription == null ? null : subscription.name())
                            && applySubscription(namespace.name(), topic.name(), subscription, tally)) {
                        tally.subscriptions++;
                    }
                }
            }
        }
    }

    private boolean applyQueue(String namespace, ServiceBusTopologyFile.Queue queue) {
        ServiceBusTopologyFile.EntityProperties p = properties(queue.properties());
        warnOnForwarding("queue", queue.name(), p);
        try {
            Response response = handler.handleCreateQueue(ACCOUNT, namespace, queue.name(),
                    Boolean.TRUE.equals(p.requiresSession()),
                    duplicateDetection(p), lifetime(p), delivery(p));
            return succeeded("queue", queue.name(), response);
        } catch (IllegalArgumentException e) {
            LOG.errorf("Skipping topology queue '%s': %s", queue.name(), e.getMessage());
            return false;
        }
    }

    private boolean applyTopic(String namespace, ServiceBusTopologyFile.Topic topic) {
        ServiceBusTopologyFile.EntityProperties p = properties(topic.properties());
        try {
            Response response = handler.handleCreateTopic(ACCOUNT, namespace, topic.name(),
                    duplicateDetection(p), lifetime(p));
            return succeeded("topic", topic.name(), response);
        } catch (IllegalArgumentException e) {
            LOG.errorf("Skipping topology topic '%s': %s", topic.name(), e.getMessage());
            return false;
        }
    }

    private boolean applySubscription(String namespace, String topicName,
                                       ServiceBusTopologyFile.Subscription subscription, Tally tally) {
        ServiceBusTopologyFile.EntityProperties p = properties(subscription.properties());
        String path = topicName + "/" + subscription.name();
        warnOnForwarding("subscription", path, p);
        try {
            Response response = handler.handleCreateSubscription(ACCOUNT, namespace,
                    topicName, subscription.name(), Boolean.TRUE.equals(p.requiresSession()),
                    "", lifetime(p), delivery(p));
            if (!succeeded("subscription", path, response)) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            LOG.errorf("Skipping topology subscription '%s': %s", path, e.getMessage());
            return false;
        }

        int applied = reconcileRules(namespace, topicName, subscription);
        tally.rules += applied;
        return true;
    }

    private int reconcileRules(String namespace, String topicName,
                               ServiceBusTopologyFile.Subscription subscription) {
        List<ServiceBusTopologyFile.Rule> declaredRules = orEmpty(subscription.rules());
        Set<String> existingRuleNames = new HashSet<>();
        for (ServiceBusModels.RuleEntity existing :
                handler.loadRules(ACCOUNT, namespace, topicName, subscription.name())) {
            existingRuleNames.add(existing.name());
        }
        Set<String> retainedRuleNames = new HashSet<>();
        int applied = 0;

        if (declaredRules.isEmpty()) {
            ServiceBusModels.RuleEntity defaultRule = ServiceBusModels.RuleEntity.trueFilter(
                    topicName, subscription.name(), DEFAULT_RULE);
            if (succeeded("rule", topicName + "/" + subscription.name() + "/" + DEFAULT_RULE,
                    handler.putRule(ACCOUNT, namespace, defaultRule))) {
                retainedRuleNames.add(DEFAULT_RULE);
            } else if (existingRuleNames.contains(DEFAULT_RULE)) {
                retainedRuleNames.add(DEFAULT_RULE);
            }
        } else {
            for (ServiceBusTopologyFile.Rule rule : declaredRules) {
                if (!hasName("rule", rule == null ? null : rule.name())) {
                    continue;
                }
                if (applyRule(namespace, topicName, subscription.name(), rule)) {
                    retainedRuleNames.add(rule.name());
                    applied++;
                } else if (existingRuleNames.contains(rule.name())) {
                    LOG.warnf("Keeping existing topology rule '%s/%s/%s' because its replacement "
                                    + "was rejected",
                            topicName, subscription.name(), rule.name());
                    retainedRuleNames.add(rule.name());
                }
            }
        }

        for (ServiceBusModels.RuleEntity existing :
                handler.loadRules(ACCOUNT, namespace, topicName, subscription.name())) {
            if (!retainedRuleNames.contains(existing.name())) {
                succeeded("rule", topicName + "/" + subscription.name() + "/" + existing.name(),
                        handler.handleDeleteRule(
                                ACCOUNT, namespace, topicName, subscription.name(), existing.name()));
            }
        }
        return applied;
    }

    private boolean applyRule(String namespace, String topicName, String subName,
                               ServiceBusTopologyFile.Rule rule) {
        String path = topicName + "/" + subName + "/" + rule.name();
        ServiceBusModels.RuleEntity entity;
        try {
            entity = toRuleEntity(topicName, subName, rule);
        } catch (IllegalArgumentException e) {
            LOG.errorf("Skipping topology rule '%s': %s", path, e.getMessage());
            return false;
        }
        return succeeded("rule", path, handler.putRule(ACCOUNT, namespace, entity));
    }

    static ServiceBusModels.RuleEntity toRuleEntity(String topicName, String subName,
                                                     ServiceBusTopologyFile.Rule rule) {
        ServiceBusTopologyFile.RuleProperties p = rule.properties();
        if (p == null || p.filterType() == null || p.filterType().isBlank()) {
            throw new IllegalArgumentException("FilterType is required");
        }
        String type = p.filterType().trim();
        String actionSql = p.action() == null ? null : p.action().sqlExpression();

        if ("Correlation".equalsIgnoreCase(type) || "CorrelationFilter".equalsIgnoreCase(type)) {
            ServiceBusTopologyFile.CorrelationFilter filter = p.correlationFilter() == null
                    ? ServiceBusTopologyFile.CorrelationFilter.EMPTY
                    : p.correlationFilter();
            Map<String, String> properties = new LinkedHashMap<>();
            Map<String, String> propertyTypes = new LinkedHashMap<>();
            if (filter.properties() != null) {
                filter.properties().forEach((key, value) -> {
                    properties.put(key, String.valueOf(value));
                    // Mirror the XML path's xsi:type tagging so typed application
                    // properties compare with their declared type in the selector.
                    if (value instanceof Boolean) {
                        propertyTypes.put(key, "boolean");
                    } else if (value instanceof Integer || value instanceof Long) {
                        propertyTypes.put(key, "long");
                    } else if (value instanceof Number) {
                        propertyTypes.put(key, "double");
                    }
                });
            }
            return new ServiceBusModels.RuleEntity(topicName, subName, rule.name(),
                    "CorrelationFilter", null, filter.correlationId(), filter.messageId(),
                    filter.to(), filter.replyTo(), filter.label(), filter.sessionId(),
                    filter.replyToSessionId(), filter.contentType(), properties, propertyTypes,
                    actionSql, Instant.now());
        }
        if ("Sql".equalsIgnoreCase(type) || "SqlFilter".equalsIgnoreCase(type)) {
            String expression = p.sqlFilter() == null ? null : p.sqlFilter().sqlExpression();
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException(
                        "SqlFilter.SqlExpression is required for FilterType 'Sql'");
            }
            return new ServiceBusModels.RuleEntity(topicName, subName, rule.name(),
                    "SqlFilter", expression, null, null, null, null, null, null, null, null,
                    Map.of(), Map.of(), actionSql, Instant.now());
        }
        throw new IllegalArgumentException("Unsupported FilterType '" + type + "'");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ServiceBusTopologyFile.EntityProperties properties(
            ServiceBusTopologyFile.EntityProperties properties) {
        return properties == null ? ServiceBusTopologyFile.EntityProperties.EMPTY : properties;
    }

    private static ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection(
            ServiceBusTopologyFile.EntityProperties p) {
        return ServiceBusEntityXml.duplicateDetectionOf(
                Boolean.TRUE.equals(p.requiresDuplicateDetection()),
                p.duplicateDetectionHistoryTimeWindow());
    }

    private static ServiceBusEntityXml.MessageLifetimeSettings lifetime(
            ServiceBusTopologyFile.EntityProperties p) {
        return ServiceBusEntityXml.messageLifetimeOf(
                p.defaultMessageTimeToLive(),
                Boolean.TRUE.equals(p.deadLetteringOnMessageExpiration()));
    }

    private static ServiceBusEntityXml.DeliverySettings delivery(
            ServiceBusTopologyFile.EntityProperties p) {
        return ServiceBusEntityXml.deliveryOf(p.maxDeliveryCount(), p.lockDuration());
    }

    private static void warnOnForwarding(String kind, String name,
                                          ServiceBusTopologyFile.EntityProperties p) {
        if (hasValue(p.forwardTo()) || hasValue(p.forwardDeadLetteredMessagesTo())) {
            LOG.warnf("Topology %s '%s' declares ForwardTo/ForwardDeadLetteredMessagesTo; "
                    + "auto-forwarding is not emulated and the setting is ignored", kind, name);
        }
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasName(String kind, String name) {
        if (name == null || name.isBlank()) {
            LOG.errorf("Skipping a Service Bus topology %s without a Name", kind);
            return false;
        }
        return true;
    }

    private static boolean succeeded(String kind, String name, Response response) {
        if (response.getStatus() / 100 == 2) {
            return true;
        }
        LOG.errorf("Could not apply topology %s '%s' (HTTP %d): %s",
                kind, name, response.getStatus(), response.getEntity());
        return false;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
