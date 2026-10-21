package io.floci.az.services.aks;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(AksStrictNamesTest.StrictNames.class)
@DisplayName("AKS: parity-overrides.strict-names refuses cluster and agent pool names outside Azure's rules")
class AksStrictNamesTest {

    public static class StrictNames implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-names", "true", "floci-az.services.aks.mocked", "true");
        }
    }

    private static final String BASE =
        "/subscriptions/sub-names/resourceGroups/rg/providers/Microsoft.ContainerService/managedClusters/";
    private static final String API = "?api-version=2024-02-01";
    private static final String BODY = "{\"location\":\"eastus\",\"properties\":{\"dnsPrefix\":\"dns\"}}";
    private static final String POOL = "{\"properties\":{\"count\":1,\"vmSize\":\"Standard_DS2_v2\"}}";

    @Test
    void aClusterNameOutsideTheRuleIsRefusedAndAValidOneIsCreated() {
        given().contentType("application/json").body(BODY).when().put(BASE + "-bad" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        given().contentType("application/json").body(BODY).when().put(BASE + "good_cluster-1" + API)
            .then().statusCode(201);
    }

    @Test
    void anAgentPoolNameOutsideTheRuleIsRefused() {
        given().contentType("application/json").body(BODY).when().put(BASE + "poolhost" + API).then().statusCode(201);
        given().contentType("application/json").body(POOL).when().put(BASE + "poolhost/agentPools/1pool" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        given().contentType("application/json").body(POOL).when().put(BASE + "poolhost/agentPools/Pool" + API)
            .then().statusCode(400).body("error.code", equalTo("InvalidResourceName"));
        given().contentType("application/json").body(POOL).when().put(BASE + "poolhost/agentPools/pool2" + API)
            .then().statusCode(200);
    }
}
