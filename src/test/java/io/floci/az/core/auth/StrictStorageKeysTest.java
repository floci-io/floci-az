package io.floci.az.core.auth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code parity-overrides.strict-storage-keys}: the Blob, Queue and Table data planes accept a Shared Key, Shared Key
 * Lite or account SAS request only when one of the account's current keys signed it. Requests are signed here as
 * Authorize with Shared Key describes, for the path-style URLs floci-az serves, where "the account name will appear
 * twice" in the canonicalized resource.
 */
@QuarkusTest
@TestProfile(StrictStorageKeysTest.Strict.class)
@DisplayName("Storage: parity-overrides.strict-storage-keys verifies account key signatures")
class StrictStorageKeysTest {

    private static final String ARM = "/subscriptions/sub-strict-keys/resourceGroups/rg-strict-keys"
            + "/providers/Microsoft.Storage/storageAccounts/";
    private static final String API = "?api-version=2025-08-01";
    private static final String VERSION = "2025-01-05";
    private static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final String WRONG_KEY = Base64.getEncoder().encodeToString(new byte[64]);

    public static class Strict implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.parity-overrides.strict-storage-keys", "true");
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    private record Signed(int status, String body, Map<String, java.util.List<String>> headers) {
        String header(String name) {
            return headers.getOrDefault(name, java.util.List.of()).stream().findFirst().orElse(null);
        }
    }

    @Test
    void blobRequestsSignedWithEitherKeyAreServed() throws Exception {
        String account = "strictblobacct";
        JsonPath keys = createAccount(account);

        assertEquals(201, blob(account, "PUT", "/signed?restype=container", null, key1(keys), false).status());
        assertEquals(201, blob(account, "PUT", "/signed/one.txt", "one", key1(keys), false).status());
        assertEquals(201, blob(account, "PUT", "/signed/two.txt", "two", key2(keys), true).status());
        Signed read = blob(account, "GET", "/signed/two.txt", null, key2(keys), false);
        assertEquals(200, read.status());
        assertEquals("two", read.body());
        assertEquals(200, blob(account, "GET", "/signed?restype=container&comp=list&include=metadata&include=tags",
                null, key1(keys), true).status());
    }

    @Test
    void aBlobRequestSignedWithAnotherKeyIsRefusedWithTheStringToSign() throws Exception {
        String account = "strictwrongacct";
        createAccount(account);

        Signed refused = blob(account, "PUT", "/refused?restype=container", null, WRONG_KEY, false);

        assertEquals(403, refused.status());
        assertEquals("AuthenticationFailed", refused.header("x-ms-error-code"));
        assertTrue(refused.body().contains("<Code>AuthenticationFailed</Code><Message>Server failed to authenticate "
                + "the request. Make sure the value of Authorization header is formed correctly including the "
                + "signature.\nRequestId:" + refused.header("x-ms-request-id") + "\nTime:"), refused.body());
        assertTrue(refused.body().contains("<AuthenticationErrorDetail>The MAC signature found in the HTTP request '"),
                refused.body());
        assertTrue(refused.body().contains("is not the same as any computed signature. Server used following string "
                + "to sign: 'PUT\n\n\n\n\n\n\n\n\n\n\n\nx-ms-date:"), refused.body());
        assertTrue(refused.body().contains("x-ms-version:" + VERSION + "\n/" + account + "/" + account
                + "/refused\nrestype:container'.</AuthenticationErrorDetail>"), refused.body());
    }

    @Test
    void aRegeneratedKeyNoLongerSignsAndTheOtherKeyStillDoes() throws Exception {
        String account = "strictrotateacct";
        JsonPath keys = createAccount(account);
        assertEquals(201, blob(account, "PUT", "/rotate?restype=container", null, key1(keys), false).status());

        given().contentType("application/json").body("{\"keyName\":\"key1\"}")
            .when().post(ARM + account + "/regenerateKey" + API).then().statusCode(200);

        assertEquals(403, blob(account, "GET", "/rotate?restype=container", null, key1(keys), false).status());
        assertEquals(200, blob(account, "GET", "/rotate?restype=container", null, key2(keys), false).status());
        assertEquals(200, blob(account, "GET", "/rotate?restype=container", null, key1(listKeys(account)), false)
                .status());
    }

    @Test
    void aHeaderNamingAnotherAccountIsRefused() throws Exception {
        String account = "strictnameacct";
        JsonPath keys = createAccount(account);

        Signed refused = send(request(account, "PUT", "/" + account + "/named?restype=container", null,
                Map.of()), "SharedKey otheraccount:", key1(keys), account, false);

        assertEquals(403, refused.status());
    }

    @Test
    void aRequestOlderThanFifteenMinutesIsRefused() throws Exception {
        String account = "strictdateacct";
        JsonPath keys = createAccount(account);
        String old = RFC1123.format(Instant.now().minusSeconds(16 * 60));

        Signed refused = send(request(account, "PUT", "/" + account + "/old?restype=container", null,
                Map.of("x-ms-date", old)), "SharedKey " + account + ":", key1(keys), account, false);

        assertEquals(403, refused.status());
        assertTrue(refused.body().contains("<AuthenticationErrorDetail>Request date header too old: '" + old
                + "'</AuthenticationErrorDetail>"), refused.body());
    }

    @Test
    void queueRequestsAreVerified() throws Exception {
        String account = "strictqueueacct";
        JsonPath keys = createAccount(account);

        assertEquals(201, signed(account, "PUT", "/" + account + "-queue/signed", null, Map.of(), key2(keys), false)
                .status());
        Signed refused = signed(account, "PUT", "/" + account + "-queue/refused", null, Map.of(), WRONG_KEY, false);
        assertEquals(403, refused.status());
        assertTrue(refused.body().contains("<AuthenticationErrorDetail>The MAC signature"), refused.body());
    }

    @Test
    void tableRequestsAreVerifiedWithTheTableStringToSign() throws Exception {
        String account = "stricttableacct";
        JsonPath keys = createAccount(account);
        Map<String, String> json = Map.of("Content-Type", "application/json", "Accept", "application/json;odata=nometadata");

        assertEquals(201, signed(account, "POST", "/" + account + "-table/Tables", "{\"TableName\":\"signed\"}", json,
                key1(keys), false).status());
        assertEquals(201, signed(account, "POST", "/" + account + "-table/Tables", "{\"TableName\":\"lite\"}", json,
                key2(keys), true).status());
        Signed refused = signed(account, "POST", "/" + account + "-table/Tables", "{\"TableName\":\"refused\"}", json,
                WRONG_KEY, false);
        assertEquals(403, refused.status());
        assertEquals("AuthenticationFailed", refused.header("x-ms-error-code"));
        assertTrue(refused.body().contains("\"odata.error\""), refused.body());
        assertTrue(refused.body().contains("RequestId:" + refused.header("x-ms-request-id")), refused.body());
    }

    @Test
    void anAccountSasToTheQueueServiceIsVerified() throws Exception {
        String account = "strictsasacct";
        JsonPath keys = createAccount(account);
        String expiry = Instant.now().plusSeconds(3600).toString().replaceAll("\\.\\d+Z$", "Z");
        String fields = account + "\nc\nq\nc\n\n" + expiry + "\n\nhttps,http\n2022-11-02\n\n";

        assertEquals(201, sas(account, "/" + account + "-queue/sasqueue", fields, expiry, key1(keys)).statusCode());
        HttpResponse<String> refused = sas(account, "/" + account + "-queue/sasrefused", fields, expiry, WRONG_KEY);
        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("Signature did not match. String to sign used was " + account), refused.body());
    }

    private HttpResponse<String> sas(String account, String path, String fields, String expiry, String key)
            throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("sv", "2022-11-02");
        query.put("ss", "q");
        query.put("srt", "c");
        query.put("sp", "c");
        query.put("se", expiry);
        query.put("spr", "https,http");
        query.put("sig", hmac(key, fields));
        String encoded = query.entrySet().stream()
                .map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return http.send(HttpRequest.newBuilder(URI.create(base() + path + "?" + encoded))
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Signed blob(String account, String method, String pathAndQuery, String body, String key, boolean lite)
            throws Exception {
        Map<String, String> headers = body == null ? Map.of()
                : Map.of("x-ms-blob-type", "BlockBlob", "Content-Type", "text/plain", "x-ms-meta-Owner", "  a   b  ");
        return signed(account, method, "/" + account + pathAndQuery, body, headers, key, lite);
    }

    private Signed signed(String account, String method, String pathAndQuery, String body,
                          Map<String, String> headers, String key, boolean lite) throws Exception {
        return send(request(account, method, pathAndQuery, body, headers),
                (lite ? "SharedKeyLite " : "SharedKey ") + account + ":", key, account, lite);
    }

    private record Unsigned(String method, String pathAndQuery, String body, Map<String, String> headers) {
    }

    private static Unsigned request(String account, String method, String pathAndQuery, String body,
                                    Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.putIfAbsent("x-ms-date", RFC1123.format(Instant.now()));
        all.put("x-ms-version", VERSION);
        return new Unsigned(method, pathAndQuery, body, all);
    }

    private Signed send(Unsigned unsigned, String scheme, String key, String account, boolean lite) throws Exception {
        String stringToSign = stringToSign(unsigned, account, lite);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + unsigned.pathAndQuery()))
                .method(unsigned.method(), unsigned.body() == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(unsigned.body()));
        unsigned.headers().forEach(builder::header);
        builder.header("Authorization", scheme + hmac(key, stringToSign));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Signed(response.statusCode(), response.body(), response.headers().map());
    }

    /** Authorize with Shared Key: the Blob, Queue and File string, or the Table one for a {@code -table} path. */
    private static String stringToSign(Unsigned request, String account, boolean lite) {
        String path = request.pathAndQuery().split("\\?", 2)[0];
        String query = request.pathAndQuery().contains("?") ? request.pathAndQuery().split("\\?", 2)[1] : "";
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(request.headers());
        String resource = "/" + account + path;
        Map<String, String> parameters = new TreeMap<>();
        for (String pair : query.isEmpty() ? new String[0] : query.split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.merge(parts[0].toLowerCase(), parts.length > 1 ? parts[1] : "",
                    (first, second) -> String.join(",", new java.util.TreeSet<>(java.util.List.of(first, second))));
        }
        String comp = parameters.containsKey("comp") ? "?comp=" + parameters.get("comp") : "";
        if (path.contains("-table")) {
            String date = headers.get("x-ms-date");
            return lite ? date + "\n" + resource + comp
                    : request.method() + "\n\n" + headers.getOrDefault("Content-Type", "") + "\n" + date + "\n"
                            + resource + comp;
        }
        StringBuilder canonicalHeaders = new StringBuilder();
        new TreeMap<>(request.headers().entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().startsWith("x-ms-"))
                .collect(Collectors.toMap(e -> e.getKey().toLowerCase(), e -> e.getValue().trim().replaceAll("\\s+", " "))))
                .forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String contentType = headers.getOrDefault("Content-Type", "");
        if (lite) {
            return request.method() + "\n\n" + contentType + "\n\n" + canonicalHeaders + resource + comp;
        }
        String length = request.body() == null || request.body().isEmpty() ? ""
                : String.valueOf(request.body().getBytes(StandardCharsets.UTF_8).length);
        StringBuilder canonicalQuery = new StringBuilder();
        parameters.forEach((name, value) -> canonicalQuery.append('\n').append(name).append(':').append(value));
        return request.method() + "\n\n\n" + length + "\n\n" + contentType + "\n\n\n\n\n\n\n"
                + canonicalHeaders + resource + canonicalQuery;
    }

    private static JsonPath createAccount(String account) {
        given().contentType("application/json").body("{\"location\":\"eastus\"}")
            .when().put(ARM + account + API).then().statusCode(200);
        return listKeys(account);
    }

    private static JsonPath listKeys(String account) {
        return given().when().post(ARM + account + "/listKeys" + API).then().statusCode(200).extract().jsonPath();
    }

    private static String key1(JsonPath keys) {
        return keys.getString("keys[0].value");
    }

    private static String key2(JsonPath keys) {
        return keys.getString("keys[1].value");
    }

    private static String base() {
        return "http://localhost:" + RestAssured.port;
    }

    private static String hmac(String key, String fields) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(fields.getBytes(StandardCharsets.UTF_8)));
    }
}
