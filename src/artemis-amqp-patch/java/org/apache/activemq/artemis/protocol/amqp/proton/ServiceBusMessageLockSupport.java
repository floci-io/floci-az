package org.apache.activemq.artemis.protocol.amqp.proton;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.activemq.artemis.core.server.MessageReference;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPSessionCallback;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.MessageAnnotations;
import org.apache.qpid.proton.amqp.messaging.Outcome;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.engine.Delivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-delivery Service Bus locks. All state changes run on the connection's event loop. */
public final class ServiceBusMessageLockSupport {
   private static final Logger LOGGER = LoggerFactory.getLogger(ServiceBusMessageLockSupport.class);
   private static final String METADATA_PREFIX = "floci-az:servicebus-peeklock:";
   private static final SimpleString LOCKED_UNTIL = SimpleString.of("x-opt-locked-until");
   private static final Symbol LOCK_LOST = Symbol.valueOf("com.microsoft:message-lock-lost");
   private static final Object EXPIRED = new Object();
   private static final Set<ServiceBusMessageLockSupport> ACTIVE = ConcurrentHashMap.newKeySet();

   private final AMQPConnectionContext connection;
   private final AMQPSessionCallback session;
   private final Map<Delivery, MessageLock> locks = new HashMap<>();

   public ServiceBusMessageLockSupport(AMQPConnectionContext connection, AMQPSessionCallback session) {
      this.connection = connection;
      this.session = session;
   }

   public void track(Delivery delivery, MessageReference reference, ServerConsumer consumer, boolean preSettled) {
      if (preSettled || consumer.getQueue().getUser() == null) {
         return;
      }
      String metadata = consumer.getQueue().getUser().toString();
      if (!metadata.startsWith(METADATA_PREFIX)) {
         return;
      }
      final long duration;
      try {
         duration = Math.multiplyExact(Long.parseLong(metadata.substring(METADATA_PREFIX.length())), 1_000L);
         if (duration <= 0) {
            throw new IllegalArgumentException("Lock duration must be positive");
         }
      } catch (ArithmeticException | IllegalArgumentException e) {
         LOGGER.warn("Ignoring invalid Service Bus message lock metadata: " + metadata, e);
         return;
      }
      MessageLock lock = new MessageLock(reference, consumer, System.currentTimeMillis() + duration, duration);
      reference.setProtocolData(Deadline.class, new Deadline(lock.until));
      locks.put(delivery, lock);
      ACTIVE.add(this);
      lock.timer = connection.getProtocolManager().getServer().getScheduledPool().schedule(
         () -> connection.runLater(() -> expire(delivery, lock)), duration, TimeUnit.MILLISECONDS);
   }

   /** Called on the owning connection event loop, just like settlement and expiration. */
   public long renew(Delivery delivery) {
      MessageLock previous = locks.get(delivery);
      if (previous == null) {
         return 0;
      }
      if (System.currentTimeMillis() >= previous.until || delivery.isSettled()) {
         expire(delivery, previous);
         return 0;
      }
      MessageLock renewed = new MessageLock(previous.reference, previous.consumer,
         System.currentTimeMillis() + previous.duration, previous.duration);
      previous.timer.cancel(false);
      locks.put(delivery, renewed);
      renewed.timer = connection.getProtocolManager().getServer().getScheduledPool().schedule(
         () -> connection.runLater(() -> expire(delivery, renewed)), renewed.duration, TimeUnit.MILLISECONDS);
      return renewed.until;
   }

   static long renew(AMQPConnectionContext connection, String entity, String linkName, UUID token) {
      for (ServiceBusMessageLockSupport support : ACTIVE) {
         if (support.connection != connection) {
            continue;
         }
         for (var entry : support.locks.entrySet()) {
            Delivery delivery = entry.getKey();
            byte[] tag = delivery.getTag();
            if (tag == null || tag.length != 16
                || !entity.equals(entry.getValue().consumer.getQueue().getName().toString())
                || (linkName != null && !linkName.equals(delivery.getLink().getName()))) {
               continue;
            }
            ByteBuffer bytes = ByteBuffer.wrap(tag);
            if (token.equals(new UUID(bytes.getLong(), bytes.getLong()))) {
               return support.renew(delivery);
            }
         }
      }
      return 0;
   }

   private void remove(Delivery delivery) {
      locks.remove(delivery);
      if (locks.isEmpty()) {
         ACTIVE.remove(this);
      }
   }

   /** Reject stale dispositions before Artemis can acknowledge a subsequently redelivered message. */
   public boolean beforeSettlement(Delivery delivery) {
      if (delivery.getContext() == EXPIRED) {
         rejectExpired(delivery);
         return false;
      }
      MessageLock lock = locks.get(delivery);
      if (lock == null) {
         return true;
      }
      if (System.currentTimeMillis() >= lock.until) {
         expire(delivery, lock);
         rejectExpired(delivery);
         return false;
      }
      if (delivery.getRemoteState() instanceof Outcome) {
         remove(delivery);
         lock.timer.cancel(false);
      }
      return true;
   }

   private void expire(Delivery delivery, MessageLock lock) {
      if (!locks.remove(delivery, lock)) {
         return;
      }
      if (locks.isEmpty()) {
         ACTIVE.remove(this);
      }
      lock.timer.cancel(false);
      if (delivery.isSettled()) {
         return;
      }
      try {
         // Retain the AMQP delivery until the peer settles it, so a late disposition gets
         // MessageLockLost instead of falling back to an unsupported management request.
         delivery.setContext(EXPIRED);
         session.cancel(lock.consumer, lock.reference.getMessage(), true);
      } catch (Exception e) {
         LOGGER.error("Could not release an expired Service Bus message lock", e);
         // Cancellation may have partially succeeded. Retrying by message ID could cancel
         // a newer delivery to this consumer. Connection teardown releases its remaining references.
         locks.keySet().forEach(pending -> pending.setContext(EXPIRED));
         close();
         connection.close(new ErrorCondition(org.apache.qpid.proton.amqp.transport.AmqpError.INTERNAL_ERROR,
            "Could not release an expired message lock"));
         connection.destroy();
      }
   }

   private void rejectExpired(Delivery delivery) {
      Rejected rejected = new Rejected();
      rejected.setError(new ErrorCondition(LOCK_LOST, "The message lock has expired"));
      delivery.disposition(rejected);
      delivery.settle();
      connection.flush();
   }

   public void close() {
      locks.values().forEach(lock -> lock.timer.cancel(false));
      locks.clear();
      ACTIVE.remove(this);
   }

   /** Large messages stream their body from disk, so replace only their outgoing metadata. */
   public static MessageAnnotations annotationsForDelivery(MessageAnnotations original, MessageReference reference) {
      Deadline deadline = reference.getProtocolData(Deadline.class);
      if (deadline == null) {
         return original;
      }
      Map<Symbol, Object> annotations = original == null ? new HashMap<>() : new HashMap<>(original.getValue());
      annotations.put(Symbol.valueOf(LOCKED_UNTIL.toString()), new Date(deadline.until()));
      return new MessageAnnotations(annotations);
   }

   private record Deadline(long until) {
   }

   private static final class MessageLock {
      private final MessageReference reference;
      private final ServerConsumer consumer;
      private final long until;
      private final long duration;
      private ScheduledFuture<?> timer;

      private MessageLock(MessageReference reference, ServerConsumer consumer, long until, long duration) {
         this.reference = reference;
         this.consumer = consumer;
         this.until = until;
         this.duration = duration;
      }
   }
}
