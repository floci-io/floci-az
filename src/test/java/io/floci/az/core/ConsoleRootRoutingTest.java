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
        assertTrue(AzureRoutingFilter.wantsConsolePage("GET", "/", BROWSER_ACCEPT));
        assertTrue(AzureRoutingFilter.wantsConsolePage("GET", "", BROWSER_ACCEPT));
        assertTrue(AzureRoutingFilter.wantsConsolePage("HEAD", "/", "TEXT/HTML"));
    }

    @Test
    void anSdkReadingTheRootKeepsTheAzureAnswer() {
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/", "application/json"));
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/", "*/*"));
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/", null));
    }

    @Test
    void onlyTheRootAndOnlyReadsAreRedirected() {
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "/devstoreaccount1", BROWSER_ACCEPT));
        assertFalse(AzureRoutingFilter.wantsConsolePage("GET", "devstoreaccount1/container", BROWSER_ACCEPT));
        assertFalse(AzureRoutingFilter.wantsConsolePage("POST", "/", BROWSER_ACCEPT));
        assertFalse(AzureRoutingFilter.wantsConsolePage("PUT", "/", BROWSER_ACCEPT));
    }
}
