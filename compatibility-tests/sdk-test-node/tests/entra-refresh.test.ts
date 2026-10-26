import {
  CryptoProvider,
  INetworkModule,
  NetworkRequestOptions,
  NetworkResponse,
  PublicClientApplication,
} from "@azure/msal-node";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const TENANT = "00000000-0000-0000-0000-000000000002";
const CLIENT = "11111111-1111-1111-1111-111111111111";
const REDIRECT = "https://app.local/callback";
const SCOPES = ["openid", "profile", "email", "offline_access"];

// MSAL requires an HTTPS authority. Its supported network adapter maps only this emulator's
// requests to the configured local transport, as the Java compatibility suite does too.
function localUrl(url: string): string {
  const requested = new URL(url);
  const emulator = new URL(BASE);
  if (requested.host !== emulator.host) throw new Error("Unexpected external MSAL request");
  requested.protocol = emulator.protocol;
  return requested.toString();
}

test("MSAL renews a PKCE session from its refresh-token cache", async () => {
  const discovery = await (await fetch(`${BASE}/${TENANT}/v2.0/.well-known/openid-configuration`)).json();
  expect(discovery.grant_types_supported).toContain("refresh_token");
  expect(discovery.scopes_supported).toContain("offline_access");
  const usedRefreshTokens: string[] = [];

  async function request<T>(url: string, method: string, options?: NetworkRequestOptions): Promise<NetworkResponse<T>> {
    if (method === "POST") {
      const form = new URLSearchParams(options?.body);
      if (form.get("grant_type") === "refresh_token") usedRefreshTokens.push(form.get("refresh_token")!);
    }
    const response = await fetch(localUrl(url), { method, headers: options?.headers, body: options?.body });
    return {
      status: response.status,
      headers: Object.fromEntries(response.headers.entries()),
      body: (await response.json()) as T,
    };
  }

  const network: INetworkModule = {
    sendGetRequestAsync: <T>(url: string, options?: NetworkRequestOptions) => request<T>(url, "GET", options),
    sendPostRequestAsync: <T>(url: string, options?: NetworkRequestOptions) => request<T>(url, "POST", options),
  };
  const authority = new URL(BASE);
  authority.protocol = "https:";
  const client = new PublicClientApplication({
    auth: {
      clientId: CLIENT,
      authority: `${authority.origin}/${TENANT}`,
      knownAuthorities: [authority.host],
      authorityMetadata: JSON.stringify(discovery),
    },
    system: { networkClient: network },
  });
  const { verifier, challenge } = await new CryptoProvider().generatePkceCodes();
  const authorization = await client.getAuthCodeUrl({
    scopes: SCOPES,
    redirectUri: REDIRECT,
    codeChallenge: challenge,
    codeChallengeMethod: "S256",
  });
  const redirect = await fetch(localUrl(authorization), { redirect: "manual" });
  expect(redirect.status).toBe(302);
  const code = new URL(redirect.headers.get("location")!).searchParams.get("code")!;
  const initial = await client.acquireTokenByCode({ code, scopes: SCOPES, redirectUri: REDIRECT, codeVerifier: verifier });
  expect(initial.account).not.toBeNull();
  expect(usedRefreshTokens).toHaveLength(0);

  // Enable against an emulator with TOKEN_LIFETIME_SECONDS=2 to exercise actual expiry too.
  if (process.env.FLOCI_AZ_ENTRA_EXPIRE_ACCESS_TOKEN === "true") {
    const claims = JSON.parse(Buffer.from(initial.accessToken.split(".")[1], "base64url").toString("utf8"));
    const wait = Math.max(0, claims.exp * 1000 - Date.now() + 50);
    expect(wait).toBeLessThan(10000);
    await new Promise((resolve) => setTimeout(resolve, wait));
    const expired = await fetch(`${BASE}/oidc/userinfo`, { headers: { Authorization: `Bearer ${initial.accessToken}` } });
    expect(expired.status).toBe(401);
  }

  const renewed = await client.acquireTokenSilent({ account: initial.account!, scopes: SCOPES, forceRefresh: true });
  expect(renewed.accessToken === initial.accessToken).toBe(false);
  expect(renewed.account?.homeAccountId).toBe(initial.account?.homeAccountId);
  expect(usedRefreshTokens).toHaveLength(1);
  const profile = await fetch(`${BASE}/oidc/userinfo`, { headers: { Authorization: `Bearer ${renewed.accessToken}` } });
  expect(profile.status).toBe(200);
  expect(initial.idTokenClaims).toHaveProperty("sub", (await profile.json()).sub);
  await client.acquireTokenSilent({ account: renewed.account!, scopes: SCOPES, forceRefresh: true });
  expect(usedRefreshTokens).toHaveLength(2);
  expect(usedRefreshTokens[0] === usedRefreshTokens[1]).toBe(false);
});
