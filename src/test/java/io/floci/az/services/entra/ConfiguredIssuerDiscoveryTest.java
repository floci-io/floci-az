package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ConfiguredIssuerDiscoveryTest.ConfiguredIssuer.class)
class ConfiguredIssuerDiscoveryTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String ISSUER = "https://login.example.test/" + TENANT + "/v2.0";

    public static class ConfiguredIssuer implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.entra.issuer", ISSUER);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:4577", "floci-az:4577"})
    void discoveryAndTokensShareTheConfiguredIssuerRegardlessOfHost(String host) throws Exception {
        for (String path : new String[]{"/v2.0/.well-known/openid-configuration",
                "/.well-known/openid-configuration"}) {
            given().header("Host", host)
                    .when().get("/" + TENANT + path)
                    .then().statusCode(200)
                    .body("issuer", is(ISSUER))
                    .body("token_endpoint", is("http://" + host + "/" + TENANT + "/oauth2/v2.0/token"));
        }

        String token = given().header("Host", host)
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET)
                .formParam("scope", "https://graph.microsoft.com/.default")
                .when().post("/" + TENANT + "/oauth2/v2.0/token")
                .then().statusCode(200).extract().path("access_token");
        byte[] claims = Base64.getUrlDecoder().decode(token.split("\\.")[1]);
        assertEquals(ISSUER, new ObjectMapper().readTree(claims).path("iss").asText());
    }
}
