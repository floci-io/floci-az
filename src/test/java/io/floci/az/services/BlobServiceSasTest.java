package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class BlobServiceSasTest {
    private static final String KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String BASE = "/devstoreaccount1/service-sas";

    @BeforeEach
    void setup() {
        given().post("/_admin/reset").then().statusCode(204);
        given().put(BASE + "?restype=container").then().statusCode(201);
        given().header("x-ms-blob-type", "BlockBlob").body("original")
                .put(BASE + "/file").then().statusCode(201);
    }

    @Test
    void serviceSasReadsAndCreatesBlobsAcrossSigningVersions() throws Exception {
        for (String version : new String[] {"2018-11-09", "2020-02-10", "2020-12-06", "2026-04-06"}) {
            given().queryParams(sas("r", "b", "file", version)).get(BASE + "/file")
                    .then().statusCode(200).body(equalTo("original"));
            given().queryParams(sas("cw", "b", version, version))
                    .header("x-ms-blob-type", "BlockBlob").body("uploaded")
                    .put(BASE + "/" + version).then().statusCode(201);
        }
    }

    @Test
    void containerSasReadsChildrenAndLists() throws Exception {
        var sas = sas("rl", "c", null, "2020-12-06");
        given().queryParams(sas).get(BASE + "/file").then().statusCode(200);
        given().queryParams(sas).queryParam("restype", "container").queryParam("comp", "list")
                .get(BASE).then().statusCode(200);
    }

    @Test
    void rejectsWrongSignatureResourceAndPermissions() throws Exception {
        var sas = sas("r", "b", "file", "2020-12-06");
        given().queryParams(sas).get(BASE + "/another").then().statusCode(403);
        given().queryParams(sas).body("overwrite").put(BASE + "/file").then()
                .statusCode(403).header("x-ms-error-code", "AuthorizationPermissionMismatch");
        sas.put("sig", "invalid");
        given().queryParams(sas).get(BASE + "/file").then().statusCode(403);
    }

    @Test
    void incompleteDelegationTokenCannotFallBackToServiceSigning() throws Exception {
        var sas = sas("r", "b", "file", "2020-12-06");
        sas.put("sktid", "incomplete-delegation");
        given().queryParams(sas).get(BASE + "/file").then().statusCode(403);
    }

    private Map<String, String> sas(String permissions, String resource, String path, String version) throws Exception {
        String expiry = Instant.now().plusSeconds(3600).toString();
        String canonical = "/blob/devstoreaccount1/service-sas" + (path == null ? "" : "/" + path);
        String fields = permissions + "\n\n" + expiry + "\n" + canonical + "\n\n\n\n" + version
                + "\n" + resource + "\n";
        if (version.compareTo("2020-12-06") >= 0) {
            fields += "\n";
        }
        fields += "\n\n\n\n\n";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(KEY), "HmacSHA256"));
        var query = new LinkedHashMap<>(Map.of("sv", version, "sp", permissions, "sr", resource, "se", expiry));
        query.put("sig", Base64.getEncoder().encodeToString(mac.doFinal(fields.getBytes(StandardCharsets.UTF_8))));
        return query;
    }
}
