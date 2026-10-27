package io.floci.az.core.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The landing page, {@code ui/index.html}: floci-aws's page, with floci-az's data. It has two views, the
 * dashboard at floci-az's root and the wait view at {@code /_floci/ui}.
 *
 * <p>{@code /_floci/ui/status} forwards {@code error} from the console's own health JSON and from Docker daemon
 * messages, so both views must render that text as text: the wait view through {@code textContent}, the
 * dashboard through {@code escapeHtml}.
 */
class ConsolePageTest {

    private static final Pattern ERROR_HTML_ASSIGNMENT = Pattern.compile("\\(\"launch-error\"\\)\\.innerHTML\\s*=");
    private static final Pattern ERROR_TEXT_ASSIGNMENT = Pattern.compile("\\(\"launch-error\"\\)\\.textContent\\s*=");
    private static final Pattern RAW_ERROR_CONCATENATION = Pattern.compile("\\+\\s*(?:ui|s)\\.error\\b");

    private static String page() throws IOException {
        try (InputStream in = ConsolePageTest.class.getClassLoader().getResourceAsStream(UiController.PAGE_RESOURCE)) {
            assertNotNull(in, UiController.PAGE_RESOURCE + " must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theWaitViewRendersTheLaunchErrorAsText() throws IOException {
        String page = page();

        assertFalse(ERROR_HTML_ASSIGNMENT.matcher(page).find(), "the launch error must not go through innerHTML");
        assertTrue(ERROR_TEXT_ASSIGNMENT.matcher(page).find(), "the launch error must go through textContent");
    }

    @Test
    void theDashboardEscapesTheStatusErrorBeforeConcatenatingIt() throws IOException {
        String page = page();

        assertFalse(RAW_ERROR_CONCATENATION.matcher(page).find(), "the status error must not be concatenated raw");
        assertTrue(page.contains("escapeHtml(ui.error)"));
    }

    @Test
    void serviceNamesAreEscapedBeforeTheyBecomeMarkup() throws IOException {
        assertTrue(page().contains("escapeHtml(s.name)"));
    }

    @Test
    void onlyTheUiPathShowsTheWaitView() throws IOException {
        // The root is served this page through an internal rewrite, so the browser's path still reads "/".
        assertTrue(page().contains("/\\/_floci\\/ui\\/?$/.test(location.pathname) ? \"starting\" : \"dashboard\""));
    }

    @Test
    void theDashboardReadsFlociAzsManifestHealthAndConsoleStatus() throws IOException {
        String page = page();

        assertTrue(page.contains("getJson(\"/_floci/info\")"));
        assertTrue(page.contains("getJson(\"/_floci/health\", 503)"), "a DOWN floci-az still answers with a body");
        assertTrue(page.contains("/_floci/ui/status"));
        assertTrue(page.contains("<span class=\"emulator\" id=\"emulator\">floci-az</span>"));
    }
}
