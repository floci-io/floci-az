package io.floci.az.services.monitor;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@DisplayName("Monitor: Application Insights components and their billing features")
class MonitorComponentsTest {

    private static final String API = "?api-version=2020-02-02";
    private static final String BILLING_API = "?api-version=2015-05-01";
    private static final String GROUP = "/subscriptions/sub-ai/resourceGroups/rg-ai/providers/Microsoft.Insights/components";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String WORKSPACE_ID =
        "/subscriptions/sub-ai/resourceGroups/rg-ai/providers/Microsoft.OperationalInsights/workspaces/ws-ai";

    /** The body azurerm's {@code azurerm_application_insights} sends on create. */
    private static final String AZURERM_BODY = "{\"name\":\"appi\",\"location\":\"eastus\",\"kind\":\"web\","
        + "\"tags\":{\"env\":\"test\"},\"properties\":{\"ApplicationId\":\"appi\",\"Application_Type\":\"web\","
        + "\"SamplingPercentage\":100,\"DisableIpMasking\":false,\"DisableLocalAuth\":false,"
        + "\"publicNetworkAccessForIngestion\":\"Enabled\",\"publicNetworkAccessForQuery\":\"Enabled\","
        + "\"ForceCustomerStorageForProfiler\":false,\"RetentionInDays\":90,"
        + "\"WorkspaceResourceId\":\"" + WORKSPACE_ID + "\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static void put(String name, String body) {
        given().contentType(ContentType.JSON).body(body)
            .when().put(GROUP + "/" + name + API)
            .then().statusCode(200);
    }

    @Test
    void aComponentIsNotFoundUntilAPutCreatesIt() {
        given().when().get(GROUP + "/appi" + API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));

        given().contentType(ContentType.JSON).body(AZURERM_BODY)
            .when().put(GROUP + "/appi" + API)
            .then().statusCode(200)
            .body("type", equalTo("Microsoft.Insights/components"))
            .body("kind", equalTo("web"))
            .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    void aComponentKeepsWhatItWasSentAndReadsBackTheKeysAzureGenerates() {
        put("appi", AZURERM_BODY);

        given().when().get(GROUP + "/appi" + API)
            .then().statusCode(200)
            .body("name", equalTo("appi"))
            .body("location", equalTo("eastus"))
            .body("tags.env", equalTo("test"))
            .body("properties.Application_Type", equalTo("web"))
            .body("properties.SamplingPercentage", equalTo(100))
            .body("properties.RetentionInDays", equalTo(90))
            .body("properties.WorkspaceResourceId", equalTo(WORKSPACE_ID))
            .body("properties.ApplicationId", equalTo("appi"))
            .body("properties.AppId", matchesPattern(UUID_PATTERN))
            .body("properties.InstrumentationKey", matchesPattern(UUID_PATTERN))
            .body("properties.ConnectionString",
                matchesPattern("InstrumentationKey=" + UUID_PATTERN + ";ApplicationId=" + UUID_PATTERN))
            .body("properties.CreationDate", matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*"));
    }

    @Test
    void aRePutKeepsTheGeneratedKeysAndReplacesTheRest() {
        put("appi", AZURERM_BODY);
        String key = given().when().get(GROUP + "/appi" + API).then().extract().path("properties.InstrumentationKey");
        String appId = given().when().get(GROUP + "/appi" + API).then().extract().path("properties.AppId");

        put("appi", "{\"location\":\"eastus\",\"kind\":\"other\",\"properties\":{\"Application_Type\":\"other\"}}");

        given().when().get(GROUP + "/appi" + API)
            .then().statusCode(200)
            .body("kind", equalTo("other"))
            .body("tags", nullValue())
            .body("properties.Application_Type", equalTo("other"))
            .body("properties.WorkspaceResourceId", nullValue())
            .body("properties.InstrumentationKey", equalTo(key))
            .body("properties.AppId", equalTo(appId));
    }

    @Test
    void aComponentWithoutWritablePropertiesGetsTheApiDefaults() {
        put("bare", "{\"location\":\"eastus\",\"properties\":{}}");

        given().when().get(GROUP + "/bare" + API)
            .then().statusCode(200)
            .body("kind", equalTo("web"))
            .body("properties.Application_Type", equalTo("web"))
            .body("properties.Flow_Type", equalTo("Bluefield"))
            .body("properties.Request_Source", equalTo("rest"))
            .body("properties.IngestionMode", equalTo("LogAnalytics"))
            .body("properties.RetentionInDays", equalTo(90))
            .body("properties.publicNetworkAccessForIngestion", equalTo("Enabled"))
            .body("properties.publicNetworkAccessForQuery", equalTo("Enabled"));
    }

    @Test
    void aPatchReplacesOnlyTheTags() {
        put("appi", AZURERM_BODY);

        given().contentType(ContentType.JSON).body("{\"tags\":{\"team\":\"obs\"}}")
            .when().patch(GROUP + "/appi" + API)
            .then().statusCode(200)
            .body("tags.team", equalTo("obs"))
            .body("tags.env", nullValue())
            .body("properties.WorkspaceResourceId", equalTo(WORKSPACE_ID));
        given().contentType(ContentType.JSON).body("{\"tags\":{}}")
            .when().patch(GROUP + "/missing" + API)
            .then().statusCode(404);
    }

    @Test
    void componentsAreListedByResourceGroupAndSubscription() {
        put("one", AZURERM_BODY);
        put("two", AZURERM_BODY);

        given().when().get(GROUP + API).then().statusCode(200).body("value", hasSize(2));
        given().when().get("/subscriptions/sub-ai/providers/Microsoft.Insights/components" + API)
            .then().statusCode(200).body("value", hasSize(2));
    }

    @Test
    void billingFeaturesStartAtTheDefaultsAndKeepWhatAPutSets() {
        put("appi", AZURERM_BODY);

        given().when().get(GROUP + "/appi/currentbillingfeatures" + BILLING_API)
            .then().statusCode(200)
            .body("CurrentBillingFeatures", contains("Basic"))
            .body("DataVolumeCap.Cap", equalTo(100.0f))
            .body("DataVolumeCap.StopSendNotificationWhenHitCap", equalTo(false));

        given().contentType(ContentType.JSON)
            .body("{\"CurrentBillingFeatures\":[\"Basic\"],\"DataVolumeCap\":{\"Cap\":5,"
                + "\"StopSendNotificationWhenHitCap\":true,\"ResetTime\":12,\"MaxHistoryCap\":999}}")
            .when().put(GROUP + "/appi/currentbillingfeatures" + BILLING_API)
            .then().statusCode(200)
            .body("DataVolumeCap.Cap", equalTo(5))
            .body("DataVolumeCap.StopSendNotificationWhenHitCap", equalTo(true));

        // ResetTime and MaxHistoryCap are read-only: the PUT's values are ignored.
        given().when().get(GROUP + "/appi/currentbillingfeatures" + BILLING_API)
            .then().statusCode(200)
            .body("DataVolumeCap.Cap", equalTo(5))
            .body("DataVolumeCap.StopSendNotificationWhenHitCap", equalTo(true))
            .body("DataVolumeCap.ResetTime", equalTo(0))
            .body("DataVolumeCap.MaxHistoryCap", equalTo(100.0f));
    }

    @Test
    void billingFeaturesOfAMissingComponentAreNotFoundAndOtherMethodsAreRefused() {
        given().when().get(GROUP + "/missing/currentbillingfeatures" + BILLING_API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));

        put("appi", AZURERM_BODY);
        given().when().delete(GROUP + "/appi/currentbillingfeatures" + BILLING_API).then().statusCode(405);
    }

    @Test
    void deletingAComponentRemovesItAndItsBillingFeatures() {
        put("appi", AZURERM_BODY);
        given().contentType(ContentType.JSON).body("{\"DataVolumeCap\":{\"Cap\":5}}")
            .when().put(GROUP + "/appi/currentbillingfeatures" + BILLING_API).then().statusCode(200);

        given().when().delete(GROUP + "/appi" + API).then().statusCode(200);
        given().when().get(GROUP + "/appi" + API).then().statusCode(404);
        given().when().delete(GROUP + "/appi" + API).then().statusCode(204);

        // A component created again under the same name starts from the default billing features.
        put("appi", AZURERM_BODY);
        given().when().get(GROUP + "/appi/currentbillingfeatures" + BILLING_API)
            .then().statusCode(200).body("DataVolumeCap.Cap", equalTo(100.0f));
    }

    @Test
    void otherPathsUnderAComponentAreNotFound() {
        put("appi", AZURERM_BODY);
        given().contentType(ContentType.JSON).body("{}")
            .when().post(GROUP + "/appi/purge" + API).then().statusCode(404);
    }
}
