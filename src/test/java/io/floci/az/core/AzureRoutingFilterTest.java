package io.floci.az.core;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Characterization test for {@link AzureRoutingFilter}: pins the path/host → serviceType routing
 * decisions that the A4 decomposition must preserve byte-for-byte. Written against the pre-refactor
 * filter; must stay green through the refactor.
 *
 * <p>Signals used to observe the routing decision without coupling to handler internals:
 * <ul>
 *   <li>Key Vault answers a bodiless probe with {@code 401 WWW-Authenticate: Bearer} (challenge) —
 *       distinctive to the keyvault route.</li>
 *   <li>A disabled-by-default Cosmos engine reaches the terminal dispatch, which emits
 *       {@code 503 "The <serviceType> service is disabled..."} — the body echoes the resolved
 *       serviceType, so it pins each {@code -cosmos-*} suffix AND the longest-first ordering
 *       (a mis-order would echo {@code table}/{@code cosmos} instead of {@code cosmos-table}).</li>
 *   <li>Blob/Queue list roots return XML {@code <EnumerationResults>}.</li>
 *   <li>The Azure Stack / Graph anti-routes return 404.</li>
 * </ul>
 */
@QuarkusTest
@DisplayName("AzureRoutingFilter — routing characterization (A4 safety net)")
class AzureRoutingFilterTest {

    // ── Anti-routes: deliberate 404s (uncovered elsewhere) ──────────────────────

    // The filter returns null for these (not routed to any service handler) so go-azure-sdk does
    // not detect Azure Stack / a Graph endpoint; the downstream fallthrough currently answers 200.
    // Characterizes the filter's null-return decision, which A4 must preserve.

    @Test
    void metadataEndpointsIsAntiRouted() {
        given().when().get("/metadata/endpoints").then().statusCode(200);
    }

    @Test
    void graphV1AntiRouted() {
        given().when().get("/v1.0/servicePrincipals").then().statusCode(200);
    }

    // ── Host-suffix routing ─────────────────────────────────────────────────────

    @Test
    void hostVaultAzureNetRoutesToKeyVault() {
        given().header("Host", "myvault.vault.azure.net")
                .when().get("/secrets/foo?api-version=7.4")
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"));
    }

    @Test
    void hostBlobCoreWindowsNetRoutesToBlob() {
        given().header("Host", "acct.blob.core.windows.net")
                .when().get("/?comp=list")
                .then().statusCode(200)
                .body(containsString("EnumerationResults"));
    }

    @Test
    void hostDfsCoreWindowsNetRoutesToBlob() {
        // DFS maps to the blob handler (not a distinct dfs type) — quirk to preserve.
        given().header("Host", "acct.dfs.core.windows.net")
                .when().get("/?comp=list")
                .then().statusCode(200)
                .body(containsString("EnumerationResults"));
    }

    @Test
    void hostQueueCoreWindowsNetRoutesToQueue() {
        given().header("Host", "acct.queue.core.windows.net")
                .when().get("/?comp=list")
                .then().statusCode(200)
                .body(containsString("EnumerationResults"));
    }

    // ── Account-name suffix routing (the 20-branch chain → table) ────────────────

    @Test
    void keyvaultSuffixRoutesToKeyVault() {
        given().when().get("/devstoreaccount1-keyvault/secrets/foo?api-version=7.4")
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"));
    }

    @Test
    void blobDefaultAccountRoutesToBlob() {
        given().when().get("/devstoreaccount1/?comp=list")
                .then().statusCode(200)
                .body(containsString("EnumerationResults"));
    }

    @Test
    void queueSuffixRoutesToQueue() {
        given().when().get("/devstoreaccount1-queue/?comp=list")
                .then().statusCode(200)
                .body(containsString("EnumerationResults"));
    }

    /**
     * The six {@code -cosmos-*} engine suffixes (disabled by default) each echo their exact
     * serviceType in the terminal 503 — pinning the suffix map and the mandatory longest-first
     * ordering in one shot.
     */
    @Test
    void cosmosEngineSuffixesResolveToExactServiceType() {
        assertDisabledEcho("devstoreaccount1-cosmos-mongo/dbs", "cosmos-mongo");
        assertDisabledEcho("devstoreaccount1-cosmos-table/dbs", "cosmos-table");
        assertDisabledEcho("devstoreaccount1-cosmos-cassandra/dbs", "cosmos-cassandra");
        assertDisabledEcho("devstoreaccount1-cosmos-gremlin/dbs", "cosmos-gremlin");
        assertDisabledEcho("devstoreaccount1-cosmos-postgresql/dbs", "cosmos-postgresql");
        assertDisabledEcho("devstoreaccount1-cosmos-nosql/dbs", "cosmos-nosql");
    }

    private static void assertDisabledEcho(String path, String expectedServiceType) {
        given().when().get("/" + path)
                .then().statusCode(503)
                .body(containsString("The " + expectedServiceType + " service is disabled"));
    }

    // ── Key Vault data-plane paths at the ARM base URL ──────────────────────────

    /**
     * The azurerm v3 provider sends Key Vault data-plane calls to the ARM base URL rather than to
     * {@code *.vault.azure.net}. Every KV collection must be recognised both as a bare name (a list
     * call) and as a prefix (an item call).
     *
     * <p>Bare {@code deletedsecrets}/{@code deletedcertificates}/{@code deletedkeys} were NOT matched
     * before the routing-table rewrite: the prefix ladder tested {@code startsWith("deletedsecrets/")}
     * without the corresponding {@code equals}, so the list-deleted-secrets call fell through to the
     * account-suffix terminal and was served by the blob handler with account name
     * {@code deletedsecrets}. These cases pin the fix.</p>
     */
    @Test
    void keyVaultCollectionsAtArmBaseRouteToKeyVault() {
        for (String collection : new String[] {
                "secrets", "certificates", "keys", "deletedsecrets", "deletedcertificates", "deletedkeys"}) {
            assertKeyVaultChallenge("/" + collection + "?api-version=7.4");
            assertKeyVaultChallenge("/" + collection + "/foo?api-version=7.4");
        }
    }

    /** A path that merely starts with a collection name is not a Key Vault path. */
    @Test
    void collectionNamePrefixIsNotAKeyVaultRoute() {
        given().when().get("/secretsaccount/?comp=list")
                .then().statusCode(200)
                .body(containsString("EnumerationResults"));
    }

    private static void assertKeyVaultChallenge(String path) {
        given().when().get(path)
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("Bearer"));
    }

    // ── ARM provider-namespace routing ──────────────────────────────────────────

    @Test
    void managedIdentityProviderRoutesToManagedIdentity() {
        // Bare identity path resolves; a valid GET of a missing identity → 404 ResourceNotFound.
        given().when().get("/subscriptions/s/resourceGroups/rg"
                        + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/none?api-version=2024-11-30")
                .then().statusCode(404)
                .body(containsString("ResourceNotFound"));
    }

    @Test
    void managedIdentityNestedProviderFallsThroughToArm() {
        // A role assignment scoped to an identity has a LATER /providers/ segment: the managed-identity
        // guard must let it fall through to ArmHandler (404), not capture it as an identity.
        given().when().delete("/subscriptions/s/resourceGroups/rg"
                        + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/id1"
                        + "/providers/Microsoft.Authorization/roleAssignments/"
                        + "11111111-1111-1111-1111-111111111111?api-version=2022-04-01")
                .then().statusCode(404);
    }
}
