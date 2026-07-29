package io.floci.az.compat;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailAddress;
import com.azure.communication.email.models.EmailMessage;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.communication.email.models.EmailSendStatus;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.util.polling.SyncPoller;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Communication Services Email Compatibility")
class EmailCompatibilityTest {

    private static final String SENDER = "DoNotReply@example.com";
    private static final String RECIPIENT = "dev@example.com";

    private EmailClient client;

    @BeforeAll
    void setUp() throws Exception {
        EmulatorConfig.assumeEmulatorRunning();
        EmulatorConfig.installEmulatorTlsCert();
        Assumptions.assumeTrue(
                System.getProperty("javax.net.ssl.trustStore") != null,
                "requires FLOCI_AZ_TLS_ENABLED=true — the ACS credential only signs https URLs");
        client = buildClient(null);
    }

    @Test
    @DisplayName("beginSend completes through the SDK's long-running-operation poller")
    void beginSendCompletesThroughTheSdkPoller() {
        SyncPoller<EmailSendResult, EmailSendResult> poller = client.beginSend(message("SDK poller"));
        poller.waitForCompletion();

        EmailSendResult result = poller.getFinalResult();
        assertNotNull(result);
        assertEquals(EmailSendStatus.SUCCEEDED, result.getStatus());
        assertNotNull(result.getId());
        assertNull(result.getError());
    }

    @Test
    @DisplayName("a caller-supplied Operation-Id is adopted as the operation id")
    void callerSuppliedOperationIdIsAdopted() {
        String operationId = UUID.randomUUID().toString();

        SyncPoller<EmailSendResult, EmailSendResult> poller =
                buildClient(operationId).beginSend(message("Operation-Id echo"));
        poller.waitForCompletion();

        assertEquals(operationId, poller.getFinalResult().getId());
    }

    private EmailClient buildClient(String operationId) {
        String endpoint = EmulatorConfig.httpBase().replace("http://", "https://");
        EmailClientBuilder builder = new EmailClientBuilder()
                .connectionString("endpoint=" + endpoint + "/;accesskey=" + EmulatorConfig.DEV_KEY);
        if (operationId != null) {
            builder.addPolicy(new StaticOperationIdPolicy(operationId));
        }
        return builder.buildClient();
    }

    private static EmailMessage message(String subject) {
        return new EmailMessage()
                .setSenderAddress(SENDER)
                .setSubject(subject)
                .setBodyHtml("<p>floci-az compatibility test</p>")
                .setToRecipients(List.of(new EmailAddress(RECIPIENT)));
    }

    private record StaticOperationIdPolicy(String operationId) implements HttpPipelinePolicy {

        private static final HttpHeaderName OPERATION_ID = HttpHeaderName.fromString("Operation-Id");

        @Override
        public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
            context.getHttpRequest().getHeaders().set(OPERATION_ID, operationId);
            return next.process();
        }
    }
}
