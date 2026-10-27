package io.floci.az.core.ui;

import io.floci.az.core.docker.HostLiterals;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /_floci/ui} starts the web console and serves the page that waits for it;
 * {@code GET /_floci/ui/status} is what that page polls.
 *
 * <p>The console runs on its own published port, so the browser is redirected to it rather than proxied: the
 * redirect URL keeps the host the browser used for floci-az, swaps in the console's port, and points at the
 * console's Azure view.
 */
@Path("_floci/ui")
public class UiController {

    private static final Logger LOG = Logger.getLogger(UiController.class);
    static final String PAGE_RESOURCE = "ui/index.html";

    private final FlociUiManager manager;
    private volatile String page;

    @Inject
    public UiController(FlociUiManager manager) {
        this.manager = manager;
    }

    /** The wait view: starts the console and polls until it can redirect to it. */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response ui() {
        manager.ensureStartedAsync();
        return html();
    }

    /**
     * The dashboard, which a browser opening floci-az's root is served: the same page, without starting the
     * console. The page picks its view from the browser's path, which stays {@code /}.
     */
    @GET
    @Path("dashboard")
    @Produces(MediaType.TEXT_HTML)
    public Response dashboard() {
        return html();
    }

    private Response html() {
        return Response.ok(page(), MediaType.TEXT_HTML_TYPE.withCharset(StandardCharsets.UTF_8.name())).build();
    }

    @GET
    @Path("status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@HeaderParam("Host") String host) {
        FlociUiManager.UiStatus status = manager.status();
        return Response.ok(statusBody(status, host)).build();
    }

    /**
     * {@code {ready, url, error}}. {@code url} is present as soon as the console has started, not only once it
     * reports ready, so the page can offer to open a console that runs but cannot reach floci-az.
     */
    static Map<String, Object> statusBody(FlociUiManager.UiStatus status, String host) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", status.ready());
        body.put("url", status.hostPort() > 0 ? redirectUrl(host, status.hostPort(), status.landingPath()) : null);
        body.put("error", status.error());
        return body;
    }

    /** {@code http://<the request's host, without its port>:<port><path>}, keeping IPv6 brackets. */
    static String redirectUrl(String hostHeader, int port, String path) {
        String landing = path == null || path.isBlank() ? "/" : path.startsWith("/") ? path : "/" + path;
        return "http://" + HostLiterals.forUrl(hostWithoutPort(hostHeader)) + ":" + port + landing;
    }

    static String hostWithoutPort(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return "localhost";
        }
        String host = hostHeader.trim();
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            return close > 0 ? host.substring(1, close) : host;
        }
        int colon = host.indexOf(':');
        if (colon >= 0 && host.indexOf(':', colon + 1) < 0) {
            return host.substring(0, colon);
        }
        return host;
    }

    private String page() {
        String cached = page;
        if (cached != null) {
            return cached;
        }
        try (InputStream in = UiController.class.getClassLoader().getResourceAsStream(PAGE_RESOURCE)) {
            if (in == null) {
                LOG.errorv("Web console page {0} is missing from the classpath", PAGE_RESOURCE);
                return "<!doctype html><title>floci-az web console</title><p>The console page is missing.</p>";
            }
            cached = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            page = cached;
            return cached;
        } catch (IOException e) {
            LOG.errorv(e, "Could not read web console page {0}", PAGE_RESOURCE);
            return "<!doctype html><title>floci-az web console</title><p>The console page could not be read.</p>";
        }
    }
}
