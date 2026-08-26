package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceBusNamespaceManagerTest {

    @Test
    void parsesNumericAndTextJolokiaMessageCounts() throws IOException {
        assertEquals(7, ServiceBusNamespaceManager.parseJolokiaMessageCount(
                "{\"status\":200,\"value\":7}"));
        assertEquals(11, ServiceBusNamespaceManager.parseJolokiaMessageCount(
                "{\"status\":200,\"value\":\"11\"}"));
    }

    @Test
    void rejectsFailedOrMalformedJolokiaMessageCounts() {
        assertThrows(IOException.class, () ->
                ServiceBusNamespaceManager.parseJolokiaMessageCount(
                        "{\"status\":404,\"error\":\"not found\"}"));
        assertThrows(IOException.class, () ->
                ServiceBusNamespaceManager.parseJolokiaMessageCount(
                        "{\"status\":200,\"value\":\"unknown\"}"));
        assertThrows(IOException.class, () ->
                ServiceBusNamespaceManager.parseJolokiaMessageCount(
                        "{\"status\":200,\"value\":-1}"));
    }

    @Test
    void batchesAllQueueAndDeadLetterCountsIntoOneJolokiaRequest() throws IOException {
        String body = ServiceBusNamespaceManager.jolokiaMessageCountRequest(
                "default", List.of("orders", "topic/Subscriptions/processor"));

        var requests = new ObjectMapper().readTree(body);
        assertEquals(4, requests.size());
        assertTrue(requests.get(0).path("mbean").asText().contains("queue=\"orders\""));
        assertTrue(requests.get(1).path("mbean").asText()
                .contains("queue=\"orders/$DeadLetterQueue\""));
        assertTrue(requests.get(2).path("mbean").asText()
                .contains("queue=\"topic/Subscriptions/processor\""));
        assertTrue(requests.get(3).path("mbean").asText()
                .contains("queue=\"topic/Subscriptions/processor/$DeadLetterQueue\""));
    }

    @Test
    void parsesBatchedQueueAndDeadLetterCounts() throws IOException {
        var counts = ServiceBusNamespaceManager.parseJolokiaMessageCounts(
                List.of("orders", "processor"),
                "[{\"status\":200,\"value\":3},{\"status\":200,\"value\":1},"
                        + "{\"status\":200,\"value\":5},{\"status\":200,\"value\":2}]");

        assertEquals(new ServiceBusNamespaceManager.MessageCounts(3, 1), counts.get("orders"));
        assertEquals(new ServiceBusNamespaceManager.MessageCounts(5, 2), counts.get("processor"));
    }

    @Test
    void rejectsIncompleteBatchedCountResponses() {
        assertThrows(IOException.class, () ->
                ServiceBusNamespaceManager.parseJolokiaMessageCounts(
                        List.of("orders"), "[{\"status\":200,\"value\":3}]"));
    }

    @Test
    void queueMBeanTargetsAnycastAddressAndQueue() {
        String mbean = ServiceBusNamespaceManager.queueMBean(
                "default", "orders/Subscriptions/processor");

        assertTrue(mbean.contains("broker=\"floci-az-servicebus-default\""));
        assertTrue(mbean.contains("address=\"orders/Subscriptions/processor\""));
        assertTrue(mbean.contains("routing-type=\"anycast\""));
        assertTrue(mbean.endsWith("queue=\"orders/Subscriptions/processor\""));
    }

    @Test
    void mockedNamespaceReturnsZeroCountsWithoutJolokia() {
        var manager = new ServiceBusNamespaceManager(null, null, null, null, null);
        manager.startMockedNamespace("default");

        ServiceBusNamespaceManager.MessageCounts counts =
                manager.getMessageCounts("default", "queue");

        assertEquals(0, counts.active());
        assertEquals(0, counts.deadLetter());
        assertEquals(0, counts.total());
    }
}
