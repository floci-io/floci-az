package io.floci.az.services.servicebus;

import io.floci.az.core.XmlParser;

import java.time.Duration;
import java.time.format.DateTimeParseException;

/** Parses settings shared by Service Bus queue and topic descriptions. */
final class ServiceBusEntityXml {

    private static final Duration DEFAULT_DUPLICATE_DETECTION_HISTORY = Duration.ofMinutes(10);
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
}
