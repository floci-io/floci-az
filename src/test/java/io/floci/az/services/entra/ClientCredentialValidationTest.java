package io.floci.az.services.entra;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** The token endpoint with {@code entra.validate-client-credentials=true}. */
@QuarkusTest
@TestProfile(ClientCredentialValidationTest.Enforced.class)
class ClientCredentialValidationTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String UNKNOWN_CLIENT = "00000000-0000-0000-0000-000000000003";

    public static class Enforced implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.entra.validate-client-credentials", "true");
        }
    }

    @Test
    void theDevAppAuthenticatesWithItsSecretInTheForm() {
        clientCredentials(EntraStore.DEV_CLIENT_ID, EntraStore.DEV_CLIENT_SECRET)
          .then().statusCode(200)
          .body("access_token", not(emptyOrNullString()));
    }

    @Test
    void theDevAppAuthenticatesWithHttpBasic() {
        given()
          .auth().preemptive().basic(EntraStore.DEV_CLIENT_ID, EntraStore.DEV_CLIENT_SECRET)
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "client_credentials")
          .formParam("scope", "https://management.azure.com/.default")
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then().statusCode(200)
          .body("access_token", not(emptyOrNullString()));
    }

    @Test
    void aWrongSecretIs401InvalidClient() {
        clientCredentials(EntraStore.DEV_CLIENT_ID, "wrong-secret")
          .then().statusCode(401)
          .body("error", is("invalid_client"))
          .body("error_codes", contains(7000215))
          .body("error_description", startsWith("AADSTS7000215: Invalid client secret provided."))
          .body("error_description", containsString("for a secret added to app '" + EntraStore.DEV_CLIENT_ID + "'"))
          .body("error_uri", is("https://login.microsoftonline.com/error?code=7000215"))
          .body("trace_id", not(emptyOrNullString()))
          .body("correlation_id", not(emptyOrNullString()));
    }

    @Test
    void anUnregisteredClientIsUnauthorizedClient() {
        clientCredentials(UNKNOWN_CLIENT, "fake-secret")
          .then().statusCode(400)
          .body("error", is("unauthorized_client"))
          .body("error_codes", contains(700016))
          .body("error_description", startsWith("AADSTS700016: Application with identifier '" + UNKNOWN_CLIENT
              + "' was not found in the directory '" + TENANT + "'."));
    }

    @Test
    void clientCredentialsWithoutASecretIs7000218() {
        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "client_credentials")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("scope", "https://management.azure.com/.default")
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then().statusCode(401)
          .body("error", is("invalid_client"))
          .body("error_codes", contains(7000218));
    }

    @Test
    void aMissingClientIdIs900144() {
        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "password")
          .formParam("username", EntraStore.DEV_USER_UPN)
          .formParam("password", "whatever")
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then().statusCode(400)
          .body("error", is("invalid_request"))
          .body("error_codes", contains(900144))
          .body("error_description", startsWith(
              "AADSTS900144: The request body must contain the following parameter: 'client_id'."));
    }

    @Test
    void aPublicClientGrantNeedsNoSecret() {
        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "password")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("username", EntraStore.DEV_USER_UPN)
          .formParam("password", "whatever")
          .formParam("scope", "openid api://resource/user_impersonation")
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT)
          .then().statusCode(200);
    }

    @Test
    void anAliasTenantReadsTheDefaultDirectory() {
        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "client_credentials")
          .formParam("client_id", EntraStore.DEV_CLIENT_ID)
          .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET)
          .formParam("scope", "https://management.azure.com/.default")
          .when().post("/organizations/oauth2/v2.0/token")
          .then().statusCode(200);
    }

    private static Response clientCredentials(String clientId, String secret) {
        return given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "client_credentials")
          .formParam("client_id", clientId)
          .formParam("client_secret", secret)
          .formParam("scope", "https://management.azure.com/.default")
          .when().post("/{tenant}/oauth2/v2.0/token", TENANT);
    }
}
