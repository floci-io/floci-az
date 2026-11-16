package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.core.server.impl.AckReason;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.apache.activemq.artemis.core.transaction.Transaction;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.utils.collections.LinkedListIterator;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.UnsignedInteger;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Header;
import org.apache.qpid.proton.amqp.messaging.MessageAnnotations;
import org.apache.qpid.proton.amqp.messaging.Properties;
import org.apache.qpid.proton.engine.Delivery;
import org.apache.qpid.proton.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service Bus message deferral.
 *
 * <p>A deferred message stays in its entity, but no receiver link is offered it again: the broker
 * plugin hook vetoes every consumer. The client fetches it by sequence number through the entity's
 * {@code $management} node, and settles it there with {@code update-disposition}, using the lock
 * token that request handed out. Both operations run on the requesting connection's event loop,
 * like lock renewal, before the request reaches routing.
 *
 * <p>Deferral is per entity: a topic message deferred on one subscription is still delivered on
 * the others, which hold their own reference to the same broker message. The state is held in
 * memory, so a broker restart makes deferred messages deliverable again.
 */
public final class ServiceBusDeferralSupport implements ActiveMQServerPlugin {
   private static final Logger LOGGER = LoggerFactory.getLogger(ServiceBusDeferralSupport.class);
   private static final String SUFFIX = "/$management";
   private static final String RECEIVE_BY_SEQUENCE_NUMBER = "com.microsoft:receive-by-sequence-number";
   private static final String UPDATE_DISPOSITION = "com.microsoft:update-disposition";
   private static final String PEEK_LOCK_PREFIX = "floci-az:servicebus-peeklock:";
   private static final String SESSION_PREFIX = "floci-az:servicebus-session:";
   private static final Symbol SEQUENCE_NUMBER = Symbol.valueOf("x-opt-sequence-number");
   private static final Symbol ENQUEUED_TIME = Symbol.valueOf("x-opt-enqueued-time");
   private static final Symbol LOCKED_UNTIL = Symbol.valueOf("x-opt-locked-until");
   private static final Symbol MESSAGE_NOT_FOUND = Symbol.valueOf("com.microsoft:message-not-found");
   private static final Symbol MESSAGE_LOCK_LOST = Symbol.valueOf("com.microsoft:message-lock-lost");
   private static final Symbol INVALID_FIELD = Symbol.valueOf("amqp:invalid-field");
   private static final UnsignedInteger RECEIVE_AND_DELETE = UnsignedInteger.ZERO;

   private static final Set<DeferredKey> DEFERRED = ConcurrentHashMap.newKeySet();
   private static final Map<UUID, DeferredLock> LOCKS = new ConcurrentHashMap<>();

   /** Called when a receiver defers a delivery: a modified outcome with undeliverable-here. */
   public static void defer(ServerConsumer consumer, long messageId) {
      Queue queue = consumer.getQueue();
      if (lockDurationMillis(queue) > 0) {
         DEFERRED.add(new DeferredKey(queue.getName().toString(), messageId));
      }
   }

   static boolean isDeferred(String queueName, long messageId) {
      return DEFERRED.contains(new DeferredKey(queueName, messageId));
   }

   @Override
   public boolean canAccept(ServerConsumer consumer, MessageReference reference) {
      return !isDeferred(consumer.getQueue().getName().toString(), reference.getMessageID());
   }

   @Override
   public void messageAcknowledged(Transaction tx, MessageReference reference, AckReason reason,
                                   ServerConsumer consumer) {
      DEFERRED.remove(new DeferredKey(reference.getQueue().getName().toString(), reference.getMessageID()));
   }

   public static boolean handle(ProtonServerReceiverContext receiver, SimpleString address,
                                AMQPMessage request, Delivery delivery) throws Exception {
      if (address == null || !address.toString().endsWith(SUFFIX) || request.getApplicationProperties() == null) {
         return false;
      }
      Object operation = request.getApplicationProperties().getValue().get("operation");
      if (!RECEIVE_BY_SEQUENCE_NUMBER.equals(operation) && !UPDATE_DISPOSITION.equals(operation)) {
         return false;
      }
      ActiveMQServer server = receiver.connection.getProtocolManager().getServer();
      Reply reply;
      try {
         if (!(request.getBody() instanceof AmqpValue value) || !(value.getValue() instanceof Map<?, ?> body)) {
            throw new IllegalArgumentException("Request body must be an AMQP map");
         }
         Queue queue = server.locateQueue(SimpleString.of(entityPath(address)));
         if (queue == null) {
            reply = Reply.error(404, "The messaging entity could not be found", Symbol.valueOf("amqp:not-found"));
         } else if (RECEIVE_BY_SEQUENCE_NUMBER.equals(operation)) {
            reply = receive(queue, body);
         } else {
            reply = updateDisposition(queue, body);
         }
      } catch (IllegalArgumentException e) {
         LOGGER.warn("Invalid Service Bus deferral request", e);
         reply = Reply.error(400, e.getMessage() == null ? "Bad Request" : e.getMessage(), INVALID_FIELD);
      }
      ServiceBusLockRenewalSupport.reply(receiver, address, request, delivery, reply.applicationProperties(),
         reply.body());
      return true;
   }

   static Reply receive(Queue queue, Map<?, ?> body) throws Exception {
      List<Long> sequenceNumbers = sequenceNumbers(body.get("sequence-numbers"));
      boolean receiveAndDelete = RECEIVE_AND_DELETE.equals(body.get("receiver-settle-mode"));
      String sessionId = body.get("session-id") instanceof String value && !value.isEmpty() ? value : null;
      String queueName = queue.getName().toString();

      Map<Long, MessageReference> found = deferredReferences(queue, sequenceNumbers, sessionId);
      if (found.size() < sequenceNumbers.size()) {
         List<Long> missing = new ArrayList<>(sequenceNumbers);
         missing.removeAll(found.keySet());
         return Reply.error(404, "Failed to retrieve the deferred message(s) with sequence number(s) " + missing,
            MESSAGE_NOT_FOUND);
      }

      long now = System.currentTimeMillis();
      long until = now + lockDurationMillis(queue);
      List<Map<String, Object>> messages = new ArrayList<>(sequenceNumbers.size());
      for (long sequenceNumber : sequenceNumbers) {
         MessageReference reference = found.get(sequenceNumber);
         Map<String, Object> entry = new HashMap<>();
         if (receiveAndDelete) {
            entry.put("message", new Binary(encode(reference, 0)));
            DEFERRED.remove(new DeferredKey(queueName, sequenceNumber));
            queue.deleteReference(sequenceNumber);
         } else {
            UUID lockToken = UUID.randomUUID();
            entry.put("message", new Binary(encode(reference, until)));
            entry.put("lock-token", lockToken);
            reference.incrementDeliveryCount();
            LOCKS.put(lockToken, new DeferredLock(new DeferredKey(queueName, sequenceNumber), until));
         }
         messages.add(entry);
      }
      LOCKS.values().removeIf(lock -> lock.until() <= now);
      return Reply.ok(Map.of("messages", messages));
   }

   static Reply updateDisposition(Queue queue, Map<?, ?> body) throws Exception {
      String status = body.get("disposition-status") instanceof String value ? value.toLowerCase(Locale.ROOT) : "";
      if (!Set.of("completed", "abandoned", "suspended", "defered").contains(status)) {
         throw new IllegalArgumentException("disposition-status '" + status + "' is not valid");
      }
      List<UUID> lockTokens = lockTokens(body.get("lock-tokens"));
      String queueName = queue.getName().toString();
      long now = System.currentTimeMillis();
      for (UUID lockToken : lockTokens) {
         DeferredLock lock = LOCKS.get(lockToken);
         if (lock == null || lock.until() <= now || !lock.key().queueName().equals(queueName)) {
            return Reply.error(410, "The lock supplied is invalid. Either the lock expired, or the message has "
               + "already been removed from the queue", MESSAGE_LOCK_LOST);
         }
      }
      for (UUID lockToken : lockTokens) {
         settle(queue, LOCKS.remove(lockToken).key().messageId(), status, body);
      }
      return Reply.ok(Map.of());
   }

   private static void settle(Queue queue, long messageId, String status, Map<?, ?> body) throws Exception {
      DeferredKey key = new DeferredKey(queue.getName().toString(), messageId);
      switch (status) {
         case "completed" -> {
            DEFERRED.remove(key);
            queue.deleteReference(messageId);
         }
         case "suspended" -> {
            MessageReference reference = deferredReferences(queue, List.of(messageId), null).get(messageId);
            if (reference != null) {
               Map<String, Object> properties = new HashMap<>();
               if (body.get("properties-to-modify") instanceof Map<?, ?> modify) {
                  modify.forEach((name, value) -> properties.put(name.toString(), value));
               }
               putIfPresent(properties, "DeadLetterReason", body.get("deadletter-reason"));
               putIfPresent(properties, "DeadLetterErrorDescription", body.get("deadletter-description"));
               ServiceBusDeadLetterSupport.applyProperties(reference.getMessage(), properties, true);
            }
            DEFERRED.remove(key);
            queue.sendMessageToDeadLetterAddress(messageId);
         }
         // Abandoning or deferring a deferred message releases its lock; it stays deferred.
         default -> {
            if (body.get("properties-to-modify") instanceof Map<?, ?> modify) {
               MessageReference reference = deferredReferences(queue, List.of(messageId), null).get(messageId);
               if (reference != null) {
                  ServiceBusDeadLetterSupport.applyProperties(reference.getMessage(), modify,
                     !(reference.getMessage() instanceof AMQPMessage));
               }
            }
         }
      }
   }

   private static void putIfPresent(Map<String, Object> properties, String name, Object value) {
      if (value != null) {
         properties.put(name, value);
      }
   }

   private static Map<Long, MessageReference> deferredReferences(Queue queue, List<Long> sequenceNumbers,
                                                                 String sessionId) {
      String queueName = queue.getName().toString();
      Map<Long, MessageReference> found = new LinkedHashMap<>();
      try (LinkedListIterator<MessageReference> iterator = queue.browserIterator()) {
         while (iterator.hasNext() && found.size() < sequenceNumbers.size()) {
            MessageReference reference = iterator.next();
            long messageId = reference.getMessageID();
            if (sequenceNumbers.contains(messageId) && isDeferred(queueName, messageId)
                && reference.getMessage() instanceof AMQPMessage message && matchesSession(message, sessionId)) {
               found.put(messageId, reference);
            }
         }
      }
      return found;
   }

   private static boolean matchesSession(AMQPMessage message, String sessionId) {
      if (sessionId == null) {
         return true;
      }
      Properties properties = message.getProperties();
      return properties != null && sessionId.equals(properties.getGroupId());
   }

   /** Encodes the message as a delivery would carry it, with a lock deadline when {@code until} is set. */
   private static byte[] encode(MessageReference reference, long until) {
      AMQPMessage source = (AMQPMessage) reference.getMessage();
      Message message = Proton.message();
      Header header = source.getHeader() == null ? new Header() : new Header(source.getHeader());
      header.setDeliveryCount(UnsignedInteger.valueOf(reference.getDeliveryCount()));
      message.setHeader(header);
      message.setProperties(source.getProperties());
      message.setApplicationProperties(source.getApplicationProperties());
      message.setBody(source.getBody());
      message.setFooter(source.getFooter());

      MessageAnnotations sourceAnnotations = source.getMessageAnnotations();
      Map<Symbol, Object> annotations = sourceAnnotations == null
         ? new HashMap<>()
         : new HashMap<>(sourceAnnotations.getValue());
      annotations.put(SEQUENCE_NUMBER, reference.getMessageID());
      Long ingress = source.getIngressTimestamp();
      annotations.put(ENQUEUED_TIME, new Date(ingress != null ? ingress : source.getTimestamp()));
      if (until > 0) {
         annotations.put(LOCKED_UNTIL, new Date(until));
      }
      message.setMessageAnnotations(new MessageAnnotations(annotations));

      int capacity = Math.max(1_024, source.getEncodeSize() + 256);
      while (true) {
         byte[] encoded = new byte[capacity];
         try {
            int length = message.encode(encoded, 0, encoded.length);
            byte[] exact = new byte[length];
            System.arraycopy(encoded, 0, exact, 0, length);
            return exact;
         } catch (BufferOverflowException e) {
            capacity = Math.multiplyExact(capacity, 2);
         }
      }
   }

   private static List<Long> sequenceNumbers(Object value) {
      List<Long> numbers = new ArrayList<>();
      if (value instanceof long[] array) {
         for (long number : array) {
            numbers.add(number);
         }
      } else {
         for (Object number : objects(value)) {
            if (!(number instanceof Number numeric)) {
               throw new IllegalArgumentException("sequence-numbers must contain longs");
            }
            numbers.add(numeric.longValue());
         }
      }
      if (numbers.isEmpty()) {
         throw new IllegalArgumentException("sequence-numbers is required");
      }
      return numbers;
   }

   private static List<UUID> lockTokens(Object value) {
      List<UUID> tokens = new ArrayList<>();
      for (Object token : objects(value)) {
         if (!(token instanceof UUID uuid)) {
            throw new IllegalArgumentException("lock-tokens must contain UUIDs");
         }
         tokens.add(uuid);
      }
      if (tokens.isEmpty()) {
         throw new IllegalArgumentException("lock-tokens is required");
      }
      return tokens;
   }

   private static List<?> objects(Object value) {
      return value instanceof Object[] array ? List.of(array) : value instanceof List<?> list ? list : List.of();
   }

   private static String entityPath(SimpleString address) {
      String entity = ServiceBusSessionSupport.normalizeEntityPath(
         address.toString().substring(0, address.length() - SUFFIX.length()));
      return entity.startsWith("/") ? entity.substring(1) : entity;
   }

   /** The entity's lock duration, or 0 for a queue that is not a Service Bus entity. */
   private static long lockDurationMillis(Queue queue) {
      String metadata = queue.getUser() == null ? "" : queue.getUser().toString();
      String seconds = metadata.startsWith(PEEK_LOCK_PREFIX) ? metadata.substring(PEEK_LOCK_PREFIX.length())
         : metadata.startsWith(SESSION_PREFIX) ? metadata.substring(SESSION_PREFIX.length()) : null;
      if (seconds == null) {
         return 0;
      }
      try {
         return Math.multiplyExact(Long.parseLong(seconds), 1_000L);
      } catch (ArithmeticException | NumberFormatException e) {
         LOGGER.warn("Ignoring invalid Service Bus lock metadata: " + metadata, e);
         return 0;
      }
   }

   private record DeferredKey(String queueName, long messageId) {
   }

   private record DeferredLock(DeferredKey key, long until) {
   }

   record Reply(Map<String, Object> applicationProperties, Map<String, Object> body) {
      static Reply ok(Map<String, Object> body) {
         return new Reply(Map.of("statusCode", 200, "statusDescription", "OK"), body);
      }

      static Reply error(int status, String description, Symbol condition) {
         return new Reply(Map.of("statusCode", status, "statusDescription", description,
            "errorCondition", condition), Map.of());
      }
   }
}
