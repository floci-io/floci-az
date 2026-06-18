package io.floci.az.services.email;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * POJOs representing Azure Communication Services Email data structures.
 *
 * <p>Models the {@code POST /emails:send} request/response payloads and the
 * {@code GET /emails/operations/{id}} status polling response, matching the
 * ACS Email REST API surface (api-version 2023-03-31 and later).
 */
public final class EmailModels {

    private EmailModels() {} // utility class

    // ── Request models ─────────────────────────────────────────────────────

    /**
     * Top-level request body for {@code POST /emails:send}.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailSendRequest {
        @JsonProperty("senderAddress")
        private String senderAddress;

        @JsonProperty("content")
        private EmailContent content;

        @JsonProperty("recipients")
        private EmailRecipients recipients;

        @JsonProperty("attachments")
        private List<EmailAttachment> attachments;

        @JsonProperty("replyTo")
        private List<EmailAddress> replyTo;

        @JsonProperty("userEngagementTrackingDisabled")
        private boolean userEngagementTrackingDisabled;

        @JsonProperty("headers")
        private Map<String, String> headers;

        public String getSenderAddress() { return senderAddress; }
        public void setSenderAddress(String senderAddress) { this.senderAddress = senderAddress; }
        public EmailContent getContent() { return content; }
        public void setContent(EmailContent content) { this.content = content; }
        public EmailRecipients getRecipients() { return recipients; }
        public void setRecipients(EmailRecipients recipients) { this.recipients = recipients; }
        public List<EmailAttachment> getAttachments() { return attachments; }
        public void setAttachments(List<EmailAttachment> attachments) { this.attachments = attachments; }
        public List<EmailAddress> getReplyTo() { return replyTo; }
        public void setReplyTo(List<EmailAddress> replyTo) { this.replyTo = replyTo; }
        public boolean isUserEngagementTrackingDisabled() { return userEngagementTrackingDisabled; }
        public void setUserEngagementTrackingDisabled(boolean v) { this.userEngagementTrackingDisabled = v; }
        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailContent {
        @JsonProperty("subject")
        private String subject;

        @JsonProperty("plainText")
        private String plainText;

        @JsonProperty("html")
        private String html;

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getPlainText() { return plainText; }
        public void setPlainText(String plainText) { this.plainText = plainText; }
        public String getHtml() { return html; }
        public void setHtml(String html) { this.html = html; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailRecipients {
        @JsonProperty("to")
        private List<EmailAddress> to;

        @JsonProperty("cc")
        private List<EmailAddress> cc;

        @JsonProperty("bcc")
        private List<EmailAddress> bcc;

        public List<EmailAddress> getTo() { return to; }
        public void setTo(List<EmailAddress> to) { this.to = to; }
        public List<EmailAddress> getCc() { return cc; }
        public void setCc(List<EmailAddress> cc) { this.cc = cc; }
        public List<EmailAddress> getBcc() { return bcc; }
        public void setBcc(List<EmailAddress> bcc) { this.bcc = bcc; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailAddress {
        @JsonProperty("address")
        private String address;

        @JsonProperty("displayName")
        private String displayName;

        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailAttachment {
        @JsonProperty("name")
        private String name;

        @JsonProperty("contentType")
        private String contentType;

        @JsonProperty("contentInBase64")
        private String contentInBase64;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public String getContentInBase64() { return contentInBase64; }
        public void setContentInBase64(String contentInBase64) { this.contentInBase64 = contentInBase64; }
    }

    // ── Response / status models ───────────────────────────────────────────

    /**
     * Stored representation of a captured email (request payload + operation metadata).
     * Serialised to JSON and persisted in the {@code StorageBackend}.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapturedEmail {
        private String operationId;
        private String messageId;
        private String status;  // Running → Succeeded
        private Instant sentAt;

        // The original send request payload
        private EmailSendRequest request;

        public String getOperationId() { return operationId; }
        public void setOperationId(String operationId) { this.operationId = operationId; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getSentAt() { return sentAt; }
        public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
        public EmailSendRequest getRequest() { return request; }
        public void setRequest(EmailSendRequest request) { this.request = request; }
    }
}
