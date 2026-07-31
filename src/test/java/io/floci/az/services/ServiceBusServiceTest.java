package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
public class ServiceBusServiceTest {

    private static final String BASE = "/devstoreaccount1-servicebus";
    private static final String SB_NS =
            "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";

    @Test
    void testExistingPathRouting() {
        given()
            .when().get("/devstoreaccount1-servicebus/$namespaceinfo")
            .then()
            .statusCode(200)
            .contentType("application/atom+xml")
            .body(containsString("<NamespaceInfo"));
    }

    @Test
    void testRootLevelPathRouting() {
        // Test routing based on spec path segment
        given()
            .when().get("/$namespaceinfo")
            .then()
            .statusCode(200)
            .contentType("application/atom+xml")
            .body(containsString("<NamespaceInfo"));
    }

    @Test
    void testRootLevelAtomPubHeaderRouting() {
        // Test routing based on Content-Type/Accept header
        given()
            .accept("application/atom+xml")
            .when().get("/some-custom-spec-path")
            .then()
            .statusCode(404) // Should route to ServiceBusHandler and return AtomPub 404 (not foundAtom)
            .contentType("application/atom+xml");
    }

    @Test
    void testHostBasedRouting() {
        // Test routing based on Host header
        given()
            .header("Host", "devstoreaccount1.servicebus.windows.net")
            .when().get("/$namespaceinfo")
            .then()
            .statusCode(200)
            .contentType("application/atom+xml")
            .body(containsString("<NamespaceInfo"));
    }

    @Test
    void queuePersistsDuplicateDetectionSettings() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"
                + "<DuplicateDetectionHistoryTimeWindow>PT20S</DuplicateDetectionHistoryTimeWindow>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/duplicate-queue")
                .then().statusCode(201)
                .body(containsString("<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"))
                .body(containsString(
                        "<DuplicateDetectionHistoryTimeWindow>PT20S</DuplicateDetectionHistoryTimeWindow>"));

        given().when().get(BASE + "/duplicate-queue")
                .then().statusCode(200)
                .body(containsString("<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"))
                .body(containsString(
                        "<DuplicateDetectionHistoryTimeWindow>PT20S</DuplicateDetectionHistoryTimeWindow>"));
    }

    @Test
    void topicPersistsDuplicateDetectionSettings() {
        String body = entry("<TopicDescription xmlns=\"" + SB_NS + "\">"
                + "<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"
                + "<DuplicateDetectionHistoryTimeWindow>PT1M</DuplicateDetectionHistoryTimeWindow>"
                + "</TopicDescription>");

        given().body(body).when().put(BASE + "/duplicate-topic")
                .then().statusCode(201)
                .body(containsString("<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"))
                .body(containsString(
                        "<DuplicateDetectionHistoryTimeWindow>PT1M</DuplicateDetectionHistoryTimeWindow>"));
    }

    @Test
    void duplicateDetectionDefaultsToTenMinutes() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/duplicate-default-window")
                .then().statusCode(201)
                .body(containsString(
                        "<DuplicateDetectionHistoryTimeWindow>PT10M</DuplicateDetectionHistoryTimeWindow>"));
    }

    @Test
    void rejectsDuplicateDetectionWindowOutsideAzureLimits() {
        String body = entry("<QueueDescription xmlns=\"" + SB_NS + "\">"
                + "<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"
                + "<DuplicateDetectionHistoryTimeWindow>PT19S</DuplicateDetectionHistoryTimeWindow>"
                + "</QueueDescription>");

        given().body(body).when().put(BASE + "/duplicate-invalid-window")
                .then().statusCode(400)
                .body(containsString("between PT20S and P7D"));
    }

    private static String entry(String description) {
        return "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + description + "</content></entry>";
    }
}
