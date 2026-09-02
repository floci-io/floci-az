package io.floci.az.services.containerapps;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestProfile(ContainerAppsHandlerTest.MockedProfile.class)
@DisplayName("Container Apps ARM and revision behavior")
public class ContainerAppsHandlerTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.container-apps.mocked", "true");
        }
    }

    private static final String SUB = "test-sub-containerapps";
    private static final String RG = "test-rg-containerapps";
    private static final String PROVIDER = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.App";
    private static final String ENVIRONMENT = "local-env";
    private static final String ENVIRONMENT_ID = PROVIDER + "/managedEnvironments/" + ENVIRONMENT;
    private static final String ENVIRONMENT_URL = ENVIRONMENT_ID + "?api-version=2025-07-01";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void managedEnvironmentCrudAndLists() {
        createEnvironment();

        given().get(ENVIRONMENT_URL).then()
                .statusCode(200)
                .body("name", equalTo(ENVIRONMENT))
                .body("type", equalTo("Microsoft.App/managedEnvironments"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.defaultDomain", startsWith("local-env."))
                .body("properties.defaultDomain", endsWith(".azurecontainerapps.io"));

        given().get(PROVIDER + "/managedEnvironments?api-version=2025-07-01").then()
                .statusCode(200).body("value", hasSize(1));
        given().get("/subscriptions/" + SUB
                        + "/providers/Microsoft.App/managedEnvironments?api-version=2025-07-01").then()
                .statusCode(200).body("value", hasSize(1));

        given().delete(ENVIRONMENT_URL).then().statusCode(204);
        given().get(ENVIRONMENT_URL).then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void managedEnvironmentResponsesHideWriteOnlySecrets() {
        String body = """
                {
                  "location": "eastus",
                  "properties": {
                    "daprAIConnectionString": "InstrumentationKey=secret",
                    "daprAIInstrumentationKey": "secret-key",
                    "appLogsConfiguration": {
                      "logAnalyticsConfiguration": {
                        "customerId": "customer",
                        "sharedKey": "shared-secret"
                      }
                    }
                  }
                }
                """;

        given().contentType("application/json").body(body).put(ENVIRONMENT_URL).then()
                .statusCode(201)
                .body("properties.daprAIConnectionString", nullValue())
                .body("properties.daprAIInstrumentationKey", nullValue())
                .body("properties.appLogsConfiguration.logAnalyticsConfiguration.sharedKey", nullValue())
                .body("properties.appLogsConfiguration.logAnalyticsConfiguration.customerId",
                        equalTo("customer"));

        given().get(ENVIRONMENT_URL).then()
                .statusCode(200)
                .body("properties.daprAIConnectionString", nullValue())
                .body("properties.daprAIInstrumentationKey", nullValue())
                .body("properties.appLogsConfiguration.logAnalyticsConfiguration.sharedKey", nullValue());
    }

    @Test
    void nameAvailabilityUsesManagedEnvironmentScope() {
        createEnvironment();
        String body = "{\"name\":\"available-app\",\"type\":\"Microsoft.App/containerApps\"}";
        String endpoint = ENVIRONMENT_ID + "/checkNameAvailability?api-version=2025-07-01";

        given().contentType("application/json").body(body).post(endpoint).then()
                .statusCode(200)
                .body("nameAvailable", equalTo(true))
                .body("reason", equalTo("None"))
                .body("message", equalTo(""));

        createApp("available-app", "Single", "v1", 1);
        given().contentType("application/json").body(body).post(endpoint).then()
                .statusCode(200)
                .body("nameAvailable", equalTo(false))
                .body("reason", equalTo("AlreadyExists"));

        given().contentType("application/json").body(body)
                .post("/subscriptions/" + SUB
                        + "/providers/Microsoft.App/locations/eastus/checkNameAvailability"
                        + "?api-version=2025-07-01")
                .then().statusCode(404);
    }

    @Test
    void resourcesAppearInResourceGroupIndex() {
        createEnvironment();
        createApp("indexed-app", "Single", "v1", 1);

        given().get("/subscriptions/" + SUB + "/resourceGroups/" + RG
                        + "/resources?api-version=2021-04-01")
                .then().statusCode(200)
                .body("value.name", hasItems(ENVIRONMENT, "indexed-app"))
                .body("value.type", hasItems(
                        "Microsoft.App/managedEnvironments", "Microsoft.App/containerApps"));
    }

    @Test
    void appCreatePreservesTemplateHidesSecretsAndCreatesScaledRevision() {
        createEnvironment();
        createApp("secret-app", "Single", "v1", 2);

        given().get(appUrl("secret-app")).then()
                .statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.runningStatus", equalTo("Running"))
                .body("properties.latestRevisionName", equalTo("secret-app--v1"))
                .body("properties.configuration.ingress.fqdn",
                        equalTo("secret-app." + environmentDomain()))
                .body("properties.configuration.secrets[0].name", equalTo("api-key"))
                .body("properties.configuration.secrets[0].value", nullValue())
                .body("properties.template.containers[0].env[0].secretRef", equalTo("api-key"));

        given().post(appUrl("secret-app", "/listSecrets")).then()
                .statusCode(200)
                .body("value[0].name", equalTo("api-key"))
                .body("value[0].value", equalTo("secret-value"));

        given().get(appUrl("secret-app", "/revisions")).then()
                .statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("secret-app--v1"))
                .body("value[0].properties.active", equalTo(true))
                .body("value[0].properties.replicas", equalTo(2))
                .body("value[0].properties.trafficWeight", equalTo(100));
    }

    @Test
    void singleAndMultipleRevisionModesFollowAzureSemantics() {
        createEnvironment();
        createApp("single-app", "Single", "v1", 1);
        updateTemplate("single-app", "Single", "v2");

        given().get(appUrl("single-app", "/revisions")).then()
                .statusCode(200)
                .body("value", hasSize(2))
                .body("value[0].properties.active", equalTo(false))
                .body("value[0].properties.replicas", equalTo(0))
                .body("value[1].properties.active", equalTo(true));

        createApp("multi-app", "Multiple", "blue", 1);
        updateTemplate("multi-app", "Multiple", "green");
        given().get(appUrl("multi-app", "/revisions")).then()
                .statusCode(200)
                .body("value", hasSize(2))
                .body("value[0].properties.active", equalTo(true))
                .body("value[1].properties.active", equalTo(true));

        given().post(appUrl("multi-app", "/revisions/multi-app--blue/deactivate")).then()
                .statusCode(200);
        given().get(appUrl("multi-app", "/revisions/multi-app--blue")).then()
                .body("properties.active", equalTo(false));
        given().post(appUrl("multi-app", "/revisions/multi-app--blue/activate")).then()
                .statusCode(200);
        given().get(appUrl("multi-app", "/revisions/multi-app--blue")).then()
                .body("properties.active", equalTo(true));
    }

    @Test
    void validatesEnvironmentScaleAndIngress() {
        given().contentType("application/json")
                .body(appBody("/missing/environment", "Single", "v1", 1, 10, 8080))
                .put(appUrl("invalid-env"))
                .then().statusCode(400).body("error.code", equalTo("ManagedEnvironmentNotFound"));

        createEnvironment();
        given().contentType("application/json")
                .body(appBody(ENVIRONMENT_ID, "Single", "v1", 3, 2, 8080))
                .put(appUrl("invalid-scale"))
                .then().statusCode(400).body("error.code", equalTo("InvalidScaleRule"));

        given().contentType("application/json")
                .body(appBody(ENVIRONMENT_ID, "Single", "v1", 1, 2, 0))
                .put(appUrl("invalid-port"))
                .then().statusCode(400).body("error.code", equalTo("InvalidParameter"));
    }

    @Test
    void externalIngressRoutesByAzureFqdnAndReportsMockedMode() {
        createEnvironment();
        createApp("ingress-app", "Single", "v1", 1);

        given().header("Host", "ingress-app." + environmentDomain())
                .get("/hello")
                .then().statusCode(503)
                .body("error.code", equalTo("ContainerAppMocked"));
    }

    @Test
    void internalIngressRejectsPublicHostCaller() {
        createEnvironment();
        given().contentType("application/json")
                .body(appBody(ENVIRONMENT_ID, "Single", "v1", 1, 4, 8080)
                        .replace("\"external\": true", "\"external\": false"))
                .put(appUrl("internal-app")).then().statusCode(201);

        given().header("Host", "internal-app." + environmentDomain())
                .get("/hello")
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void switchingMultipleModeToSingleDeactivatesOlderRevision() {
        createEnvironment();
        createApp("mode-app", "Multiple", "blue", 1);
        updateTemplate("mode-app", "Multiple", "green");

        given().contentType("application/json")
                .body("{\"properties\":{\"configuration\":{\"activeRevisionsMode\":\"Single\"}}}")
                .patch(appUrl("mode-app")).then().statusCode(200);

        given().get(appUrl("mode-app", "/revisions")).then()
                .statusCode(200)
                .body("value", hasSize(2))
                .body("value[0].properties.active", equalTo(false))
                .body("value[1].properties.active", equalTo(true));
    }

    @Test
    void deletingEnvironmentInUseReturnsConflict() {
        createEnvironment();
        createApp("using-app", "Single", "v1", 1);

        given().delete(ENVIRONMENT_URL).then().statusCode(409)
                .body("error.code", equalTo("ManagedEnvironmentInUse"));
        given().delete(appUrl("using-app")).then().statusCode(204);
        given().delete(ENVIRONMENT_URL).then().statusCode(204);
    }

    private static void createEnvironment() {
        given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"properties\":{\"zoneRedundant\":false}}")
                .put(ENVIRONMENT_URL).then().statusCode(201)
                .body("id", equalTo(ENVIRONMENT_ID));
    }

    private static void createApp(String name, String mode, String suffix, int minReplicas) {
        given().contentType("application/json")
                .body(appBody(ENVIRONMENT_ID, mode, suffix, minReplicas, 4, 8080))
                .put(appUrl(name)).then().statusCode(201)
                .body("properties.latestReadyRevisionName", notNullValue());
    }

    private static void updateTemplate(String name, String mode, String suffix) {
        given().contentType("application/json")
                .body("""
                        {
                          "properties": {
                            "configuration": {"activeRevisionsMode": "%s"},
                            "template": {
                              "revisionSuffix": "%s",
                              "containers": [{"name": "web", "image": "nginx:alpine"}],
                              "scale": {"minReplicas": 1, "maxReplicas": 4}
                            }
                          }
                        }
                        """.formatted(mode, suffix))
                .patch(appUrl(name)).then().statusCode(200);
    }

    private static String appBody(String environmentId, String mode, String suffix,
                                  int minReplicas, int maxReplicas, int targetPort) {
        return """
                {
                  "location": "eastus",
                  "tags": {"env": "test"},
                  "properties": {
                    "environmentId": "%s",
                    "configuration": {
                      "activeRevisionsMode": "%s",
                      "secrets": [{"name": "api-key", "value": "secret-value"}],
                      "ingress": {"external": true, "targetPort": %d}
                    },
                    "template": {
                      "revisionSuffix": "%s",
                      "containers": [{
                        "name": "web",
                        "image": "nginx:alpine",
                        "env": [{"name": "API_KEY", "secretRef": "api-key"}]
                      }],
                      "scale": {"minReplicas": %d, "maxReplicas": %d,
                                "rules": [{"name": "http", "http": {"metadata": {"concurrentRequests": "10"}}}]}
                    }
                  }
                }
                """.formatted(environmentId, mode, targetPort, suffix, minReplicas, maxReplicas);
    }

    private static String appUrl(String name) {
        return appUrl(name, "");
    }

    private static String appUrl(String name, String child) {
        return PROVIDER + "/containerApps/" + name + child + "?api-version=2025-07-01";
    }

    private static String environmentDomain() {
        return given().get(ENVIRONMENT_URL).then().statusCode(200)
                .extract().path("properties.defaultDomain");
    }
}
