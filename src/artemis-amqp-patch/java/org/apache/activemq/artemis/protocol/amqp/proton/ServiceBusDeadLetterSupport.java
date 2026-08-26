package org.apache.activemq.artemis.protocol.amqp.proton;

import java.util.Map;

import org.apache.activemq.artemis.api.core.ActiveMQPropertyConversionException;
import org.apache.activemq.artemis.api.core.Message;
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
         message.putObjectProperty(entry.getKey().toString(), entry.getValue());
      }
      message.reencode();
   }
}
