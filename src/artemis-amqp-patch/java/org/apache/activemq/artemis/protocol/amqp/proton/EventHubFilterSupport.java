package org.apache.activemq.artemis.protocol.amqp.proton;

import java.util.regex.Pattern;

/** Translates an Event Hubs start position into a selector Artemis can parse. */
public final class EventHubFilterSupport {

   /**
    * Properties the partition plugin stamps, one per annotation an Event Hubs start position can
    * be expressed against. The names are duplicated in {@code EventHubPartitionPlugin}: the plugin
    * and this patch are built into separate jars, so neither can see the other's constants.
    */
   public static final String OFFSET_PROPERTY = "floci_offset";
   public static final String SEQUENCE_NUMBER_PROPERTY = "floci_sequence_number";
   public static final String ENQUEUED_TIME_PROPERTY = "floci_enqueued_time";

   private static final String ANNOTATION_PREFIX = "amqp.annotation.";

   /** {@code amqp.annotation.x-opt-offset > '@latest'} — the start position with no numeric form. */
   private static final Pattern LATEST =
      Pattern.compile("amqp\\.annotation\\.x-opt-offset\\s*>=?\\s*'@latest'");

   /** A quoted operand on one of the rewritten properties, e.g. {@code floci_offset > '-1'}. */
   private static final Pattern QUOTED_OPERAND = Pattern.compile(
      "(" + OFFSET_PROPERTY + "|" + SEQUENCE_NUMBER_PROPERTY + "|" + ENQUEUED_TIME_PROPERTY + ")"
         + "(\\s*(?:>=|<=|<>|>|<|=)\\s*)'(-?\\d+)'");

   private EventHubFilterSupport() {
   }

   /**
    * Rewrites an Event Hubs start-position selector, and returns anything else unchanged.
    *
    * <p>Every start position arrives as a selector over an annotation — including "earliest",
    * which is sent as {@code amqp.annotation.x-opt-offset > '-1'}. Artemis rejects all of them,
    * and not for the reason it first appears: quoted operands are legal, and Artemis reads
    * annotations in selectors under its own {@code m.} prefix, so an unrecognised name simply
    * falls through to a property lookup. The blocker is the hyphens. {@code x-opt-offset} is not
    * an identifier in the selector grammar — it parses as {@code x - opt - offset} — so the
    * expression fails to parse and the receiver's attach is refused before a value is ever
    * compared. Underscored names parse, so the annotations are mapped onto the properties the
    * partition plugin stamps under those names.
    *
    * <p>Quoted numbers are unquoted with them: the stamped properties are numeric, and Artemis
    * compares a number against a string constant only when a thread-local conversion flag happens
    * to be set.
    *
    * <p>{@code @latest} has no numeric form. It means "only what arrives from now on", so it
    * becomes a comparison against the clock — read here, as the link attaches, which is the moment
    * "now" refers to. The comparison is inclusive because the clock has only millisecond
    * resolution: an event enqueued in the same millisecond as the attach is on the wrong side of a
    * strict {@code >} even when it genuinely arrived afterwards, and would then be excluded
    * forever. Inclusive instead risks replaying whatever else landed in that one millisecond,
    * which is a duplicate rather than a loss — and Event Hubs delivery is at-least-once, so a
    * consumer already has to tolerate one. A monotonic broker-local counter would make the
    * boundary exact, but it would restart with the broker, and messages outliving it would
    * compare against an origin that no longer means anything.
    */
   public static String rewriteSelector(String selector) {
      if (selector == null || !selector.contains(ANNOTATION_PREFIX)) {
         return selector;
      }

      String rewritten = LATEST.matcher(selector)
         .replaceAll(ENQUEUED_TIME_PROPERTY + " >= " + System.currentTimeMillis());

      rewritten = rewritten
         .replace(ANNOTATION_PREFIX + "x-opt-enqueued-time", ENQUEUED_TIME_PROPERTY)
         .replace(ANNOTATION_PREFIX + "x-opt-sequence-number", SEQUENCE_NUMBER_PROPERTY)
         .replace(ANNOTATION_PREFIX + "x-opt-offset", OFFSET_PROPERTY);

      return QUOTED_OPERAND.matcher(rewritten).replaceAll("$1$2$3");
   }
}
