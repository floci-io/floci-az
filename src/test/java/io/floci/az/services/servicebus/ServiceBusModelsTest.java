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

    @Test
    void legacyEntitiesUseDefaultMessageTtl() throws Exception {
        ServiceBusModels.QueueEntity queue = objectMapper.readValue("""
                {
                  "name": "legacy-queue",
                  "maxDeliveryCount": 10,
                  "lockDurationSeconds": 60,
                  "maxSizeInMegabytes": 1024,
                  "requiresSession": false
                }
                """, ServiceBusModels.QueueEntity.class);
        ServiceBusModels.TopicEntity topic = objectMapper.readValue("""
                {
                  "name": "legacy-topic",
                  "maxSizeInMegabytes": 1024
                }
                """, ServiceBusModels.TopicEntity.class);
        ServiceBusModels.SubscriptionEntity subscription = objectMapper.readValue("""
                {
                  "topicName": "legacy-topic",
                  "name": "legacy-subscription",
                  "maxDeliveryCount": 10,
                  "lockDurationSeconds": 60,
                  "requiresSession": false
                }
                """, ServiceBusModels.SubscriptionEntity.class);

        assertEquals(ServiceBusEntityXml.DEFAULT_MESSAGE_TTL_MILLIS,
                queue.defaultMessageTtlMillis());
        assertEquals(ServiceBusEntityXml.DEFAULT_MESSAGE_TTL_MILLIS,
                topic.defaultMessageTtlMillis());
        assertEquals(ServiceBusEntityXml.DEFAULT_MESSAGE_TTL_MILLIS,
                subscription.defaultMessageTtlMillis());
    }
}
