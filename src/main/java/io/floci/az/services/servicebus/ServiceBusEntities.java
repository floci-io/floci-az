package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.servicebus.ServiceBusModels.QueueEntity;
import io.floci.az.services.servicebus.ServiceBusModels.RuleEntity;
import io.floci.az.services.servicebus.ServiceBusModels.SubscriptionEntity;
import io.floci.az.services.servicebus.ServiceBusModels.TopicEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed access to the Service Bus entities of one broker namespace, for the ARM management plane.
 *
 * <p>Reads go to the entity store {@link ServiceBusHandler} keeps, through the same storage backend and the same
 * keys; writes go through the handler's own create and delete paths, so an entity ARM creates is provisioned on the
 * broker and visible to the Atom API exactly as one the Atom API creates. Entity names compare ignoring case, as
 * Service Bus compares them, but the store keys keep the casing of the call that created the entity.
 */
@ApplicationScoped
public class ServiceBusEntities {

    private static final Logger LOG = Logger.getLogger(ServiceBusEntities.class);

    /** The account segment every Service Bus storage key carries; the Atom API routes all traffic under it. */
    static final String ACCOUNT = "devstoreaccount1";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ServiceBusHandler handler;
    private final StorageBackend<String, StoredObject> store;

    @Inject
    public ServiceBusEntities(ServiceBusHandler handler, StorageFactory storageFactory) {
        this.handler = handler;
        this.store = storageFactory.create("servicebus");
    }

    /** An entity write the handler refused, with the status it answered. */
    static final class Refused extends RuntimeException {
        private final int status;

        Refused(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    // ── Queues ───────────────────────────────────────────────────────────────

    List<QueueEntity> queues(String namespace) {
        return children(ServiceBusHandler.queuePrefix(ACCOUNT, namespace), QueueEntity.class);
    }

    Optional<QueueEntity> queue(String namespace, String name) {
        return find(ServiceBusHandler.queueKey(ACCOUNT, namespace, name), QueueEntity.class)
                .or(() -> queues(namespace).stream().filter(q -> q.name().equalsIgnoreCase(name)).findFirst());
    }

    /**
     * Creates the queue, or applies the settings to the one that exists through the Atom update path (#702), which
     * refuses a change to RequiresSession or RequiresDuplicateDetection as Azure does.
     */
    QueueEntity createOrUpdateQueue(String namespace, String name, boolean requiresSession,
                                    ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection,
                                    ServiceBusEntityXml.MessageLifetimeSettings lifetime,
                                    ServiceBusEntityXml.DeliverySettings delivery,
                                    ServiceBusEntityXml.EntityMetadata metadata,
                                    ServiceBusEntityXml.FixedSettings fixed) {
        Optional<QueueEntity> existing = queue(namespace, name);
        if (existing.isPresent()) {
            String stored = existing.get().name();
            accepted(handler.handleUpdateQueue(ACCOUNT, namespace, stored, requiresSession, duplicateDetection,
                    lifetime, delivery, metadata, fixed));
            return queue(namespace, stored)
                    .orElseThrow(() -> new Refused(500, "The queue '" + name + "' was not stored."));
        }
        accepted(handler.handleCreateQueue(ACCOUNT, namespace, name, requiresSession, duplicateDetection, lifetime,
                delivery, metadata));
        return queue(namespace, name).orElseThrow(() -> new Refused(500, "The queue '" + name + "' was not stored."));
    }

    boolean deleteQueue(String namespace, String name) {
        return queue(namespace, name)
                .map(queue -> handler.handleDeleteQueue(ACCOUNT, namespace, queue.name()).getStatus() / 100 == 2)
                .orElse(false);
    }

    // ── Topics ───────────────────────────────────────────────────────────────

    List<TopicEntity> topics(String namespace) {
        return children(ServiceBusHandler.topicPrefix(ACCOUNT, namespace), TopicEntity.class);
    }

    Optional<TopicEntity> topic(String namespace, String name) {
        return find(ServiceBusHandler.topicKey(ACCOUNT, namespace, name), TopicEntity.class)
                .or(() -> topics(namespace).stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst());
    }

    /** Creates the topic, or applies the settings to the one that exists; RequiresDuplicateDetection is fixed. */
    TopicEntity createOrUpdateTopic(String namespace, String name,
                                    ServiceBusEntityXml.DuplicateDetectionSettings duplicateDetection,
                                    ServiceBusEntityXml.MessageLifetimeSettings lifetime,
                                    ServiceBusEntityXml.EntityMetadata metadata,
                                    ServiceBusEntityXml.FixedSettings fixed) {
        Optional<TopicEntity> existing = topic(namespace, name);
        if (existing.isPresent()) {
            String stored = existing.get().name();
            accepted(handler.handleUpdateTopic(ACCOUNT, namespace, stored, duplicateDetection, lifetime, metadata,
                    fixed));
            return topic(namespace, stored)
                    .orElseThrow(() -> new Refused(500, "The topic '" + name + "' was not stored."));
        }
        accepted(handler.handleCreateTopic(ACCOUNT, namespace, name, duplicateDetection, lifetime, metadata));
        return topic(namespace, name).orElseThrow(() -> new Refused(500, "The topic '" + name + "' was not stored."));
    }

    /** Deletes the topic with its subscriptions and their rules. */
    boolean deleteTopic(String namespace, String name) {
        return topic(namespace, name)
                .map(topic -> handler.handleDeleteTopic(ACCOUNT, namespace, topic.name()).getStatus() / 100 == 2)
                .orElse(false);
    }

    // ── Subscriptions ────────────────────────────────────────────────────────

    List<SubscriptionEntity> subscriptions(String namespace, String topic) {
        return children(ServiceBusHandler.subPrefix(ACCOUNT, namespace, topic), SubscriptionEntity.class);
    }

    Optional<SubscriptionEntity> subscription(String namespace, String topic, String name) {
        return find(ServiceBusHandler.subKey(ACCOUNT, namespace, topic, name), SubscriptionEntity.class)
                .or(() -> subscriptions(namespace, topic).stream()
                        .filter(sub -> sub.name().equalsIgnoreCase(name)).findFirst());
    }

    /**
     * Creates the subscription with a {@code $Default} rule that accepts every message, or applies the settings to the
     * one that exists, keeping its rules; RequiresSession is fixed. {@code topic} is the stored topic name.
     */
    SubscriptionEntity createOrUpdateSubscription(String namespace, String topic, String name,
                                                  boolean requiresSession,
                                                  ServiceBusEntityXml.MessageLifetimeSettings lifetime,
                                                  ServiceBusEntityXml.DeliverySettings delivery,
                                                  ServiceBusEntityXml.EntityMetadata metadata,
                                                  ServiceBusEntityXml.FixedSettings fixed) {
        Optional<SubscriptionEntity> existing = subscription(namespace, topic, name);
        if (existing.isPresent()) {
            String stored = existing.get().name();
            accepted(handler.handleUpdateSubscription(ACCOUNT, namespace, topic, stored, lifetime, delivery, metadata,
                    fixed));
            return subscription(namespace, topic, stored)
                    .orElseThrow(() -> new Refused(500, "The subscription '" + name + "' was not stored."));
        }
        accepted(handler.handleCreateSubscription(ACCOUNT, namespace, topic, name, requiresSession, lifetime, delivery,
                metadata, RuleEntity.trueFilter(topic, name, "$Default")));
        return subscription(namespace, topic, name)
                .orElseThrow(() -> new Refused(500, "The subscription '" + name + "' was not stored."));
    }

    /** Deletes the subscription with its rules. */
    boolean deleteSubscription(String namespace, String topic, String name) {
        return subscription(namespace, topic, name)
                .map(sub -> handler.handleDeleteSubscription(ACCOUNT, namespace, topic, sub.name())
                        .getStatus() / 100 == 2)
                .orElse(false);
    }

    // ── Rules ────────────────────────────────────────────────────────────────

    List<RuleEntity> rules(String namespace, String topic, String subscription) {
        return handler.loadRules(ACCOUNT, namespace, topic, subscription);
    }

    Optional<RuleEntity> rule(String namespace, String topic, String subscription, String name) {
        return rules(namespace, topic, subscription).stream()
                .filter(rule -> rule.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /** Stores the rule and re-applies the subscription's selector on the broker; whether it was new. */
    boolean putRule(String namespace, RuleEntity rule) {
        boolean created = rule(namespace, rule.topicName(), rule.subscriptionName(), rule.name()).isEmpty();
        accepted(handler.putRule(ACCOUNT, namespace, rule));
        return created;
    }

    boolean deleteRule(String namespace, String topic, String subscription, String name) {
        return rule(namespace, topic, subscription, name)
                .map(rule -> {
                    accepted(handler.handleDeleteRule(ACCOUNT, namespace, topic, subscription, rule.name()));
                    return true;
                })
                .orElse(false);
    }

    // ── Store access ─────────────────────────────────────────────────────────

    /** The entities directly under {@code prefix}; deeper keys belong to their children. */
    private <T> List<T> children(String prefix, Class<T> type) {
        return store.scan(key -> key.startsWith(prefix) && key.indexOf('/', prefix.length()) < 0).stream()
                .map(stored -> decode(stored, type))
                .filter(Objects::nonNull)
                .toList();
    }

    private <T> Optional<T> find(String key, Class<T> type) {
        return store.get(key).map(stored -> decode(stored, type));
    }

    private static <T> T decode(StoredObject stored, Class<T> type) {
        try {
            return MAPPER.readValue(stored.data(), type);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to read stored Service Bus entity '%s'", stored.key());
            return null;
        }
    }

    /** Throws {@link Refused} for an answer that is not a success; the handler answers Atom XML, not ARM JSON. */
    private static void accepted(Response response) {
        int status = response.getStatus();
        if (status / 100 == 2) {
            return;
        }
        Object entity = response.getEntity();
        throw new Refused(status, entity == null ? "The Service Bus entity write was refused." : messageOf(entity));
    }

    /** The {@code Detail} text of an Atom error body, else the body itself. */
    private static String messageOf(Object entity) {
        String body = String.valueOf(entity);
        int start = body.indexOf("<Detail>");
        int end = body.indexOf("</Detail>");
        return start >= 0 && end > start ? body.substring(start + "<Detail>".length(), end) : body;
    }
}
