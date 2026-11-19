package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
public class ServiceBusServiceTest {

    private static final String BASE = "/devstoreaccount1-servicebus";
    private static final String SB_NS =
            "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";
    private static final String SB_COUNT_NS =
            "http://schemas.microsoft.com/netservices/2011/06/servicebus";

    @Test
    void testExistingPathRouting() {
        given()
            .when().get("/devstoreaccount1-servicebus/$namespaceinfo")
            .then()
            .statusCode(200)
            .contentType("application/atom+xml")
            .body(containsString("<NamespaceInfo"));
    }

    @Test
    void testRootLevelPathRouting() {
        // Test routing based on spec path segment
        given()
            .when().get("/$namespaceinfo")
            .then()
            .statusCode(200)
            .contentType("application/atom+xml")
            .body(containsString("<NamespaceInfo"));
    }

    @Test
    void testRootLevelAtomPubHeaderRouting() {
        // Test routing based on Content-Type/Accept header
        given()
            .accept("application/atom+xml")
            .when().get("/some-custom-spec-path")
            .then()
            .statusCode(404) // Should route to ServiceBusHandler and return AtomPub 404 (not foundAtom)
            .contentType("application/atom+xml");
    }

    @Test
    void testHostBasedRouting() {
        // Test routing based on Host header
        given()
            .header("Host", "devstoreaccount1.servicebus.windows.net")
            .when().get("/$namespaceinfo")
            .then()
            .statusCode(200)
            .contentType("application/atom+xml")
            .body(containsString("<NamespaceInfo"));
    }

    @Test
    void apiVersionDoesNotOverrideAnotherServicesAccountSuffix() {
        given()
            .when().get("/routing-appconfig-appconfig/kv?api-version=2024-09-01")
            .then()
            .statusCode(200)
            .body(containsString("\"items\""));
    }

    @Test
    void dotNetAdministrationClientCanUseEntityNameEndingInAccountSuffix() {
        given()
            .header("User-Agent", "azsdk-net-Messaging.ServiceBus/7.20.1")
            .queryParam("api-version", "2021-05")
            .body(entry("<QueueDescription xmlns=\"" + SB_NS + "\"/>"))
            .when().put("/orders-queue")
            .then()
            .statusCode(201)
            .contentType("application/atom+xml")
            .body(containsString("<QueueDescription"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders-table", "orders-appconfig", "orders-keyvault", "orders-functions"})
    void dotNetAdministrationClientOverridesEveryConflictingAccountSuffix(String entityName) {
        given()
            .header("User-Agent", "azsdk-net-Messaging.ServiceBus/7.20.1")
            .queryParam("api-version", "2021-05")
            .body(entry("<QueueDescription xmlns=\"" + SB_NS + "\"/>"))
            .when().put("/" + entityName)
            .then()
            .statusCode(201)
            .contentType("application/atom+xml")
            .body(containsString("<QueueDescription"));
    }

    @Test
    void dotNetAdministrationClientCanUseEntityNameEndingInServiceBusSuffix() {
        given()
            .header("User-Agent", "azsdk-net-Messaging.ServiceBus/7.20.1")
            .queryParam("api-version", "2021-05")
            .body(entry("<QueueDescription xmlns=\"" + SB_NS + "\"/>"))
            .when().put("/orders-servicebus")
            .then()
            .statusCode(201)
            .contentType("application/atom+xml")
            .body(containsString("<QueueDescription"));
    }

    @Test
    void dotNetAdministrationClientCanUseExplicitServiceBusAccountPrefix() {
        given()
            .header("User-Agent", "azsdk-net-Messaging.ServiceBus/7.20.1")
            .queryParam("api-version", "2021-05")
            .body(entry("<QueueDescription xmlns=\"" + SB_NS + "\"/>"))
            .when().put(BASE + "/explicit-dotnet")
            .then()
            .statusCode(201)
            .contentType("application/atom+xml")
            .body(containsString("<QueueDescription"));
    }

    @Test
    void atomPubClientCanUseEntityNameEndingInAccountSuffix() {
        given()
            .contentType("application/atom+xml")
            .accept("application/atom+xml")
            .body(entry("<QueueDescription xmlns=\"" + SB_NS + "\"/>"))
            .when().put("/atom-orders-queue")
            .then()
            .statusCode(201)
            .contentType("application/atom+xml")
            .body(containsString("<QueueDescription"));
    }

    @Test
    void explicitServiceBusAccountSuffixStillStripsPrefixForAtomPub() {
        given()
            .contentType("application/atom+xml")
            .body("")
            .when().put("/devstoreaccount1-servicebus/default/queues/atom-explicit")
            .then()
            .statusCode(201);
    }

    @Test
    void queuePersistsDuplicateDetectionSettings() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"
                + "<DuplicateDetectionHistoryTimeWindow>PT20S</DuplicateDetectionHistoryTimeWindow>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/duplicate-queue")
                .then().statusCode(201)
                .body(containsString("<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"))
                .body(containsString(
                        "<DuplicateDetectionHistoryTimeWindow>PT20S</DuplicateDetectionHistoryTimeWindow>"));

        given().when().get(BASE + "/duplicate-queue")
                .then().statusCode(200)
                .body(containsString("<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"))
                .body(containsString(
                        "<DuplicateDetectionHistoryTimeWindow>PT20S</DuplicateDetectionHistoryTimeWindow>"));
    }

    @Test
    void topicPersistsDuplicateDetectionSettings() {
        String body = entry("<TopicDescription xmlns=\"" + SB_NS + "\">"
                + "<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"
                + "<DuplicateDetectionHistoryTimeWindow>PT1M</DuplicateDetectionHistoryTimeWindow>"
                + "</TopicDescription>");

        given().body(body).when().put(BASE + "/duplicate-topic")
                .then().statusCode(201)
                .body(containsString("<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"))
                .body(containsString(
                        "<DuplicateDetectionHistoryTimeWindow>PT1M</DuplicateDetectionHistoryTimeWindow>"));
    }

    @Test
    void duplicateDetectionDefaultsToTenMinutes() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/duplicate-default-window")
                .then().statusCode(201)
                .body(containsString(
                        "<DuplicateDetectionHistoryTimeWindow>PT10M</DuplicateDetectionHistoryTimeWindow>"));
    }

    @Test
    void rejectsDuplicateDetectionWindowOutsideAzureLimits() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"
                + "<DuplicateDetectionHistoryTimeWindow>PT19S</DuplicateDetectionHistoryTimeWindow>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/duplicate-invalid-window")
                .then().statusCode(400)
                .body(containsString("between PT20S and P7D"));
    }

    @Test
    void queuePersistsMessageLifetimeSettings() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<DefaultMessageTimeToLive>PT2S</DefaultMessageTimeToLive>"
                + "<DeadLetteringOnMessageExpiration>true</DeadLetteringOnMessageExpiration>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/ttl-queue")
                .then().statusCode(201)
                .body(containsString("<DefaultMessageTimeToLive>PT2S</DefaultMessageTimeToLive>"))
                .body(containsString(
                        "<DeadLetteringOnMessageExpiration>true</DeadLetteringOnMessageExpiration>"));

        given().when().get(BASE + "/ttl-queue")
                .then().statusCode(200)
                .body(containsString("<DefaultMessageTimeToLive>PT2S</DefaultMessageTimeToLive>"))
                .body(containsString(
                        "<DeadLetteringOnMessageExpiration>true</DeadLetteringOnMessageExpiration>"));
    }

    @Test
    void topicAndSubscriptionPersistIndependentMessageLifetimeSettings() {
        given().body(entry("<TopicDescription xmlns=\"" + SB_NS + "\">"
                        + "<DefaultMessageTimeToLive>PT3S</DefaultMessageTimeToLive>"
                        + "</TopicDescription>"))
                .when().put(BASE + "/ttl-topic")
                .then().statusCode(201)
                .body(containsString("<DefaultMessageTimeToLive>PT3S</DefaultMessageTimeToLive>"));

        String subscriptionBody = entry("<SubscriptionDescription xmlns=\"" + SB_NS + "\">"
                + "<DefaultMessageTimeToLive>PT4S</DefaultMessageTimeToLive>"
                + "<DeadLetteringOnMessageExpiration>true</DeadLetteringOnMessageExpiration>"
                + "</SubscriptionDescription>");
        given().body(subscriptionBody)
                .when().put(BASE + "/ttl-topic/subscriptions/ttl-subscription")
                .then().statusCode(201)
                .body(containsString("<DefaultMessageTimeToLive>PT4S</DefaultMessageTimeToLive>"))
                .body(containsString(
                        "<DeadLetteringOnMessageExpiration>true</DeadLetteringOnMessageExpiration>"));
    }

    @Test
    void rejectsNonPositiveDefaultMessageTimeToLive() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<DefaultMessageTimeToLive>PT0S</DefaultMessageTimeToLive>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/invalid-ttl")
                .then().statusCode(400)
                .body(containsString("DefaultMessageTimeToLive must be positive"));
    }

    @Test
    void queuePersistsMaxDeliveryCountAndLockDuration() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<LockDuration>PT30S</LockDuration>"
                + "<MaxDeliveryCount>5</MaxDeliveryCount>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/delivery-queue")
                .then().statusCode(201)
                .body(containsString("<LockDuration>PT30S</LockDuration>"))
                .body(containsString("<MaxDeliveryCount>5</MaxDeliveryCount>"));

        given().when().get(BASE + "/delivery-queue")
                .then().statusCode(200)
                .body(containsString("<LockDuration>PT30S</LockDuration>"))
                .body(containsString("<MaxDeliveryCount>5</MaxDeliveryCount>"));
    }

    @Test
    void subscriptionPersistsMaxDeliveryCountAndLockDuration() {
        given().body(entry("<TopicDescription xmlns=\"" + SB_NS + "\"/>"))
                .when().put(BASE + "/delivery-topic")
                .then().statusCode(201);

        String subscriptionBody = entry("<SubscriptionDescription xmlns=\"" + SB_NS + "\">"
                + "<LockDuration>PT45S</LockDuration>"
                + "<MaxDeliveryCount>3</MaxDeliveryCount>"
                + "</SubscriptionDescription>");
        given().body(subscriptionBody)
                .when().put(BASE + "/delivery-topic/subscriptions/delivery-subscription")
                .then().statusCode(201)
                .body(containsString("<LockDuration>PT45S</LockDuration>"))
                .body(containsString("<MaxDeliveryCount>3</MaxDeliveryCount>"));
    }

    @Test
    void deliverySettingsDefaultToConfiguredValues() {
        given().body(entry("<QueueDescription xmlns=\"" + SB_NS + "\"/>"))
                .when().put(BASE + "/delivery-default-queue")
                .then().statusCode(201)
                .body(containsString("<LockDuration>PT1M</LockDuration>"))
                .body(containsString("<MaxDeliveryCount>10</MaxDeliveryCount>"));
    }

    @Test
    void queueAndSubscriptionDescriptionsExposeRuntimeCountShape() {
        given().body(entry("<QueueDescription xmlns=\"" + SB_NS + "\"/>"))
                .when().put(BASE + "/runtime-count-queue")
                .then()
                .statusCode(201)
                .body(containsString("<MessageCount>0</MessageCount>"))
                .body(containsString("<CountDetails xmlns:sb=\"" + SB_COUNT_NS + "\">"))
                .body(containsString("<sb:ActiveMessageCount>0</sb:ActiveMessageCount>"))
                .body(containsString("<sb:DeadLetterMessageCount>0</sb:DeadLetterMessageCount>"))
                .body(containsString("<SizeInBytes>0</SizeInBytes>"))
                .body(containsString("<CreatedAt>"))
                .body(containsString("<UpdatedAt>"));

        given().body(entry("<TopicDescription xmlns=\"" + SB_NS + "\"/>"))
                .when().put(BASE + "/runtime-count-topic")
                .then().statusCode(201);
        given().body(entry("<SubscriptionDescription xmlns=\"" + SB_NS + "\"/>"))
                .when().put(BASE + "/runtime-count-topic/subscriptions/runtime-count-sub")
                .then()
                .statusCode(201)
                .body(containsString("<MessageCount>0</MessageCount>"))
                .body(containsString("<CountDetails xmlns:sb=\"" + SB_COUNT_NS + "\">"))
                .body(containsString("<sb:ActiveMessageCount>0</sb:ActiveMessageCount>"))
                .body(containsString("<sb:DeadLetterMessageCount>0</sb:DeadLetterMessageCount>"))
                .body(not(containsString("<SizeInBytes>")))
                .body(containsString("<CreatedAt>"))
                .body(containsString("<UpdatedAt>"));
    }

    @Test
    void rejectsMaxDeliveryCountOutsideAzureLimits() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<MaxDeliveryCount>0</MaxDeliveryCount>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/delivery-invalid-count")
                .then().statusCode(400)
                .body(containsString("MaxDeliveryCount must be between 1 and 2000"));
    }

    @Test
    void rejectsLockDurationAboveFiveMinutes() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<LockDuration>PT6M</LockDuration>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/delivery-invalid-lock")
                .then().statusCode(400)
                .body(containsString("LockDuration must be between PT1S and PT5M"));
    }

    @Test
    void queuePersistsDescriptiveSettings() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<MaxSizeInMegabytes>2048</MaxSizeInMegabytes>"
                + "<EnableBatchedOperations>false</EnableBatchedOperations>"
                + "<AutoDeleteOnIdle>PT2H</AutoDeleteOnIdle>"
                + "<UserMetadata>owned-by &amp; billing</UserMetadata>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/metadata-queue")
                .then().statusCode(201)
                .body(containsString("<MaxSizeInMegabytes>2048</MaxSizeInMegabytes>"))
                .body(containsString("<EnableBatchedOperations>false</EnableBatchedOperations>"))
                .body(containsString("<AutoDeleteOnIdle>PT2H</AutoDeleteOnIdle>"))
                .body(containsString("<UserMetadata>owned-by &amp; billing</UserMetadata>"));

        given().when().get(BASE + "/metadata-queue")
                .then().statusCode(200)
                .body(containsString("<MaxSizeInMegabytes>2048</MaxSizeInMegabytes>"))
                .body(containsString("<EnableBatchedOperations>false</EnableBatchedOperations>"))
                .body(containsString("<AutoDeleteOnIdle>PT2H</AutoDeleteOnIdle>"))
                .body(containsString("<UserMetadata>owned-by &amp; billing</UserMetadata>"));
    }

    @Test
    void topicAndSubscriptionPersistDescriptiveSettings() {
        given().body(entry("<TopicDescription xmlns=\"" + SB_NS + "\">"
                        + "<MaxSizeInMegabytes>2048</MaxSizeInMegabytes>"
                        + "<SupportOrdering>true</SupportOrdering>"
                        + "<UserMetadata>topic-meta</UserMetadata>"
                        + "</TopicDescription>"))
                .when().put(BASE + "/metadata-topic")
                .then().statusCode(201);

        given().when().get(BASE + "/metadata-topic")
                .then().statusCode(200)
                .body(containsString("<MaxSizeInMegabytes>2048</MaxSizeInMegabytes>"))
                .body(containsString("<SupportOrdering>true</SupportOrdering>"))
                .body(containsString("<UserMetadata>topic-meta</UserMetadata>"));

        given().body(entry("<SubscriptionDescription xmlns=\"" + SB_NS + "\">"
                        + "<AutoDeleteOnIdle>PT1H</AutoDeleteOnIdle>"
                        + "<UserMetadata>sub-meta</UserMetadata>"
                        + "</SubscriptionDescription>"))
                .when().put(BASE + "/metadata-topic/subscriptions/metadata-subscription")
                .then().statusCode(201);

        given().when().get(BASE + "/metadata-topic/subscriptions/metadata-subscription")
                .then().statusCode(200)
                .body(containsString("<AutoDeleteOnIdle>PT1H</AutoDeleteOnIdle>"))
                .body(containsString("<UserMetadata>sub-meta</UserMetadata>"));
    }

    @Test
    void descriptiveSettingsDefaultToAzureValues() {
        given().body(entry("<QueueDescription xmlns=\"" + SB_NS + "\"/>"))
                .when().put(BASE + "/metadata-default-queue")
                .then().statusCode(201)
                .body(containsString("<MaxSizeInMegabytes>1024</MaxSizeInMegabytes>"))
                .body(containsString("<EnableBatchedOperations>true</EnableBatchedOperations>"))
                .body(containsString("<AutoDeleteOnIdle>P10675199DT2H48M5.4775807S</AutoDeleteOnIdle>"))
                .body(not(containsString("<UserMetadata>")));
    }

    @Test
    void rejectsAutoDeleteOnIdleBelowFiveMinutes() {
        given().body(entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                        + "<AutoDeleteOnIdle>PT1M</AutoDeleteOnIdle>"
                        + "</QueueDescription>"))
                .when().put(BASE + "/metadata-invalid-idle")
                .then().statusCode(400)
                .body(containsString("AutoDeleteOnIdle must be at least PT5M"));
    }

    @Test
    void updatingAQueueWithIfMatchAppliesTheNewSettings() {
        given().body(queueDescription("PT30S", 3))
                .when().put(BASE + "/update-queue")
                .then().statusCode(201);

        given().header("If-Match", "*").body(queueDescription("PT50S", 7))
                .when().put(BASE + "/update-queue")
                .then().statusCode(200)
                .body(containsString("<LockDuration>PT50S</LockDuration>"))
                .body(containsString("<MaxDeliveryCount>7</MaxDeliveryCount>"));

        given().when().get(BASE + "/update-queue")
                .then().statusCode(200)
                .body(containsString("<LockDuration>PT50S</LockDuration>"))
                .body(containsString("<MaxDeliveryCount>7</MaxDeliveryCount>"));
    }

    @Test
    void updatingAQueueKeepsRequiresSessionFromCreation() {
        given().body(entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                        + "<RequiresSession>true</RequiresSession></QueueDescription>"))
                .when().put(BASE + "/update-session-queue")
                .then().statusCode(201);

        given().header("If-Match", "*").body(queueDescription("PT50S", 7))
                .when().put(BASE + "/update-session-queue")
                .then().statusCode(200)
                .body(containsString("<RequiresSession>true</RequiresSession>"));
    }

    @Test
    void puttingAnExistingQueueWithoutIfMatchLeavesItUnchanged() {
        given().body(queueDescription("PT30S", 3))
                .when().put(BASE + "/recreate-queue")
                .then().statusCode(201);

        given().body(queueDescription("PT50S", 7))
                .when().put(BASE + "/recreate-queue")
                .then().statusCode(200)
                .body(containsString("<MaxDeliveryCount>3</MaxDeliveryCount>"));
    }

    @Test
    void updatingATopicWithIfMatchAppliesTheNewSettings() {
        given().body(entry("<TopicDescription xmlns=\"" + SB_NS + "\"/>"))
                .when().put(BASE + "/update-topic")
                .then().statusCode(201)
                .body(containsString("<EnableSubscriptionPartitioning>false</EnableSubscriptionPartitioning>"))
                .body(containsString("<IsAnonymousAccessible>false</IsAnonymousAccessible>"));

        given().header("If-Match", "*")
                .body(entry("<TopicDescription xmlns=\"" + SB_NS + "\">"
                        + "<DefaultMessageTimeToLive>PT1H</DefaultMessageTimeToLive>"
                        + "<UserMetadata>updated</UserMetadata>"
                        + "</TopicDescription>"))
                .when().put(BASE + "/update-topic")
                .then().statusCode(200)
                .body(containsString("<DefaultMessageTimeToLive>PT1H</DefaultMessageTimeToLive>"))
                .body(containsString("<UserMetadata>updated</UserMetadata>"));

        given().when().get(BASE + "/update-topic")
                .then().statusCode(200)
                .body(containsString("<DefaultMessageTimeToLive>PT1H</DefaultMessageTimeToLive>"))
                .body(containsString("<UserMetadata>updated</UserMetadata>"));
    }

    @Test
    void updatingASubscriptionWithIfMatchAppliesTheNewSettings() {
        given().body(entry("<TopicDescription xmlns=\"" + SB_NS + "\"/>"))
                .when().put(BASE + "/update-sub-topic")
                .then().statusCode(201);
        given().body(subscriptionDescription("PT30S", 3))
                .when().put(BASE + "/update-sub-topic/subscriptions/update-sub")
                .then().statusCode(201);

        given().header("If-Match", "*").body(subscriptionDescription("PT50S", 7))
                .when().put(BASE + "/update-sub-topic/subscriptions/update-sub")
                .then().statusCode(200)
                .body(containsString("<LockDuration>PT50S</LockDuration>"))
                .body(containsString("<MaxDeliveryCount>7</MaxDeliveryCount>"));

        given().when().get(BASE + "/update-sub-topic/subscriptions/update-sub")
                .then().statusCode(200)
                .body(containsString("<LockDuration>PT50S</LockDuration>"))
                .body(containsString("<MaxDeliveryCount>7</MaxDeliveryCount>"));
    }

    private static String queueDescription(String lockDuration, int maxDeliveryCount) {
        return entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<LockDuration>" + lockDuration + "</LockDuration>"
                + "<MaxDeliveryCount>" + maxDeliveryCount + "</MaxDeliveryCount>"
                + "</QueueDescription>");
    }

    private static String subscriptionDescription(String lockDuration, int maxDeliveryCount) {
        return entry("<SubscriptionDescription xmlns=\"" + SB_NS + "\">"
                + "<LockDuration>" + lockDuration + "</LockDuration>"
                + "<MaxDeliveryCount>" + maxDeliveryCount + "</MaxDeliveryCount>"
                + "</SubscriptionDescription>");
    }

    private static String entry(String description) {
        return "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + description + "</content></entry>";
    }
}
