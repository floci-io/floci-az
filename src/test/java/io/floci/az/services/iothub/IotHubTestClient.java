package io.floci.az.services.iothub;

import io.restassured.specification.RequestSpecification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Creates hubs over ARM and signs requests to their service API, as the az CLI's {@code azure-iot} extension does:
 * a token for the hub's host name, signed with a policy key read through {@code listkeys}.
 */
public final class IotHubTestClient {

    static final String SUB = "10700000-0000-4000-8000-000000000003";
    static final String API = "?api-version=2023-06-30";
    public static final String SERVICE_API = "?api-version=2026-11-01";
    private static final String HUB_BODY = "{\"location\": \"westus2\", \"sku\": {\"name\": \"S1\", \"capacity\": 1}}";

    private IotHubTestClient() {
    }

    static String hubPath(String name) {
        return "/subscriptions/" + SUB + "/resourceGroups/rg/providers/Microsoft.Devices/IotHubs/" + name;
    }

    static String host(String hub) {
        return hub + ".azure-devices.net";
    }

    static void createHub(String name) {
        given().contentType("application/json").body(HUB_BODY).when().put(hubPath(name) + API).then().statusCode(201);
    }

    static void deleteHub(String name) {
        given().when().delete(hubPath(name) + API).then().statusCode(200);
    }

    /** The policy {@code keyName} of {@code hub}, keys included. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> policy(String hub, String keyName) {
        List<Map<String, Object>> policies = given().when().post(hubPath(hub) + "/listkeys" + API)
                .then().statusCode(200).extract().path("value");
        return policies.stream().filter(policy -> keyName.equals(policy.get("keyName"))).findFirst().orElseThrow();
    }

    /** A token as the CLI signs one: for the host name, valid for ten minutes. */
    public static String token(String resource, String keyName, String base64Key) {
        return token(resource, keyName, base64Key, Instant.now().getEpochSecond() + 600);
    }

    static String token(String resource, String keyName, String base64Key, long expiry) {
        String encoded = URLEncoder.encode(resource, StandardCharsets.UTF_8);
        String signature = sign(base64Key, encoded + "\n" + expiry);
        String token = "SharedAccessSignature sr=" + encoded + "&sig="
                + URLEncoder.encode(signature, StandardCharsets.UTF_8) + "&se=" + expiry;
        return keyName == null ? token : token + "&skn=" + keyName;
    }

    static String sign(String base64Key, String toSign) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A request to {@code hub}'s service API, signed with its {@code iothubowner} policy. */
    static RequestSpecification owner(String hub) {
        return as(hub, "iothubowner");
    }

    /** A request to {@code hub}'s service API, signed with the primary key of {@code keyName}. */
    static RequestSpecification as(String hub, String keyName) {
        Map<String, Object> policy = policy(hub, keyName);
        return unsigned(hub).header("Authorization", token(host(hub), keyName, (String) policy.get("primaryKey")));
    }

    static RequestSpecification unsigned(String hub) {
        return given().header("Host", host(hub)).urlEncodingEnabled(false).contentType("application/json");
    }
}
