package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TenantResolverTest {

    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String UNKNOWN_TENANT = "7d3b1f0e-5c4a-4e2b-9a8d-112233445566";

    @Inject TenantResolver resolver;

    @Test
    void aliasesResolveToTheDefaultTenant() {
        for (String alias : new String[] {"common", "organizations", "consumers"}) {
            assertEquals(new TenantRef(DEFAULT_TENANT, DEFAULT_TENANT, true), resolver.resolve(alias, null), alias);
        }
    }

    @Test
    void theDefaultTenantIdIsKnown() {
        assertEquals(new TenantRef(DEFAULT_TENANT, DEFAULT_TENANT, true), resolver.resolve(DEFAULT_TENANT, null));
        assertEquals(resolver.defaultTenant(), resolver.resolve(null, null));
    }

    @Test
    void anUnknownTenantIdPassesThroughAndReadsTheDefaultDirectory() {
        assertEquals(new TenantRef(UNKNOWN_TENANT, DEFAULT_TENANT, false), resolver.resolve(UNKNOWN_TENANT, null));
    }

    @Test
    void theHintIsUsedOnlyWhenThereIsNoSegment() {
        assertEquals(UNKNOWN_TENANT, resolver.resolve(null, UNKNOWN_TENANT).tenantId());
        assertEquals(UNKNOWN_TENANT, resolver.resolve("", UNKNOWN_TENANT).tenantId());
        assertEquals(DEFAULT_TENANT, resolver.resolve("common", UNKNOWN_TENANT).tenantId());
    }

    @Test
    void aTokenForAnUnknownTenantKeepsItsTenantAndTheDevServicePrincipal() throws Exception {
        String token = given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "client_credentials")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("client_secret", "anything")
          .formParam("scope", "https://management.azure.com/.default")
          .when().post("/{tenant}/oauth2/v2.0/token", UNKNOWN_TENANT)
          .then().statusCode(200)
          .extract().path("access_token");

        Map<?, ?> claims = new ObjectMapper().readValue(Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
        assertEquals(UNKNOWN_TENANT, claims.get("tid"));
        assertEquals(EntraStore.DEV_SP_OBJECT_ID, claims.get("oid"),
            "an unregistered tenant id still resolves the dev app from the default directory");
    }
}
