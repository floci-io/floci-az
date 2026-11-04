package io.floci.az.services.entra;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class PasswordGrantPermissiveTest {
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String TOKEN = "/" + TENANT + "/oauth2/v2.0/token";

    @Inject GraphDirectoryStore directory;

    @Test
    void credentialsRemainOptionalAndUnknownUsersRemainSyntheticByDefault() {
        given().contentType("application/x-www-form-urlencoded").formParam("grant_type", "password")
                .post(TOKEN).then().statusCode(200).body("access_token", not(emptyOrNullString()))
                .body("refresh_token", nullValue());
        given().contentType("application/x-www-form-urlencoded").formParam("grant_type", "password")
                .formParam("client_id", "unregistered-client").formParam("username", "synthetic@local.invalid")
                .formParam("password", "arbitrary-password").formParam("scope", "openid offline_access")
                .post(TOKEN).then().statusCode(200).body("id_token", not(emptyOrNullString()))
                .body("refresh_token", nullValue());
    }

    @Test
    void accountStatusIsIgnoredForPasswordAndRefreshGrantsByDefault() {
        String id = UUID.randomUUID().toString();
        DirectoryObject user = GraphDirectoryStore.user(TENANT, id, id + "@local.invalid", "Permissive user", null);
        user.props().put("accountEnabled", false);
        user.props().putObject("passwordProfile").put("forceChangePasswordNextSignIn", true);
        directory.put(user);
        try {
            given().contentType("application/x-www-form-urlencoded").formParam("grant_type", "password")
                    .formParam("username", user.text("userPrincipalName")).formParam("scope", "offline_access")
                    .post(TOKEN).then().statusCode(200).body("refresh_token", nullValue());
            String token = given().contentType("application/x-www-form-urlencoded")
                    .formParam("grant_type", "password").formParam("client_id", EntraStore.DEV_CLIENT_ID)
                    .formParam("username", user.text("userPrincipalName")).formParam("scope", "offline_access")
                    .post(TOKEN).then().statusCode(200).body("refresh_token", not(emptyOrNullString()))
                    .extract().path("refresh_token");
            given().contentType("application/x-www-form-urlencoded").formParam("grant_type", "refresh_token")
                    .formParam("client_id", EntraStore.DEV_CLIENT_ID).formParam("refresh_token", token)
                    .post(TOKEN).then().statusCode(200);
        } finally {
            directory.delete(TENANT, DirectoryEntitySet.USERS, id);
        }
    }
}
