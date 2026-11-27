package io.floci.az.services.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@DisplayName("Web ARM: a site's config documents other than config/web")
class WebSiteConfigTest {

    private static final String API = "?api-version=2023-12-01";
    private static final String SITE_BODY = "{\"location\":\"eastus\",\"kind\":\"functionapp,linux\"}";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String site(String sub, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/rg/providers/Microsoft.Web/sites/" + name;
    }

    private static void putSite(String path) {
        given().contentType("application/json").body(SITE_BODY).when().put(path + API).then().statusCode(200);
    }

    private static void putConfig(String path, String properties) {
        given().contentType("application/json").body("{\"properties\":" + properties + "}")
            .when().put(path + API).then().statusCode(200);
    }

    @Test
    void aPutConfigReadsBackThroughGetAndTheListAction() {
        String app = site("sub-cfg", "cfgsite");
        putSite(app);
        given().contentType("application/json").body("{\"properties\":{\"FOO\":\"bar\"}}")
            .when().put(app + "/config/appsettings" + API)
            .then().statusCode(200)
            .body("id", equalTo(app + "/config/appsettings"))
            .body("name", equalTo("appsettings"))
            .body("type", equalTo("Microsoft.Web/sites/config"))
            .body("properties.FOO", equalTo("bar"));

        given().when().post(app + "/config/appsettings/list" + API)
            .then().statusCode(200).body("properties.FOO", equalTo("bar"));
        // The API defines no GET for app settings: they carry secrets, so they are read through the list action.
        given().when().get(app + "/config/appsettings" + API).then().statusCode(405);
    }

    @Test
    void aConfigNeverWrittenReadsBackWithEmptyProperties() {
        String app = site("sub-default", "defaultsite");
        putSite(app);
        given().when().get(app + "/config/logs" + API)
            .then().statusCode(200)
            .body("name", equalTo("logs"))
            .body("properties", equalTo(Map.of()));
        given().when().post(app + "/config/azurestorageaccounts/list" + API)
            .then().statusCode(200).body("properties", equalTo(Map.of()));
    }

    @Test
    void aRePutReplacesTheConfigWhole() {
        String app = site("sub-replace", "replacesite");
        putSite(app);
        putConfig(app + "/config/appsettings", "{\"A\":\"1\"}");
        putConfig(app + "/config/appsettings", "{\"B\":\"2\"}");
        given().when().post(app + "/config/appsettings/list" + API)
            .then().statusCode(200)
            .body("properties.B", equalTo("2"))
            .body("properties", not(hasKey("A")));
    }

    @Test
    void configNamesMatchIgnoringCaseAndReadBackAsTheApiSpellsThem() {
        String app = site("sub-case", "casesite");
        putSite(app);
        putConfig(app + "/config/AzureStorageAccounts", "{\"share\":{\"type\":\"AzureFiles\"}}");
        given().when().post(app + "/config/azurestorageaccounts/list" + API)
            .then().statusCode(200)
            .body("name", equalTo("azurestorageaccounts"))
            .body("properties.share.type", equalTo("AzureFiles"));
        given().when().get(app + "/config/AUTHSETTINGSV2" + API)
            .then().statusCode(200).body("name", equalTo("authsettingsV2"));
    }

    @Test
    void aConfigNameTheApiDoesNotDefineAnswers404() {
        String app = site("sub-unknown", "unknownsite");
        putSite(app);
        given().contentType("application/json").body("{\"properties\":{}}")
            .when().put(app + "/config/notaconfig" + API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void aConfigOfASiteThatDoesNotExistAnswers404() {
        String missing = site("sub-missing", "nosite");
        given().when().get(missing + "/config/appsettings" + API).then().statusCode(404);
        given().when().post(missing + "/config/appsettings/list" + API).then().statusCode(404);
        given().contentType("application/json").body("{\"properties\":{\"A\":\"1\"}}")
            .when().put(missing + "/config/appsettings" + API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
        given().when().get(missing + API).then().statusCode(404);
    }

    @Test
    void onlyPostReachesTheListActionAndAConfigIsNotDeletedOnItsOwn() {
        String app = site("sub-methods", "methodsite");
        putSite(app);
        given().when().get(app + "/config/appsettings/list" + API).then().statusCode(405);
        given().when().delete(app + "/config/appsettings" + API).then().statusCode(405);
    }

    @Test
    void eachConfigAnswersTheMethodsTheApiDefinesForIt() {
        String app = site("sub-spec", "specsite");
        putSite(app);
        putConfig(app + "/config/authsettingsV2", "{\"platform\":{\"enabled\":false}}");
        // authsettingsV2 reads back through a GET, and its list action is a GET too, as in the spec.
        given().when().get(app + "/config/authsettingsV2" + API)
            .then().statusCode(200).body("properties.platform.enabled", equalTo(false));
        given().when().get(app + "/config/authsettingsV2/list" + API)
            .then().statusCode(200).body("properties.platform.enabled", equalTo(false));
        given().when().post(app + "/config/authsettingsV2/list" + API).then().statusCode(405);

        // logs and slotConfigNames have a GET and no list action.
        given().when().get(app + "/config/slotConfigNames" + API).then().statusCode(200);
        given().when().post(app + "/config/logs/list" + API).then().statusCode(404);

        // backup answers 404 until one is set, as Azure does for a site without a backup, and is the one config the
        // API lets a client DELETE.
        given().when().post(app + "/config/backup/list" + API).then().statusCode(404);
        putConfig(app + "/config/backup", "{\"enabled\":true}");
        given().when().post(app + "/config/backup/list" + API)
            .then().statusCode(200).body("properties.enabled", equalTo(true));
        given().when().delete(app + "/config/backup" + API).then().statusCode(200);
        given().when().post(app + "/config/backup/list" + API).then().statusCode(404);
    }

    @Test
    void deletingTheSiteDeletesItsConfigs() {
        String app = site("sub-delete", "deletesite");
        putSite(app);
        putConfig(app + "/config/appsettings", "{\"A\":\"1\"}");
        given().when().delete(app + API).then().statusCode(200);

        putSite(app);
        given().when().post(app + "/config/appsettings/list" + API)
            .then().statusCode(200).body("properties", equalTo(Map.of()));
    }

    @Test
    void aResetClearsTheConfigs() {
        String app = site("sub-reset", "resetsite");
        putSite(app);
        putConfig(app + "/config/appsettings", "{\"A\":\"1\"}");
        given().post("/_admin/reset").then().statusCode(204);

        putSite(app);
        given().when().post(app + "/config/appsettings/list" + API)
            .then().statusCode(200).body("properties", equalTo(Map.of()));
    }

    @Test
    void publishingCredentialsAreAListActionThatReturnsTheSamePairEachTime() {
        String app = site("sub-publish", "publishsite");
        putSite(app);
        String password = given().when().post(app + "/config/publishingcredentials/list" + API)
            .then().statusCode(200)
            .body("name", equalTo("publishingcredentials"))
            .body("type", equalTo("Microsoft.Web/sites/publishingcredentials"))
            .body("properties.publishingUserName", equalTo("$publishsite"))
            .body("properties.scmUri", startsWith("https://$publishsite:"))
            .extract().path("properties.publishingPassword");

        given().when().post(app + "/config/publishingcredentials/list" + API)
            .then().statusCode(200).body("properties.publishingPassword", equalTo(password));
        given().when().get(app + "/config/publishingcredentials" + API).then().statusCode(404);
        given().when().get(app + "/config/publishingcredentials/list" + API).then().statusCode(405);
        given().when().post(site("sub-publish", "nosite") + "/config/publishingcredentials/list" + API)
            .then().statusCode(404);
    }

    @Test
    void basicPublishingCredentialsPoliciesDefaultToAllowedAndKeepWhatAPutSets() {
        String app = site("sub-policy", "policysite");
        putSite(app);
        given().when().get(app + "/basicPublishingCredentialsPolicies/ftp" + API)
            .then().statusCode(200)
            .body("id", equalTo(app + "/basicPublishingCredentialsPolicies/ftp"))
            .body("name", equalTo("ftp"))
            .body("type", equalTo("Microsoft.Web/sites/basicPublishingCredentialsPolicies"))
            .body("properties.allow", equalTo(true));

        putConfig(app + "/basicPublishingCredentialsPolicies/scm", "{\"allow\":false}");
        given().when().get(app + "/basicPublishingCredentialsPolicies/SCM" + API)
            .then().statusCode(200).body("properties.allow", equalTo(false));
        given().when().get(app + "/basicPublishingCredentialsPolicies" + API)
            .then().statusCode(200)
            .body("value.name", contains("ftp", "scm"))
            .body("value.properties.allow", contains(true, false));

        given().when().get(app + "/basicPublishingCredentialsPolicies/sftp" + API).then().statusCode(404);
        given().when().delete(app + "/basicPublishingCredentialsPolicies/ftp" + API).then().statusCode(405);
        given().when().get(site("sub-policy", "nosite") + "/basicPublishingCredentialsPolicies/ftp" + API)
            .then().statusCode(404);
    }
}
