package io.floci.az.services.functions;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link FunctionsServiceHandler} in {@code mocked=true} mode: the management plane
 * (create app, deploy/list function) works from state with no runtime container, and invocations
 * return a synthetic 200 stub instead of executing user code.
 */
@QuarkusTest
@TestProfile(FunctionsServiceHandlerMockedTest.MockedProfile.class)
@DisplayName("FunctionsServiceHandler — mocked mode (no Docker)")
@SuppressWarnings("unused")
class FunctionsServiceHandlerMockedTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.functions.mocked", "true");
        }
    }

    private static final String FN = "/devstoreaccount1-functions";

    @Test
    @DisplayName("deploy + list work, and invocation returns a synthetic 200 stub")
    void managementWorksAndInvokeIsStubbed() {
        // create app
        given()
            .contentType("application/json")
            .body("{\"runtime\":\"python\",\"linuxFxVersion\":\"Python|3.12\"}")
            .when().put(FN + "/admin/apps/mockapp")
            .then().statusCode(201);

        // deploy a function (no code — management plane only)
        given()
            .contentType("application/json")
            .body("{\"handler\":\"index.handler\"}")
            .when().put(FN + "/admin/apps/mockapp/functions/hello")
            .then().statusCode(201);

        // it is listable
        given()
            .when().get(FN + "/admin/apps/mockapp/functions")
            .then().statusCode(200)
            .body("value", hasSize(1));

        // invocation returns the synthetic stub (no container launched)
        given()
            .contentType("application/json")
            .body("{}")
            .when().post(FN + "/api/mockapp/hello")
            .then().statusCode(200)
            .body("mocked", equalTo(true))
            .body("function", equalTo("hello"));
    }

    @Test
    @DisplayName("a function ZIP larger than Jackson's 20 MB string default is accepted")
    void deployAcceptsZipBeyondJacksonStringDefault() {
        // Real Azure Functions puts no ~20 MB cap on the deployment package; a Python function
        // bundling a few SDKs easily exceeds it. Jackson's default maxStringLength is 20,000,000
        // characters, so the base64 field has to be longer than that to cover the regression.
        String zipBase64 = "A".repeat(24_000_000);

        given()
            .contentType("application/json")
            .body("{\"runtime\":\"python\",\"linuxFxVersion\":\"Python|3.12\"}")
            .when().put(FN + "/admin/apps/bigapp")
            .then().statusCode(201);

        given()
            .contentType("application/json")
            .body("{\"handler\":\"function_app.handler\",\"zipBase64\":\"" + zipBase64 + "\"}")
            .when().put(FN + "/admin/apps/bigapp/functions/big")
            .then().statusCode(201);

        given()
            .when().get(FN + "/admin/apps/bigapp/functions")
            .then().statusCode(200)
            .body("value", hasSize(1));
    }

    @Test
    @DisplayName("invoking an unknown function still returns 404 in mocked mode")
    void unknownFunctionStill404() {
        given()
            .contentType("application/json")
            .body("{}")
            .when().post(FN + "/api/ghostapp/ghostfn")
            .then().statusCode(404);
    }

    @Test
    @DisplayName("incompatible root and v1 layouts are rejected in one app")
    void incompatibleLayoutsAreRejected() throws Exception {
        String app = "layout-" + UUID.randomUUID().toString().substring(0, 8);

        given()
            .contentType("application/json")
            .body("{\"runtime\":\"python\",\"linuxFxVersion\":\"Python|3.12\"}")
            .when().put(FN + "/admin/apps/" + app)
            .then().statusCode(201);

        given()
            .contentType("application/json")
            .body(deployBody("function_app.hello", rootLayoutZip()))
            .when().put(FN + "/admin/apps/" + app + "/functions/hello")
            .then().statusCode(201);

        given()
            .contentType("application/json")
            .body(deployBody("function_app.goodbye", rootLayoutZip()))
            .when().put(FN + "/admin/apps/" + app + "/functions/goodbye")
            .then().statusCode(409)
            .body("Code", equalTo("IncompatibleFunctionLayout"));

        given()
            .contentType("application/json")
            .body(deployBody("index.handler", v1LayoutZip()))
            .when().put(FN + "/admin/apps/" + app + "/functions/legacy")
            .then().statusCode(409)
            .body("Code", equalTo("IncompatibleFunctionLayout"));
    }

    private static String deployBody(String handler, String zipBase64) {
        return "{\"handler\":\"" + handler + "\",\"zipBase64\":\"" + zipBase64 + "\"}";
    }

    private static String rootLayoutZip() throws Exception {
        return zipBase64(Map.of(
                "function_app.py", "import azure.functions\n",
                "host.json", "{\"version\":\"2.0\"}"));
    }

    private static String v1LayoutZip() throws Exception {
        return zipBase64(Map.of(
                "function.json", "{}",
                "index.js", "module.exports = async function() {};\n"));
    }

    private static String zipBase64(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entries.forEach((name, content) -> {
                try {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(content.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
