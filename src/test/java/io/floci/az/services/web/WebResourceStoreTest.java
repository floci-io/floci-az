package io.floci.az.services.web;

import io.floci.az.core.StoredObject;
import io.floci.az.core.storage.StorageBackend;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@DisplayName("Web ARM: sites in the ARM resource store, names owned by the registry")
class WebResourceStoreTest {

    private static final String API = "?api-version=2023-01-01";
    private static final String SITE_BODY = "{\"location\":\"eastus\",\"kind\":\"functionapp,linux\","
        + "\"properties\":{\"siteConfig\":{\"linuxFxVersion\":\"Python|3.12\"}}}";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static String site(String sub, String rg, String name) {
        return "/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/Microsoft.Web/sites/" + name;
    }

    private static void put(String path, String body, int status) {
        given().contentType("application/json").body(body).when().put(path + API).then().statusCode(status);
    }

    @Test
    void aSitePutThroughOtherCasingUpdatesTheOneSite() {
        put(site("sub-sitecase", "RG-Case", "CaseSite"), SITE_BODY, 200);
        given().contentType("application/json").body(SITE_BODY)
            .when().put(site("SUB-SITECASE", "rg-case", "casesite") + API)
            .then().statusCode(200)
            .body("id", equalTo(site("sub-sitecase", "RG-Case", "CaseSite")))
            .body("name", equalTo("CaseSite"))
            .body("properties.siteConfig.linuxFxVersion", equalTo("Python|3.12"));

        given().when().get("/subscriptions/sub-sitecase/resourceGroups/rg-case/providers/Microsoft.Web/sites" + API)
            .then().statusCode(200).body("value", hasSize(1));
        given().when().get("/subscriptions/sub-sitecase/providers/Microsoft.Web/sites" + API)
            .then().statusCode(200).body("value", hasSize(1))
            .body("value[0].id", equalTo(site("sub-sitecase", "RG-Case", "CaseSite")))
            .body("value[0]._sub", nullValue());
        given().when().get("/subscriptions/sub-sitecase/resourceGroups/RG-CASE/resources" + API)
            .then().statusCode(200).body("value", hasSize(1))
            .body("value[0].type", equalTo("Microsoft.Web/sites"));
    }

    @Test
    void sitesAreKeptUnderTheirCanonicalIds() {
        put(site("Sub-Stored", "RG", "StoredSite"), SITE_BODY, 200);

        StorageBackend<String, StoredObject> backend = storageFactory.create(WebArmService.STORE);
        assertEquals(List.of(site("Sub-Stored", "RG", "StoredSite").toLowerCase(Locale.ROOT)),
            List.copyOf(backend.keys()));

        given().when().delete(site("sub-stored", "rg", "storedsite") + API).then().statusCode(200);
        given().when().get(site("Sub-Stored", "RG", "StoredSite") + API).then().statusCode(404);
        assertEquals(List.of(), List.copyOf(backend.keys()));
    }

    @Test
    void theWebConfigIsReadAndReplacedThroughAnyCasing() {
        put(site("sub-config", "rg", "ConfigSite"), SITE_BODY, 200);
        given().when().get(site("SUB-CONFIG", "RG", "configsite") + "/config/web" + API)
            .then().statusCode(200)
            .body("name", equalTo("web"))
            .body("type", equalTo("Microsoft.Web/sites/config"))
            .body("properties.linuxFxVersion", equalTo("Python|3.12"));
        given().contentType("application/json").body("{\"properties\":{\"linuxFxVersion\":\"Node|20\"}}")
            .when().put(site("sub-config", "RG", "configsite") + "/config/web" + API)
            .then().statusCode(200).body("properties.linuxFxVersion", equalTo("Node|20"));
        given().when().get(site("sub-config", "rg", "ConfigSite") + API)
            .then().statusCode(200)
            .body("properties.siteConfig.linuxFxVersion", equalTo("Node|20"))
            .body("location", equalTo("eastus"));
        given().when().get(site("sub-config", "rg", "nosite") + "/config/web" + API).then().statusCode(404);
    }

    @Test
    void anInvalidLinuxFxVersionWritesNothingAndHoldsNoName() {
        given().contentType("application/json")
            .body("{\"location\":\"eastus\",\"properties\":{\"siteConfig\":{\"linuxFxVersion\":\"Python\"}}}")
            .when().put(site("sub-fx-a", "rg", "fxsite") + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidLinuxFxVersion"));
        given().when().get(site("sub-fx-a", "rg", "fxsite") + API).then().statusCode(404);
        put(site("sub-fx-b", "rg", "fxsite"), SITE_BODY, 200);
    }

    @Test
    void aSiteNameBelongsToItsFirstOwnerWithTheAppServiceConflict() {
        put(site("sub-site-a", "rg", "ownedsite"), SITE_BODY, 200);
        for (String path : List.of(site("sub-site-b", "rg", "OwnedSite"), site("sub-site-a", "rg-other", "OwnedSite"))) {
            given().contentType("application/json").body(SITE_BODY)
                .when().put(path + API)
                .then().statusCode(409)
                .body("Code", equalTo("Conflict"))
                .body("Message", equalTo("Website with given name OwnedSite already exists."))
                .body("ErrorEntity.ExtendedCode", equalTo("54001"))
                .body("ErrorEntity.MessageTemplate", equalTo("Website with given name {0} already exists."))
                .body("ErrorEntity.Parameters", equalTo(List.of("OwnedSite")))
                .body("error", nullValue());
        }
        given().when().get(site("sub-site-b", "rg", "ownedsite") + API).then().statusCode(404);
    }

    @Test
    void aChildPutFromAnotherSubscriptionNeitherWritesNorTakesTheSite() {
        put(site("sub-child-a", "rg", "takensite"), SITE_BODY, 200);
        String nodeSite = "{\"location\":\"westus\",\"properties\":{\"siteConfig\":{\"linuxFxVersion\":\"Node|20\"}}}";

        for (String child : List.of("/slots/staging", "/config/appsettings", "/config/web/extra")) {
            given().contentType("application/json").body(nodeSite)
                .when().put(site("sub-child-b", "rg", "takensite") + child + API)
                .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
        }
        given().contentType("application/json").body("{\"properties\":{\"linuxFxVersion\":\"Node|20\"}}")
            .when().put(site("sub-child-b", "rg", "takensite") + "/config/web" + API)
            .then().statusCode(404);
        given().when().get(site("sub-child-b", "rg", "takensite") + API).then().statusCode(404);
        given().when().get("/subscriptions/sub-child-b/providers/Microsoft.Web/sites" + API)
            .then().statusCode(200).body("value", hasSize(0));
        // A child DELETE does not reach the parent either.
        given().when().delete(site("sub-child-a", "rg", "takensite") + "/slots/staging" + API).then().statusCode(404);

        given().when().get(site("sub-child-a", "rg", "takensite") + API).then().statusCode(200)
            .body("id", equalTo(site("sub-child-a", "rg", "takensite")))
            .body("location", equalTo("eastus"))
            .body("properties.siteConfig.linuxFxVersion", equalTo("Python|3.12"));
        given().when().get("/devstoreaccount1-functions/admin/apps/takensite").then().statusCode(200)
            .body("linuxFxVersion", equalTo("Python|3.12"));
        given().contentType("application/json").body("{\"name\":\"takensite\",\"type\":\"Microsoft.Web/sites\"}")
            .when().post("/subscriptions/sub-child-b/providers/Microsoft.Web/checknameavailability" + API)
            .then().statusCode(200).body("nameAvailable", equalTo(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Microsoft.Web/checknameavailability", "Microsoft.Web/checkNameAvailability",
        "microsoft.web/CheckNameAvailability"})
    void checkNameAvailabilityAnswersFromTheRegistry(String operation) {
        String check = "/subscriptions/sub-check/providers/" + operation + API;
        String request = "{\"name\":\"checksite\",\"type\":\"Microsoft.Web/sites\"}";

        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
        put(site("sub-owner", "rg", "checksite"), SITE_BODY, 200);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(false))
            .body("reason", equalTo("AlreadyExists"))
            .body("message", equalTo("Website with given name checksite already exists."));
        given().contentType("application/json").body("{\"name\":\"CHECKSITE\",\"type\":\"Site\"}")
            .when().post(check).then().statusCode(200).body("nameAvailable", equalTo(false));
        given().contentType("application/json").body("{\"name\":\"checksite\",\"type\":\"Microsoft.Web/sites/slots\"}")
            .when().post(check).then().statusCode(200).body("nameAvailable", equalTo(true));
        given().when().delete(site("sub-owner", "rg", "checksite") + API).then().statusCode(200);
        given().contentType("application/json").body(request).when().post(check).then().statusCode(200)
            .body("nameAvailable", equalTo(true));
    }

    @Test
    void anUnknownSubscriptionLevelWebPathIsNotFound() {
        put(site("sub-norg", "rg", "norgsite"), SITE_BODY, 200);
        given().when().get("/subscriptions/sub-norg/providers/Microsoft.Web/sites/norgsite" + API)
            .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
    }
}
