package io.floci.az.services.servicebus;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void deliverySettingsAbsentElementsAreNull() {
        ServiceBusEntityXml.DeliverySettings settings = ServiceBusEntityXml.parseDelivery("");

        assertNull(settings.maxDeliveryCount());
        assertNull(settings.lockDurationSeconds());
    }

    @Test
    void parsesMaxDeliveryCountAndLockDuration() {
        ServiceBusEntityXml.DeliverySettings settings = ServiceBusEntityXml.parseDelivery("""
                <QueueDescription>
                  <LockDuration>PT30S</LockDuration>
                  <MaxDeliveryCount>5</MaxDeliveryCount>
                </QueueDescription>
                """);

        assertEquals(5, settings.maxDeliveryCount());
        assertEquals(30L, settings.lockDurationSeconds());
    }

    @Test
    void rejectsMaxDeliveryCountOutsideAzureLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusEntityXml.parseDelivery(
                        "<MaxDeliveryCount>0</MaxDeliveryCount>"));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusEntityXml.parseDelivery(
                        "<MaxDeliveryCount>2001</MaxDeliveryCount>"));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusEntityXml.parseDelivery(
                        "<MaxDeliveryCount>many</MaxDeliveryCount>"));
    }

    @Test
    void rejectsLockDurationOutsideAzureLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusEntityXml.parseDelivery(
                        "<LockDuration>PT6M</LockDuration>"));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusEntityXml.parseDelivery(
                        "<LockDuration>PT0S</LockDuration>"));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceBusEntityXml.parseDelivery(
                        "<LockDuration>soon</LockDuration>"));
    }
}
