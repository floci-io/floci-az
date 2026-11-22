package io.floci.az.services.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@DisplayName("Web ARM: App Service plans (serverFarms)")
class WebServerFarmTest {

    private static final String API = "?api-version=2023-12-01";
    private static final String PLAN_BODY = "{\"location\":\"eastus\",\"kind\":\"linux\","
        + "\"sku\":{\"name\":\"Y1\",\"tier\":\"Dynamic\"},\"properties\":{\"reserved\":true},"
        + "\"tags\":{\"env\":\"test\"}}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static final String SITE_API = "?api-version=2023-01-01";

    private static void putPlan(String path, String body) {
        given().contentType("application/json").body(body).when().put(path + API).then().statusCode(200);
    }

    private static String plan(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Web/serverFarms/" + name;
    }

    @Test
    void aPutPlanIsReadBackWithItsSkuAndLinuxFlag() {
        given().contentType("application/json").body(PLAN_BODY)
            .when().put(plan("sub-plan", "rg-plan", "my-plan") + API)
            .then().statusCode(200)
            .body("id", equalTo(plan("sub-plan", "rg-plan", "my-plan")))
            .body("type", equalTo("Microsoft.Web/serverFarms"))
            .body("kind", equalTo("linux"))
            .body("sku.name", equalTo("Y1"))
            .body("properties.reserved", equalTo(true))
            .body("properties.provisioningState", equalTo("Succeeded"));

        given().when().get(plan("sub-plan", "rg-plan", "my-plan") + API)
            .then().statusCode(200)
            .body("location", equalTo("eastus"))
            .body("tags.env", equalTo("test"));
    }

    @Test
    void plansAreListedByResourceGroupAndSubscription() {
        given().contentType("application/json").body(PLAN_BODY)
            .when().put(plan("sub-list", "rg-a", "plan-a") + API).then().statusCode(200);
        given().contentType("application/json").body(PLAN_BODY)
            .when().put(plan("sub-list", "rg-b", "plan-b") + API).then().statusCode(200);

        given().when().get("/subscriptions/sub-list/resourceGroups/rg-a/providers/Microsoft.Web/serverFarms" + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get("/subscriptions/sub-list/providers/Microsoft.Web/serverFarms" + API)
            .then().statusCode(200).body("value", hasSize(2));
    }

    @Test
    void aPatchChangesOnlyWhatItSends() {
        given().contentType("application/json").body(PLAN_BODY)
            .when().put(plan("sub-patch", "rg", "patched") + API).then().statusCode(200);
        given().contentType("application/json").body("{\"sku\":{\"name\":\"EP1\",\"tier\":\"ElasticPremium\"},"
                + "\"properties\":{\"maximumElasticWorkerCount\":3}}")
            .when().patch(plan("sub-patch", "rg", "patched") + API)
            .then().statusCode(200)
            .body("sku.name", equalTo("EP1"))
            .body("properties.maximumElasticWorkerCount", equalTo(3))
            .body("properties.reserved", equalTo(true))
            .body("tags.env", equalTo("test"))
            .body("location", equalTo("eastus"));
    }

    @Test
    void aPatchOfAMissingPlanAnswers404() {
        given().contentType("application/json").body("{\"tags\":{\"a\":\"b\"}}")
            .when().patch(plan("sub-patch", "rg", "absent") + API).then().statusCode(404);
    }

    @Test
    void aWriteToAPlanCollectionAnswers405AndLeavesThePlans() {
        putPlan(plan("sub-coll", "rg", "kept"), PLAN_BODY);
        String rgCollection = "/subscriptions/sub-coll/resourceGroups/rg/providers/Microsoft.Web/serverFarms";
        String subCollection = "/subscriptions/sub-coll/providers/Microsoft.Web/serverFarms";

        given().when().delete(rgCollection + API).then().statusCode(405);
        given().contentType("application/json").body(PLAN_BODY).when().put(rgCollection + API).then().statusCode(405);
        given().when().delete(subCollection + API).then().statusCode(405);
        given().contentType("application/json").body("{}").when().post(subCollection + API).then().statusCode(405);

        given().when().get(plan("sub-coll", "rg", "kept") + API).then().statusCode(200);
    }

    @Test
    void aDeletedPlanAnswers404() {
        given().contentType("application/json").body(PLAN_BODY)
            .when().put(plan("sub-del", "rg", "gone") + API).then().statusCode(200);
        given().when().delete(plan("sub-del", "rg", "gone") + API).then().statusCode(200);
        given().when().get(plan("sub-del", "rg", "gone") + API).then().statusCode(404);
    }

    @Test
    void azurermLowerCasePathReachesThePlan() {
        given().contentType("application/json").body(PLAN_BODY)
            .when().put(plan("sub-case", "rg", "CasePlan") + API).then().statusCode(200);
        given().when()
            .get("/subscriptions/sub-case/resourceGroups/rg/providers/microsoft.web/serverfarms/caseplan" + API)
            .then().statusCode(200).body("name", equalTo("CasePlan"));
    }

    @Test
    void aRePutReplacesThePlanWholeAndKeepsItsOriginalIdCasing() {
        putPlan(plan("sub-reput", "RG-Case", "CasePlan"), PLAN_BODY);
        given().contentType("application/json").body("{\"location\":\"eastus\",\"sku\":{\"name\":\"B1\"}}")
            .when().put(plan("SUB-REPUT", "rg-case", "caseplan") + API)
            .then().statusCode(200)
            .body("id", equalTo(plan("sub-reput", "RG-Case", "CasePlan")))
            .body("name", equalTo("CasePlan"))
            .body("sku.name", equalTo("B1"))
            .body("$", not(hasKey("tags")))
            .body("properties.reserved", equalTo(false));
    }

    @Test
    void theSameNameInAnotherSubscriptionIsADifferentPlan() {
        putPlan(plan("sub-one", "rg", "shared-name"), PLAN_BODY);
        putPlan(plan("sub-two", "rg", "shared-name"), PLAN_BODY);
        given().when().delete(plan("sub-one", "rg", "shared-name") + API).then().statusCode(200);
        given().when().get(plan("sub-two", "rg", "shared-name") + API).then().statusCode(200);
    }

    @Test
    void aPlanAndASiteWithTheSameNameCoexist() {
        String site = "/subscriptions/sub-both/resourceGroups/rg/providers/Microsoft.Web/sites/same-name";
        putPlan(plan("sub-both", "rg", "same-name"), PLAN_BODY);
        given().contentType("application/json").body("{\"location\":\"eastus\",\"kind\":\"functionapp,linux\"}")
            .when().put(site + SITE_API).then().statusCode(200);

        given().when().get(plan("sub-both", "rg", "same-name") + API)
            .then().statusCode(200).body("type", equalTo("Microsoft.Web/serverFarms"));
        given().when().get(site + SITE_API)
            .then().statusCode(200).body("type", equalTo("Microsoft.Web/sites"));
        given().when().get("/subscriptions/sub-both/resources" + API)
            .then().statusCode(200).body("value", hasSize(2))
            .body("value.type", hasItem("Microsoft.Web/serverFarms"));
    }

    @Test
    void aResetClearsThePlans() {
        putPlan(plan("sub-reset", "rg", "reset-me"), PLAN_BODY);
        given().post("/_admin/reset").then().statusCode(204);
        given().when().get(plan("sub-reset", "rg", "reset-me") + API).then().statusCode(404);
    }

    @Test
    void aPathUnderAPlanAnswers404AndAPostAnswers405() {
        putPlan(plan("sub-child", "rg", "parent"), PLAN_BODY);
        given().when().get(plan("sub-child", "rg", "parent") + "/virtualNetworkConnections" + API)
            .then().statusCode(404);
        given().contentType("application/json").body("{}")
            .when().post(plan("sub-child", "rg", "parent") + API).then().statusCode(405);
    }
}
