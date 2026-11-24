package io.floci.az.services.containerapps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The calls {@code azd deploy} makes to roll a container app to a new image, in its order
 * ({@code containerAppService.AddRevision} in azd's {@code pkg/containerapps/container_app.go}): read the environment's
 * mode at api-version 2026-07-01, read the app, set {@code template.revisionSuffix} to {@code azd-<unix seconds>} and
 * the new image, put the values {@code listSecrets} answers back into the secrets, and PATCH the whole body it read,
 * read-only fields included.
 */
@QuarkusTest
@DisplayName("Container Apps: the revision update azd deploy sends")
class ContainerAppsAzdDeployTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "?api-version=2025-07-01";
    private static final String PROVIDER = "/subscriptions/test-sub-aca-azd/resourceGroups/test-rg-aca-azd"
            + "/providers/Microsoft.App";
    private static final String ENVIRONMENT_ID = PROVIDER + "/managedEnvironments/azd-env";
    private static final String APP = "web";
    private static final String APP_URL = PROVIDER + "/containerApps/" + APP + API;
    private static final String SUFFIX = "azd-1700000000";
    private static final String NEW_IMAGE = "azdregistry.azurecr.io/floci-azd/web-dev:azd-deploy-1700000000";

    @BeforeEach
    void setUp() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
                .put(ENVIRONMENT_ID + API).then().statusCode(201);
    }

    @Test
    void anAzdDeployAddsARevisionFromTheWholeAppBody() throws Exception {
        createApp("Single");
        // azd reads the environment's mode at its own api-version; no environmentMode means not an Express one.
        given().get(ENVIRONMENT_ID + "?api-version=2026-07-01").then()
                .statusCode(200)
                .body("properties", not(hasKey("environmentMode")));
        String previousRevision = given().get(APP_URL).then().extract().path("properties.latestRevisionName");

        ObjectNode body = azdRevisionBody();
        given().contentType("application/json").body(MAPPER.writeValueAsString(body))
                .patch(APP_URL).then().statusCode(200);

        given().get(APP_URL).then()
                .statusCode(200)
                .body("properties.latestRevisionName", equalTo(APP + "--" + SUFFIX))
                .body("properties.latestReadyRevisionName", equalTo(APP + "--" + SUFFIX))
                .body("properties.template.containers[0].image", equalTo(NEW_IMAGE))
                .body("properties.configuration.ingress.fqdn", notNullValue());
        given().post(PROVIDER + "/containerApps/" + APP + "/listSecrets" + API).then()
                .statusCode(200)
                .body("value[0].name", equalTo("api-key"))
                .body("value[0].value", equalTo("secret-value"));
        given().get(PROVIDER + "/containerApps/" + APP + "/revisions/" + previousRevision + API).then()
                .statusCode(200)
                .body("properties.active", equalTo(false));
    }

    @Test
    void anAzdDeployInMultipleModeSendsAllTrafficToTheNewRevision() throws Exception {
        createApp("Multiple");
        String previousRevision = given().get(APP_URL).then().extract().path("properties.latestRevisionName");

        ObjectNode body = azdRevisionBody();
        ArrayNode traffic = ((ObjectNode) body.path("properties").path("configuration").path("ingress"))
                .putArray("traffic");
        traffic.addObject().put("revisionName", APP + "--" + SUFFIX).put("weight", 100);
        given().contentType("application/json").body(MAPPER.writeValueAsString(body))
                .patch(APP_URL).then().statusCode(200);

        // The traffic rule azd sent is the one kept: the weights below would read the same if it were dropped,
        // since the latest revision takes all traffic by default.
        given().get(APP_URL).then()
                .statusCode(200)
                .body("properties.configuration.ingress.traffic", hasSize(1))
                .body("properties.configuration.ingress.traffic[0].revisionName", equalTo(APP + "--" + SUFFIX))
                .body("properties.configuration.ingress.traffic[0].weight", equalTo(100))
                .body("properties.configuration.ingress.traffic[0]", not(hasKey("latestRevision")));
        given().get(PROVIDER + "/containerApps/" + APP + "/revisions/" + APP + "--" + SUFFIX + API).then()
                .statusCode(200)
                .body("properties.active", equalTo(true))
                .body("properties.trafficWeight", equalTo(100));
        given().get(PROVIDER + "/containerApps/" + APP + "/revisions/" + previousRevision + API).then()
                .statusCode(200)
                .body("properties.trafficWeight", equalTo(0));
    }

    @Test
    void theEnvironmentIdAzdMayReadAsManagedEnvironmentIdIsAccepted() throws Exception {
        createApp("Single");

        ObjectNode body = azdRevisionBody();
        ObjectNode properties = (ObjectNode) body.path("properties");
        properties.remove("environmentId");
        properties.put("managedEnvironmentId", ENVIRONMENT_ID);
        given().contentType("application/json").body(MAPPER.writeValueAsString(body))
                .patch(APP_URL).then().statusCode(200);

        given().get(APP_URL).then()
                .statusCode(200)
                .body("properties.latestRevisionName", equalTo(APP + "--" + SUFFIX));
    }

    @Test
    void twoDeploysInTheSameSecondCollide() throws Exception {
        createApp("Single");
        String body = MAPPER.writeValueAsString(azdRevisionBody());
        given().contentType("application/json").body(body).patch(APP_URL).then().statusCode(200);

        // azd's suffix has one-second granularity, so a second deploy within the same second reuses the name.
        ObjectNode again = azdRevisionBody();
        ((ObjectNode) again.path("properties").path("template").path("containers").get(0))
                .put("image", NEW_IMAGE + "-again");
        given().contentType("application/json").body(MAPPER.writeValueAsString(again))
                .patch(APP_URL).then()
                .statusCode(400)
                .body("error.code", equalTo("ContainerAppRevisionAlreadyExists"));
    }

    /**
     * What azd PATCHes: the app as GET answered it, with the new suffix and image, and the secrets as listSecrets
     * answered them (GET hides their values).
     */
    private static ObjectNode azdRevisionBody() throws Exception {
        ObjectNode app = (ObjectNode) MAPPER.readTree(given().get(APP_URL).then().statusCode(200)
                .extract().asString());
        ObjectNode template = (ObjectNode) app.path("properties").path("template");
        template.put("revisionSuffix", SUFFIX);
        ((ObjectNode) template.path("containers").get(0)).put("image", NEW_IMAGE);
        JsonNode secrets = MAPPER.readTree(given().post(PROVIDER + "/containerApps/" + APP + "/listSecrets" + API)
                .then().statusCode(200).extract().asString()).path("value");
        ((ObjectNode) app.path("properties").path("configuration")).set("secrets", secrets);
        return app;
    }

    private static void createApp(String mode) {
        given().contentType("application/json").body("""
                {
                  "location": "eastus",
                  "properties": {
                    "environmentId": "%s",
                    "configuration": {
                      "activeRevisionsMode": "%s",
                      "secrets": [{"name": "api-key", "value": "secret-value"}],
                      "ingress": {"external": true, "targetPort": 8080}
                    },
                    "template": {
                      "containers": [{"name": "web", "image": "nginx:alpine",
                                      "env": [{"name": "API_KEY", "secretRef": "api-key"}]}],
                      "scale": {"minReplicas": 1, "maxReplicas": 2}
                    }
                  }
                }
                """.formatted(ENVIRONMENT_ID, mode))
                .put(APP_URL).then().statusCode(201);
    }
}
