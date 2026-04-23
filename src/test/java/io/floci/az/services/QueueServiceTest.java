package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class QueueServiceTest {

    private static final String ACCOUNT = "devstoreaccount1-queue";
    private static final String QUEUE = "test-queue";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    @Test
    void createAndDeleteQueue() {
        given()
            .when().put("/{account}/{queue}", ACCOUNT, QUEUE)
            .then().statusCode(201);

        given()
            .when().delete("/{account}/{queue}", ACCOUNT, QUEUE)
            .then().statusCode(204);
    }

    @Test
    void putAndGetMessage() {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);

        given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>hello</MessageText></QueueMessage>")
            .when().post("/{account}/{queue}/messages", ACCOUNT, QUEUE)
            .then().statusCode(201);

        given()
            .when().get("/{account}/{queue}/messages", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .body(containsString("hello"));
    }

    @Test
    void getFromEmptyQueueReturnsEmptyList() {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);

        given()
            .when().get("/{account}/{queue}/messages", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .body(not(containsString("<QueueMessage>")));
    }

    @Test
    void getMissingQueueReturns404() {
        given()
            .when().get("/{account}/no-such-queue/messages", ACCOUNT)
            .then().statusCode(404);
    }

    @Test
    void deleteMessage() {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);
        given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>to-delete</MessageText></QueueMessage>")
            .post("/{account}/{queue}/messages", ACCOUNT, QUEUE);

        String messageId = given()
            .get("/{account}/{queue}/messages", ACCOUNT, QUEUE)
            .xmlPath().getString("QueueMessagesList.QueueMessage.MessageId");

        given()
            .when().delete("/{account}/{queue}/messages/{id}?popreceipt=receipt", ACCOUNT, QUEUE, messageId)
            .then().statusCode(204);

        given()
            .when().get("/{account}/{queue}/messages", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .body(not(containsString("to-delete")));
    }

    @Test
    void peekOnlyDoesNotHideMessage() {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);
        given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>peek-me</MessageText></QueueMessage>")
            .post("/{account}/{queue}/messages", ACCOUNT, QUEUE);

        given()
            .when().get("/{account}/{queue}/messages?peekonly=true", ACCOUNT, QUEUE)
            .then().statusCode(200).body(containsString("peek-me"));

        // Message should still be visible after peek
        given()
            .when().get("/{account}/{queue}/messages?peekonly=true", ACCOUNT, QUEUE)
            .then().statusCode(200).body(containsString("peek-me"));
    }

    @Test
    void numOfMessagesValidation() {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);

        given()
            .when().get("/{account}/{queue}/messages?numofmessages=0", ACCOUNT, QUEUE)
            .then().statusCode(400);

        given()
            .when().get("/{account}/{queue}/messages?numofmessages=33", ACCOUNT, QUEUE)
            .then().statusCode(400);
    }

    @Test
    void clearMessages() {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);
        given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>msg1</MessageText></QueueMessage>")
            .post("/{account}/{queue}/messages", ACCOUNT, QUEUE);
        given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>msg2</MessageText></QueueMessage>")
            .post("/{account}/{queue}/messages", ACCOUNT, QUEUE);

        given()
            .when().delete("/{account}/{queue}/messages", ACCOUNT, QUEUE)
            .then().statusCode(204);

        given()
            .when().get("/{account}/{queue}/messages?numofmessages=32", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .body(not(containsString("<QueueMessage>")));
    }
}