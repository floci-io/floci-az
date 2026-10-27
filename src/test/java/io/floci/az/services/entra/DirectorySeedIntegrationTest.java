package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.storage.StorageFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(DirectorySeedIntegrationTest.Seeded.class)
class DirectorySeedIntegrationTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String CLIENT = "aaaaaaaa-1111-1111-1111-111111111111";
    private static final String USER = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String GROUP = "cccccccc-0000-0000-0000-000000000001";
    private static final String SECRET = "synthetic-seed-test-secret";

    public static class Seeded implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.entra.seed-file", seedFile().toString(),
                    "floci-az.services.entra.validate-client-credentials", "true");
        }
    }

    @Inject
    DirectoryReader directory;

    @Inject
    GraphDirectoryStore store;

    @Inject
    StorageFactory storage;

    static Path seedFile() {
        return Path.of("src/test/resources/entra/directory-seed.json").toAbsolutePath();
    }

    @Test
    void configuredSeedIsAvailableAtStartupAndKeepsClientSecretsHashed() {
        assertEquals("Seed User", directory.user(TENANT, USER).orElseThrow().displayName());
        assertEquals(Set.of(GROUP), directory.memberGroupIds(TENANT, USER));
        assertTrue(directory.applicationByAppId(TENANT, CLIENT).orElseThrow()
                .passwordCredentials().getFirst().matches(SECRET));
        String keyId = given().get("/v1.0/applications/aaaaaaaa-0000-0000-0000-000000000001")
                .then().statusCode(200).extract().path("passwordCredentials[0].keyId");
        assertEquals(keyId, UUID.fromString(keyId).toString());
        storage.create("entra").scan(key -> true).forEach(entry ->
                assertFalse(new String(entry.data(), StandardCharsets.UTF_8).contains(SECRET)));
        given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials").formParam("client_id", CLIENT)
                .formParam("client_secret", SECRET).formParam("scope", "https://graph.microsoft.com/.default")
                .post("/" + TENANT + "/oauth2/v2.0/token").then().statusCode(200);
        given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials").formParam("client_id", CLIENT)
                .formParam("client_secret", "wrong").post("/" + TENANT + "/oauth2/v2.0/token")
                .then().statusCode(401);
    }

    @Test
    void loginHintSelectsTheSeededUserAndGraphSeesItsRole() throws Exception {
        String redirect = "https://seed.example/callback";
        String location = given().redirects().follow(false).queryParam("client_id", CLIENT)
                .queryParam("redirect_uri", redirect).queryParam("scope", "openid")
                .queryParam("login_hint", "seed-user@example.test")
                .get("/" + TENANT + "/oauth2/v2.0/authorize").then().statusCode(302).extract().header("Location");
        String code = URLDecoder.decode(URI.create(location).getRawQuery().split("code=")[1].split("&")[0],
                StandardCharsets.UTF_8);
        String token = given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "authorization_code").formParam("client_id", CLIENT)
                .formParam("client_secret", SECRET).formParam("code", code).formParam("redirect_uri", redirect)
                .post("/" + TENANT + "/oauth2/v2.0/token").then().statusCode(200).extract().path("id_token");
        assertEquals(USER, new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
                .path("sub").asText());
        given().contentType("application/json").body("{}")
                .post("/v1.0/users/" + USER + "/getMemberGroups").then().statusCode(200).body("value", contains(GROUP));
    }

    @Test
    void repeatedSeedDoesNotOverwriteExistingObjectsOrDuplicateMemberships() {
        DirectoryObject original = store.get(TENANT, DirectoryEntitySet.USERS, USER).orElseThrow();
        store.put(GraphDirectoryStore.user(TENANT, USER, "seed-user@example.test", "Changed later", original.text("mail")));
        try {
            DirectorySeed.load(seedFile(), TENANT, store);
            DirectorySeed.load(seedFile(), TENANT, store);
            assertEquals("Changed later", directory.user(TENANT, USER).orElseThrow().displayName());
            assertEquals(Set.of(USER), store.links(TENANT, GROUP, GraphDirectoryStore.MEMBERS));
        } finally {
            store.put(original);
        }
    }
}
