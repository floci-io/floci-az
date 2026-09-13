package org.apache.activemq.artemis.protocol.amqp.broker;

import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.activemq.artemis.protocol.amqp.util.NettyWritable;
import org.apache.activemq.artemis.protocol.amqp.util.TLSEncode;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Header;
import org.apache.qpid.proton.amqp.messaging.MessageAnnotations;
import org.apache.qpid.proton.amqp.messaging.Properties;
import org.apache.qpid.proton.codec.EncoderImpl;
import org.apache.qpid.proton.codec.WritableBuffer;

/** Preserves in-memory broker metadata when routing or dead-lettering a streamed message. */
public final class ServiceBusLargeMessageSupport {
   private ServiceBusLargeMessageSupport() {
   }

   public static ByteBuf headerForCopy(AMQPLargeMessage message, AtomicInteger bodyPosition, boolean deadLetterOrExpiry) {
      Header currentHeader = AMQPMessageBrokerAccessor.getCurrentHeader(message);
      Properties currentProperties = AMQPMessageBrokerAccessor.getCurrentProperties(message);
      MessageAnnotations annotations = AMQPMessageBrokerAccessor.getDecodedMessageAnnotations(message);
      ApplicationProperties applicationProperties = AMQPMessageBrokerAccessor.getDecodedApplicationProperties(message);
      Header header = currentHeader == null ? null : new Header(currentHeader);
      Properties properties = currentProperties == null ? null : new Properties(currentProperties);
      if (deadLetterOrExpiry) {
         if (header != null) {
            header.setTtl(null);
         }
         if (properties != null) {
            properties.setAbsoluteExpiryTime(null);
         }
      }

      bodyPosition.set(AMQPMessageBrokerAccessor.getRemainingBodyPosition(message));
      ByteBuf buffer = Unpooled.buffer();
      EncoderImpl encoder = TLSEncode.getEncoder();
      WritableBuffer previous = encoder.getBuffer();
      try {
         encoder.setByteBuffer(new NettyWritable(buffer));
         if (header != null) {
            encoder.writeObject(header);
         }
         if (annotations != null) {
            encoder.writeObject(annotations);
         }
         if (properties != null) {
            encoder.writeObject(properties);
         }
         if (applicationProperties != null) {
            encoder.writeObject(applicationProperties);
         }
         return buffer;
      } catch (RuntimeException | Error error) {
         buffer.release();
         throw error;
      } finally {
         encoder.setByteBuffer(previous);
      }
   }
}
