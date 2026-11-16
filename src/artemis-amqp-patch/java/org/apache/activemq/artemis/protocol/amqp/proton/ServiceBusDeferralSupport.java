package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.security.SecurityAuth;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service Bus message deferral.
 *
 * <p>A deferred message stays in its entity, but no receiver link is offered it again: the broker
 * plugin hook vetoes every consumer. The client fetches it by sequence number through the entity's
 * {@code $management} node, and settles it there with {@code update-disposition}, using the lock
 * token that request handed out. Both operations run on the requesting connection's event loop,
 * like lock renewal, before the request reaches routing.
 *
 * <p>A deferred message holds at most one lock at a time: while it is locked or being settled,
 * receiving it again finds nothing, as Azure documents for {@code MessageNotFound}. The lock
 * belongs to the receiver that took it, which renews it through {@code renew-lock} like a delivery
 * lock, and on a session entity both operations require the caller to hold the session. One reply
 * carries at most what a peek reply does.
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

   private static final Symbol SESSION_LOCK_LOST = Symbol.valueOf("com.microsoft:session-lock-lost");
   /** The bound peek applies to one reply (ServiceBusManagementPlugin): 250 messages and about 1 MiB. */
   static final int MAX_MESSAGE_COUNT = 250;
   static final int MAX_RESPONSE_PAYLOAD_BYTES = 1_048_576;

   /**
    * Every deferred message with its lock state. Each transition (claim, renew, settle, release) is
    * one atomic operation on that message's entry, so two requests can never both act on it.
    */
   private static final ConcurrentMap<DeferredKey, DeferredState> DEFERRED = new ConcurrentHashMap<>();

   /** Called when a receiver defers a delivery: a modified outcome with undeliverable-here. */
   public static void defer(ServerConsumer consumer, long messageId) {
      Queue queue = consumer.getQueue();
      if (lockDurationMillis(queue) > 0) {
         DEFERRED.put(new DeferredKey(queue.getName().toString(), messageId), DeferredState.AVAILABLE);
      }
   }

   static boolean isDeferred(String queueName, long messageId) {
      return DEFERRED.containsKey(new DeferredKey(queueName, messageId));
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

   @Override
   public void afterDestroyQueue(Queue queue, SimpleString address, SecurityAuth session,
                                 boolean checkConsumerCount, boolean removeConsumers, boolean autoDeleteAddress) {
      String queueName = queue.getName().toString();
      DEFERRED.keySet().removeIf(key -> key.queueName().equals(queueName));
   }

   public static boolean handle(ProtonServerReceiverContext receiver, SimpleString address,
                                AMQPMessage request, Delivery delivery) throws Exception {
      if (address == null || !address.toString().endsWith(SUFFIX) || request.getApplicationProperties() == null) {
         return false;
      }
      Map<?, ?> properties = request.getApplicationProperties().getValue();
      Object operation = properties.get("operation");
      if (!RECEIVE_BY_SEQUENCE_NUMBER.equals(operation) && !UPDATE_DISPOSITION.equals(operation)) {
         return false;
      }
      AMQPConnectionContext connection = receiver.connection;
      String linkName = properties.get("associated-link-name") instanceof String name ? name : null;
      ActiveMQServer server = connection.getProtocolManager().getServer();
      Reply reply;
      try {
         if (!(request.getBody() instanceof AmqpValue value) || !(value.getValue() instanceof Map<?, ?> body)) {
            throw new IllegalArgumentException("Request body must be an AMQP map");
         }
         Queue queue = server.locateQueue(SimpleString.of(entityPath(address)));
         if (queue == null) {
            reply = Reply.error(404, "The messaging entity could not be found", Symbol.valueOf("amqp:not-found"));
         } else if (RECEIVE_BY_SEQUENCE_NUMBER.equals(operation)) {
            reply = receive(queue, body, connection, linkName);
         } else {
            reply = updateDisposition(queue, body, connection, linkName);
         }
      } catch (IllegalArgumentException e) {
         LOGGER.warn("Invalid Service Bus deferral request", e);
         reply = Reply.error(400, e.getMessage() == null ? "Bad Request" : e.getMessage(), INVALID_FIELD);
      } catch (Exception e) {
         // As the management plugin answers a request it fails to process.
         LOGGER.error("Could not process Service Bus deferral request", e);
         reply = Reply.error(500, "Internal Server Error", Symbol.valueOf("amqp:internal-error"));
      }
      ServiceBusLockRenewalSupport.reply(receiver, address, request, delivery, reply.applicationProperties(),
         reply.body());
      return true;
   }

   static Reply receive(Queue queue, Map<?, ?> body, AMQPConnectionContext connection, String linkName)
      throws Exception {
      long now = System.currentTimeMillis();
      List<Long> sequenceNumbers = sequenceNumbers(body.get("sequence-numbers"));
      boolean receiveAndDelete = RECEIVE_AND_DELETE.equals(body.get("receiver-settle-mode"));
      String sessionId = body.get("session-id") instanceof String value && !value.isEmpty() ? value : null;
      String queueName = queue.getName().toString();
      if (isSessionEntity(queue) && !ownsSession(connection, queueName, linkName, sessionId)) {
         return sessionLockLost();
      }

      Map<Long, MessageReference> found = deferredReferences(queue, sequenceNumbers, sessionId);
      if (found.size() < sequenceNumbers.size()) {
         List<Long> missing = new ArrayList<>(sequenceNumbers);
         missing.removeAll(found.keySet());
         return messageNotFound(missing);
      }

      // Claim every message before anything is handed out or deleted. A message that is locked or
      // being settled is not found, as Azure documents for MessageNotFound. ReceiveAndDelete
      // settles at once, so its claim is a settling one that no other request can take over.
      long duration = lockDurationMillis(queue);
      Map<MessageReference, DeferredState> claimed = new LinkedHashMap<>();
      for (MessageReference reference : boundedBatch(sequenceNumbers, found)) {
         DeferredKey key = new DeferredKey(queueName, reference.getMessageID());
         DeferredState claim = new DeferredState(new DeferredLock(UUID.randomUUID(), now + duration, duration,
            sessionId, AmqpReplyRouting.connectionId(connection), linkName), receiveAndDelete);
         if (DEFERRED.computeIfPresent(key, (ignored, current) -> current.claimable(now) ? claim : current) != claim) {
            claimed.forEach((claimedReference, claimedState) -> DEFERRED.replace(
               new DeferredKey(queueName, claimedReference.getMessageID()), claimedState, DeferredState.AVAILABLE));
            return messageNotFound(List.of(reference.getMessageID()));
         }
         claimed.put(reference, claim);
      }

      List<Map<String, Object>> messages = new ArrayList<>(claimed.size());
      for (Map.Entry<MessageReference, DeferredState> claim : claimed.entrySet()) {
         MessageReference reference = claim.getKey();
         DeferredLock lock = claim.getValue().lock();
         Map<String, Object> entry = new HashMap<>();
         if (receiveAndDelete) {
            entry.put("message", new Binary(encode(reference, 0)));
            queue.deleteReference(reference.getMessageID());
            DEFERRED.remove(new DeferredKey(queueName, reference.getMessageID()), claim.getValue());
         } else {
            entry.put("message", new Binary(encode(reference, lock.until())));
            entry.put("lock-token", lock.token());
            reference.incrementDeliveryCount();
         }
         messages.add(entry);
      }
      return Reply.ok(Map.of("messages", messages));
   }

   /**
    * The first requested messages that fit one reply. The client receives the rest by asking
    * again with their sequence numbers, which stay deferred and unlocked.
    */
   private static List<MessageReference> boundedBatch(List<Long> sequenceNumbers,
                                                      Map<Long, MessageReference> found) {
      List<MessageReference> batch = new ArrayList<>();
      long bytes = 0;
      for (long sequenceNumber : sequenceNumbers) {
         MessageReference reference = found.get(sequenceNumber);
         int size = ((AMQPMessage) reference.getMessage()).getEncodeSize();
         if (batch.size() == MAX_MESSAGE_COUNT
             || (!batch.isEmpty() && bytes + size > MAX_RESPONSE_PAYLOAD_BYTES)) {
            break;
         }
         batch.add(reference);
         bytes += size;
      }
      return batch;
   }

   static Reply updateDisposition(Queue queue, Map<?, ?> body, AMQPConnectionContext connection,
                                  String linkName) throws Exception {
      long now = System.currentTimeMillis();
      String status = body.get("disposition-status") instanceof String value ? value.toLowerCase(Locale.ROOT) : "";
      if (!Set.of("completed", "abandoned", "suspended", "defered").contains(status)) {
         throw new IllegalArgumentException("disposition-status '" + status + "' is not valid");
      }
      List<UUID> lockTokens = lockTokens(body.get("lock-tokens"));
      if (new HashSet<>(lockTokens).size() < lockTokens.size()) {
         return lockLost();
      }
      String queueName = queue.getName().toString();
      // Move every message from locked to settling before settling any of them. A settling
      // message cannot be claimed, renewed or settled by another request, and its lock cannot
      // expire, so only this request ever moves it on.
      List<Settlement> settlements = new ArrayList<>();
      for (UUID lockToken : lockTokens) {
         HeldLock held = heldLock(queueName, lockToken, now);
         Reply refusal = null;
         if (held == null || !heldByCaller(held.state().lock(), connection, linkName)) {
            refusal = lockLost();
         } else if (held.state().lock().sessionId() != null
            && !ownsSession(connection, queueName, linkName, held.state().lock().sessionId())) {
            refusal = sessionLockLost();
         } else {
            DeferredState settling = new DeferredState(held.state().lock(), true);
            if (DEFERRED.replace(held.key(), held.state(), settling)) {
               settlements.add(new Settlement(held.key(), held.state(), settling));
            } else {
               refusal = lockLost();
            }
         }
         if (refusal != null) {
            settlements.forEach(settlement -> DEFERRED.replace(settlement.key(), settlement.settling(),
               settlement.locked()));
            return refusal;
         }
      }
      for (int settled = 0; settled < settlements.size(); settled++) {
         try {
            settle(queue, settlements.get(settled), status, body);
         } catch (Exception e) {
            // Messages already settled stay settled. This one and the rest go back to their lock,
            // so they are not left settling, where nothing could ever move them on.
            settlements.subList(settled, settlements.size()).forEach(settlement ->
               DEFERRED.replace(settlement.key(), settlement.settling(), settlement.locked()));
            throw e;
         }
      }
      return Reply.ok(Map.of());
   }

   /** Renews a deferred message's lock: the new deadline, or 0 when the caller holds no active lock on it. */
   static long renew(AMQPConnectionContext connection, String entity, String linkName, UUID lockToken) {
      long now = System.currentTimeMillis();
      HeldLock held = heldLock(entity, lockToken, now);
      if (held == null || !heldByCaller(held.state().lock(), connection, linkName)) {
         return 0;
      }
      DeferredLock lock = held.state().lock();
      if (lock.sessionId() != null && !ownsSession(connection, entity, linkName, lock.sessionId())) {
         return 0;
      }
      DeferredState renewed = new DeferredState(new DeferredLock(lock.token(), now + lock.duration(),
         lock.duration(), lock.sessionId(), lock.connectionId(), lock.linkName()), false);
      return DEFERRED.replace(held.key(), held.state(), renewed) ? renewed.lock().until() : 0;
   }

   /** The entry whose active, unsettled lock has this token, as a snapshot to compare-and-set against. */
   private static HeldLock heldLock(String queueName, UUID lockToken, long now) {
      for (Map.Entry<DeferredKey, DeferredState> entry : DEFERRED.entrySet()) {
         DeferredState state = entry.getValue();
         if (entry.getKey().queueName().equals(queueName) && state.heldBy(lockToken, now)) {
            return new HeldLock(entry.getKey(), state);
         }
      }
      return null;
   }

   /**
    * The receiver that took the lock: its connection, and its link when either side names one.
    * Ordinary lock renewal (ServiceBusMessageLockSupport) scopes a lock the same way.
    */
   private static boolean heldByCaller(DeferredLock lock, AMQPConnectionContext connection, String linkName) {
      return lock.connectionId().equals(AmqpReplyRouting.connectionId(connection))
         && (linkName == null || lock.linkName() == null || linkName.equals(lock.linkName()));
   }

   private static boolean isSessionEntity(Queue queue) {
      return queue.getUser() != null && queue.getUser().toString().startsWith(SESSION_PREFIX);
   }

   private static boolean ownsSession(AMQPConnectionContext connection, String queueName, String linkName,
                                      String sessionId) {
      return sessionId != null && ServiceBusSessionSupport.owns(connection, queueName, linkName, sessionId);
   }

   private static Reply messageNotFound(List<Long> sequenceNumbers) {
      return Reply.error(404, "Failed to retrieve the deferred message(s) with sequence number(s) " + sequenceNumbers,
         MESSAGE_NOT_FOUND);
   }

   private static Reply lockLost() {
      return Reply.error(410, "The lock supplied is invalid. Either the lock expired, or the message has "
         + "already been removed from the queue", MESSAGE_LOCK_LOST);
   }

   private static Reply sessionLockLost() {
      return Reply.error(410, "The session lock has expired or is not held by this receiver", SESSION_LOCK_LOST);
   }

   private static void settle(Queue queue, Settlement settlement, String status, Map<?, ?> body) throws Exception {
      long messageId = settlement.key().messageId();
      switch (status) {
         case "completed" -> {
            queue.deleteReference(messageId);
            DEFERRED.remove(settlement.key(), settlement.settling());
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
            queue.sendMessageToDeadLetterAddress(messageId);
            DEFERRED.remove(settlement.key(), settlement.settling());
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
            DEFERRED.replace(settlement.key(), settlement.settling(), DeferredState.AVAILABLE);
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
      return new ArrayList<>(new LinkedHashSet<>(numbers));
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

   /**
    * A lock handed out by receive-by-sequence-number, scoped to the receiver that asked for it. It
    * keeps the connection's id (AmqpReplyRouting) rather than the connection, so an expired lock
    * holds nothing of a client that has gone.
    */
   private record DeferredLock(UUID token, long until, long duration, String sessionId,
                               String connectionId, String linkName) {
   }

   /** A deferred message's state: available, locked (an unexpired lock) or being settled. */
   private record DeferredState(DeferredLock lock, boolean settling) {
      static final DeferredState AVAILABLE = new DeferredState(null, false);

      boolean claimable(long now) {
         return !settling && (lock == null || lock.until() <= now);
      }

      boolean heldBy(UUID lockToken, long now) {
         return !settling && lock != null && lock.token().equals(lockToken) && lock.until() > now;
      }
   }

   private record HeldLock(DeferredKey key, DeferredState state) {
   }

   private record Settlement(DeferredKey key, DeferredState locked, DeferredState settling) {
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
