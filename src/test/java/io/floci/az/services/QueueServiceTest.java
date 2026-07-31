package io.floci.az.services;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
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
    void getQueueServicePropertiesReturnsXml() {
        given()
            .when().get("/{account}?restype=service&comp=properties", ACCOUNT)
            .then()
            .statusCode(200)
            .contentType("application/xml")
            .body(startsWith("<StorageServiceProperties>"))
            .body("StorageServiceProperties.Logging.Version", equalTo("1.0"));
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
    void createExistingQueuePreservesMetadata() {
        given()
            .header("x-ms-meta-owner", "sdk")
            .put("/{account}/{queue}", ACCOUNT, QUEUE)
            .then().statusCode(201);

        given()
            .put("/{account}/{queue}", ACCOUNT, QUEUE)
            .then().statusCode(204);

        given()
            .when().get("/{account}/{queue}?comp=metadata", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .header("x-ms-meta-owner", equalTo("sdk"));
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

        Response dequeue = given()
            .get("/{account}/{queue}/messages", ACCOUNT, QUEUE);
        String messageId = dequeue.xmlPath().getString("QueueMessagesList.QueueMessage.MessageId");
        String popReceipt = dequeue.xmlPath().getString("QueueMessagesList.QueueMessage.PopReceipt");

        given()
            .when().delete("/{account}/{queue}/messages/{id}?popreceipt={receipt}", ACCOUNT, QUEUE, messageId, popReceipt)
            .then().statusCode(204);

        given()
            .when().get("/{account}/{queue}/messages", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .body(not(containsString("to-delete")));
    }

    @Test
    void deleteMessageWithWrongPopReceiptReturns400() {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);
        given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>receipt-check</MessageText></QueueMessage>")
            .post("/{account}/{queue}/messages", ACCOUNT, QUEUE);

        String messageId = given()
            .get("/{account}/{queue}/messages", ACCOUNT, QUEUE)
            .xmlPath().getString("QueueMessagesList.QueueMessage.MessageId");

        given()
            .when().delete("/{account}/{queue}/messages/{id}?popreceipt=wrong", ACCOUNT, QUEUE, messageId)
            .then()
            .statusCode(400)
            .header("x-ms-error-code", equalTo("PopReceiptMismatch"));
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

    @Test
    void setAndGetQueueMetadata() {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);

        given()
            .header("x-ms-meta-owner", "sdk")
            .header("x-ms-meta-purpose", "compat")
            .when().put("/{account}/{queue}?comp=metadata", ACCOUNT, QUEUE)
            .then().statusCode(204);

        given()
            .when().get("/{account}/{queue}?comp=metadata", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .header("x-ms-meta-owner", equalTo("sdk"))
            .header("x-ms-meta-purpose", equalTo("compat"))
            .header("x-ms-approximate-messages-count", equalTo("0"));
    }

    @Test
    void listQueuesIncludesMetadataWhenRequested() {
        given()
            .header("x-ms-meta-owner", "sdk")
            .put("/{account}/{queue}", ACCOUNT, QUEUE);

        given()
            .when().get("/{account}?comp=list&include=metadata", ACCOUNT)
            .then()
            .statusCode(200)
            .body(containsString("test-queue"))
            .body(containsString("owner"))
            .body(containsString("sdk"));
    }

    @Test
    void updateMessageChangesTextAndPopReceipt() {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);
        given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>before</MessageText></QueueMessage>")
            .post("/{account}/{queue}/messages", ACCOUNT, QUEUE);

        Response dequeue = given()
            .get("/{account}/{queue}/messages", ACCOUNT, QUEUE);
        String messageId = dequeue.xmlPath().getString("QueueMessagesList.QueueMessage.MessageId");
        String popReceipt = dequeue.xmlPath().getString("QueueMessagesList.QueueMessage.PopReceipt");

        String newReceipt = given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>after</MessageText></QueueMessage>")
            .when().put("/{account}/{queue}/messages/{id}?popreceipt={receipt}&visibilitytimeout=0",
                    ACCOUNT, QUEUE, messageId, popReceipt)
            .then()
            .statusCode(204)
            .header("x-ms-popreceipt", not(isEmptyOrNullString()))
            .extract().header("x-ms-popreceipt");

        given()
            .when().delete("/{account}/{queue}/messages/{id}?popreceipt={receipt}", ACCOUNT, QUEUE, messageId, popReceipt)
            .then().statusCode(400);

        given()
            .when().get("/{account}/{queue}/messages?peekonly=true", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .body(containsString("after"))
            .body(not(containsString("before")));

        given()
            .when().delete("/{account}/{queue}/messages/{id}?popreceipt={receipt}", ACCOUNT, QUEUE, messageId, newReceipt)
            .then().statusCode(204);
    }

    @Test
    void messageTtlExpiresMessages() throws Exception {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);
        given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>short-lived</MessageText></QueueMessage>")
            .post("/{account}/{queue}/messages?messagettl=1", ACCOUNT, QUEUE);

        Thread.sleep(1200);

        given()
            .when().get("/{account}/{queue}/messages?peekonly=true", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .body(not(containsString("short-lived")));
    }

    @Test
    void enqueueVisibilityTimeoutHidesMessageInitially() throws Exception {
        given().put("/{account}/{queue}", ACCOUNT, QUEUE);
        given()
            .contentType("application/xml")
            .body("<QueueMessage><MessageText>delayed</MessageText></QueueMessage>")
            .post("/{account}/{queue}/messages?visibilitytimeout=1&messagettl=5", ACCOUNT, QUEUE);

        given()
            .when().get("/{account}/{queue}/messages?peekonly=true", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .body(not(containsString("delayed")));

        Thread.sleep(1200);

        given()
            .when().get("/{account}/{queue}/messages?peekonly=true", ACCOUNT, QUEUE)
            .then()
            .statusCode(200)
            .body(containsString("delayed"));
    }
}
