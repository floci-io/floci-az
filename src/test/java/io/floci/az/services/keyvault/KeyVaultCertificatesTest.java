package io.floci.az.services.keyvault;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class KeyVaultCertificatesTest {
    @Test
    void certificateKeyRotationPolicySurvivesRenewalAndRecovery() {
        String vault = "/cert" + UUID.randomUUID().toString().replace("-", "") + "-keyvault";
        String policy = """
                {"policy":{"issuer":{"name":"Self"},"key_props":{"kty":"EC"},"x509_props":{"subject":"CN=test"}}}
                """;
        given().header("Authorization", "Bearer test").contentType("application/json").body(policy)
                .post(vault + "/certificates/test/create").then().statusCode(202);
        given().header("Authorization", "Bearer test").contentType("application/json")
                .body("{\"attributes\":{\"expiryTime\":\"P2Y\"}}")
                .put(vault + "/keys/test/rotationpolicy").then().statusCode(200);
        given().header("Authorization", "Bearer test").contentType("application/json").body(policy)
                .post(vault + "/certificates/test/create").then().statusCode(202);
        given().header("Authorization", "Bearer test").delete(vault + "/certificates/test").then().statusCode(200);
        given().header("Authorization", "Bearer test").post(vault + "/deletedcertificates/test/recover").then().statusCode(200);
        given().header("Authorization", "Bearer test").get(vault + "/keys/test/rotationpolicy")
                .then().statusCode(200).body("attributes.expiryTime", equalTo("P2Y"));
    }

    @Test
    void protectsIndependentlyCreatedBackingObjects() {
        String vault = "/cert" + UUID.randomUUID().toString().replace("-", "") + "-keyvault";
        String policy = """
                {"policy":{"issuer":{"name":"Self"},"x509_props":{"subject":"CN=test"}}}
                """;
        given().header("Authorization", "Bearer test").contentType("application/json").body("{\"value\":\"independent\"}")
                .put(vault + "/secrets/standalone").then().statusCode(200);
        given().header("Authorization", "Bearer test").contentType("application/json").body(policy)
                .post(vault + "/certificates/standalone/create").then().statusCode(409);
        given().header("Authorization", "Bearer test").contentType("application/json").body(policy)
                .post(vault + "/certificates/test/create").then().statusCode(202);
        given().header("Authorization", "Bearer test").contentType("application/json").body("{\"value\":\"independent\"}")
                .put(vault + "/secrets/test").then().statusCode(200);
        given().header("Authorization", "Bearer test").delete(vault + "/certificates/test").then().statusCode(409);
        given().header("Authorization", "Bearer test").get(vault + "/secrets/test").then().body("value", equalTo("independent"));
        given().header("Authorization", "Bearer test").get(vault + "/certificates/test").then().statusCode(200);
    }

    @Test
    void refusesToReuseKeyWithDifferentCurve() {
        String vault = "/cert" + UUID.randomUUID().toString().replace("-", "") + "-keyvault";
        String policy = """
                {"policy":{"issuer":{"name":"Self"},"key_props":{"kty":"EC","crv":"P-256","reuse_key":true},
                "x509_props":{"subject":"CN=test"}}}
                """;
        given().header("Authorization", "Bearer test").contentType("application/json").body(policy)
                .post(vault + "/certificates/test/create").then().statusCode(202);
        given().header("Authorization", "Bearer test").contentType("application/json").body(policy.replace("P-256", "P-384"))
                .post(vault + "/certificates/test/create").then().statusCode(400);
        given().header("Authorization", "Bearer test").get(vault + "/certificates/test/versions")
                .then().body("value.size()", equalTo(1));
    }

    @Test
    void rejectsUnsupportedIssuerAndInvalidKeySizeWithoutCreatingObjects() {
        String vault = "/cert" + UUID.randomUUID().toString().replace("-", "") + "-keyvault";
        given().header("Authorization", "Bearer test").contentType("application/json").body("null")
                .post(vault + "/certificates/test/create").then().statusCode(400);
        String policy = """
                {"policy":{"issuer":{"name":"External"},"x509_props":{"subject":"CN=test"}}}
                """;
        given().header("Authorization", "Bearer test").contentType("application/json").body(policy)
                .post(vault + "/certificates/test/create").then().statusCode(400);
        given().header("Authorization", "Bearer test").contentType("application/json")
                .body("{\"policy\":{\"issuer\":{\"name\":\"Self\"},\"key_props\":{\"key_size\":123},\"x509_props\":{\"subject\":\"CN=test\"}}}")
                .post(vault + "/certificates/test/create").then().statusCode(400);
        given().header("Authorization", "Bearer test").get(vault + "/certificates")
                .then().statusCode(200).body("value", empty());
        given().header("Authorization", "Bearer test").get(vault + "/secrets/test").then().statusCode(404);
    }

    @Test
    void nonExportableEcCertificateHasNoPrivateKeyInSecret() throws Exception {
        String vault = "/cert" + UUID.randomUUID().toString().replace("-", "") + "-keyvault";
        given().header("Authorization", "Bearer test").contentType("application/json").body("""
                {"policy":{"issuer":{"name":"Self"},"key_props":{"kty":"EC","crv":"P-256","exportable":false},
                "secret_props":{"contentType":"application/x-pkcs12"},
                "x509_props":{"subject":"CN=test","key_usage":["digitalSignature"],"sans":{"dns_names":["test.local"]}}}}
                """)
                .post(vault + "/certificates/test/create").then().statusCode(202);
        String secret = given().header("Authorization", "Bearer test").get(vault + "/secrets/test")
                .then().statusCode(200).extract().path("value");
        KeyStore pfx = KeyStore.getInstance("PKCS12");
        pfx.load(new ByteArrayInputStream(Base64.getDecoder().decode(secret)), new char[0]);
        String alias = pfx.aliases().nextElement();
        assertFalse(pfx.isKeyEntry(alias));
        var certificate = (java.security.cert.X509Certificate) pfx.getCertificate(alias);
        assertEquals("EC", certificate.getPublicKey().getAlgorithm());
        assertTrue(certificate.getKeyUsage()[0]);
        assertTrue(certificate.getSubjectAlternativeNames().stream().anyMatch(entry -> entry.get(1).equals("test.local")));
    }

    @Test
    void createsRealCertificateWithMatchingPrivateKeyAndVersions() throws Exception {
        String vault = "/cert" + UUID.randomUUID().toString().replace("-", "") + "-keyvault";
        String create = vault + "/certificates/signing/create?api-version=7.4";
        String policy = """
                {"policy":{"issuer":{"name":"Self"},
                "key_props":{"kty":"RSA","key_size":2048,"exportable":true},
                "secret_props":{"contentType":"application/x-pkcs12"},
                "x509_props":{"subject":"CN=PalCal","validity_months":12}}}
                """;
        given().header("Authorization", "Bearer test").contentType("application/json").body(policy)
                .post(create).then().statusCode(202).body("status", equalTo("completed"));
        Map<String, Object> certificate = given().header("Authorization", "Bearer test")
                .get(vault + "/certificates/signing").then().statusCode(200).extract().as(Map.class);
        String id = (String) certificate.get("id");
        String version = id.substring(id.lastIndexOf('/') + 1);
        assertTrue(((String) certificate.get("sid")).endsWith("/secrets/signing/" + version));
        assertTrue(((String) certificate.get("kid")).endsWith("/keys/signing/" + version));
        byte[] der = Base64.getDecoder().decode((String) certificate.get("cer"));
        var publicCertificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        String secret = given().header("Authorization", "Bearer test")
                .get(vault + "/secrets/signing/" + version).then().statusCode(200)
                .extract().path("value");
        KeyStore pfx = KeyStore.getInstance("PKCS12");
        pfx.load(new ByteArrayInputStream(Base64.getDecoder().decode(secret)), new char[0]);
        String alias = pfx.aliases().nextElement();
        assertNotNull(pfx.getKey(alias, new char[0]));
        assertArrayEquals(publicCertificate.getEncoded(), pfx.getCertificate(alias).getEncoded());
        given().header("Authorization", "Bearer test").get(vault + "/keys/signing/" + version)
                .then().statusCode(200).body("key.kty", equalTo("RSA"));
        given().header("Authorization", "Bearer test").contentType("application/json").body(policy)
                .post(create).then().statusCode(202);
        given().header("Authorization", "Bearer test").get(vault + "/certificates/signing/versions")
                .then().statusCode(200).body("value.size()", equalTo(2));
        given().header("Authorization", "Bearer test").get(vault + "/certificates/signing/" + version)
                .then().statusCode(200).body("id", equalTo(id));
    }
}
