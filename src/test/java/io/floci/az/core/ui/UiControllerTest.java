package io.floci.az.core.ui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UiControllerTest {

    @Test
    void theRedirectKeepsTheBrowsersHostAndSwapsInTheConsolePort() {
        assertEquals("http://localhost:4500/", UiController.redirectUrl("localhost:4577", 4500, "/"));
        assertEquals("http://floci-az:4500/", UiController.redirectUrl("floci-az", 4500, "/"));
        assertEquals("http://192.168.1.20:4501/", UiController.redirectUrl("192.168.1.20:4577", 4501, "/"));
    }

    @Test
    void anIpv6HostKeepsItsBrackets() {
        assertEquals("http://[::1]:4500/", UiController.redirectUrl("[::1]:4577", 4500, "/"));
        assertEquals("http://[fd00::5]:4500/", UiController.redirectUrl("[fd00::5]", 4500, "/"));
    }

    @Test
    void aMissingHostFallsBackToLocalhost() {
        assertEquals("http://localhost:4500/", UiController.redirectUrl(null, 4500, "/"));
        assertEquals("http://localhost:4500/", UiController.redirectUrl("  ", 4500, "/"));
    }

    @Test
    void theRedirectPointsAtTheConsolesLandingPath() {
        assertEquals("http://localhost:4500/console/azure",
                UiController.redirectUrl("localhost:4577", 4500, "/console/azure"));
        assertEquals("http://localhost:4500/console/azure",
                UiController.redirectUrl("localhost:4577", 4500, "console/azure"));
        assertEquals("http://[::1]:4500/console/azure",
                UiController.redirectUrl("[::1]:4577", 4500, "/console/azure"));
        assertEquals("http://localhost:4500/", UiController.redirectUrl("localhost:4577", 4500, null));
    }

    @Test
    void theUrlIsOfferedAsSoonAsTheConsoleHasStartedNotOnlyWhenReady() {
        Map<String, Object> body = UiController.statusBody(
                new FlociUiManager.UiStatus(true, true, false, 4500, "/console/azure", "cannot reach floci-az"),
                "localhost:4577");

        assertEquals(false, body.get("ready"));
        assertEquals("http://localhost:4500/console/azure", body.get("url"));
        assertEquals("cannot reach floci-az", body.get("error"));
    }

    @Test
    void thereIsNoUrlBeforeTheConsoleHasStarted() {
        Map<String, Object> body = UiController.statusBody(
                new FlociUiManager.UiStatus(true, false, false, 0, null, null), "localhost:4577");

        assertNull(body.get("url"));
    }
}
