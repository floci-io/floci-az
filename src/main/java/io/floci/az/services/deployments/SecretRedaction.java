package io.floci.az.services.deployments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Key Vault secrets one deployment read, kept so that no error message it produces quotes them. A function that
 * fails on a value quotes it ({@code int()} of a non-numeric string, for one), and a fetched secret, unlike a value
 * the caller supplied, was never in the request. Each secret is redacted whole, and for a {@code secureObject} every
 * text value inside it as well.
 */
final class SecretRedaction {

    static final String REDACTED = "[secure value]";

    private static final Logger LOG = Logger.getLogger(SecretRedaction.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> secrets = new CopyOnWriteArrayList<>();

    /** Records a secret read for the deployment. */
    void add(String secret) {
        if (secret == null || secret.isEmpty()) {
            return;
        }
        secrets.add(secret);
        try {
            JsonNode parsed = MAPPER.readTree(secret);
            if (parsed != null && parsed.isContainerNode()) {
                addTextValues(parsed);
            }
        } catch (JsonProcessingException e) {
            // Not JSON, so the secret is only ever redacted whole; the parser's message quotes it, so it is not logged.
            LOG.debug("A Key Vault secret of the deployment is plain text");
        }
    }

    private void addTextValues(JsonNode node) {
        if (node.isTextual() && !node.asText().isEmpty()) {
            secrets.add(node.asText());
        }
        node.forEach(this::addTextValues);
    }

    /** {@code message} with every recorded secret replaced, the longest first so a secret inside another goes too. */
    String redact(String message) {
        if (message == null || secrets.isEmpty()) {
            return message;
        }
        String redacted = message;
        for (String secret : secrets.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            redacted = redacted.replace(secret, REDACTED);
        }
        return redacted;
    }

    /** {@code e} with its message redacted, or {@code e} itself when it quotes no secret. */
    TemplateException redact(TemplateException e) {
        String redacted = redact(e.getMessage());
        if (redacted == null || redacted.equals(e.getMessage())) {
            return e;
        }
        return new TemplateException(e.code(), redacted);
    }
}
