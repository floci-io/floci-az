package io.floci.az.services.servicebus;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The {@code Microsoft.ServiceBus} management plane, at the api-version azurerm pins (2024-01-01). The bodies are the
 * shapes azurerm and the spec examples send; the answers pin what azurerm reads back, and that an entity ARM creates
 * is the entity the Atom API serves.
 */
@QuarkusTest
@DisplayName("Microsoft.ServiceBus: namespaces, entities and authorization rules")
class ServiceBusArmServiceTest {

    private static final String SUB = "17800000-0000-4000-8000-000000000001";
    private static final String OTHER_SUB = "17800000-0000-4000-8000-000000000002";
    private static final String API = "?api-version=2024-01-01";
    private static final String KEY_PATTERN = "[A-Za-z0-9+/]{43}=";
    private static final String NAMESPACE_BODY =
            "{\"location\": \"eastus\", \"sku\": {\"name\": \"Standard\", \"tier\": \"Standard\"},"
                    + " \"properties\": {\"minimumTlsVersion\": \"1.2\", \"publicNetworkAccess\": \"Enabled\","
                    + " \"disableLocalAuth\": false}, \"tags\": {\"env\": \"test\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String namespaces(String sub, String rg) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.ServiceBus/namespaces";
    }

    private static String ns(String name) {
        return namespaces(SUB, "rg") + "/" + name;
    }

    private static Response put(String path, String body) {
        return given().contentType("application/json").body(body).when().put(path + API);
    }

    private static void createNamespace(String name) {
        put(ns(name), NAMESPACE_BODY).then().statusCode(201);
    }

    // ── Namespaces ───────────────────────────────────────────────────────────

    @Test
    void creatingANamespaceAnswersItProvisionedWithTheReadOnlyPropertiesAzureAdds() {
        put(ns("sbns-alpha"), NAMESPACE_BODY).then().statusCode(201)
                .header("Azure-AsyncOperation", nullValue())
                .body("id", equalTo(ns("sbns-alpha")))
                .body("name", equalTo("sbns-alpha"))
                .body("type", equalTo("Microsoft.ServiceBus/Namespaces"))
                .body("location", equalTo("eastus"))
                .body("tags.env", equalTo("test"))
                .body("sku.name", equalTo("Standard"))
                .body("sku.tier", equalTo("Standard"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.status", equalTo("Active"))
                .body("properties.serviceBusEndpoint", equalTo("https://sbns-alpha.servicebus.windows.net:443/"))
                .body("properties.metricId", equalTo(SUB + ":sbns-alpha"))
                .body("properties.minimumTlsVersion", equalTo("1.2"))
                .body("properties.publicNetworkAccess", equalTo("Enabled"))
                .body("properties.disableLocalAuth", equalTo(false))
                .body("properties.premiumMessagingPartitions", equalTo(0));

        given().get(ns("sbns-alpha") + API).then().statusCode(200).body("sku.name", equalTo("Standard"));
        put(ns("sbns-alpha"), NAMESPACE_BODY).then().statusCode(200);
    }

    @Test
    void aPremiumNamespaceHasACapacityAndOnePartition() {
        put(ns("sbns-premium"), "{\"location\": \"eastus\", \"sku\": {\"name\": \"Premium\"}}").then()
                .statusCode(201)
                .body("sku.tier", equalTo("Premium"))
                .body("sku.capacity", equalTo(1))
                .body("properties.premiumMessagingPartitions", equalTo(1));
    }

    @Test
    void anUnknownSkuIsRefused() {
        put(ns("sbns-badsku"), "{\"location\": \"eastus\", \"sku\": {\"name\": \"Gold\"}}").then()
                .statusCode(400).body("error.code", equalTo("BadRequest"));
    }

    @Test
    void namespacesListByResourceGroupAndBySubscription() {
        createNamespace("sbns-list-one");
        put(namespaces(SUB, "rg2") + "/sbns-list-two", NAMESPACE_BODY).then().statusCode(201);

        given().get(namespaces(SUB, "rg") + API).then().statusCode(200)
                .body("value.name", equalTo(List.of("sbns-list-one")));
        given().get("/subscriptions/" + SUB + "/providers/Microsoft.ServiceBus/namespaces" + API).then()
                .statusCode(200).body("value.name", hasItems("sbns-list-one", "sbns-list-two"));
    }

    @Test
    void patchingANamespaceMergesTagsAndProperties() {
        createNamespace("sbns-patch");
        given().contentType("application/json")
                .body("{\"tags\": {\"team\": \"core\"}, \"properties\": {\"minimumTlsVersion\": \"1.1\"}}")
                .when().patch(ns("sbns-patch") + API).then().statusCode(200)
                .body("tags.team", equalTo("core"))
                .body("tags.env", nullValue())
                .body("properties.minimumTlsVersion", equalTo("1.1"))
                .body("properties.publicNetworkAccess", equalTo("Enabled"));
    }

    @Test
    void deletingANamespaceRemovesItAndEverythingUnderIt() {
        createNamespace("sbns-delete");
        put(ns("sbns-delete") + "/queues/orders", "{\"properties\": {}}").then().statusCode(200);
        put(ns("sbns-delete") + "/topics/events", "{\"properties\": {}}").then().statusCode(200);

        given().delete(ns("sbns-delete") + API).then().statusCode(200);
        given().get(ns("sbns-delete") + API).then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
        given().get("/devstoreaccount1-servicebus/sbns-delete/queues/orders").then().statusCode(404);
        given().delete(ns("sbns-delete") + API).then().statusCode(204);

        // Re-created, the namespace starts empty.
        createNamespace("sbns-delete");
        given().get(ns("sbns-delete") + "/queues" + API).then().statusCode(200).body("value", hasSize(0));
    }

    @Test
    void aNamespaceNameIsGlobalAcrossSubscriptions() {
        createNamespace("sbns-global");
        put(namespaces(OTHER_SUB, "rg") + "/sbns-global", NAMESPACE_BODY).then().statusCode(409);
    }

    @Test
    void checkNameAvailabilityAnswersFromTheNameRuleAndTheRegistry() {
        createNamespace("sbns-taken");
        String path = "/subscriptions/" + OTHER_SUB + "/providers/Microsoft.ServiceBus/CheckNameAvailability" + API;

        given().contentType("application/json").body("{\"name\": \"sbns-taken\"}").post(path).then()
                .statusCode(200).body("nameAvailable", equalTo(false)).body("reason", equalTo("NameInUse"));
        given().contentType("application/json").body("{\"name\": \"sbns-free\"}").post(path).then()
                .statusCode(200).body("nameAvailable", equalTo(true)).body("reason", equalTo("None"));
        given().contentType("application/json").body("{\"name\": \"orders-sb\"}").post(path).then()
                .statusCode(200).body("nameAvailable", equalTo(false)).body("reason", equalTo("InvalidName"));
        given().contentType("application/json").body("{\"name\": \"ab\"}").post(path).then()
                .statusCode(200).body("reason", equalTo("InvalidName"));
    }

    @Test
    void theNetworkRuleSetIsAnsweredBeforeAnyIsWritten() {
        createNamespace("sbns-network");
        given().get(ns("sbns-network") + "/networkRuleSets/default" + API).then().statusCode(200)
                .body("name", equalTo("default"))
                .body("properties.defaultAction", equalTo("Allow"))
                .body("properties.publicNetworkAccess", equalTo("Enabled"))
                .body("properties.ipRules", hasSize(0));

        put(ns("sbns-network") + "/networkRuleSets/default",
                "{\"properties\": {\"defaultAction\": \"Deny\", \"ipRules\": [{\"ipMask\": \"10.0.0.0/24\","
                        + " \"action\": \"Allow\"}]}}").then().statusCode(200)
                .body("properties.defaultAction", equalTo("Deny"));
        given().get(ns("sbns-network") + "/networkRuleSets" + API).then().statusCode(200)
                .body("value[0].properties.ipRules[0].ipMask", equalTo("10.0.0.0/24"));
    }

    @Test
    void aChildOfAMissingNamespaceIsRefusedWithParentResourceNotFound() {
        put(ns("sbns-missing") + "/queues/orders", "{\"properties\": {}}").then().statusCode(404)
                .body("error.code", equalTo("ParentResourceNotFound"));
    }

    // ── Authorization rules ──────────────────────────────────────────────────

    @Test
    void aNamespaceHasTheRootManageRuleWithEveryRight() {
        createNamespace("sbns-root");
        given().get(ns("sbns-root") + "/AuthorizationRules/RootManageSharedAccessKey" + API).then().statusCode(200)
                .body("type", equalTo("Microsoft.ServiceBus/Namespaces/AuthorizationRules"))
                .body("properties.rights", hasItems("Listen", "Send", "Manage"));

        given().post(ns("sbns-root") + "/AuthorizationRules/RootManageSharedAccessKey/listKeys" + API).then()
                .statusCode(200)
                .body("keyName", equalTo("RootManageSharedAccessKey"))
                .body("primaryKey", matchesPattern(KEY_PATTERN))
                .body("secondaryKey", matchesPattern(KEY_PATTERN))
                .body("primaryConnectionString", startsWith("Endpoint=sb://"))
                .body("primaryConnectionString", containsString(";SharedAccessKeyName=RootManageSharedAccessKey;"))
                .body("primaryConnectionString", endsWith(";UseDevelopmentEmulator=true"));
    }

    @Test
    void anEntityRuleCarriesTheEntityPathInItsConnectionStrings() {
        createNamespace("sbns-entityrule");
        put(ns("sbns-entityrule") + "/queues/orders", "{\"properties\": {}}").then().statusCode(200);
        put(ns("sbns-entityrule") + "/queues/orders/authorizationRules/sender",
                "{\"properties\": {\"rights\": [\"Send\"]}}").then().statusCode(200)
                .body("properties.rights", equalTo(List.of("Send")));

        given().post(ns("sbns-entityrule") + "/queues/orders/authorizationRules/sender/ListKeys" + API).then()
                .statusCode(200).body("primaryConnectionString", endsWith(";EntityPath=orders"));
        given().get(ns("sbns-entityrule") + "/queues/orders/authorizationRules" + API).then().statusCode(200)
                .body("value.name", equalTo(List.of("sender")));
    }

    @Test
    void regeneratingAKeyReplacesThatKeyOnly() {
        createNamespace("sbns-regen");
        String rule = ns("sbns-regen") + "/AuthorizationRules/RootManageSharedAccessKey";
        Response before = given().post(rule + "/listKeys" + API);

        Response after = given().contentType("application/json").body("{\"keyType\": \"PrimaryKey\"}")
                .post(rule + "/regenerateKeys" + API);
        after.then().statusCode(200);
        assertNotEquals(before.path("primaryKey"), (Object) after.path("primaryKey"));
        assertEquals(before.<String>path("secondaryKey"), after.path("secondaryKey"));

        given().contentType("application/json").body("{\"keyType\": \"Tertiary\"}")
                .post(rule + "/regenerateKeys" + API).then().statusCode(400);
    }

    @Test
    void aManageRuleWithoutListenAndSendIsRefused() {
        createNamespace("sbns-manage");
        put(ns("sbns-manage") + "/AuthorizationRules/admin", "{\"properties\": {\"rights\": [\"Manage\"]}}").then()
                .statusCode(400);
    }

    // ── Entities ─────────────────────────────────────────────────────────────

    @Test
    void aQueueEchoesWhatAzurermComparesAfterAnUpdate() {
        createNamespace("sbns-queue");
        put(ns("sbns-queue") + "/queues/orders",
                "{\"properties\": {\"lockDuration\": \"PT1M\", \"maxDeliveryCount\": 7, \"requiresSession\": true,"
                        + " \"deadLetteringOnMessageExpiration\": true, \"defaultMessageTimeToLive\": \"P14D\","
                        + " \"enablePartitioning\": true, \"enableExpress\": false, \"enableBatchedOperations\": true,"
                        + " \"status\": \"Active\", \"maxSizeInMegabytes\": 2048,"
                        + " \"forwardDeadLetteredMessagesTo\": \"dlq\"}}").then().statusCode(200)
                .body("type", equalTo("Microsoft.ServiceBus/Namespaces/Queues"))
                .body("properties.lockDuration", equalTo("PT1M"))
                .body("properties.maxDeliveryCount", equalTo(7))
                .body("properties.requiresSession", equalTo(true))
                .body("properties.deadLetteringOnMessageExpiration", equalTo(true))
                .body("properties.defaultMessageTimeToLive", equalTo("P14D"))
                .body("properties.enablePartitioning", equalTo(true))
                .body("properties.enableExpress", equalTo(false))
                .body("properties.enableBatchedOperations", equalTo(true))
                .body("properties.status", equalTo("Active"))
                .body("properties.maxSizeInMegabytes", equalTo(2048))
                .body("properties.forwardDeadLetteredMessagesTo", equalTo("dlq"))
                .body("properties.countDetails.activeMessageCount", equalTo(0));

        given().get(ns("sbns-queue") + "/queues/ORDERS" + API).then().statusCode(200)
                .body("name", equalTo("orders"))
                .body("properties.lockDuration", equalTo("PT1M"));
    }

    @Test
    void aQueueCreatedThroughArmIsTheQueueTheAtomApiServes() {
        createNamespace("sbns-atom");
        put(ns("sbns-atom") + "/queues/orders", "{\"properties\": {\"maxDeliveryCount\": 4}}").then()
                .statusCode(200);

        given().get("/devstoreaccount1-servicebus/sbns-atom/queues/orders").then().statusCode(200)
                .body(containsString("<MaxDeliveryCount>4</MaxDeliveryCount>"));
    }

    @Test
    void aQueueCreatedOverAtomShowsInTheArmListing() {
        createNamespace("sbns-fromatom");
        given().body("<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                        + "<QueueDescription"
                        + " xmlns=\"http://schemas.microsoft.com/netservices/2010/10/servicebus/connect\"/>"
                        + "</content></entry>")
                .when().put("/devstoreaccount1-servicebus/sbns-fromatom/queues/legacy").then().statusCode(201);

        given().get(ns("sbns-fromatom") + "/queues" + API).then().statusCode(200)
                .body("value.name", hasItem("legacy"))
                .body("value[0].properties.status", equalTo("Active"));
    }

    @Test
    void aZeroSizeAsAzurermSendsItTakesTheDefaults() {
        createNamespace("sbns-zero");
        put(ns("sbns-zero") + "/queues/orders",
                "{\"properties\": {\"maxSizeInMegabytes\": 0, \"maxMessageSizeInKilobytes\": 0}}").then()
                .statusCode(200)
                .body("properties.maxSizeInMegabytes", equalTo(1024))
                .body("properties.maxMessageSizeInKilobytes", equalTo(256));
        put(ns("sbns-zero") + "/topics/events", "{\"properties\": {\"maxSizeInMegabytes\": 0}}").then()
                .statusCode(200)
                .body("properties.maxSizeInMegabytes", equalTo(1024));
        put(ns("sbns-zero") + "/queues/negative", "{\"properties\": {\"maxSizeInMegabytes\": -1}}").then()
                .statusCode(400);
    }

    @Test
    void aQueueWithAnInvalidLockDurationIsRefused() {
        createNamespace("sbns-badlock");
        put(ns("sbns-badlock") + "/queues/orders", "{\"properties\": {\"lockDuration\": \"PT10M\"}}").then()
                .statusCode(400);
    }

    @Test
    void deletingAQueueAnswers200ThenNoContent() {
        createNamespace("sbns-dq");
        put(ns("sbns-dq") + "/queues/orders", "{\"properties\": {}}").then().statusCode(200);
        given().delete(ns("sbns-dq") + "/queues/orders" + API).then().statusCode(200);
        given().delete(ns("sbns-dq") + "/queues/orders" + API).then().statusCode(204);
        given().get(ns("sbns-dq") + "/queues/orders" + API).then().statusCode(404);
    }

    @Test
    void aNamespacePutInAnotherCasingStartsNoSecondBroker() {
        createNamespace("sbns-Casing");
        put(ns("sbns-casing"), NAMESPACE_BODY).then().statusCode(200).body("name", equalTo("sbns-Casing"));
        // A refused PUT, here a location change spelled in yet another casing, starts nothing either.
        put(ns("SBNS-CASING"), NAMESPACE_BODY.replace("eastus", "westus")).then().statusCode(400)
                .body("error.code", equalTo("InvalidResourceLocation"));

        given().get("/devstoreaccount1-servicebus/namespaces").then().statusCode(200)
                .body("namespaces.name", hasItem("sbns-Casing"))
                .body("namespaces.name", not(hasItem("sbns-casing")))
                .body("namespaces.name", not(hasItem("SBNS-CASING")));
    }

    @Test
    void aNamespaceHasNoDisasterRecoveryPairing() {
        put(ns("sbns-geodr"), "{\"location\": \"eastus\", \"sku\": {\"name\": \"Premium\"}}").then()
                .statusCode(201);
        // azurerm lists the aliases after writing any authorization rule on a Premium namespace.
        given().get(ns("sbns-geodr") + "/disasterRecoveryConfigs" + API).then().statusCode(200)
                .body("value", hasSize(0));
        given().get(ns("sbns-geodr") + "/disasterRecoveryConfigs/alias" + API).then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void aSubscriptionKeepsTheSettingsItWasCreatedWith() {
        createNamespace("sbns-subsettings");
        put(ns("sbns-subsettings") + "/topics/events", "{\"properties\": {}}").then().statusCode(200);
        put(ns("sbns-subsettings") + "/topics/events/subscriptions/tuned",
                "{\"properties\": {\"enableBatchedOperations\": false, \"autoDeleteOnIdle\": \"PT1H\","
                        + " \"userMetadata\": \"audit\"}}").then().statusCode(200)
                .body("properties.enableBatchedOperations", equalTo(false))
                .body("properties.autoDeleteOnIdle", equalTo("PT1H"))
                .body("properties.userMetadata", equalTo("audit"));
        given().get(ns("sbns-subsettings") + "/topics/events/subscriptions/tuned" + API).then().statusCode(200)
                .body("properties.enableBatchedOperations", equalTo(false))
                .body("properties.autoDeleteOnIdle", equalTo("PT1H"));
    }

    @Test
    void theUnboundedTimeToLiveAzurermSendsComesBackAsSent() {
        String unbounded = "P10675199DT2H48M5.4775807S";
        createNamespace("sbns-ttl");
        put(ns("sbns-ttl") + "/topics/events", "{\"properties\": {\"defaultMessageTimeToLive\": \""
                + unbounded + "\"}}").then().statusCode(200)
                .body("properties.defaultMessageTimeToLive", equalTo(unbounded));
        put(ns("sbns-ttl") + "/topics/events/subscriptions/all", "{\"properties\": {\"defaultMessageTimeToLive\": \""
                + unbounded + "\"}}").then().statusCode(200)
                .body("properties.defaultMessageTimeToLive", equalTo(unbounded));
        given().get(ns("sbns-ttl") + "/topics/events/subscriptions/all" + API).then().statusCode(200)
                .body("properties.defaultMessageTimeToLive", equalTo(unbounded));
        given().get(ns("sbns-ttl") + "/topics/events" + API).then().statusCode(200)
                .body("properties.defaultMessageTimeToLive", equalTo(unbounded));
    }

    @Test
    void aSubscriptionGetsTheDefaultRuleWhichCanBeDeleted() {
        createNamespace("sbns-sub");
        put(ns("sbns-sub") + "/topics/events", "{\"properties\": {\"supportOrdering\": true}}").then()
                .statusCode(200).body("properties.supportOrdering", equalTo(true));
        put(ns("sbns-sub") + "/topics/events/subscriptions/audit",
                "{\"properties\": {\"maxDeliveryCount\": 3, \"lockDuration\": \"PT30S\"}}").then().statusCode(200)
                .body("type", equalTo("Microsoft.ServiceBus/Namespaces/Topics/Subscriptions"))
                .body("properties.maxDeliveryCount", equalTo(3))
                .body("properties.lockDuration", equalTo("PT30S"));
        // azurerm sends an empty clientAffineProperties; answered back it would plan a replacement.
        put(ns("sbns-sub") + "/topics/events/subscriptions/plain",
                "{\"properties\": {\"isClientAffine\": false, \"clientAffineProperties\": {}}}").then()
                .statusCode(200)
                .body("properties.isClientAffine", equalTo(false))
                .body("properties", not(hasKey("clientAffineProperties")));

        String rule = ns("sbns-sub") + "/topics/events/subscriptions/audit/rules/$Default";
        given().get(rule + API).then().statusCode(200)
                .body("properties.filterType", equalTo("SqlFilter"))
                .body("properties.sqlFilter.sqlExpression", equalTo("1=1"))
                .body("properties.sqlFilter.compatibilityLevel", equalTo(20));
        given().delete(rule + API).then().statusCode(200);
        given().get(ns("sbns-sub") + "/topics/events/subscriptions/audit/rules" + API).then().statusCode(200)
                .body("value", hasSize(0));
        given().get(ns("sbns-sub") + "/topics/events" + API).then().body("properties.subscriptionCount",
                equalTo(2));
    }

    @Test
    void sqlAndCorrelationRulesRoundTrip() {
        createNamespace("sbns-rules");
        put(ns("sbns-rules") + "/topics/events", "{\"properties\": {}}").then().statusCode(200);
        put(ns("sbns-rules") + "/topics/events/subscriptions/audit", "{\"properties\": {}}").then().statusCode(200);
        String rules = ns("sbns-rules") + "/topics/events/subscriptions/audit/rules";

        put(rules + "/high", "{\"properties\": {\"filterType\": \"SqlFilter\","
                + " \"sqlFilter\": {\"sqlExpression\": \"priority > 5\"}, \"action\": {\"sqlExpression\":"
                + " \"SET routed = 'yes'\"}}}").then().statusCode(200)
                .body("properties.sqlFilter.sqlExpression", equalTo("priority > 5"))
                .body("properties.action.sqlExpression", equalTo("SET routed = 'yes'"));
        put(rules + "/corr", "{\"properties\": {\"filterType\": \"CorrelationFilter\", \"correlationFilter\":"
                + " {\"label\": \"order\", \"properties\": {\"region\": \"eu\"}}}}").then().statusCode(200)
                .body("properties.correlationFilter.label", equalTo("order"))
                .body("properties.correlationFilter.properties.region", equalTo("eu"));

        given().get(rules + API).then().statusCode(200).body("value.name", hasItems("$Default", "high", "corr"));
        given().get("/devstoreaccount1-servicebus/sbns-rules/topics/events/subscriptions/audit/rules/high").then()
                .statusCode(200).body(containsString("priority &gt; 5"));
    }

    @Test
    void deletingATopicRemovesItsSubscriptionsAndRules() {
        createNamespace("sbns-dt");
        put(ns("sbns-dt") + "/topics/events", "{\"properties\": {}}").then().statusCode(200);
        put(ns("sbns-dt") + "/topics/events/subscriptions/audit", "{\"properties\": {}}").then().statusCode(200);
        put(ns("sbns-dt") + "/topics/events/authorizationRules/listen", "{\"properties\": {\"rights\": [\"Listen\"]}}")
                .then().statusCode(200);

        given().delete(ns("sbns-dt") + "/topics/events" + API).then().statusCode(200);
        put(ns("sbns-dt") + "/topics/events", "{\"properties\": {}}").then().statusCode(200);
        given().get(ns("sbns-dt") + "/topics/events/subscriptions" + API).then().body("value", hasSize(0));
        given().get(ns("sbns-dt") + "/topics/events/authorizationRules" + API).then().body("value", hasSize(0));
    }

    @Test
    void aResourceGroupListingShowsTheNamespaceButNotItsChildren() {
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .put("/subscriptions/" + SUB + "/resourcegroups/rg?api-version=2021-04-01").then()
                .statusCode(anyOf(equalTo(200), equalTo(201)));
        createNamespace("sbns-index");
        put(ns("sbns-index") + "/queues/orders", "{\"properties\": {}}").then().statusCode(200);
        given().get("/subscriptions/" + SUB + "/resourceGroups/rg/resources?api-version=2021-04-01").then()
                .statusCode(200)
                .body("value.type", hasItem("Microsoft.ServiceBus/Namespaces"))
                .body("value.type", not(hasItem("Microsoft.ServiceBus/Namespaces/Queues")));
    }
}
