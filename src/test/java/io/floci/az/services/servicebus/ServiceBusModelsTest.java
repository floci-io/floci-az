package io.floci.az.services.servicebus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceBusModelsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void legacyQueueUsesDefaultDuplicateDetectionHistory() throws Exception {
        ServiceBusModels.QueueEntity queue = objectMapper.readValue("""
                {
                  "name": "legacy",
                  "maxDeliveryCount": 10,
                  "lockDurationSeconds": 60,
                  "maxSizeInMegabytes": 1024,
                  "requiresSession": false,
                  "requiresDuplicateDetection": false
                }
                """, ServiceBusModels.QueueEntity.class);

        assertEquals(600, queue.duplicateDetectionHistorySeconds());
    }

    @Test
    void legacyTopicUsesDefaultDuplicateDetectionHistory() throws Exception {
        ServiceBusModels.TopicEntity topic = objectMapper.readValue("""
                {
                  "name": "legacy",
                  "maxSizeInMegabytes": 1024,
                  "requiresDuplicateDetection": false
                }
                """, ServiceBusModels.TopicEntity.class);

        assertEquals(600, topic.duplicateDetectionHistorySeconds());
    }
}
