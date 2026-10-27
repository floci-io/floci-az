package io.floci.az.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A browser opening the emulator's root gets the web console page; everything else at {@code /} keeps its
 * Azure answer. The Cosmos Java SDK reads its DatabaseAccount at {@code /} with {@code Accept: application/json}.
 */
class ConsoleRootRoutingTest {

    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    @Test
    void aBrowserOpeningTheRootGetsTheConsolePage() {
        assertTrue(AzureRoutingFilter.wantsConsolePage("GET", "/", null, BROWSER_ACCEPT, false));
        assertTrue(AzureRoutingFilter.wantsConsolePage("GET", "", null, BROWSER_ACCEPT, false));
        assertTrue(AzureRoutingFilter.wantsConsolePage("HEAD", "/", null, "TEXT/HTML", false));
    }

    @Test
    void anSdkReadingTheRootKeepsTheAzureAnswer() {
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/", null, "application/json", false));
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/", null, "*/*", false));
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/", null, null, false));
    }

    @Test
    void onlyTheRootAndOnlyReadsAreRedirected() {
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/devstoreaccount1", null, BROWSER_ACCEPT, false));
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "devstoreaccount1/container", null, BROWSER_ACCEPT, false));
        assertFalse(AzureRoutingFilter.wantsConsolePage("POST", "/", null, BROWSER_ACCEPT, false));
        assertFalse(AzureRoutingFilter.wantsConsolePage("PUT", "/", null, BROWSER_ACCEPT, false));
    }

    @Test
    void aBrowsersAzureCallAtTheRootKeepsTheAzureAnswer() {
        // Host-style List Containers (devstoreaccount1.blob.localhost/?comp=list) opened in a browser.
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/", "comp=list", BROWSER_ACCEPT, false));
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/", null, BROWSER_ACCEPT, true));
    }
}
