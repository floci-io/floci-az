package org.apache.activemq.artemis.protocol.amqp.proton;

import java.util.Date;
import java.util.Map;

import org.apache.activemq.artemis.api.core.ActiveMQPropertyConversionException;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;

/** Applies Azure dead-letter outcome metadata to the message before Artemis reroutes it. */
public final class ServiceBusDeadLetterSupport {

   private ServiceBusDeadLetterSupport() {
   }

   public static void apply(Message message, Rejected rejected)
      throws ActiveMQPropertyConversionException {
      ErrorCondition error = rejected.getError();
      if (error == null || error.getInfo() == null) {
         return;
      }

      for (Object rawEntry : error.getInfo().entrySet()) {
         Map.Entry<?, ?> entry = (Map.Entry<?, ?>) rawEntry;
         message.putObjectProperty(entry.getKey().toString(), compatiblePropertyValue(entry.getValue()));
      }
      message.reencode();
   }

   private static Object compatiblePropertyValue(Object value) {
      if (value == null || value instanceof Boolean || value instanceof Byte
         || value instanceof Short || value instanceof Integer || value instanceof Long
         || value instanceof Float || value instanceof Double || value instanceof String
         || value instanceof SimpleString || value instanceof byte[]) {
         return value;
      }
      if (value instanceof Date date) {
         return date.toInstant().toString();
      }
      return value.toString();
   }
}
