package org.apache.activemq.artemis.protocol.amqp.proton;

/** Reduces the several ways a client can address one event hub down to the entity path itself. */
public final class AmqpEntityAddress {

   private static final String CONSUMER_GROUPS_SEGMENT = "/ConsumerGroups/";
   private static final String PARTITIONS_SEGMENT = "/Partitions/";

   private AmqpEntityAddress() {
   }

   /**
    * Strips the scheme and host from an address that names an event hub, leaving the path.
    *
    * <p>There is no single form a client sends. The Java SDK names the entity alone; the Python and
    * Rust SDKs name it under {@code {scheme}://{host}/}, with the namespace as the host, in
    * whatever case the client was configured with. Artemis matches addresses exactly, so without
    * this every spelling is a different address. Since {@code auto-create-addresses} is on, the
    * spellings with no topology behind them are created empty and their messages discarded with no
    * error anywhere — and worse, the spellings that do have topology have a private copy of it, so
    * a producer on one and a consumer on another read and write different queues while the
    * management node reports a single hub.
    *
    * <p>The scheme is dropped rather than examined: SDKs put {@code amqps} in the address whatever
    * the transport turns out to be, so it carries no information about the connection.
    *
    * <p>Only event-hub-shaped paths are reduced — a bare entity, or one followed by
    * {@code /Partitions/{id}} or {@code /ConsumerGroups/{group}/Partitions/{id}}. floci also serves
    * addresses whose path carries the namespace ({@code amqp://host/{namespace}/{entity}}), and
    * those name a different topology: the same path without a host is the multicast address that
    * path-addressing clients publish to. Reducing one onto the other would merge two address
    * families that are deliberately distinct, so anything that is not event-hub-shaped is returned
    * exactly as it arrived.
    */
   public static String toEntityPath(String address) {
      if (address == null) {
         return null;
      }
      // Unconditional, and not only for the addresses reduced below: clients send an entity path
      // with a leading slash as readily as without one, Service Bus among them, and an address
      // that keeps it matches nothing.
      String path = stripLeadingSlashes(address);

      int schemeEnd = path.indexOf("://");
      if (schemeEnd < 0) {
         return path;
      }
      int hostEnd = path.indexOf('/', schemeEnd + 3);
      if (hostEnd < 0 || hostEnd == path.length() - 1) {
         return path;
      }
      String afterHost = stripLeadingSlashes(path.substring(hostEnd + 1));
      return isEventHubPath(afterHost) ? afterHost : path;
   }

   private static String stripLeadingSlashes(String value) {
      int i = 0;
      while (i < value.length() && value.charAt(i) == '/') {
         i++;
      }
      return value.substring(i);
   }

   /** True for {@code entity}, {@code entity/Partitions/n} and the consumer-group form. */
   private static boolean isEventHubPath(String path) {
      if (path.isEmpty()) {
         return false;
      }
      int consumerGroups = path.indexOf(CONSUMER_GROUPS_SEGMENT);
      if (consumerGroups > 0) {
         return path.indexOf(PARTITIONS_SEGMENT, consumerGroups) > 0;
      }
      int partitions = path.indexOf(PARTITIONS_SEGMENT);
      if (partitions > 0) {
         return path.indexOf('/', partitions + PARTITIONS_SEGMENT.length()) < 0;
      }
      return path.indexOf('/') < 0;
   }
}
