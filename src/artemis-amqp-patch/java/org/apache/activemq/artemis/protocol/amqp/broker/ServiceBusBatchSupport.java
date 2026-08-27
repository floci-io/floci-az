package org.apache.activemq.artemis.protocol.amqp.broker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.activemq.artemis.core.persistence.CoreMessageObjectPools;
import org.apache.activemq.artemis.protocol.amqp.util.TLSEncode;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.AmqpSequence;
import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.amqp.messaging.Footer;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.codec.DecoderImpl;
import org.apache.qpid.proton.codec.ReadableBuffer;

/** Decodes Azure Service Bus batched-message-format transfers into individual AMQP messages. */
public final class ServiceBusBatchSupport {

   private static final long BATCHED_MESSAGE_FORMAT = 0x80013700L;

   private ServiceBusBatchSupport() {
   }

   public static boolean isBatch(AMQPMessage message) {
      return (message.getMessageFormat() & 0xFFFFFFFFL) == BATCHED_MESSAGE_FORMAT;
   }

   public static List<AMQPStandardMessage> decode(
      AMQPMessage envelope, CoreMessageObjectPools objectPools) {
      if (!isBatch(envelope)) {
         throw new IllegalArgumentException("Message does not use Azure batched-message-format");
      }

      ReadableBuffer buffer = envelope.getData().duplicate().rewind();
      DecoderImpl decoder = TLSEncode.getDecoder();
      List<byte[]> encodedMessages = new ArrayList<>();

      decoder.setBuffer(buffer);
      try {
         while (buffer.hasRemaining()) {
            Section section = (Section) decoder.readObject();
            if (section instanceof Data data) {
               encodedMessages.add(copyPayload(data));
            } else if (section instanceof AmqpValue || section instanceof AmqpSequence) {
               throw new IllegalArgumentException(
                  "Azure batched-message-format body must contain only AMQP Data sections");
            } else if (section instanceof Footer) {
               break;
            }
         }
      } finally {
         decoder.setBuffer(null);
      }

      if (encodedMessages.isEmpty()) {
         throw new IllegalArgumentException(
            "Azure batched-message-format transfer contains no messages");
      }
      return encodedMessages.stream()
         .map(payload -> new AMQPStandardMessage(
            AMQPMessage.DEFAULT_MESSAGE_FORMAT, payload, null, objectPools))
         .toList();
   }

   private static byte[] copyPayload(Data section) {
      Binary encodedMessage = section.getValue();
      if (encodedMessage == null || encodedMessage.getLength() == 0) {
         throw new IllegalArgumentException(
            "Azure batched-message-format contains an empty message");
      }

      int start = encodedMessage.getArrayOffset();
      return Arrays.copyOfRange(
         encodedMessage.getArray(), start, start + encodedMessage.getLength());
   }
}
