package io.floci.az.services.arm;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The Providers API in the default configuration: every served namespace is listed and registered, a single
 * namespace answers a {@code Provider} body, and register and unregister answer the final state at once.
 */
@QuarkusTest
@DisplayName("ARM providers: list, get, register and unregister")
class ProviderApiTest {

    private static final String SUB = "7a1d0000-0000-4000-8000-00000000c0b2";
    private static final String API = "?api-version=2024-11-01";
    private static final String PROVIDERS = "/subscriptions/" + SUB + "/providers";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void theListNamesEveryServedNamespaceRegisteredAndNothingElse() {
        given().when().get(PROVIDERS + API).then().statusCode(200)
            .body("value.namespace", hasItems("Microsoft.Storage", "Microsoft.KeyVault", "Microsoft.Network",
                "Microsoft.Resources"))
            .body("value.namespace", not(hasItem("Microsoft.CostManagement")))
            .body("value.registrationState", everyItem(equalTo("Registered")))
            .body("value.find { it.namespace == 'Microsoft.Resources' }.registrationPolicy",
                equalTo("RegistrationFree"))
            .body("value.find { it.namespace == 'Microsoft.Storage' }.registrationPolicy",
                equalTo("RegistrationRequired"));
    }

    @Test
    void aSingleNamespaceIsAProviderBodyInItsCanonicalCasing() {
        given().when().get(PROVIDERS + "/microsoft.storage" + API).then().statusCode(200)
            .body("id", equalTo(PROVIDERS + "/Microsoft.Storage"))
            .body("namespace", equalTo("Microsoft.Storage"))
            .body("registrationState", equalTo("Registered"))
            .body("resourceTypes.find { it.resourceType == 'storageAccounts' }.apiVersions", hasItem("2023-01-01"))
            .body("resourceTypes.find { it.resourceType == 'storageAccounts' }.locations", hasItem("East US"))
            .body("resourceTypes.find { it.resourceType == 'storageAccounts' }.capabilities",
                equalTo("SupportsTags, SupportsLocation"));
    }

    @Test
    void unregisterThenRegisterAnswerTheFinalStateAtOnce() {
        given().when().post(PROVIDERS + "/Microsoft.KeyVault/unregister" + API).then().statusCode(200)
            .body("namespace", equalTo("Microsoft.KeyVault"))
            .body("registrationState", equalTo("Unregistered"));
        given().when().get(PROVIDERS + "/Microsoft.KeyVault" + API).then().statusCode(200)
            .body("registrationState", equalTo("Unregistered"));
        given().when().post(PROVIDERS + "/Microsoft.KeyVault/register" + API).then().statusCode(200)
            .body("registrationState", equalTo("Registered"));
        given().when().post(PROVIDERS + "/Microsoft.KeyVault/register" + API).then().statusCode(200)
            .body("registrationState", equalTo("Registered"));
    }

    @Test
    void registrationReachesTheProvidersApiForNamespacesOtherHandlersServe() {
        for (String namespace : new String[] {"Microsoft.Network", "Microsoft.App", "Microsoft.ManagedIdentity"}) {
            given().when().post(PROVIDERS + "/" + namespace + "/register" + API).then().statusCode(200)
                .body("namespace", equalTo(namespace))
                .body("registrationState", equalTo("Registered"))
                .body("resourceTypes", notNullValue());
        }
    }

    @Test
    void anUnservedNamespaceIsInvalidResourceNamespace() {
        given().when().get(PROVIDERS + "/Microsoft.DoesNotExist" + API).then().statusCode(404)
            .body("error.code", equalTo("InvalidResourceNamespace"))
            .body("error.message", equalTo("The resource namespace 'Microsoft.DoesNotExist' is invalid."));
        given().when().post(PROVIDERS + "/Microsoft.DoesNotExist/register" + API).then().statusCode(404)
            .body("error.code", equalTo("InvalidResourceNamespace"));
    }
}
