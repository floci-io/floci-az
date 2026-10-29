package org.apache.activemq.artemis.protocol.amqp.proton;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPMessage;
import org.apache.qpid.proton.engine.Record;

import java.util.UUID;

/**
 * Sends the reply to a CBS or management request to the connection that sent the request, and to
 * no other connection.
 *
 * <p>Azure answers a request on {@code $cbs} or {@code $management} on the connection that sent
 * it. Here the reply goes back to the request address, and that address is MULTICAST: each client
 * that attaches a receiver to it gets a subscription queue of its own, so without a filter every
 * client receives every reply. A client that matches replies by correlation id drops the strays.
 * A client that takes the next reply as its own reads another client's reply, or gets one on a
 * link it has already detached and loses the connection. The Rust SDK does both, and it uses the
 * same {@code reply-to} on every connection, so routing by {@code reply-to} cannot tell two
 * clients apart.
 *
 * <p>So the broker stamps each request with the id of the connection that sent it, each
 * responder copies the stamp onto its reply, and a subscription on a reply address matches only
 * the stamp of its own connection.
 *
 * <p>Azure sends no such property, so the client must not receive the stamp. A request carries it
 * as an application property, because the responder that answers it reads the request over AMQP.
 * A reply carries it as a broker property ({@link AMQPMessage#getExtraProperties()}): the broker
 * filters on a broker property but does not send it, so the client receives the reply with only
 * the properties Azure sends.
 */
public final class AmqpReplyRouting {

   /**
    * Name of the stamp: the id of the requesting connection. The code that copies it onto replies
    * is built into other jars and cannot see this constant, so the name is duplicated in
    * {@code AmqpManagementReply} (which both responders build their replies with) and in
    * {@code ServiceBusManagementPlugin}.
    */
   public static final String CONNECTION_ID_PROPERTY = "floci_connection_id";

   private static final SimpleString STAMP = SimpleString.of(CONNECTION_ID_PROPERTY);

   /** Every CBS and management request names its operation. A reply never does. */
   private static final String OPERATION = "operation";

   /** Service Bus puts the entity path in front of {@code $management}; Event Hubs does not. */
   private static final String CBS_NODE = "$cbs";
   private static final String MANAGEMENT_NODE = "$management";

   private static final String CONNECTION_ID_KEY = AmqpReplyRouting.class.getName();

   private AmqpReplyRouting() {
   }

   /**
    * True for an address a client receives replies on: {@code $cbs}, {@code $management} or
    * {@code {entity}/$management}. The intercept addresses the responders read requests from are
    * not reply addresses.
    */
   public static boolean isReplyAddress(SimpleString address) {
      if (address == null) {
         return false;
      }
      String path = address.toString();
      String node = path.substring(path.lastIndexOf('/') + 1);
      return CBS_NODE.equals(node) || MANAGEMENT_NODE.equals(node);
   }

   /**
    * Handles a message that arrives on a reply address, before the broker routes it.
    *
    * <p>A request gets the id of the connection that sent it. A stamp the client set itself is
    * overwritten, so a client cannot ask for the replies of another connection.
    *
    * <p>A reply comes from a responder, which copied the stamp of its request as an application
    * property. The stamp moves to the broker properties of the reply, so the client does not
    * receive it.
    *
    * @param linkAddress the target address of the link the message arrived on; null on an
    *                    anonymous relay link, where the message carries its own address
    */
   public static void onReceive(AMQPConnectionContext connection, SimpleString linkAddress, Message message) {
      SimpleString address = linkAddress != null ? linkAddress : message.getAddressSimpleString();
      if (!isReplyAddress(address) || !(message instanceof AMQPMessage amqpMessage)) {
         return;
      }
      if (amqpMessage.getObjectProperty(OPERATION) != null) {
         stampRequest(connection, amqpMessage);
      } else {
         hideStamp(amqpMessage);
      }
   }

   /**
    * Copies the stamp of a request onto a reply the broker builds itself. The stamp goes to the
    * broker properties of the reply, so the client does not receive it.
    */
   public static void copyStamp(Message request, AMQPMessage reply) {
      Object connectionId = request.getObjectProperty(CONNECTION_ID_PROPERTY);
      if (connectionId != null) {
         holdStamp(reply, connectionId);
      }
   }

   /**
    * The selector of a subscription on {@code address}. On a reply address it matches only the
    * replies stamped with this connection's id, and never a request: a client receives replies
    * only, as in Azure. The client's own selector, if it sent one, is kept in parentheses so that
    * an {@code OR} in it cannot widen the match. On any other address the client's selector is
    * returned unchanged.
    *
    * <p>Artemis looks a selector's property up in the application properties first and in the
    * broker properties next ({@code AMQPMessage#getObjectPropertyForFilter}), so the selector
    * matches the stamp on a reply, where it is a broker property.
    */
   public static String replySelector(AMQPConnectionContext connection, SimpleString address, String selector) {
      if (!isReplyAddress(address)) {
         return selector;
      }
      String ownReplies = CONNECTION_ID_PROPERTY + " = '" + connectionId(connection) + "' AND " + OPERATION + " IS NULL";
      return selector == null || selector.isBlank() ? ownReplies : "(" + selector + ") AND " + ownReplies;
   }

   private static void stampRequest(AMQPConnectionContext connection, AMQPMessage request) {
      request.putStringProperty(CONNECTION_ID_PROPERTY, connectionId(connection));
      // An AMQP message is sent from its encoded form, so the responder would not see a property
      // set here unless the message is encoded again.
      request.reencode();
   }

   /**
    * Moves the stamp of a reply from its application properties, which the client receives, to
    * its broker properties, which the client does not. The reply is not routed yet, so no other
    * queue or consumer holds it.
    */
   private static void hideStamp(AMQPMessage reply) {
      Object connectionId = reply.removeProperty(CONNECTION_ID_PROPERTY);
      if (connectionId == null) {
         return;
      }
      // Encoded again for the same reason as a request: the reply is sent from its encoded form.
      reply.reencode();
      holdStamp(reply, connectionId);
   }

   private static void holdStamp(AMQPMessage reply, Object connectionId) {
      reply.createExtraProperties().putSimpleStringProperty(STAMP, SimpleString.of(connectionId.toString()));
   }

   /**
    * The id of an AMQP connection, made the first time it is asked for and kept with the
    * connection.
    *
    * <p>It is not the connection id Artemis uses. That is the Netty channel id, and its string
    * form is the short one, which Netty documents as not globally unique. A random UUID is unique.
    * Only the connection's own event loop calls this (the attach of a link and the delivery of a
    * message both run there), so the check and the set need no lock.
    */
   static String connectionId(AMQPConnectionContext connection) {
      Record attachments = connection.getHandler().getConnection().attachments();
      String id = attachments.get(CONNECTION_ID_KEY, String.class);
      if (id == null) {
         id = UUID.randomUUID().toString();
         attachments.set(CONNECTION_ID_KEY, String.class, id);
      }
      return id;
   }
}
