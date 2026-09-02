package io.floci.az.services.containerapps;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Quarkus-level tests for {@link ContainerAppsHandler}, exercising the {@code Microsoft.App}
 * ARM surface (managedEnvironments, containerApps, jobs) in mocked mode (no Docker/runtime).
 *
 * <p>Beyond CRUD, this pins the azurerm-provider fidelity rules from the schema research: the
 * environment's {@code appLogsConfiguration.destination} must read back {@code "log-analytics"}
 * even though the client never sends it explicitly; secrets are accepted but never echoed on GET,
 * only via {@code listSecrets}, byte-identical; {@code ingress.traffic[]} is echoed verbatim
 * (never gains a fabricated {@code revisionName}); and a job's {@code configuration.triggerType}
 * must land on {@code "Manual"} or {@code manual_trigger_config} drops out of state.</p>
 */
@QuarkusTest
@TestProfile(ContainerAppsHandlerTest.MockedProfile.class)
@DisplayName("ContainerAppsHandler — managedEnvironments/containerApps/jobs lifecycle and azurerm fidelity (mocked mode)")
@SuppressWarnings("unused")
class ContainerAppsHandlerTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.containerapps.mocked", "true");
        }
    }

    private static final String SUB  = "test-sub-ca";
    private static final String RG   = "test-rg-ca";
    private static final String API  = "?api-version=2025-07-01";
    private static final String BASE =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.App";
    private static final String SUB_BASE =
            "/subscriptions/" + SUB + "/providers/Microsoft.App";

    private static final String ENV_ID =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG + "/providers/Microsoft.App/managedEnvironments/env1";
    private static final String IDENTITY_ID =
            "/subscriptions/" + SUB + "/resourceGroups/" + RG
                    + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/deployer";

    // ── Fixture bodies (shaped after the pinned azurerm 4.79.0 ground truth) ──────

    private static final String ENV_CREATE_BODY = """
            {
              "location": "eastus",
              "tags": {"env": "test"},
              "identity": {"type": "None", "userAssignedIdentities": null},
              "properties": {
                "appLogsConfiguration": {
                  "logAnalyticsConfiguration": {
                    "customerId": "11111111-2222-3333-4444-555555555555",
                    "sharedKey": "topsecretsharedkey"
                  }
                },
                "vnetConfiguration": {
                  "infrastructureSubnetId": "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.Network/virtualNetworks/vnet1/subnets/snet1",
                  "internal": false
                },
                "zoneRedundant": false
              }
            }
            """.formatted(SUB, RG);

    private static final String APP_CREATE_BODY = """
            {
              "location": "eastus",
              "tags": {"env": "test"},
              "identity": {
                "type": "UserAssigned",
                "userAssignedIdentities": {"%s": {}}
              },
              "properties": {
                "managedEnvironmentId": "%s",
                "workloadProfileName": "",
                "template": {
                  "containers": [
                    {
                      "name": "web",
                      "image": "myacr.azurecr.io/dashboard:latest",
                      "command": [],
                      "args": [],
                      "env": [
                        {"name": "PLAIN", "value": "visible"},
                        {"name": "DB_URL", "secretRef": "database-url"}
                      ],
                      "resources": {"cpu": 0.5, "memory": "1Gi"}
                    }
                  ],
                  "scale": {
                    "minReplicas": 1,
                    "maxReplicas": 3,
                    "rules": [
                      {
                        "name": "redis-queue-depth",
                        "custom": {
                          "type": "redis",
                          "identity": "",
                          "metadata": {
                            "address": "redis:6379",
                            "listName": "zoom_to_gong_imports",
                            "listLength": "5",
                            "enableTLS": "true",
                            "databaseIndex": "0"
                          },
                          "auth": [{"secretRef": "redis-password", "triggerParameter": "password"}]
                        }
                      }
                    ]
                  },
                  "revisionSuffix": ""
                },
                "configuration": {
                  "activeRevisionsMode": "single",
                  "secrets": [
                    {"name": "database-url", "identity": "%s", "keyVaultUrl": "https://kv1.vault.azure.net/secrets/db-url"},
                    {"name": "redis-password", "value": "hunter2"}
                  ],
                  "registries": [{"server": "myacr.azurecr.io", "identity": "%s"}],
                  "ingress": {
                    "external": true,
                    "targetPort": 8080,
                    "transport": "auto",
                    "traffic": [{"latestRevision": true, "weight": 100}]
                  }
                }
              }
            }
            """.formatted(IDENTITY_ID, ENV_ID, IDENTITY_ID, IDENTITY_ID);

    private static final String JOB_CREATE_BODY = """
            {
              "location": "eastus",
              "tags": {"env": "test"},
              "identity": {
                "type": "UserAssigned",
                "userAssignedIdentities": {"%s": {}}
              },
              "properties": {
                "environmentId": "%s",
                "workloadProfileName": "",
                "configuration": {
                  "replicaTimeout": 600,
                  "replicaRetryLimit": 1,
                  "triggerType": "manual",
                  "manualTriggerConfig": {"parallelism": 1, "replicaCompletionCount": 1},
                  "secrets": [
                    {"name": "database-url", "identity": "%s", "keyVaultUrl": "https://kv1.vault.azure.net/secrets/db-url"}
                  ],
                  "registries": [{"server": "myacr.azurecr.io", "identity": "%s"}]
                },
                "template": {
                  "containers": [
                    {
                      "name": "migrate",
                      "image": "myacr.azurecr.io/migrator:latest",
                      "command": ["python"],
                      "args": ["-m", "src.cli", "migrate"]
                    }
                  ]
                }
              }
            }
            """.formatted(IDENTITY_ID, ENV_ID, IDENTITY_ID, IDENTITY_ID);

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private void createEnv(String name) {
        given().contentType("application/json").body(ENV_CREATE_BODY)
                .when().put(BASE + "/managedEnvironments/" + name + API)
                .then().statusCode(201);
    }

    private void createApp(String name) {
        given().contentType("application/json").body(APP_CREATE_BODY)
                .when().put(BASE + "/containerApps/" + name + API)
                .then().statusCode(201);
    }

    private void createJob(String name) {
        given().contentType("application/json").body(JOB_CREATE_BODY)
                .when().put(BASE + "/jobs/" + name + API)
                .then().statusCode(201);
    }

    // ── managedEnvironments: CRUD lifecycle ────────────────────────────────────

    @Test
    @DisplayName("GET unknown environment returns 404 ResourceNotFound")
    void envGetUnknownReturns404() {
        given().when().get(BASE + "/managedEnvironments/no-such-env" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    @DisplayName("PUT creates environment (201) with Succeeded state and echoed identity/tags")
    void envCreateReturns201() {
        given().contentType("application/json").body(ENV_CREATE_BODY)
                .when().put(BASE + "/managedEnvironments/env1" + API)
                .then().statusCode(201)
                .body("name", equalTo("env1"))
                .body("type", equalTo("Microsoft.App/managedEnvironments"))
                .body("id", equalTo(BASE + "/managedEnvironments/env1"))
                .body("tags.env", equalTo("test"))
                .body("identity.type", equalTo("None"))
                .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("Environment appLogsConfiguration.destination reads back log-analytics even though never sent")
    void envDestinationForcedToLogAnalytics() {
        createEnv("env-logs");
        given().when().get(BASE + "/managedEnvironments/env-logs" + API)
                .then().statusCode(200)
                .body("properties.appLogsConfiguration.destination", equalTo("log-analytics"))
                .body("properties.appLogsConfiguration.logAnalyticsConfiguration.customerId",
                        equalTo("11111111-2222-3333-4444-555555555555"));
    }

    @Test
    @DisplayName("Environment shared key is never echoed back")
    void envSharedKeyNeverEchoed() {
        createEnv("env-secret");
        given().when().get(BASE + "/managedEnvironments/env-secret" + API)
                .then().statusCode(200)
                .body("properties.appLogsConfiguration.logAnalyticsConfiguration",
                        not(hasKey("sharedKey")));
    }

    @Test
    @DisplayName("Environment gains stable computed fields: defaultDomain, staticIp, vnet CIDRs")
    void envComputedFieldsStableAcrossGets() {
        createEnv("env-computed");
        String firstDomain = given().when().get(BASE + "/managedEnvironments/env-computed" + API)
                .then().statusCode(200)
                .body("properties.defaultDomain", containsString(".eastus.azurecontainerapps.io"))
                .body("properties.staticIp", not(emptyOrNullString()))
                .body("properties.vnetConfiguration.dockerBridgeCidr", containsString("/16"))
                .body("properties.publicNetworkAccess", equalTo("Enabled"))
                .extract().path("properties.defaultDomain");
        String secondDomain = given().when().get(BASE + "/managedEnvironments/env-computed" + API)
                .then().statusCode(200).extract().path("properties.defaultDomain");
        org.junit.jupiter.api.Assertions.assertEquals(firstDomain, secondDomain,
                "defaultDomain must be stable across repeated GETs, or every plan shows drift");
    }

    @Test
    @DisplayName("PUT existing environment returns 200 (update)")
    void envUpdateReturns200() {
        createEnv("env-upd");
        given().contentType("application/json").body(ENV_CREATE_BODY)
                .when().put(BASE + "/managedEnvironments/env-upd" + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("PATCH updates the environment and returns 200")
    void envPatchUpdates() {
        createEnv("env-patch");
        given().contentType("application/json").body("{\"tags\": {\"env\": \"patched\"}}")
                .when().patch(BASE + "/managedEnvironments/env-patch" + API)
                .then().statusCode(200)
                .body("tags.env", equalTo("patched"))
                .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("PATCH on an unknown environment returns 404")
    void envPatchUnknownReturns404() {
        given().contentType("application/json").body("{\"tags\": {}}")
                .when().patch(BASE + "/managedEnvironments/nope" + API)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("DELETE environment is synchronous 204, idempotent when absent")
    void envDeleteThenGet404() {
        createEnv("env-del");
        given().when().delete(BASE + "/managedEnvironments/env-del" + API)
                .then().statusCode(204);
        given().when().get(BASE + "/managedEnvironments/env-del" + API)
                .then().statusCode(404);
        given().when().delete(BASE + "/managedEnvironments/env-del" + API)
                .then().statusCode(204);
    }

    @Test
    @DisplayName("List environments by resource group and by subscription both find it")
    void envListBothScopes() {
        createEnv("env-list");
        given().when().get(BASE + "/managedEnvironments" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("env-list"));
        given().when().get(SUB_BASE + "/managedEnvironments" + API)
                .then().statusCode(200)
                .body("value", hasSize(1));
    }

    // ── containerApps: CRUD lifecycle ──────────────────────────────────────────

    @Test
    @DisplayName("GET unknown container app returns 404 ResourceNotFound")
    void appGetUnknownReturns404() {
        given().when().get(BASE + "/containerApps/no-such-app" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    @DisplayName("PUT creates container app (201) with Succeeded state and identity")
    void appCreateReturns201() {
        given().contentType("application/json").body(APP_CREATE_BODY)
                .when().put(BASE + "/containerApps/app1" + API)
                .then().statusCode(201)
                .body("name", equalTo("app1"))
                .body("type", equalTo("Microsoft.App/containerApps"))
                .body("identity.type", equalTo("UserAssigned"))
                .body("identity.userAssignedIdentities['" + IDENTITY_ID + "'].clientId", not(emptyOrNullString()))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.managedEnvironmentId", equalTo(ENV_ID))
                .body("properties.environmentId", equalTo(ENV_ID))
                .body("properties.template.containers[0].image", equalTo("myacr.azurecr.io/dashboard:latest"));
    }

    @Test
    @DisplayName("PUT without containers is a 400 InvalidRequestContent")
    void appMissingContainersIs400() {
        String body = "{\"location\": \"eastus\", \"properties\": {\"managedEnvironmentId\": \"" + ENV_ID
                + "\", \"template\": {\"containers\": []}, \"configuration\": {}}}";
        given().contentType("application/json").body(body)
                .when().put(BASE + "/containerApps/app-bad" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestContent"));
    }

    @Test
    @DisplayName("PUT existing container app returns 200 (update)")
    void appUpdateReturns200() {
        createApp("app-upd");
        given().contentType("application/json").body(APP_CREATE_BODY)
                .when().put(BASE + "/containerApps/app-upd" + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("DELETE container app is synchronous 204, idempotent when absent")
    void appDeleteThenGet404() {
        createApp("app-del");
        given().when().delete(BASE + "/containerApps/app-del" + API)
                .then().statusCode(204);
        given().when().get(BASE + "/containerApps/app-del" + API)
                .then().statusCode(404);
        given().when().delete(BASE + "/containerApps/app-del" + API)
                .then().statusCode(204);
    }

    @Test
    @DisplayName("List container apps by resource group and by subscription both find it")
    void appListBothScopes() {
        createApp("app-list");
        given().when().get(BASE + "/containerApps" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("app-list"));
        given().when().get(SUB_BASE + "/containerApps" + API)
                .then().statusCode(200)
                .body("value", hasSize(1));
    }

    // ── containerApps: azurerm fidelity / round-trip rules ─────────────────────

    @Test
    @DisplayName("Round trip: scale-rule metadata/auth, ingress.traffic, and enum casing survive create->get with no diff")
    void appRoundTripPreservesConfigWithNoDiff() {
        createApp("app-roundtrip");
        given().when().get(BASE + "/containerApps/app-roundtrip" + API)
                .then().statusCode(200)
                // activeRevisionsMode is canonicalized from the client's lowercase "single".
                .body("properties.configuration.activeRevisionsMode", equalTo("Single"))
                // custom scale rule: every field echoed byte-identical, including identity:"" and
                // the metadata map's exact keys/casing (address/listName/listLength/enableTLS/databaseIndex).
                .body("properties.template.scale.rules[0].name", equalTo("redis-queue-depth"))
                .body("properties.template.scale.rules[0].custom.type", equalTo("redis"))
                .body("properties.template.scale.rules[0].custom.identity", equalTo(""))
                .body("properties.template.scale.rules[0].custom.metadata.address", equalTo("redis:6379"))
                .body("properties.template.scale.rules[0].custom.metadata.listName", equalTo("zoom_to_gong_imports"))
                .body("properties.template.scale.rules[0].custom.metadata.enableTLS", equalTo("true"))
                .body("properties.template.scale.rules[0].custom.auth[0].secretRef", equalTo("redis-password"))
                .body("properties.template.scale.rules[0].custom.auth[0].triggerParameter", equalTo("password"))
                // ingress.traffic[] echoed exactly as sent — latestRevision:true, weight:100, and
                // critically no fabricated "revisionName" key (§8 rule 1).
                .body("properties.configuration.ingress.traffic[0].latestRevision", equalTo(true))
                .body("properties.configuration.ingress.traffic[0].weight", equalTo(100))
                .body("properties.configuration.ingress.traffic[0]", not(hasKey("revisionName")))
                // registries echoed verbatim.
                .body("properties.configuration.registries[0].server", equalTo("myacr.azurecr.io"))
                .body("properties.configuration.registries[0].identity", equalTo(IDENTITY_ID));
    }

    @Test
    @DisplayName("Secrets are never echoed on GET; configuration.secrets is absent")
    void appSecretsNeverOnGet() {
        createApp("app-secret-get");
        given().when().get(BASE + "/containerApps/app-secret-get" + API)
                .then().statusCode(200)
                .body("properties.configuration", not(hasKey("secrets")));
    }

    @Test
    @DisplayName("listSecrets returns KV-backed secrets with keyVaultUrl/identity byte-identical, and raw values for plain secrets")
    void appListSecretsReturnsExactValues() {
        createApp("app-secrets");
        given().when().post(BASE + "/containerApps/app-secrets/listSecrets" + API)
                .then().statusCode(200)
                .body("value.find { it.name == 'database-url' }.keyVaultUrl",
                        equalTo("https://kv1.vault.azure.net/secrets/db-url"))
                .body("value.find { it.name == 'database-url' }.identity", equalTo(IDENTITY_ID))
                .body("value.find { it.name == 'database-url' }", not(hasKey("value")))
                .body("value.find { it.name == 'redis-password' }.value", equalTo("hunter2"))
                .body("value.find { it.name == 'redis-password' }", not(hasKey("keyVaultUrl")));
    }

    @Test
    @DisplayName("listSecrets returns an empty string, never null, for a secret submitted with no value")
    void appListSecretsNameOnlySecretReturnsEmptyString() {
        String body = APP_CREATE_BODY.replace(
                "{\"name\": \"redis-password\", \"value\": \"hunter2\"}",
                "{\"name\": \"redis-password\"}");
        given().contentType("application/json").body(body)
                .when().put(BASE + "/containerApps/app-nullsecret" + API)
                .then().statusCode(201);
        given().when().post(BASE + "/containerApps/app-nullsecret/listSecrets" + API)
                .then().statusCode(200)
                .body("value.find { it.name == 'redis-password' }.value", equalTo(""));
    }

    @Test
    @DisplayName("listSecrets on an unknown app returns 404")
    void appListSecretsUnknown404() {
        given().when().post(BASE + "/containerApps/nope/listSecrets" + API)
                .then().statusCode(404);
    }

    @Test
    @DisplayName("Ingress fqdn and latestRevisionFqdn/latestRevisionName are stable across repeated GETs")
    void appComputedFieldsStable() {
        createApp("app-computed");
        Map<String, Object> first = given().when().get(BASE + "/containerApps/app-computed" + API)
                .then().statusCode(200)
                .body("properties.configuration.ingress.fqdn", not(emptyOrNullString()))
                .body("properties.latestRevisionName", startsWith("app-computed--"))
                .extract().jsonPath().getMap("properties");
        Map<String, Object> second = given().when().get(BASE + "/containerApps/app-computed" + API)
                .then().statusCode(200).extract().jsonPath().getMap("properties");
        org.junit.jupiter.api.Assertions.assertEquals(first.get("latestRevisionName"), second.get("latestRevisionName"));
        org.junit.jupiter.api.Assertions.assertEquals(
                ((Map<?, ?>) first.get("configuration")).get("ingress") instanceof Map<?, ?> i1 ? i1.get("fqdn") : null,
                ((Map<?, ?>) second.get("configuration")).get("ingress") instanceof Map<?, ?> i2 ? i2.get("fqdn") : null);
    }

    @Test
    @DisplayName("PUT the identical body twice is idempotent: revisionSuffix/latestRevisionName/ingress.fqdn never drift")
    void appRepeatedIdenticalPutIsIdempotent() {
        Map<String, Object> firstProps = given().contentType("application/json").body(APP_CREATE_BODY)
                .when().put(BASE + "/containerApps/app-idempotent" + API)
                .then().statusCode(201)
                .extract().jsonPath().getMap("properties");

        // A second apply of the exact same config (as `terraform plan` after a no-op apply would
        // send) must come back 200 with every server-computed field unchanged.
        Map<String, Object> secondProps = given().contentType("application/json").body(APP_CREATE_BODY)
                .when().put(BASE + "/containerApps/app-idempotent" + API)
                .then().statusCode(200)
                .extract().jsonPath().getMap("properties");

        org.junit.jupiter.api.Assertions.assertEquals(
                firstProps.get("latestRevisionName"), secondProps.get("latestRevisionName"));
        org.junit.jupiter.api.Assertions.assertEquals(
                ((Map<?, ?>) firstProps.get("template")).get("revisionSuffix"),
                ((Map<?, ?>) secondProps.get("template")).get("revisionSuffix"));
        Object firstFqdn = ((Map<?, ?>) firstProps.get("configuration")).get("ingress") instanceof Map<?, ?> i1
                ? i1.get("fqdn") : null;
        Object secondFqdn = ((Map<?, ?>) secondProps.get("configuration")).get("ingress") instanceof Map<?, ?> i2
                ? i2.get("fqdn") : null;
        org.junit.jupiter.api.Assertions.assertEquals(firstFqdn, secondFqdn);

        Map<String, Object> thirdGetProps = given().when().get(BASE + "/containerApps/app-idempotent" + API)
                .then().statusCode(200)
                .extract().jsonPath().getMap("properties");
        org.junit.jupiter.api.Assertions.assertEquals(
                secondProps.get("latestRevisionName"), thirdGetProps.get("latestRevisionName"));
    }

    // ── jobs: CRUD lifecycle ────────────────────────────────────────────────────

    @Test
    @DisplayName("GET unknown job returns 404 ResourceNotFound")
    void jobGetUnknownReturns404() {
        given().when().get(BASE + "/jobs/no-such-job" + API)
                .then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    @DisplayName("PUT creates job (201) with Succeeded state, Manual trigger, and echoed manualTriggerConfig")
    void jobCreateReturns201() {
        given().contentType("application/json").body(JOB_CREATE_BODY)
                .when().put(BASE + "/jobs/job1" + API)
                .then().statusCode(201)
                .body("name", equalTo("job1"))
                .body("type", equalTo("Microsoft.App/jobs"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.environmentId", equalTo(ENV_ID))
                .body("properties.configuration.triggerType", equalTo("Manual"))
                .body("properties.configuration.manualTriggerConfig.parallelism", equalTo(1))
                .body("properties.configuration.manualTriggerConfig.replicaCompletionCount", equalTo(1))
                .body("properties.configuration.replicaTimeout", equalTo(600))
                .body("properties.configuration.replicaRetryLimit", equalTo(1));
    }

    @Test
    @DisplayName("Job configuration.secrets is never echoed on GET")
    void jobSecretsNeverOnGet() {
        createJob("job-secret-get");
        given().when().get(BASE + "/jobs/job-secret-get" + API)
                .then().statusCode(200)
                .body("properties.configuration", not(hasKey("secrets")));
    }

    @Test
    @DisplayName("Job listSecrets echoes keyVaultUrl/identity byte-identical")
    void jobListSecretsReturnsExactValues() {
        createJob("job-secrets");
        given().when().post(BASE + "/jobs/job-secrets/listSecrets" + API)
                .then().statusCode(200)
                .body("value[0].name", equalTo("database-url"))
                .body("value[0].keyVaultUrl", equalTo("https://kv1.vault.azure.net/secrets/db-url"))
                .body("value[0].identity", equalTo(IDENTITY_ID));
    }

    @Test
    @DisplayName("PUT without containers is a 400 InvalidRequestContent")
    void jobMissingContainersIs400() {
        String body = "{\"location\": \"eastus\", \"properties\": {\"environmentId\": \"" + ENV_ID
                + "\", \"configuration\": {\"triggerType\": \"Manual\"}, \"template\": {\"containers\": []}}}";
        given().contentType("application/json").body(body)
                .when().put(BASE + "/jobs/job-bad" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("InvalidRequestContent"));
    }

    @Test
    @DisplayName("PUT existing job returns 200 (update)")
    void jobUpdateReturns200() {
        createJob("job-upd");
        given().contentType("application/json").body(JOB_CREATE_BODY)
                .when().put(BASE + "/jobs/job-upd" + API)
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("DELETE job is synchronous 204, idempotent when absent")
    void jobDeleteThenGet404() {
        createJob("job-del");
        given().when().delete(BASE + "/jobs/job-del" + API)
                .then().statusCode(204);
        given().when().get(BASE + "/jobs/job-del" + API)
                .then().statusCode(404);
        given().when().delete(BASE + "/jobs/job-del" + API)
                .then().statusCode(204);
    }

    @Test
    @DisplayName("List jobs by resource group and by subscription both find it")
    void jobListBothScopes() {
        createJob("job-list");
        given().when().get(BASE + "/jobs" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("job-list"));
        given().when().get(SUB_BASE + "/jobs" + API)
                .then().statusCode(200)
                .body("value", hasSize(1));
    }

    // ── LRO status stub ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("The operations endpoint reports terminal Succeeded")
    void operationsEndpointSucceeded() {
        given().when().get(SUB_BASE + "/locations/eastus/operations/" + java.util.UUID.randomUUID() + API)
                .then().statusCode(200)
                .body("status", equalTo("Succeeded"));
    }

    // ── Resource-group index (terraform destroy ordering) ──────────────────────

    @Test
    @DisplayName("Environments, apps, and jobs all appear in the resource-group /resources index")
    void allThreeAppearInRgResourceIndex() {
        createEnv("idx-env");
        createApp("idx-app");
        createJob("idx-job");
        given().when().get("/subscriptions/" + SUB + "/resourceGroups/" + RG + "/resources" + API)
                .then().statusCode(200)
                .body("value.find { it.name == 'idx-env' }.type", equalTo("Microsoft.App/managedEnvironments"))
                .body("value.find { it.name == 'idx-app' }.type", equalTo("Microsoft.App/containerApps"))
                .body("value.find { it.name == 'idx-job' }.type", equalTo("Microsoft.App/jobs"));
    }

    // ── Admin reset ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /_admin/reset wipes environments, apps, and jobs")
    void adminResetClearsAll() {
        createEnv("reset-env");
        createApp("reset-app");
        createJob("reset-job");
        given().post("/_admin/reset").then().statusCode(204);
        given().when().get(BASE + "/managedEnvironments" + API)
                .then().statusCode(200).body("value", hasSize(0));
        given().when().get(BASE + "/containerApps" + API)
                .then().statusCode(200).body("value", hasSize(0));
        given().when().get(BASE + "/jobs" + API)
                .then().statusCode(200).body("value", hasSize(0));
    }
}
