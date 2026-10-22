package io.floci.az.services.monitor.activitylog;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestProfile(ActivityLogTest.SmallPages.class)
@DisplayName("Activity Log: Administrative entries for ARM writes, the list filter, $select and paging")
class ActivityLogTest {

    public static class SmallPages implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.monitor.activity-log.page-size", "3");
        }
    }

    private static final String SUB = "aaaaaaaa-2222-2222-2222-000000000001";
    private static final String SUB_SCOPE = "/subscriptions/" + SUB;
    private static final String RG = "rg-activity";
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/" + RG;
    private static final String SA = RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/activitysa1";
    private static final String LOGS = SUB_SCOPE + "/providers/Microsoft.Insights/eventtypes/management/values?api-version=2017-03-01-preview";

    private String since;

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
        since = Instant.now().minusSeconds(60).toString();
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(RG_SCOPE + "?api-version=2021-04-01").then().statusCode(isOneOf(200, 201));
    }

    @Test
    void writesAndDeletesProduceBeginAndEndEntries() {
        String token = appToken();
        given().header("Authorization", "Bearer " + token)
                .header("x-ms-correlation-request-id", "corr-write-1")
                .header("x-ms-client-request-id", "client-1")
                .contentType("application/json")
                .body("{\"location\": \"eastus\", \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"}}")
                .when().put(SA + "?api-version=2023-01-01")
                .then().statusCode(isOneOf(200, 201));

        list("eventTimestamp ge '" + since + "' and correlationId eq 'corr-write-1'")
                .statusCode(200)
                .body("value", hasSize(2))
                .body("value[0].eventName.value", equalTo("EndRequest"))
                .body("value[0].eventName.localizedValue", equalTo("End request"))
                .body("value[0].category.value", equalTo("Administrative"))
                .body("value[0].operationName.value", equalTo("Microsoft.Storage/storageAccounts/write"))
                .body("value[0].status.value", equalTo("Succeeded"))
                .body("value[0].subStatus.value", isOneOf("OK", "Created"))
                .body("value[0].subStatus.localizedValue", isOneOf("OK (HTTP Status Code: 200)", "Created (HTTP Status Code: 201)"))
                .body("value[0].level", equalTo("Informational"))
                .body("value[0].resourceId", equalTo(SA))
                .body("value[0].resourceType.value", equalTo("Microsoft.Storage/storageAccounts"))
                .body("value[0].resourceProviderName.value", equalTo("Microsoft.Storage"))
                .body("value[0].resourceGroupName", equalTo(RG))
                .body("value[0].subscriptionId", equalTo(SUB))
                .body("value[0].authorization.action", equalTo("Microsoft.Storage/storageAccounts/write"))
                .body("value[0].authorization.scope", equalTo(SA))
                .body("value[0].httpRequest.method", equalTo("PUT"))
                .body("value[0].httpRequest.clientRequestId", equalTo("client-1"))
                .body("value[0].httpRequest.uri", startsWith(SA))
                .body("value[0].claims.appid", equalTo("eval-app"))
                .body("value[0].claims.idtyp", equalTo("app"))
                .body("value[0].claims.'http://schemas.microsoft.com/identity/claims/objectidentifier'", notNullValue())
                .body("value[0].caller", notNullValue())
                .body("value[0].properties.statusCode", isOneOf("OK", "Created"))
                .body("value[0].id", startsWith(SA + "/events/"))
                .body("value[0].id", containsString("/ticks/"))
                .body("value[0].channels", equalTo("Operation"))
                .body("value[1].eventName.value", equalTo("BeginRequest"))
                .body("value[1].status.value", equalTo("Started"))
                .body("value[1].operationId", equalTo(given().when().get(LOGS + "&$filter=eventTimestamp ge '" + since + "' and correlationId eq 'corr-write-1'").then().extract().path("value[0].operationId")));

        given().header("x-ms-correlation-request-id", "corr-delete-1")
                .when().delete(SA + "?api-version=2023-01-01")
                .then().statusCode(isOneOf(200, 202, 204));
        list("eventTimestamp ge '" + since + "' and operations eq 'Microsoft.Storage/storageAccounts/delete'")
                .statusCode(200)
                .body("value", hasSize(2))
                .body("value[0].operationName.value", equalTo("Microsoft.Storage/storageAccounts/delete"))
                .body("value[0].caller", nullValue());

        // Reads are never logged.
        given().when().get(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts?api-version=2023-01-01").then().statusCode(200);
        list("eventTimestamp ge '" + since + "' and operations eq 'Microsoft.Storage/storageAccounts/read'")
                .statusCode(200).body("value", hasSize(0));
    }

    @Test
    void failedWritesAreLoggedAsFailedAtErrorLevel() {
        given().header("x-ms-correlation-request-id", "corr-bad")
                .contentType("application/json").body("{\"location\": \"eastus\"}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/Bad-Name?api-version=2023-01-01")
                .then().statusCode(400);
        list("eventTimestamp ge '" + since + "' and correlationId eq 'corr-bad' and levels eq 'Error'")
                .statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].status.value", equalTo("Failed"))
                .body("value[0].subStatus.value", equalTo("BadRequest"))
                .body("value[0].properties.statusCode", equalTo("BadRequest"))
                .body("value[0].properties.statusMessage", containsString("AccountNameInvalid"));
    }

    @Test
    void resourceGroupWritesAndActionsAreLogged() {
        list("eventTimestamp ge '" + since + "' and resourceUri eq '" + RG_SCOPE + "'")
                .statusCode(200)
                .body("value[0].operationName.value", equalTo("Microsoft.Resources/subscriptions/resourceGroups/write"))
                .body("value[0].resourceType.value", equalTo("Microsoft.Resources/subscriptions/resourceGroups"))
                .body("value[0].resourceProviderName.value", equalTo("Microsoft.Resources"));

        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"}}")
                .when().put(SA + "?api-version=2023-01-01").then().statusCode(isOneOf(200, 201));
        given().when().post(SA + "/listKeys?api-version=2023-01-01").then().statusCode(200);
        list("eventTimestamp ge '" + since + "' and operations eq 'Microsoft.Storage/storageAccounts/listKeys/action'")
                .statusCode(200)
                .body("value", hasSize(2))
                .body("value[0].resourceId", equalTo(SA))
                .body("value[0].subStatus.value", equalTo("OK"));
    }

    @Test
    void selectAndPagingAndFilterErrors() {
        for (int i = 1; i <= 2; i++) {
            given().contentType("application/json")
                    .body("{\"location\": \"eastus\", \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"}}")
                    .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/pagedsa" + i + "?api-version=2023-01-01")
                    .then().statusCode(isOneOf(200, 201));
        }
        // Two writes plus the resource group: six entries over pages of three.
        String next = given().when().get(LOGS + "&$filter=eventTimestamp ge '" + since + "'&$select=eventName,operationName,status,eventTimestamp,correlationId")
                .then().statusCode(200)
                .body("value", hasSize(3))
                .body("value[0].eventName.value", notNullValue())
                .body("value[0].operationName.value", notNullValue())
                .body("value[0].resourceId", nullValue())
                .body("value[0].claims", nullValue())
                .body("nextLink", containsString("$skiptoken="))
                .body("nextLink", containsString("$select="))
                .extract().path("nextLink");
        // A write between pages does not shift the next page: the cursor names the entry the page ended on.
        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"kind\": \"StorageV2\", \"sku\": {\"name\": \"Standard_LRS\"}}")
                .when().put(RG_SCOPE + "/providers/Microsoft.Storage/storageAccounts/pagedsa3?api-version=2023-01-01")
                .then().statusCode(isOneOf(200, 201));
        // The link is already encoded, as a client follows it verbatim.
        String relative = next.substring(next.indexOf("/subscriptions/"));
        given().urlEncodingEnabled(false).when().get(relative)
                .then().statusCode(200)
                .body("value", hasSize(3))
                .body("nextLink", nullValue());

        // The CLI's window: unquoted timestamps, no offset.
        given().when().get(LOGS + "&$filter=eventTimestamp ge " + since.substring(0, since.length() - 1)
                        + " and eventTimestamp le " + Instant.now().plusSeconds(60).toString().replace("Z", ""))
                .then().statusCode(200)
                .body("value.size()", greaterThan(0));
        given().when().get(LOGS)
                .then().statusCode(400)
                .body("code", equalTo("BadRequest"))
                .body("message", containsString("eventTimestamp ge"))
                .body("error", nullValue());
        given().when().get(LOGS + "&$filter=eventTimestamp ge '" + since + "' and colour eq 'blue'")
                .then().statusCode(400)
                .body("code", equalTo("BadRequest"));
        given().when().get(LOGS + "&$filter=eventTimestamp ge '" + since + "'&$skiptoken=garbage")
                .then().statusCode(400);
        given().when().get(LOGS + "&$filter=eventTimestamp ge '" + since + "'&$skiptoken=" + ActivityLogEndpoint.encode(-1))
                .then().statusCode(400);
        given().when().get(LOGS + "&$filter=eventTimestamp ge '" + since + "'&$skiptoken=YmVmb3JlOi0x")
                .then().statusCode(400);
        given().when().post(LOGS + "&$filter=eventTimestamp ge '" + since + "'")
                .then().statusCode(405);
    }

    private static ValidatableResponse list(String filter) {
        return given().when().get(LOGS + "&$filter=" + filter).then();
    }

    private static String appToken() {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", "eval-app")
                .formParam("client_secret", "secret")
                .formParam("scope", "https://management.azure.com/.default")
                .when().post("/00000000-0000-0000-0000-000000000002/oauth2/v2.0/token")
                .then().statusCode(200).extract().path("access_token");
    }
}
