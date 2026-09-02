package io.floci.az.services.servicebus;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end check of the declarative topology loader: a Config.json in the official
 * emulator's format is applied at startup, and the resulting entities are visible (with
 * their properties) through the same management API the SDKs use. Runs in mocked mode,
 * so it verifies the management-plane state without Docker.
 */
@QuarkusTest
@TestProfile(ServiceBusTopologyLoaderTest.TopologyProfile.class)
@DisplayName("Service Bus — declarative topology loaded from Config.json at startup")
class ServiceBusTopologyLoaderTest {

    private static final String BASE = "/devstoreaccount1-servicebus";
    private static final String SB_NS =
            "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    @Inject
    ServiceBusTopologyLoader topologyLoader;

    public static class TopologyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try (InputStream in = TopologyProfile.class.getResourceAsStream(
                    "/servicebus/topology-config.json")) {
                Path file = Files.createTempFile("floci-az-sb-topology", ".json");
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                file.toFile().deleteOnExit();
                return Map.of("floci-az.services.service-bus.topology-file", file.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    void loadsQueuesAndSkipsInvalidOnes() {
        given().when().get(BASE + "/$Resources/queues")
                .then().statusCode(200)
                .body(containsString("queue.plain"))
                .body(containsString("queue.sessions"))
                .body(not(containsString("queue.invalid")));
    }

    @Test
    void queuePropertiesRoundTrip() {
        given().when().get(BASE + "/queue.sessions")
                .then().statusCode(200)
                .body(containsString("<RequiresSession>true</RequiresSession>"))
                .body(containsString("<MaxDeliveryCount>3</MaxDeliveryCount>"))
                .body(containsString("<LockDuration>PT1M</LockDuration>"))
                .body(containsString("<DeadLetteringOnMessageExpiration>true"
                        + "</DeadLetteringOnMessageExpiration>"))
                .body(containsString("<DefaultMessageTimeToLive>PT1H</DefaultMessageTimeToLive>"));
    }

    @Test
    void loadsTopicWithDuplicateDetection() {
        given().when().get(BASE + "/$Resources/topics")
                .then().statusCode(200)
                .body(containsString("topic.orders"));

        given().when().get(BASE + "/topic.orders")
                .then().statusCode(200)
                .body(containsString("<RequiresDuplicateDetection>true</RequiresDuplicateDetection>"))
                .body(containsString(
                        "<DuplicateDetectionHistoryTimeWindow>PT30S</DuplicateDetectionHistoryTimeWindow>"));
    }

    @Test
    void loadsSubscriptions() {
        given().when().get(BASE + "/topic.orders/subscriptions")
                .then().statusCode(200)
                .body(containsString("<title type=\"text\">all</title>"))
                .body(containsString("<title type=\"text\">filtered</title>"))
                .body(containsString("<title type=\"text\">sql</title>"))
                .body(containsString("<title type=\"text\">rejected</title>"));

        given().when().get(BASE + "/topic.orders/subscriptions/filtered")
                .then().statusCode(200)
                .body(containsString("<MaxDeliveryCount>5</MaxDeliveryCount>"));
    }

    @Test
    void subscriptionWithoutRulesKeepsDefaultTrueFilter() {
        given().when().get(BASE + "/topic.orders/subscriptions/all/rules")
                .then().statusCode(200)
                .body(containsString("$Default"))
                .body(containsString("TrueFilter"));
    }

    @Test
    void declaredCorrelationRuleReplacesDefault() {
        given().when().get(BASE + "/topic.orders/subscriptions/filtered/rules")
                .then().statusCode(200)
                .body(containsString("by-subject"))
                .body(not(containsString("$Default")))
                .body(containsString("order-created"))
                .body(containsString("region"));
    }

    @Test
    void declaredSqlRuleReplacesDefault() {
        given().when().get(BASE + "/topic.orders/subscriptions/sql/rules")
                .then().statusCode(200)
                .body(containsString("sql-rule"))
                .body(not(containsString("$Default")))
                .body(containsString("SqlFilter"))
                .body(containsString("priority"));
    }

    @Test
    void rejectedDeclaredRulesPreserveMatchAllDefault() {
        given().when().get(BASE + "/topic.orders/subscriptions/rejected/rules")
                .then().statusCode(200)
                .body(containsString("$Default"))
                .body(not(containsString("unsupported")));
    }

    @Test
    void reloadRemovesRulesAbsentFromTopology() {
        String staleRule = "<entry xmlns=\"http://www.w3.org/2005/Atom\">"
                + "<content type=\"application/xml\">"
                + "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"SqlFilter\"><SqlExpression>priority = 1</SqlExpression></Filter>"
                + "<Name>stale</Name></RuleDescription></content></entry>";
        String rules = BASE + "/topic.orders/subscriptions/filtered/rules";

        given().body(staleRule).when().put(rules + "/stale").then().statusCode(201);

        topologyLoader.load();

        given().when().get(rules)
                .then().statusCode(200)
                .body(containsString("by-subject"))
                .body(not(containsString("stale")))
                .body(not(containsString("$Default")));
    }
}
