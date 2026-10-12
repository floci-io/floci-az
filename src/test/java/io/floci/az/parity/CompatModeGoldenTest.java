package io.floci.az.parity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.floci.az.services.entra.EntraStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins what the default configuration ({@code parity: compat}) answers to a fixed script of representative
 * ARM, Entra, cloud-metadata and data-plane calls. The golden files under {@code compat-golden/} were captured
 * from {@code main}, so any response a client could rely on that changes in the default configuration fails
 * here. A Fix-class change updates the golden file in the same commit and names the fix there; anything
 * else belongs behind {@code floci-az.parity}.
 *
 * <p>Each step compares the status, the headers clients act on ({@link #PINNED_HEADERS}) and the body, after
 * {@link Normaliser} has replaced the values that legitimately change between runs: timestamps, GUIDs the
 * emulator generates, etags and the test port. A JWT is compared by its header and claim shape, never by its
 * signature, key id or time values.
 *
 * <p>Run with {@code -Dcompat.golden.record=true} to write the files instead of asserting (into
 * {@code compat.golden.dir}, by default {@code src/test/resources/compat-golden}). Record only from
 * {@code main}: recording on a branch would pin the branch's behaviour instead of guarding against it.
 */
@QuarkusTest
@DisplayName("Default configuration answers exactly as main does")
class CompatModeGoldenTest {

    private static final String RECORD_PROPERTY = "compat.golden.record";
    private static final String DIR_PROPERTY = "compat.golden.dir";
    private static final String RESOURCE_DIR = "compat-golden/";

    /** The headers a client acts on; the rest (request ids, dates, server) are transport noise. */
    private static final List<String> PINNED_HEADERS = List.of("Location", "Retry-After", "WWW-Authenticate",
        "Content-Type", "Azure-AsyncOperation");

    private static final String DEFAULT_SUB = "00000000-0000-0000-0000-000000000001";
    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000002";
    /** Never configured, so every ARM call under it exercises first use. */
    private static final String FIRST_USE_SUB = "c0a7c0a7-0000-4000-8000-000000000001";
    private static final String RG = "golden-rg";
    private static final String STORAGE_ACCOUNT = "goldensa0001";
    private static final String VAULT = "golden-kv-0001";
    private static final String ARM_API = "?api-version=2022-12-01";
    private static final String RG_API = "?api-version=2021-04-01";
    private static final String STORAGE_API = "?api-version=2023-01-01";
    private static final String KV_API = "?api-version=2023-07-01";

    /**
     * Ids that are the same on every run: the ones the script sends and the fixed ids of the seeded Entra
     * objects, so a token that names the wrong identity fails. Every other GUID in a response was generated
     * by the emulator.
     */
    private static final Set<String> STABLE_GUIDS = Set.of(DEFAULT_SUB, DEFAULT_TENANT, FIRST_USE_SUB,
        EntraStore.DEV_CLIENT_ID, EntraStore.DEV_APP_OBJECT_ID, EntraStore.DEV_SP_OBJECT_ID,
        EntraStore.DEV_USER_OBJECT_ID, EntraStore.DEV_GROUP_OBJECT_ID);

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    // ── The script ──────────────────────────────────────────────────────────

    @Test
    void armManagementPlane() {
        String sub = "/subscriptions/" + FIRST_USE_SUB;
        String rg = sub + "/resourceGroups/" + RG;
        String account = rg + "/providers/Microsoft.Storage/storageAccounts/" + STORAGE_ACCOUNT;
        Script script = new Script("arm");
        script.step("tenants list", given(), "GET", "/tenants" + ARM_API);
        script.step("subscriptions list", given(), "GET", "/subscriptions" + ARM_API);
        script.step("subscription get, default", given(), "GET", "/subscriptions/" + DEFAULT_SUB + ARM_API);
        script.step("subscription get, first use", given(), "GET", sub + ARM_API);
        script.step("subscriptions list after first use", given(), "GET", "/subscriptions" + ARM_API);
        script.step("locations list", given(), "GET", sub + "/locations" + ARM_API);
        script.step("providers list", given(), "GET", sub + "/providers" + ARM_API);
        script.step("provider get, Microsoft.Storage", given(), "GET",
            sub + "/providers/Microsoft.Storage" + ARM_API);
        script.step("resource group put", json("{\"location\":\"eastus\",\"tags\":{\"env\":\"golden\"}}"),
            "PUT", rg + RG_API);
        script.step("resource group get", given(), "GET", rg + RG_API);
        script.step("resource groups list", given(), "GET", sub + "/resourceGroups" + RG_API);
        script.step("storage name check, free", json("{\"name\":\"" + STORAGE_ACCOUNT
            + "\",\"type\":\"Microsoft.Storage/storageAccounts\"}"), "POST",
            sub + "/providers/Microsoft.Storage/checkNameAvailability" + STORAGE_API);
        script.step("storage account put", json("{\"location\":\"eastus\",\"kind\":\"StorageV2\","
            + "\"sku\":{\"name\":\"Standard_LRS\"}}"), "PUT", account + STORAGE_API);
        script.step("storage account get", given(), "GET", account + STORAGE_API);
        script.step("storage account listKeys", given(), "POST", account + "/listKeys" + STORAGE_API);
        script.step("storage name check, taken", json("{\"name\":\"" + STORAGE_ACCOUNT
            + "\",\"type\":\"Microsoft.Storage/storageAccounts\"}"), "POST",
            sub + "/providers/Microsoft.Storage/checkNameAvailability" + STORAGE_API);
        script.step("key vault put", json("{\"location\":\"eastus\",\"properties\":{\"tenantId\":\""
            + DEFAULT_TENANT + "\",\"sku\":{\"family\":\"A\",\"name\":\"standard\"},\"accessPolicies\":[]}}"),
            "PUT", rg + "/providers/Microsoft.KeyVault/vaults/" + VAULT + KV_API);
        script.step("storage account delete", given(), "DELETE", account + STORAGE_API);
        script.step("resource group delete", given(), "DELETE", rg + RG_API);
        script.step("resource group get after delete", given(), "GET", rg + RG_API);
        script.finish();
    }

    @Test
    void entraIdentityPlatform() {
        Script script = new Script("entra");
        script.step("discovery, tenant v2.0", given(), "GET",
            "/" + DEFAULT_TENANT + "/v2.0/.well-known/openid-configuration");
        script.step("discovery, common v2.0", given(), "GET", "/common/v2.0/.well-known/openid-configuration");
        script.step("token, client credentials", given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET)
                .formParam("scope", "https://management.azure.com/.default"),
            "POST", "/" + DEFAULT_TENANT + "/oauth2/v2.0/token");
        script.finish();
    }

    @Test
    void cloudMetadata() {
        Script script = new Script("metadata");
        script.step("endpoints, 2022-09-01", given(), "GET", "/metadata/endpoints?api-version=2022-09-01");
        script.step("endpoints, 2019-05-01", given(), "GET", "/metadata/endpoints?api-version=2019-05-01");
        script.finish();
    }

    @Test
    void dataPlane() {
        Script script = new Script("data-plane");
        script.step("blob container create", given(), "PUT", "/devstoreaccount1/golden-container?restype=container");
        script.step("blob put", given().contentType("text/plain").header("x-ms-blob-type", "BlockBlob")
            .body("golden blob body"), "PUT", "/devstoreaccount1/golden-container/golden.txt");
        script.step("blob get", given(), "GET", "/devstoreaccount1/golden-container/golden.txt");
        script.step("queue create", given(), "PUT", "/devstoreaccount1-queue/golden-queue");
        script.step("key vault challenge without a token", given(), "GET",
            "/devstoreaccount1-keyvault/secrets?api-version=7.4");
        script.finish();
    }

    private static RequestSpecification json(String body) {
        return given().contentType("application/json").body(body);
    }

    // ── Recording and comparing ─────────────────────────────────────────────

    /** One golden file: the steps run in order, then recorded or compared. */
    private static final class Script {
        private final String name;
        private final Map<String, JsonNode> steps = new LinkedHashMap<>();
        private final Normaliser normaliser = new Normaliser(RestAssured.port);

        Script(String name) {
            this.name = name;
        }

        void step(String label, RequestSpecification request, String method, String path) {
            Response response = request.when().request(method, path).then().extract().response();
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("request", method + " " + path);
            node.put("status", response.statusCode());
            ObjectNode headers = node.putObject("headers");
            for (String header : PINNED_HEADERS) {
                String value = response.getHeader(header);
                if (value != null) {
                    headers.put(header, normaliser.text(value));
                }
            }
            node.set("body", normaliser.body(response.getHeader("Content-Type"), response.asByteArray()));
            steps.put(label, node);
        }

        void finish() {
            // Steps keep their script order; inside a step, keys are sorted (Map.of bodies have no stable order).
            ObjectNode actual = JsonNodeFactory.instance.objectNode();
            steps.forEach((label, step) -> actual.set(label, sorted(step)));
            if (Boolean.getBoolean(RECORD_PROPERTY)) {
                record(actual);
                return;
            }
            JsonNode expected = load();
            assertEquals(fieldNames(expected), fieldNames(actual), name + ": the script's steps changed");
            for (String step : fieldNames(expected)) {
                assertEquals(pretty(expected.get(step)), pretty(actual.get(step)),
                    name + " / " + step + ": the default configuration no longer answers as main did");
            }
        }

        private void record(JsonNode actual) {
            Path dir = Path.of(System.getProperty(DIR_PROPERTY, "src/test/resources/compat-golden"));
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(name + ".json"), MAPPER.writeValueAsString(actual) + "\n");
            } catch (IOException e) {
                throw new IllegalStateException("cannot record " + name, e);
            }
        }

        private JsonNode load() {
            try (InputStream in = CompatModeGoldenTest.class.getClassLoader()
                    .getResourceAsStream(RESOURCE_DIR + name + ".json")) {
                assertNotNull(in, "missing golden file " + RESOURCE_DIR + name + ".json; record it from main");
                return MAPPER.readTree(in);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read golden file " + name, e);
            }
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Pretty JSON with object keys sorted, so key order (unstable for {@code Map.of} bodies) never matters. */
    private static String pretty(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(sorted(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            fields.forEach(copy::set);
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(sorted(element)));
            return copy;
        }
        return node;
    }

    // ── Normalising volatile values ─────────────────────────────────────────

    /**
     * Replaces the values that change between two runs of the same script, and nothing else. Each rule names
     * the value it hides, so a new volatile value shows up as a failure to be classified rather than being
     * swallowed by a broad pattern.
     */
    static final class Normaliser {

        private static final Pattern GUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        private static final Pattern ISO_TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?");
        private static final Pattern HTTP_DATE =
            Pattern.compile("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT");
        private static final Pattern JWT =
            Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

        /** Object fields whose value is an etag or a secret generated per run. */
        private static final Set<String> VOLATILE_FIELDS = Set.of("etag");

        /** Claims that carry time or per-token randomness; their presence is compared, not their value. */
        private static final Set<String> VOLATILE_CLAIMS = Set.of("iat", "nbf", "exp", "uti", "aio", "rh", "jti",
            "xms_tcdt");

        private final String port;

        Normaliser(int port) {
            this.port = ":" + port;
        }

        JsonNode body(String contentType, byte[] bytes) {
            if (bytes.length == 0) {
                return JsonNodeFactory.instance.nullNode();
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) {
                try {
                    return json(MAPPER.readTree(text), null);
                } catch (JsonProcessingException e) {
                    return new TextNode("unparseable JSON: " + text(text));
                }
            }
            return new TextNode(text(text));
        }

        private JsonNode json(JsonNode node, String fieldName) {
            if (node.isObject()) {
                ObjectNode copy = JsonNodeFactory.instance.objectNode();
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    copy.set(field.getKey(), json(field.getValue(), field.getKey()));
                }
                return copy;
            }
            if (node.isArray()) {
                ArrayNode copy = JsonNodeFactory.instance.arrayNode();
                node.forEach(element -> copy.add(json(element, fieldName)));
                return copy;
            }
            if (node.isTextual()) {
                String value = node.asText();
                if (JWT.matcher(value).matches() && value.startsWith("eyJ")) {
                    return token(value);
                }
                if (fieldName != null && VOLATILE_FIELDS.contains(fieldName.toLowerCase(Locale.ROOT))) {
                    return new TextNode("<" + fieldName.toLowerCase(Locale.ROOT) + ">");
                }
                return new TextNode(text(value));
            }
            return node;
        }

        /** A JWT as its header and claims, time and per-token values replaced; the signature is dropped. */
        private JsonNode token(String jwt) {
            String[] parts = jwt.split("\\.");
            ObjectNode token = JsonNodeFactory.instance.objectNode();
            token.set("header", claims(decode(parts[0])));
            token.set("claims", claims(decode(parts[1])));
            return token;
        }

        private JsonNode claims(JsonNode decoded) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            decoded.fields().forEachRemaining(claim -> {
                String name = claim.getKey();
                if (VOLATILE_CLAIMS.contains(name) || "kid".equals(name) || "x5t".equals(name)) {
                    String type = claim.getValue().getNodeType().name().toLowerCase(Locale.ROOT);
                    copy.put(name, "<" + name + ":" + type + ">");
                } else {
                    copy.set(name, json(claim.getValue(), name));
                }
            });
            return copy;
        }

        private static JsonNode decode(String segment) {
            try {
                return MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
            } catch (IOException | IllegalArgumentException e) {
                return new TextNode("undecodable JWT segment");
            }
        }

        String text(String value) {
            String result = value.replace(port, ":<port>");
            result = ISO_TIMESTAMP.matcher(result).replaceAll("<timestamp>");
            result = HTTP_DATE.matcher(result).replaceAll("<http-date>");
            Matcher guids = GUID.matcher(result);
            StringBuilder out = new StringBuilder();
            while (guids.find()) {
                String guid = guids.group();
                guids.appendReplacement(out, STABLE_GUIDS.contains(guid.toLowerCase(Locale.ROOT)) ? guid : "<guid>");
            }
            guids.appendTail(out);
            return out.toString();
        }
    }
}
