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

    private static final String TOPIC_BODY = entry("<TopicDescription xmlns=\"" + SB_NS + "\"/>");

    private static void createTopicAndSubscription(String topic, String sub) {
        given().body(TOPIC_BODY).when().put(BASE + "/" + topic).then().statusCode(201);
        given().when().put(BASE + "/" + topic + "/subscriptions/" + sub).then().statusCode(201);
    }

    private static String rulesPath(String topic, String sub) {
        return BASE + "/" + topic + "/subscriptions/" + sub + "/rules";
    }

    private static String entry(String inner) {
        return "<entry xmlns=\"http://www.w3.org/2005/Atom\"><content type=\"application/xml\">"
                + inner + "</content></entry>";
    }

    private static String ruleBody(String ruleName, String filterXml, String actionXml) {
        return entry("<RuleDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + filterXml
                + (actionXml == null ? "" : actionXml)
                + "<Name>" + ruleName + "</Name></RuleDescription>");
    }

    private static String correlationRuleBody(String ruleName, String label) {
        return ruleBody(ruleName,
                "<Filter i:type=\"CorrelationFilter\"><Label>" + label + "</Label></Filter>",
                "<Action i:type=\"EmptyRuleAction\"/>");
    }

    private static String sqlRuleBody(String ruleName, String escapedExpression) {
        return ruleBody(ruleName,
                "<Filter i:type=\"SqlFilter\"><SqlExpression>" + escapedExpression
                        + "</SqlExpression></Filter>",
                null);
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
        given().body(sqlRuleBody("sql1", "color='blue' AND sys.Label='x'"))
                .when().put(rulesPath("rules-topic-sql", "sub1") + "/sql1")
                .then()
                .statusCode(201)
                .body(containsString("i:type=\"SqlFilter\""))
                .body(containsString("color=&apos;blue&apos;"));
    }

    @Test
    void unsupportedCorrelationFieldIsRejected() {
        createTopicAndSubscription("rules-topic-reject", "sub1");
        String body = ruleBody("bad",
                "<Filter i:type=\"CorrelationFilter\"><MessageId>m1</MessageId></Filter>", null);

        given().body(body)
                .when().put(rulesPath("rules-topic-reject", "sub1") + "/bad")
                .then()
                .statusCode(400)
                .body(containsString("not supported"));
    }

    @Test
    void subscriptionCreateHonoursDefaultRuleDescription() {
        given().body(TOPIC_BODY).when().put(BASE + "/rules-topic-drd").then().statusCode(201);

        String subBody = entry("<SubscriptionDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<DefaultRuleDescription>"
                + "<Filter i:type=\"CorrelationFilter\"><Label>order.created</Label></Filter>"
                + "<Name>initial</Name>"
                + "</DefaultRuleDescription>"
                + "</SubscriptionDescription>");

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

    @Test
    void rulesOnMissingTopicReturn404() {
        given().when().get(rulesPath("no-such-topic", "nosub")).then().statusCode(404);
        given().body(correlationRuleBody("r", "x"))
                .when().put(rulesPath("no-such-topic", "nosub") + "/r")
                .then().statusCode(404);
        given().when().delete(rulesPath("no-such-topic", "nosub") + "/r").then().statusCode(404);
    }

    @Test
    void nonGetOnRulesCollectionReturns405() {
        createTopicAndSubscription("rules-topic-405", "sub1");
        given().when().delete(rulesPath("rules-topic-405", "sub1")).then().statusCode(405);
        given().body(correlationRuleBody("r", "x"))
                .when().put(rulesPath("rules-topic-405", "sub1"))
                .then().statusCode(405);
    }

    @Test
    void invalidSqlFiltersAreRejectedWith400() {
        createTopicAndSubscription("rules-topic-badsql", "sub1");
        String rules = rulesPath("rules-topic-badsql", "sub1");

        // unterminated string literal
        given().body(sqlRuleBody("bad1", "color = 'unterminated"))
                .when().put(rules + "/bad1").then().statusCode(400);
        // unsupported system property
        given().body(sqlRuleBody("bad2", "sys.MessageId = 'x'"))
                .when().put(rules + "/bad2").then().statusCode(400);
        // unsupported modulo operator
        given().body(sqlRuleBody("bad3", "quantity % 2 = 0"))
                .when().put(rules + "/bad3").then().statusCode(400);

        // failed creates must not leave partial rules behind
        given().when().get(rules)
                .then().statusCode(200)
                .body(not(containsString("bad1")))
                .body(not(containsString("bad2")))
                .body(not(containsString("bad3")));
    }

    @Test
    void emptyCorrelationFilterIsRejectedWith400() {
        createTopicAndSubscription("rules-topic-emptycorr", "sub1");
        String body = ruleBody("empty", "<Filter i:type=\"CorrelationFilter\"></Filter>", null);
        given().body(body)
                .when().put(rulesPath("rules-topic-emptycorr", "sub1") + "/empty")
                .then().statusCode(400)
                .body(containsString("at least one property"));
    }

    @Test
    void sqlRuleActionIsStoredAndEchoed() {
        createTopicAndSubscription("rules-topic-action", "sub1");
        String body = ruleBody("with-action",
                "<Filter i:type=\"SqlFilter\"><SqlExpression>color='red'</SqlExpression></Filter>",
                "<Action i:type=\"SqlRuleAction\"><SqlExpression>SET quantity = 1</SqlExpression></Action>");
        String rules = rulesPath("rules-topic-action", "sub1");

        given().body(body).when().put(rules + "/with-action")
                .then().statusCode(201)
                .body(containsString("i:type=\"SqlRuleAction\""));

        given().when().get(rules + "/with-action")
                .then().statusCode(200)
                .body(containsString("SET quantity = 1"));
    }

    @Test
    void falseFilterRuleIsAccepted() {
        createTopicAndSubscription("rules-topic-false", "sub1");
        String body = ruleBody("none",
                "<Filter i:type=\"FalseFilter\"><SqlExpression>1=0</SqlExpression></Filter>", null);
        given().body(body)
                .when().put(rulesPath("rules-topic-false", "sub1") + "/none")
                .then().statusCode(201)
                .body(containsString("i:type=\"FalseFilter\""));
    }

    @Test
    void subscriptionCreateWithUnsupportedDefaultRuleIsRejected() {
        given().body(TOPIC_BODY).when().put(BASE + "/rules-topic-baddrd").then().statusCode(201);

        String subBody = entry("<SubscriptionDescription xmlns:i=\"" + XSI_NS + "\" xmlns=\"" + SB_NS + "\">"
                + "<DefaultRuleDescription>"
                + "<Filter i:type=\"CorrelationFilter\"><MessageId>m1</MessageId></Filter>"
                + "<Name>bad</Name></DefaultRuleDescription>"
                + "</SubscriptionDescription>");

        given().body(subBody)
                .when().put(BASE + "/rules-topic-baddrd/subscriptions/sub1")
                .then().statusCode(400);

        // the subscription must not have been half-created
        given().when().get(BASE + "/rules-topic-baddrd/subscriptions/sub1")
                .then().statusCode(404);
    }

    @Test
    void recreatingExistingSubscriptionKeepsItsRules() {
        createTopicAndSubscription("rules-topic-recreate", "sub1");
        String rules = rulesPath("rules-topic-recreate", "sub1");
        given().body(correlationRuleBody("keep-me", "red"))
                .when().put(rules + "/keep-me").then().statusCode(201);
        given().when().delete(rules + "/$Default").then().statusCode(200);

        // idempotent re-create returns the existing subscription and leaves rules alone
        given().when().put(BASE + "/rules-topic-recreate/subscriptions/sub1")
                .then().statusCode(200);
        given().when().get(rules)
                .then().statusCode(200)
                .body(containsString("keep-me"))
                .body(not(containsString("$Default")));
    }

    @Test
    void updatingRuleCanChangeFilterType() {
        createTopicAndSubscription("rules-topic-retype", "sub1");
        String rules = rulesPath("rules-topic-retype", "sub1");

        given().body(correlationRuleBody("morph", "red"))
                .when().put(rules + "/morph").then().statusCode(201);
        given().body(sqlRuleBody("morph", "color='blue'"))
                .when().put(rules + "/morph")
                .then().statusCode(200)
                .body(containsString("i:type=\"SqlFilter\""));

        given().when().get(rules + "/morph")
                .then().statusCode(200)
                .body(containsString("i:type=\"SqlFilter\""))
                .body(not(containsString("CorrelationFilter")));
    }

    @Test
    void deletingTopicCascadesRules() {
        createTopicAndSubscription("rules-topic-tcascade", "sub1");
        given().body(correlationRuleBody("r1", "red"))
                .when().put(rulesPath("rules-topic-tcascade", "sub1") + "/r1")
                .then().statusCode(201);

        given().when().delete(BASE + "/rules-topic-tcascade").then().statusCode(200);

        // recreating the same topic + subscription starts fresh with only $Default
        createTopicAndSubscription("rules-topic-tcascade", "sub1");
        given().when().get(rulesPath("rules-topic-tcascade", "sub1"))
                .then().statusCode(200)
                .body(containsString("$Default"))
                .body(not(containsString("<Name>r1</Name>")));
    }

    @Test
    void deletingRuleTwiceReturns404() {
        createTopicAndSubscription("rules-topic-del2", "sub1");
        String rule = rulesPath("rules-topic-del2", "sub1") + "/$Default";
        given().when().delete(rule).then().statusCode(200);
        given().when().delete(rule).then().statusCode(404);
    }

    @Test
    void legacyNamespacePathSupportsRuleCrud() {
        String legacyBase = BASE + "/default/topics/rules-topic-legacy";
        given().when().put(legacyBase).then().statusCode(201);
        given().when().put(legacyBase + "/subscriptions/sub1").then().statusCode(201);

        given().body(correlationRuleBody("legacy-rule", "red"))
                .when().put(legacyBase + "/subscriptions/sub1/rules/legacy-rule")
                .then().statusCode(201)
                .body(containsString("<Name>legacy-rule</Name>"));

        given().when().get(legacyBase + "/subscriptions/sub1/rules")
                .then().statusCode(200)
                .body(containsString("legacy-rule"))
                .body(containsString("$Default"));

        given().when().delete(legacyBase + "/subscriptions/sub1/rules/legacy-rule")
                .then().statusCode(200);
        given().when().get(legacyBase + "/subscriptions/sub1/rules/legacy-rule")
                .then().statusCode(404);
    }

    @Test
    void feedsContainExactlyOneXmlProlog() {
        createTopicAndSubscription("rules-topic-prolog", "sub1");
        given().body(correlationRuleBody("r1", "red"))
                .when().put(rulesPath("rules-topic-prolog", "sub1") + "/r1")
                .then().statusCode(201);

        for (String path : new String[]{
                rulesPath("rules-topic-prolog", "sub1"),
                BASE + "/rules-topic-prolog/subscriptions"}) {
            String feed = given().when().get(path)
                    .then().statusCode(200).extract().asString();
            long prologs = feed.split("<\\?xml", -1).length - 1;
            org.junit.jupiter.api.Assertions.assertEquals(1, prologs,
                    "feed at " + path + " must contain exactly one XML prolog");
        }
    }

    @Test
    void unparseableRuleBodyDegradesToTrueFilter() {
        // lenient like the rest of the management plane: garbage bodies fall back
        // to Azure's default TrueFilter instead of failing the create
        createTopicAndSubscription("rules-topic-garbage", "sub1");
        given().body("this is not xml <at all")
                .when().put(rulesPath("rules-topic-garbage", "sub1") + "/lenient")
                .then().statusCode(201)
                .body(containsString("i:type=\"TrueFilter\""));
    }
}
