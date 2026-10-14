package io.floci.az.services.entra;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ClientAuthenticator} in the default configuration, where nothing is enforced, plus its
 * enforcing check called directly. The enforced HTTP envelope is pinned by
 * {@link ClientCredentialValidationTest}.
 */
@QuarkusTest
class ClientAuthenticatorTest {

    private static final String UNKNOWN_CLIENT = "00000000-0000-0000-0000-000000000003";

    @Inject ClientAuthenticator authenticator;
    @Inject TenantResolver tenants;

    private TenantRef tenant() {
        return tenants.defaultTenant();
    }

    @Test
    void validationIsOffByDefault() {
        assertFalse(authenticator.enforced());
    }

    @Test
    void unenforcedEveryClientIsAccepted() {
        assertTrue(authenticator.authenticate(new ClientCredentials(UNKNOWN_CLIENT, "fake-secret", false),
            tenant(), true).isEmpty());
        assertTrue(authenticator.authenticate(new ClientCredentials(EntraStore.DEV_CLIENT_ID, "wrong", false),
            tenant(), true).isEmpty());
        assertTrue(authenticator.authenticate(new ClientCredentials(null, null, false), tenant(), true).isEmpty());
        assertTrue(authenticator.authenticate(new ClientCredentials(EntraStore.DEV_CLIENT_ID, null, false),
            tenant(), true).isEmpty());
    }

    @Test
    void unenforcedAnUnregisteredClientStillGetsAToken() {
        // The terraform and opentofu suites log in exactly like this.
        given()
          .contentType("application/x-www-form-urlencoded")
          .formParam("grant_type", "client_credentials")
          .formParam("client_id", UNKNOWN_CLIENT)
          .formParam("client_secret", "fake-secret")
          .formParam("scope", "https://management.azure.com/.default")
          .when().post("/{tenant}/oauth2/v2.0/token", tenant().tenantId())
          .then().statusCode(200)
          .body("access_token", not(emptyOrNullString()));
    }

    @Test
    void theDevSecretValidates() {
        assertEquals(Optional.empty(), authenticator.check(
            new ClientCredentials(EntraStore.DEV_CLIENT_ID, EntraStore.DEV_CLIENT_SECRET, false), tenant(), true));
    }

    @Test
    void aWrongSecretIsAadsts7000215() {
        ClientAuthenticator.Rejection rejection = authenticator.check(
            new ClientCredentials(EntraStore.DEV_CLIENT_ID, "wrong", false), tenant(), true).orElseThrow();
        assertEquals(AadstsError.INVALID_CLIENT_SECRET, rejection.error());
        assertEquals(List.of(EntraStore.DEV_CLIENT_ID), rejection.args());
    }

    @Test
    void anUnknownClientIsAadsts700016() {
        ClientAuthenticator.Rejection rejection = authenticator.check(
            new ClientCredentials(UNKNOWN_CLIENT, "fake-secret", false), tenant(), true).orElseThrow();
        assertEquals(AadstsError.APPLICATION_NOT_FOUND, rejection.error());
        assertEquals(List.of(UNKNOWN_CLIENT, tenant().tenantId()), rejection.args());
    }

    @Test
    void aMissingClientIdIsAadsts900144() {
        assertEquals(AadstsError.MISSING_REQUIRED_PARAMETER,
            authenticator.check(new ClientCredentials(null, "x", false), tenant(), false).orElseThrow().error());
    }

    @Test
    void aConfidentialGrantWithoutACredentialIsAadsts7000218() {
        assertEquals(AadstsError.MISSING_CLIENT_CREDENTIAL, authenticator.check(
            new ClientCredentials(EntraStore.DEV_CLIENT_ID, null, false), tenant(), true).orElseThrow().error());
    }

    @Test
    void aPublicGrantNeedsOnlyAKnownClientId() {
        assertTrue(authenticator.check(new ClientCredentials(EntraStore.DEV_CLIENT_ID, null, false), tenant(), false)
            .isEmpty());
    }

    @Test
    void anAssertionIsAcceptedUnverified() {
        assertTrue(authenticator.check(new ClientCredentials(EntraStore.DEV_CLIENT_ID, null, true), tenant(), true)
            .isEmpty());
    }

    @Test
    void credentialsAreReadFromTheFormThenFromHttpBasic() {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
            ("my%3Aclient:s3cr%26t").getBytes(StandardCharsets.UTF_8));

        ClientCredentials fromBasic = ClientCredentials.from(Map.of(), basic);
        assertEquals(new ClientCredentials("my:client", "s3cr&t", false), fromBasic);

        ClientCredentials fromForm = ClientCredentials.from(
            Map.of("client_id", "form-id", "client_secret", "form-secret", "client_assertion", "jwt"), basic);
        assertEquals(new ClientCredentials("form-id", "form-secret", true), fromForm);

        assertEquals(new ClientCredentials(null, null, false), ClientCredentials.from(Map.of(), "Basic !!!"));
    }
}
