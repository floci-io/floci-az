package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Rule CRUD over the spec-compatible management paths. Runs against the mocked
 * Service Bus namespace (no broker), which exercises the full ATOM protocol.
 */
@QuarkusTest
public class ServiceBusRulesTest {

    private static final String BASE = "/devstoreaccount1-servicebus";
    private static final String SB_NS = "http://schemas.microsoft.com/netservices/2010/10/servicebus/connect";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    private static final String TOPIC_BODY =
            "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                    + "<TopicDescription xmlns=\"" + SB_NS + "\"/></content></entry>";

    private static void createTopicAndSubscription(String topic, String sub) {
        given().body(TOPIC_BODY).when().put(BASE + "/" + topic).then().statusCode(201);
        given().when().put(BASE + "/" + topic + "/subscriptions/" + sub).then().statusCode(201);
    }

    private static String rulesPath(String topic, String sub) {
        return BASE + "/" + topic + "/subscriptions/" + sub + "/rules";
    }

    private static String correlationRuleBody(String ruleName, String label) {
        return "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"CorrelationFilter\"><Label>" + label + "</Label></Filter>"
                + "<Action i:type=\"EmptyRuleAction\"/>"
                + "<Name>" + ruleName + "</Name>"
                + "</RuleDescription></content></entry>";
    }

    @Test
    void subscriptionStartsWithDefaultTrueFilterRule() {
        createTopicAndSubscription("rules-topic-default", "sub1");

        given().when().get(rulesPath("rules-topic-default", "sub1"))
                .then()
                .statusCode(200)
                .contentType("application/atom+xml")
                .body(containsString("<title type=\"text\">$Default</title>"))
                .body(containsString("i:type=\"TrueFilter\""));

        given().when().get(rulesPath("rules-topic-default", "sub1") + "/$Default")
                .then()
                .statusCode(200)
                .body(containsString("<Name>$Default</Name>"));
    }

    @Test
    void correlationRuleCrudAndDefaultReplacement() {
        createTopicAndSubscription("rules-topic-crud", "sub1");
        String rules = rulesPath("rules-topic-crud", "sub1");

        // Azure SDK flow: add the real rule, then delete $Default
        given().body(correlationRuleBody("only-red", "red"))
                .when().put(rules + "/only-red")
                .then()
                .statusCode(201)
                .contentType("application/atom+xml")
                .body(containsString("i:type=\"CorrelationFilter\""))
                .body(containsString("<Label>red</Label>"))
                .body(containsString("<Name>only-red</Name>"));

        given().when().delete(rules + "/$Default").then().statusCode(200);

        given().when().get(rules)
                .then()
                .statusCode(200)
                .body(containsString("only-red"))
                .body(not(containsString("$Default")));

        // update in place (PUT to existing name) returns 200
        given().body(correlationRuleBody("only-red", "blue"))
                .when().put(rules + "/only-red")
                .then()
                .statusCode(200)
                .body(containsString("<Label>blue</Label>"));

        given().when().delete(rules + "/only-red").then().statusCode(200);
        given().when().get(rules + "/only-red").then().statusCode(404);
    }

    @Test
    void sqlRuleIsAcceptedAndReturned() {
        createTopicAndSubscription("rules-topic-sql", "sub1");
        String body = "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"SqlFilter\"><SqlExpression>color='blue' AND sys.Label='x'</SqlExpression></Filter>"
                + "<Name>sql1</Name></RuleDescription></content></entry>";

        given().body(body)
                .when().put(rulesPath("rules-topic-sql", "sub1") + "/sql1")
                .then()
                .statusCode(201)
                .body(containsString("i:type=\"SqlFilter\""))
                .body(containsString("color=&apos;blue&apos;"));
    }

    @Test
    void unsupportedCorrelationFieldIsRejected() {
        createTopicAndSubscription("rules-topic-reject", "sub1");
        String body = "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + "<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<Filter i:type=\"CorrelationFilter\"><MessageId>m1</MessageId></Filter>"
                + "<Name>bad</Name></RuleDescription></content></entry>";

        given().body(body)
                .when().put(rulesPath("rules-topic-reject", "sub1") + "/bad")
                .then()
                .statusCode(400)
                .body(containsString("not supported"));
    }

    @Test
    void subscriptionCreateHonoursDefaultRuleDescription() {
        given().body(TOPIC_BODY).when().put(BASE + "/rules-topic-drd").then().statusCode(201);

        String subBody = "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + "<SubscriptionDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<DefaultRuleDescription>"
                + "<Filter i:type=\"CorrelationFilter\"><Label>order.created</Label></Filter>"
                + "<Name>initial</Name>"
                + "</DefaultRuleDescription>"
                + "</SubscriptionDescription></content></entry>";

        given().body(subBody)
                .when().put(BASE + "/rules-topic-drd/subscriptions/sub1")
                .then().statusCode(201);

        given().when().get(rulesPath("rules-topic-drd", "sub1"))
                .then()
                .statusCode(200)
                .body(containsString("<Name>initial</Name>"))
                .body(containsString("<Label>order.created</Label>"))
                .body(not(containsString("$Default")));
    }

    @Test
    void ruleEntriesDoNotLeakIntoSubscriptionList() {
        createTopicAndSubscription("rules-topic-list", "sub1");
        given().body(correlationRuleBody("r1", "red"))
                .when().put(rulesPath("rules-topic-list", "sub1") + "/r1")
                .then().statusCode(201);

        Response resp = given()
                .when().get(BASE + "/rules-topic-list/subscriptions")
                .then()
                .statusCode(200)
                .body(not(containsString("RuleDescription")))
                .extract().response();
        long entries = resp.asString().split("<entry", -1).length - 1;
        org.junit.jupiter.api.Assertions.assertEquals(1, entries,
                "subscription feed must contain exactly the one subscription");
    }

    @Test
    void deletingSubscriptionRemovesItsRules() {
        createTopicAndSubscription("rules-topic-cascade", "sub1");
        given().body(correlationRuleBody("r1", "red"))
                .when().put(rulesPath("rules-topic-cascade", "sub1") + "/r1")
                .then().statusCode(201);

        given().when().delete(BASE + "/rules-topic-cascade/subscriptions/sub1")
                .then().statusCode(200);

        // re-creating the subscription must start fresh with only $Default
        given().when().put(BASE + "/rules-topic-cascade/subscriptions/sub1")
                .then().statusCode(201);
        given().when().get(rulesPath("rules-topic-cascade", "sub1"))
                .then()
                .statusCode(200)
                .body(containsString("$Default"))
                .body(not(containsString("<Name>r1</Name>")));
    }

    @Test
    void rulesOnMissingSubscriptionReturn404() {
        given().body(TOPIC_BODY).when().put(BASE + "/rules-topic-404").then().statusCode(201);
        given().when().get(rulesPath("rules-topic-404", "nosub")).then().statusCode(404);
        given().body(correlationRuleBody("r", "x"))
                .when().put(rulesPath("rules-topic-404", "nosub") + "/r")
                .then().statusCode(404);
    }
}
