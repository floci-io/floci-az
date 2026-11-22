package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.postoffice.PostOffice;
import org.apache.activemq.artemis.core.server.ActiveMQServer;

/**
 * Resolves a Service Bus entity path a client attached to onto the spelling the entity was
 * created with.
 *
 * <p>Service Bus compares queue, topic and subscription paths ignoring case: a queue created as
 * {@code example-case} accepts a sender on {@code EXAMPLE-CASE}. Artemis matches addresses
 * exactly, so without this every other spelling fails the attach with {@code AMQ119002} (sender)
 * or {@code AMQ119010} (receiver). The lookup covers every address the namespace declares, so a
 * dead-letter queue, a subscription ({@code topic/Subscriptions/sub}) and an entity management
 * node ({@code entity/$management}) resolve the same way as a queue.
 *
 * <p>An exact match always wins, and the scan only runs when there is none. When more than one
 * address matches ignoring case, none is preferred and the path is returned as it arrived, so the
 * attach fails as it did before. The Event Hubs broker is left alone: its addresses are declared
 * by topology and its clients' spelling is reduced by {@link AmqpEntityAddress}.
 */
public final class ServiceBusEntityNames {

   private ServiceBusEntityNames() {
   }

   /** The declared address that {@code entityPath} names ignoring case, else {@code entityPath} unchanged. */
   public static String canonicalEntityPath(ActiveMQServer server, String entityPath) {
      if (entityPath == null || entityPath.isEmpty() || !ServiceBusSessionSupport.isServiceBusBroker(server)) {
         return entityPath;
      }
      PostOffice postOffice = server.getPostOffice();
      if (postOffice == null || postOffice.getAddressInfo(SimpleString.of(entityPath)) != null) {
         return entityPath;
      }
      String match = null;
      for (SimpleString address : postOffice.getAddresses()) {
         String candidate = address.toString();
         if (!candidate.equalsIgnoreCase(entityPath)) {
            continue;
         }
         if (match != null) {
            return entityPath;
         }
         match = candidate;
      }
      return match == null ? entityPath : match;
   }
}
