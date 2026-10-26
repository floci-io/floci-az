package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@DisplayName("ARM: a type listed across a whole subscription answers every resource group's resources")
class SubscriptionWideListTest {

    private static final String SUB = "/subscriptions/sub-wide-list";
    private static final List<String> GROUPS = List.of("rg-wide-a", "rg-wide-b");

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    /** Creates {@code name-a} and {@code name-b}, one in each group, and checks both lists. */
    private static void assertListedAcrossGroups(String type, String apiVersion, String body, String name) {
        for (String group : GROUPS) {
            given().contentType(ContentType.JSON).body(body)
                .when().put(SUB + "/resourceGroups/" + group + "/providers/" + type + "/" + name
                    + group.substring(group.length() - 2) + "?api-version=" + apiVersion)
                .then().statusCode(anyOf(equalTo(200), equalTo(201)));
        }
        given().when().get(SUB + "/providers/" + type + "?api-version=" + apiVersion)
            .then().statusCode(200).contentType(ContentType.JSON)
            .body("value.name", containsInAnyOrder(name + "-a", name + "-b"));
        given().when().get(SUB + "/resourceGroups/rg-wide-a/providers/" + type + "?api-version=" + apiVersion)
            .then().statusCode(200).contentType(ContentType.JSON)
            .body("value", hasSize(1))
            .body("value[0].name", equalTo(name + "-a"));
    }

    @Test
    void eventGridTopicsAreListedAcrossTheSubscription() {
        assertListedAcrossGroups("Microsoft.EventGrid/topics", "2025-02-15",
            "{\"location\":\"eastus\",\"properties\":{}}", "wide-topic");
    }

    @Test
    void apiManagementServicesAreListedAcrossTheSubscription() {
        assertListedAcrossGroups("Microsoft.ApiManagement/service", "2022-08-01",
            "{\"location\":\"eastus\",\"sku\":{\"name\":\"Developer\",\"capacity\":1},"
                + "\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"x\"}}", "wide-apim");
    }

    @Test
    void theApiManagementSubscriptionListMatchesTheTypeInAnyCaseAndOnlyAnswersGet() {
        given().contentType(ContentType.JSON)
            .body("{\"location\":\"eastus\",\"sku\":{\"name\":\"Developer\",\"capacity\":1},"
                + "\"properties\":{\"publisherEmail\":\"a@b.c\",\"publisherName\":\"x\"}}")
            .when().put(SUB + "/resourceGroups/rg-wide-a/providers/Microsoft.ApiManagement/service/case-apim"
                + "?api-version=2022-08-01")
            .then().statusCode(anyOf(equalTo(200), equalTo(201)));
        given().when().get(SUB + "/providers/Microsoft.ApiManagement/Service?api-version=2022-08-01")
            .then().statusCode(200).body("value.name", containsInAnyOrder("case-apim"));
        for (String method : List.of("PUT", "POST", "DELETE")) {
            given().contentType(ContentType.JSON).body("{}")
                .when().request(method, SUB + "/providers/Microsoft.ApiManagement/service?api-version=2022-08-01")
                .then().statusCode(405);
        }
    }

    @Test
    void logAnalyticsWorkspacesAreListedAcrossTheSubscription() {
        assertListedAcrossGroups("Microsoft.OperationalInsights/workspaces", "2022-10-01",
            "{\"location\":\"eastus\"}", "wide-ws");
    }

    @Test
    void dataCollectionEndpointsAndRulesAreListedAcrossTheSubscription() {
        assertListedAcrossGroups("Microsoft.Insights/dataCollectionEndpoints", "2022-06-01",
            "{\"location\":\"eastus\"}", "wide-dce");
        assertListedAcrossGroups("Microsoft.Insights/dataCollectionRules", "2022-06-01",
            "{\"location\":\"eastus\",\"properties\":{}}", "wide-dcr");
    }

    @Test
    void networkResourcesAreListedAcrossTheSubscription() {
        assertListedAcrossGroups("Microsoft.Network/networkSecurityGroups", "2023-09-01",
            "{\"location\":\"eastus\"}", "wide-nsg");
        assertListedAcrossGroups("Microsoft.Network/publicIPAddresses", "2023-09-01",
            "{\"location\":\"eastus\"}", "wide-pip");
    }

    @Test
    void anEventGridEventSubscriptionListAtSubscriptionScopeAnswers() {
        given().when().get(SUB + "/providers/Microsoft.EventGrid/eventSubscriptions?api-version=2025-02-15")
            .then().statusCode(200).body("value", hasSize(0));
    }

    @Test
    void anUnservedSubscriptionLevelPathOfAnOptedInNamespaceIsArmNotFound() {
        for (String path : List.of("Microsoft.ApiManagement/service/no-group",
                "Microsoft.EventGrid/topics/no-group", "Microsoft.OperationalInsights/workspaces/no-group",
                "Microsoft.Insights/unknownType")) {
            given().when().get(SUB + "/providers/" + path + "?api-version=2022-10-01")
                .then().statusCode(404).contentType(ContentType.JSON)
                .body("error.code", equalTo("ResourceNotFound"));
        }
    }

    @Test
    void apiManagementCheckNameAvailabilityAnswersAnUnownedNameAvailable() {
        given().contentType(ContentType.JSON).body("{\"name\":\"any-apim\"}")
            .when().post(SUB + "/providers/Microsoft.ApiManagement/checkNameAvailability?api-version=2022-08-01")
            .then().statusCode(200).body("nameAvailable", equalTo(true));
    }
}
