package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Consumer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.ServerConsumer;
import org.apache.activemq.artemis.protocol.amqp.exceptions.ActiveMQAMQPException;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.engine.EndpointState;
import org.apache.qpid.proton.engine.Sender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Admits the receivers of an Event Hubs partition in a consumer group, by the rules Azure sets.
 *
 * <p>A receiver may attach with an epoch, which the SDKs call the owner level, in the
 * {@code com.microsoft:epoch} link property. The receiver with the highest epoch owns the partition
 * in its consumer group:
 * <ul>
 *    <li>A receiver with an epoch disconnects every receiver with a lower or equal epoch, and every
 *    receiver without one.</li>
 *    <li>A receiver with a lower epoch than the owner's is refused.</li>
 *    <li>A receiver without an epoch is refused while the partition has an owner.</li>
 * </ul>
 * Both a disconnect and a refusal carry {@code amqp:link:stolen}, which the SDKs report as the
 * consumer disconnected.
 *
 * <p>Without an owner, at most five receivers read one partition of one consumer group. The next one
 * is refused with {@code amqp:resource-limit-exceeded}, which the SDKs report as quota exceeded.
 *
 * <p>A partition of a consumer group is one consumer group queue, and its receivers are that queue's
 * consumers, so the rules read the broker's own state and keep none of their own. Every consumer
 * group queue is non-destructive (see {@code ArtemisConfigGenerator}). That is how a queue is told
 * apart from any other, a Service Bus queue included, which keeps no such rules.
 */
public final class EventHubReceivers {

   private static final Logger LOGGER = LoggerFactory.getLogger(EventHubReceivers.class);

   /** Azure's limit of receivers per partition per consumer group. */
   static final int MAX_RECEIVERS = 5;
   static final Symbol EPOCH = Symbol.valueOf("com.microsoft:epoch");
   static final Symbol RESOURCE_LIMIT_EXCEEDED = Symbol.valueOf("amqp:resource-limit-exceeded");
   static final Symbol LINK_STOLEN = Symbol.valueOf("amqp:link:stolen");

   /** Admits one receiver at a time, so that two attaching at once cannot both take the last place. */
   private static final Object ADMISSION = new Object();

   private EventHubReceivers() {
   }

   /**
    * Creates the consumer of a receiver that attaches to {@code queueName}, once Azure's rules
    * admit it, and then disconnects the receivers it takes the partition from. A queue that is not
    * a consumer group queue admits every receiver.
    */
   public static ServerConsumer attach(ActiveMQServer server, SimpleString queueName, Sender link,
                                       Callable<ServerConsumer> create) throws Exception {
      Queue queue = server.locateQueue(queueName);
      if (queue == null || !queue.isNonDestructive()) {
         return create.call();
      }
      synchronized (ADMISSION) {
         List<ProtonServerSenderContext> displaced = admit(open(queue.getConsumers()), link);
         ServerConsumer consumer = create.call();
         displaced.forEach(receiver -> disconnect(receiver, link));
         return consumer;
      }
   }

   /**
    * Refuses {@code link} when the receivers already open on its partition do not admit it, and
    * otherwise returns the receivers it disconnects.
    */
   static List<ProtonServerSenderContext> admit(List<ProtonServerSenderContext> receivers, Sender link)
      throws ActiveMQAMQPException {
      Long epoch = epoch(link);
      Sender owner = receivers.stream()
         .map(ProtonServerSenderContext::getSender)
         .filter(receiver -> epoch(receiver) != null)
         .max(Comparator.comparing(EventHubReceivers::epoch))
         .orElse(null);
      Long ownerEpoch = owner == null ? null : epoch(owner);
      if (epoch == null) {
         if (owner != null) {
            throw refused(LINK_STOLEN, "At least one receiver for the endpoint is created with epoch of '"
               + ownerEpoch + "', and so non-epoch receiver is not allowed. Either reconnect with a higher epoch,"
               + " or make sure all epoch receivers are closed or disconnected.");
         }
         if (receivers.size() >= MAX_RECEIVERS) {
            throw refused(RESOURCE_LIMIT_EXCEEDED,
               "Exceeded the maximum number of allowed receivers per partition in a consumer group which is "
                  + MAX_RECEIVERS + ". Receiver '" + link.getName() + "' is refused. List of connected receivers - "
                  + receivers.stream().map(receiver -> receiver.getSender().getName()).collect(Collectors.joining(", "))
                  + ".");
         }
         return List.of();
      }
      if (owner != null && ownerEpoch > epoch) {
         throw refused(LINK_STOLEN, "Receiver '" + owner.getName() + "' with a higher epoch '" + ownerEpoch
            + "' already exists. Receiver '" + link.getName() + "' with epoch " + epoch + " cannot be created."
            + " Make sure you are creating receiver with increasing epoch value to ensure connectivity,"
            + " or ensure all old epoch receivers are closed or disconnected.");
      }
      return receivers;
   }

   /**
    * The receivers of a queue's consumers whose links are still open. A link stays a consumer for a
    * moment after it closes, until its connection removes it, and no longer counts as a receiver then.
    */
   static List<ProtonServerSenderContext> open(Collection<Consumer> consumers) {
      List<ProtonServerSenderContext> receivers = new ArrayList<>();
      for (Consumer consumer : consumers) {
         if (consumer instanceof ServerConsumer server
            && server.getProtocolContext() instanceof ProtonServerSenderContext receiver
            && receiver.getSender().getLocalState() != EndpointState.CLOSED
            && receiver.getSender().getRemoteState() != EndpointState.CLOSED) {
            receivers.add(receiver);
         }
      }
      return receivers;
   }

   /** The epoch a receiver attached with, or null if it has none. */
   static Long epoch(Sender link) {
      Map<Symbol, Object> properties = link.getRemoteProperties();
      return properties != null && properties.get(EPOCH) instanceof Number epoch ? epoch.longValue() : null;
   }

   /** Closes a receiver that {@code link} took the partition from, on the receiver's own connection. */
   private static void disconnect(ProtonServerSenderContext receiver, Sender link) {
      Long epoch = epoch(receiver.getSender());
      ErrorCondition stolen = new ErrorCondition(LINK_STOLEN, "New receiver '" + link.getName()
         + "' with higher epoch of '" + epoch(link) + "' is created hence current receiver '"
         + receiver.getSender().getName() + "' with epoch '" + (epoch == null ? "nil" : epoch)
         + "' is getting disconnected. If you are recreating the receiver, make sure a higher epoch is used.");
      AMQPConnectionContext connection = receiver.getSessionContext().getAMQPConnectionContext();
      connection.runNow(() -> {
         try {
            receiver.close(stolen);
            connection.flush();
         } catch (ActiveMQAMQPException e) {
            LOGGER.warn("Failed to disconnect an Event Hubs receiver from its partition", e);
         }
      });
   }

   private static ActiveMQAMQPException refused(Symbol condition, String message) {
      return new ActiveMQAMQPException(condition, message, ActiveMQExceptionType.GENERIC_EXCEPTION);
   }
}
