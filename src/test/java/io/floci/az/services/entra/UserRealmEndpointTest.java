package io.floci.az.services.entra;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Home realm discovery as MSAL4J sends it before a username-password grant:
 * {@code GET https://{host}/common/userrealm/{username}?api-version=1.0} with
 * {@code Accept: application/json, text/javascript, *}{@code /*}.
 */
@QuarkusTest
class UserRealmEndpointTest {

    @Test
    void userRealmAnswersAManagedAccountSoMsalProceedsWithThePasswordGrant() {
        given()
            .accept("application/json, text/javascript, */*")
            .queryParam("api-version", "1.0")
          .when().get("/common/userrealm/{username}", "dev@floci.example")
          .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("ver", is("1.0"))
            .body("account_type", is("Managed"))
            .body("domain_name", is("floci.example"))
            .body("cloud_instance_name", is("microsoftonline.com"))
            .body("cloud_audience_urn", is("urn:federation:MicrosoftOnline"))
            .body("$", not(hasKey("federation_protocol")))
            .body("$", not(hasKey("federation_metadata_url")));
    }

    @Test
    void userRealmAcceptsAPercentEncodedUsername() {
        given()
            .urlEncodingEnabled(false)
          .when().get("/common/userrealm/alice%40contoso.com?api-version=1.0")
          .then()
            .statusCode(200)
            .body("account_type", is("Managed"))
            .body("domain_name", is("contoso.com"));
    }

    @Test
    void userRealmIsNotServedUnderASpecificTenant() {
        given()
          .when().get("/00000000-0000-0000-0000-000000000002/userrealm/{username}", "dev@floci.example")
          .then()
            .statusCode(not(200));
    }
}
