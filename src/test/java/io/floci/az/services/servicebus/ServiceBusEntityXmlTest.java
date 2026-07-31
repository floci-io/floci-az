package io.floci.az.services.servicebus;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceBusEntityXmlTest {

    @Test
    void defaultsToFourteenDaysWithoutDeadLettering() {
        ServiceBusEntityXml.MessageLifetimeSettings settings =
                ServiceBusEntityXml.parseMessageLifetime("");

        assertEquals(Duration.ofDays(14).toMillis(), settings.ttlMillis());
        assertFalse(settings.deadLetterOnExpiration());
    }

    @Test
    void parsesFractionalDurationAndDeadLetterFlag() {
        ServiceBusEntityXml.MessageLifetimeSettings settings =
                ServiceBusEntityXml.parseMessageLifetime("""
                        <QueueDescription>
                          <DefaultMessageTimeToLive>PT1.5S</DefaultMessageTimeToLive>
                          <DeadLetteringOnMessageExpiration>true</DeadLetteringOnMessageExpiration>
                        </QueueDescription>
                        """);

        assertEquals(1_500, settings.ttlMillis());
        assertTrue(settings.deadLetterOnExpiration());
    }

    @Test
    void rejectsInvalidAndNonPositiveDurations() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusEntityXml.parseMessageLifetime(
                        "<DefaultMessageTimeToLive>soon</DefaultMessageTimeToLive>"));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusEntityXml.parseMessageLifetime(
                        "<DefaultMessageTimeToLive>-PT1S</DefaultMessageTimeToLive>"));
    }
}
