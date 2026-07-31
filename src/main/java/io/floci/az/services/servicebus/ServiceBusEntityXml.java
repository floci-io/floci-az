package io.floci.az.services.servicebus;

import io.floci.az.core.XmlParser;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/** Parses settings shared by Service Bus queue and topic descriptions. */
final class ServiceBusEntityXml {

    static final long DEFAULT_DUPLICATE_DETECTION_HISTORY_SECONDS = Duration.ofMinutes(10).toSeconds();
    static final long DEFAULT_MESSAGE_TTL_MILLIS = Duration.ofDays(14).toMillis();
    private static final Duration DEFAULT_DUPLICATE_DETECTION_HISTORY =
            Duration.ofSeconds(DEFAULT_DUPLICATE_DETECTION_HISTORY_SECONDS);
    private static final long MIN_DUPLICATE_DETECTION_HISTORY_SECONDS = Duration.ofSeconds(20).toSeconds();
    private static final long MAX_DUPLICATE_DETECTION_HISTORY_SECONDS = Duration.ofDays(7).toSeconds();

    private ServiceBusEntityXml() {}

    static DuplicateDetectionSettings duplicateDetection(String xml) {
        boolean enabled = Boolean.parseBoolean(
                XmlParser.extractFirst(xml, "RequiresDuplicateDetection", "false").trim());
        String rawHistory = XmlParser.extractFirst(
                xml, "DuplicateDetectionHistoryTimeWindow",
                DEFAULT_DUPLICATE_DETECTION_HISTORY.toString()).trim();
        long historySeconds;
        try {
            Duration history = Duration.parse(rawHistory);
            if (history.isNegative() || history.isZero() || history.getNano() != 0) {
                throw new IllegalArgumentException(
                        "DuplicateDetectionHistoryTimeWindow must be a whole positive number of seconds");
            }
            historySeconds = history.getSeconds();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "DuplicateDetectionHistoryTimeWindow must be an ISO-8601 duration", e);
        }
        if (historySeconds < MIN_DUPLICATE_DETECTION_HISTORY_SECONDS
                || historySeconds > MAX_DUPLICATE_DETECTION_HISTORY_SECONDS) {
            throw new IllegalArgumentException(
                    "DuplicateDetectionHistoryTimeWindow must be between PT20S and P7D");
        }
        return new DuplicateDetectionSettings(enabled, historySeconds);
    }

    record DuplicateDetectionSettings(boolean enabled, long historySeconds) {}

    static MessageLifetimeSettings parseMessageLifetime(String xml) {
        String value = XmlParser.extractFirst(xml, "DefaultMessageTimeToLive", "P14D").trim();
        final long ttlMillis;
        try {
            ttlMillis = Duration.parse(value).toMillis();
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException(
                    "DefaultMessageTimeToLive must be a valid ISO 8601 duration", e);
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("DefaultMessageTimeToLive must be positive");
        }
        boolean deadLetterOnExpiration = Boolean.parseBoolean(
                XmlParser.extractFirst(xml, "DeadLetteringOnMessageExpiration", "false").trim());
        return new MessageLifetimeSettings(ttlMillis, deadLetterOnExpiration);
    }

    record MessageLifetimeSettings(long ttlMillis, boolean deadLetterOnExpiration) {}
}
