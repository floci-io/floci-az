package io.floci.az.services.deployments;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Linked templates and parameter files ({@code linked-templates.md}, "Linked template"): a deployment's
 * {@code templateLink.uri} and {@code parametersLink.uri} are downloaded over HTTP or HTTPS and inlined, so the rest
 * of the deployment sees an inline template. The links themselves stay in the properties, as Azure echoes them.
 *
 * <ul>
 *   <li>A link that cannot be downloaded, or whose content is not a JSON object, is {@code InvalidContentLink}, the
 *       code Azure documents for it; a {@code contentVersion} the link names that the content does not carry is the
 *       same error.</li>
 *   <li>{@code parameters} and {@code parametersLink} together are refused, as Azure documents.</li>
 *   <li>A template spec ({@code templateLink.id}) is refused: floci-az does not serve template specs.</li>
 * </ul>
 *
 * <p>A download, headers and body together, must finish within 30 seconds and stops at 4 MB, Azure's limit on a
 * template's size; a server that sends its headers and then stalls is cut off at the deadline.
 */
@ApplicationScoped
public class LinkedTemplates {

    private static final Logger LOG = Logger.getLogger(LinkedTemplates.class);

    static final String INVALID_CONTENT_LINK = "InvalidContentLink";
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final JsonMapper LENIENT = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build();

    private final Duration timeout;
    private final HttpClient http;

    public LinkedTemplates() {
        this(TIMEOUT);
    }

    /** A downloader with its own deadline, for tests. */
    LinkedTemplates(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * {@code properties} with a linked template and parameter file downloaded and inlined as {@code template} and
     * {@code parameters}; unchanged when it links neither. Throws {@link TemplateException} for a link it cannot use.
     */
    public JsonNode inline(JsonNode properties) {
        if (!(properties instanceof ObjectNode linked)
                || (!properties.has("templateLink") && !properties.has("parametersLink"))) {
            return properties;
        }
        ObjectNode inlined = linked.deepCopy();
        JsonNode templateLink = linked.path("templateLink");
        if (templateLink.isObject()) {
            if (templateLink.has("id")) {
                throw DeploymentTemplate.invalid("The template link names a template spec ('"
                    + templateLink.get("id").asText() + "'), which floci-az does not serve.");
            }
            inlined.set("template", download("template", templateLink));
        }
        JsonNode parametersLink = linked.path("parametersLink");
        if (parametersLink.isObject()) {
            if (linked.has("parameters")) {
                throw DeploymentTemplate.invalid("The deployment has both 'parameters' and 'parametersLink'; give "
                    + "the parameters one way.");
            }
            JsonNode file = download("parameters file", parametersLink);
            inlined.set("parameters", file.has("parameters") ? file.get("parameters") : file);
        }
        return inlined;
    }

    /** The JSON object a link points to, checked against the {@code contentVersion} the link names. */
    private JsonNode download(String what, JsonNode link) {
        String uri = link.path("uri").asText("");
        if (uri.isBlank()) {
            throw new TemplateException(INVALID_CONTENT_LINK, "The " + what + " link has no 'uri'.");
        }
        JsonNode content = parse(uri, fetch(uri));
        String version = link.path("contentVersion").asText("");
        if (!version.isEmpty() && !version.equals(content.path("contentVersion").asText(""))) {
            throw new TemplateException(INVALID_CONTENT_LINK, "The content version '"
                + content.path("contentVersion").asText("") + "' of '" + uri + "' does not match the content version '"
                + version + "' the link names.");
        }
        return content;
    }

    private byte[] fetch(String uri) {
        URI target;
        try {
            target = URI.create(uri);
        } catch (IllegalArgumentException e) {
            throw unavailable(uri, "it is not a valid URI");
        }
        if (!"http".equalsIgnoreCase(target.getScheme()) && !"https".equalsIgnoreCase(target.getScheme())) {
            throw unavailable(uri, "only HTTP and HTTPS links can be downloaded");
        }
        Instant deadline = Instant.now().plus(timeout);
        try {
            HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(target).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] content = readBody(uri, response.body(), deadline);
            if (response.statusCode() != 200) {
                throw unavailable(uri, "it answered " + response.statusCode());
            }
            if (content.length > MAX_BYTES) {
                throw unavailable(uri, "it is larger than 4 MB");
            }
            return content;
        } catch (IOException e) {
            LOG.debugv("Linked template {0} could not be downloaded: {1}", uri, e.getMessage());
            throw unavailable(uri, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(uri, "the download was interrupted");
        }
    }

    /**
     * At most {@code MAX_BYTES + 1} bytes of {@code body}, read by the deadline. The request timeout ends once the
     * headers arrive, so the body is read on a thread of its own and the stream closed at the deadline, which ends a
     * read that a stalled server would otherwise hold forever.
     */
    private byte[] readBody(String uri, InputStream body, Instant deadline) throws IOException, InterruptedException {
        FutureTask<byte[]> read = new FutureTask<>(() -> body.readNBytes(MAX_BYTES + 1));
        Thread.ofVirtual().name("linked-template-read").start(read);
        try {
            return read.get(Math.max(0, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            read.cancel(true);
            throw unavailable(uri, "it did not finish downloading within " + timeout.toSeconds() + " seconds");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        } finally {
            body.close();
        }
    }

    private static JsonNode parse(String uri, byte[] content) {
        try {
            JsonNode parsed = LENIENT.readTree(content);
            if (parsed instanceof ObjectNode) {
                return parsed;
            }
        } catch (IOException e) {
            LOG.debugv("Linked content {0} is not JSON: {1}", uri, e.getMessage());
        }
        throw new TemplateException(INVALID_CONTENT_LINK, "The content of '" + uri + "' is not a JSON object.");
    }

    private static TemplateException unavailable(String uri, String reason) {
        return new TemplateException(INVALID_CONTENT_LINK, "Unable to download deployment content from '" + uri
            + "': " + reason + ".");
    }
}
