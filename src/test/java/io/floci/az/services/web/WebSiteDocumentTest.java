package io.floci.az.services.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@DisplayName("Web ARM: a site reads back the properties, kind and identity a PUT sent")
class WebSiteDocumentTest {

    private static final String API = "?api-version=2023-12-01";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String PLAN_ID =
        "/subscriptions/sub-doc/resourceGroups/rg/providers/Microsoft.Web/serverFarms/doc-plan";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String site(String name) {
        return "/subscriptions/sub-doc/resourceGroups/rg/providers/Microsoft.Web/sites/" + name;
    }

    private static void putSite(String path, String body) {
        given().contentType("application/json").body(body).when().put(path + API).then().statusCode(200);
    }

    @Test
    void aSiteKeepsTheServerFarmIdAndTheOtherPropertiesItWasSent() {
        putSite(site("docsite"), "{\"location\":\"eastus\",\"kind\":\"functionapp,linux\",\"properties\":{"
            + "\"serverFarmId\":\"" + PLAN_ID + "\",\"httpsOnly\":true,\"clientCertEnabled\":false,"
            + "\"siteConfig\":{\"linuxFxVersion\":\"Python|3.12\",\"alwaysOn\":false}}}");

        given().when().get(site("docsite") + API)
            .then().statusCode(200)
            .body("properties.serverFarmId", equalTo(PLAN_ID))
            .body("properties.httpsOnly", equalTo(true))
            .body("properties.clientCertEnabled", equalTo(false))
            .body("properties.state", equalTo("Running"))
            .body("properties.enabled", equalTo(true))
            .body("properties.defaultHostName", equalTo("docsite.azurewebsites.net"))
            .body("properties.siteConfig.alwaysOn", equalTo(false))
            .body("properties.siteConfig.linuxFxVersion", equalTo("Python|3.12"));
        given().when().get(site("docsite") + "/config/web" + API)
            .then().statusCode(200).body("properties.alwaysOn", equalTo(false));
    }

    @Test
    void aSiteReadsBackTheKindItWasSent() {
        putSite(site("kindsite"), "{\"location\":\"eastus\",\"kind\":\"functionapp,linux,container\"}");
        given().when().get(site("kindsite") + API)
            .then().statusCode(200).body("kind", equalTo("functionapp,linux,container"));

        putSite(site("nokindsite"), "{\"location\":\"eastus\"}");
        given().when().get(site("nokindsite") + API)
            .then().statusCode(200).body("kind", equalTo("functionapp,linux"));
    }

    @Test
    void aSystemAssignedIdentityGetsAPrincipalThatARePutKeeps() {
        String withIdentity = "{\"location\":\"eastus\",\"identity\":{\"type\":\"SystemAssigned\"}}";
        putSite(site("idsite"), withIdentity);
        String principal = given().when().get(site("idsite") + API)
            .then().statusCode(200)
            .body("identity.type", equalTo("SystemAssigned"))
            .body("identity.principalId", matchesPattern(UUID_PATTERN))
            .extract().path("identity.principalId");

        putSite(site("idsite"), withIdentity);
        given().when().get(site("idsite") + API)
            .then().statusCode(200).body("identity.principalId", equalTo(principal));

        // A PUT without an identity replaces the site whole, as in Azure: the identity is gone.
        putSite(site("idsite"), "{\"location\":\"eastus\"}");
        given().when().get(site("idsite") + API)
            .then().statusCode(200).body("$", not(hasKey("identity")));
    }

    @Test
    void aPutOfConfigWebReplacesTheSiteConfigWholeAndKeepsTheRestOfTheSite() {
        putSite(site("cfgwebsite"), "{\"location\":\"eastus\",\"identity\":{\"type\":\"SystemAssigned\"},"
            + "\"properties\":{\"serverFarmId\":\"" + PLAN_ID + "\","
            + "\"siteConfig\":{\"linuxFxVersion\":\"Python|3.12\",\"alwaysOn\":true}}}");
        given().contentType("application/json")
            .body("{\"properties\":{\"linuxFxVersion\":\"Python|3.12\",\"ftpsState\":\"Disabled\"}}")
            .when().put(site("cfgwebsite") + "/config/web" + API)
            .then().statusCode(200).body("properties.ftpsState", equalTo("Disabled"));

        given().when().get(site("cfgwebsite") + API)
            .then().statusCode(200)
            .body("properties.serverFarmId", equalTo(PLAN_ID))
            .body("identity.principalId", matchesPattern(UUID_PATTERN))
            .body("properties.siteConfig.ftpsState", equalTo("Disabled"))
            .body("properties.siteConfig", not(hasKey("alwaysOn")));
    }

    @Test
    void appSettingsAndConnectionStringsInASitePutReadBackFromTheirConfigs() {
        putSite(site("settingssite"), "{\"location\":\"eastus\",\"properties\":{\"siteConfig\":{"
            + "\"appSettings\":[{\"name\":\"FUNCTIONS_EXTENSION_VERSION\",\"value\":\"~4\"},"
            + "{\"name\":\"AzureWebJobsStorage__accountName\",\"value\":\"mystorage\"}],"
            + "\"connectionStrings\":[{\"name\":\"db\",\"connectionString\":\"Server=x\",\"type\":\"SQLAzure\"}]}}}");

        given().when().post(site("settingssite") + "/config/appsettings/list" + API)
            .then().statusCode(200)
            .body("properties.FUNCTIONS_EXTENSION_VERSION", equalTo("~4"))
            .body("properties.AzureWebJobsStorage__accountName", equalTo("mystorage"));
        given().when().post(site("settingssite") + "/config/connectionstrings/list" + API)
            .then().statusCode(200)
            .body("properties.db.value", equalTo("Server=x"))
            .body("properties.db.type", equalTo("SQLAzure"));
        // Azure keeps them out of the site's siteConfig, which config/web reads back.
        given().when().get(site("settingssite") + API)
            .then().statusCode(200).body("properties.siteConfig", not(hasKey("appSettings")));

        // A re-PUT that sends no appSettings leaves them as they were.
        putSite(site("settingssite"), "{\"location\":\"eastus\"}");
        given().when().post(site("settingssite") + "/config/appsettings/list" + API)
            .then().statusCode(200).body("properties.FUNCTIONS_EXTENSION_VERSION", equalTo("~4"));
    }
}
