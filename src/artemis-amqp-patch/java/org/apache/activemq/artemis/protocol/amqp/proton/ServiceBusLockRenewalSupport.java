package org.apache.activemq.artemis.protocol.amqp.proton;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPStandardMessage;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Properties;
import org.apache.qpid.proton.engine.Delivery;

/** Azure AMQP renewal operations, dispatched on the requesting connection's event loop. */
public final class ServiceBusLockRenewalSupport {
   private static final String SUFFIX = "/$management";
   private static final String MESSAGE_RENEW = "com.microsoft:renew-lock";
   private static final String SESSION_RENEW = "com.microsoft:renew-session-lock";

   private ServiceBusLockRenewalSupport() {
   }

   public static boolean handle(ProtonServerReceiverContext receiver, SimpleString address,
                                AMQPMessage request, Delivery delivery) throws Exception {
      AMQPConnectionContext connection = receiver.connection;
      if (address == null || !address.toString().endsWith(SUFFIX)
          || request.getApplicationProperties() == null) {
         return false;
      }
      Map<?, ?> properties = request.getApplicationProperties().getValue();
      Object operation = properties.get("operation");
      if (!MESSAGE_RENEW.equals(operation) && !SESSION_RENEW.equals(operation)) {
         return false;
      }
      String entity = ServiceBusSessionSupport.normalizeEntityPath(
         address.toString().substring(0, address.length() - SUFFIX.length()));
      if (entity.startsWith("/")) {
         entity = entity.substring(1);
      }
      String link = properties.get("associated-link-name") instanceof String name ? name : null;
      int status = 200;
      Map<String, Object> response;
      String condition = SESSION_RENEW.equals(operation)
         ? "com.microsoft:session-lock-lost" : "com.microsoft:message-lock-lost";
      try {
         if (!(request.getBody() instanceof AmqpValue value)
             || !(value.getValue() instanceof Map<?, ?> body)) {
            throw new IllegalArgumentException("Renewal body must be an AMQP map");
         }
         if (SESSION_RENEW.equals(operation)) {
            if (!(body.get("session-id") instanceof String sessionId) || sessionId.isEmpty()) {
               throw new IllegalArgumentException("session-id is required");
            }
            long until = ServiceBusSessionSupport.renew(connection.getProtocolManager().getServer(),
               connection, entity, link, sessionId);
            response = until == 0 ? Map.of() : Map.of("expiration", new Date(until));
         } else {
            Object tokens = body.get("lock-tokens");
            List<?> values = tokens instanceof Object[] array ? List.of(array)
               : tokens instanceof List<?> list ? list : List.of();
            if (values.isEmpty() || values.stream().anyMatch(token -> !(token instanceof UUID))) {
               throw new IllegalArgumentException("lock-tokens must contain UUIDs");
            }
            List<Date> expirations = new ArrayList<>();
            for (Object token : values) {
               long until = ServiceBusMessageLockSupport.renew(connection, entity, link, (UUID) token);
               if (until == 0) {
                  expirations.clear();
                  break;
               }
               expirations.add(new Date(until));
            }
            response = expirations.isEmpty() ? Map.of()
               : Map.of("expirations", expirations.toArray(Date[]::new));
         }
         if (response.isEmpty()) {
            status = 410;
         }
      } catch (IllegalArgumentException e) {
         status = 400;
         condition = "amqp:invalid-field";
         response = Map.of();
      }
      Properties replyProperties = new Properties();
      replyProperties.setCorrelationId(request.getProperties().getMessageId());
      var server = connection.getProtocolManager().getServer();
      var reply = AMQPStandardMessage.createMessage(server.getStorageManager().generateID(), 0,
         null, null, replyProperties, null, null,
         status == 200 ? Map.of("statusCode", status, "statusDescription", "OK")
            : Map.of("statusCode", status, "statusDescription", "Lock renewal failed",
               "errorCondition", org.apache.qpid.proton.amqp.Symbol.valueOf(condition)),
         null, new AmqpValue(response));
      reply.setAddress(address);
      server.getPostOffice().route(reply, false);
      delivery.disposition(Accepted.getInstance());
      receiver.settle(delivery);
      connection.flush();
      return true;
   }
}
