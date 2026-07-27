// Entra ID phase 2 + Graph compatibility test.
//
// Drives the interactive authorization-code+PKCE grant and the narrow Microsoft Graph slice
// through the real wire protocol (raw fetch), plus the real client libraries a browser-frontend +
// Node-backend app would use: @azure/msal-node's CryptoProvider for real PKCE code generation
// (not a hand-rolled approximation), and @microsoft/microsoft-graph-client for the Graph call.
//
// Notably NOT using msal-node's ConfidentialClientApplication (getAuthCodeUrl/acquireTokenByCode):
// its authority validation unconditionally rejects non-https URIs (UrlString.validateAsUri in
// @azure/msal-common — there is no override flag), and this emulator is HTTP-only by default.
// Enabling TLS suite-wide to accommodate one library's fixed requirement would be a much bigger
// change than this test warrants, so the token exchange below drives the wire protocol directly —
// which is exactly what ConfidentialClientApplication does internally over HTTPS in production.
//
// There is no real interactive consent screen: GET .../authorize auto-approves against the seeded
// dev user and redirects immediately, which is what keeps this fully scriptable in CI.
import { CryptoProvider } from "@azure/msal-node";
import { Client } from "@microsoft/microsoft-graph-client";
import { createHash } from "node:crypto";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const TENANT = "00000000-0000-0000-0000-000000000002";
const CLIENT_ID = "11111111-1111-1111-1111-111111111111";
const CLIENT_SECRET = "floci-az-dev-secret";
const DEV_USER_UPN = "dev-user@floci-az.local";
// Documented dev seed (EntraStore.DEV_GROUP_OBJECT_ID) — a well-known fixture, like the client id/secret above.
const DEV_GROUP_ID = "44444444-4444-4444-4444-444444444444";
const REDIRECT_URI = "https://app.local/callback";

const TOKEN_URL = `${BASE}/${TENANT}/oauth2/v2.0/token`;

function decodeJwtPayload(token: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8"));
}

// ── Raw wire-protocol tests ─────────────────────────────────────────────────────

async function authorize(params: Record<string, string>): Promise<Response> {
  const url = new URL(`${BASE}/${TENANT}/oauth2/v2.0/authorize`);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  return fetch(url, { redirect: "manual" });
}

function codeFrom(resp: Response): string {
  const location = resp.headers.get("location");
  if (!location) throw new Error("authorize did not redirect");
  const code = new URL(location).searchParams.get("code");
  if (!code) throw new Error(`no code in redirect: ${location}`);
  return code;
}

test("authorize auto-approves and redirects with a code", async () => {
  const resp = await authorize({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: "code",
    state: "characterization-state",
  });
  expect(resp.status).toBe(302);
  const location = resp.headers.get("location")!;
  expect(location.startsWith(`${REDIRECT_URI}?`)).toBe(true);
  expect(location).toContain("state=characterization-state");
  expect(codeFrom(resp)).toBeTruthy();
});

function s256Challenge(verifier: string): string {
  return createHash("sha256").update(verifier).digest("base64url");
}

test("authorization_code grant rejects a wrong PKCE verifier", async () => {
  const resp = await authorize({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: "code",
    code_challenge: s256Challenge("correct-verifier"),
    code_challenge_method: "S256",
  });
  const code = codeFrom(resp);

  const tokenResp = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: CLIENT_ID,
      redirect_uri: REDIRECT_URI,
      code,
      code_verifier: "wrong-verifier",
    }),
  });
  expect(tokenResp.status).toBe(400);
  expect(((await tokenResp.json()) as { error: string }).error).toBe("invalid_grant");
});

// ── SDK-driven end-to-end: real PKCE codes + real Graph client, group membership check ─────────

test("auth-code+PKCE sign-in (real PKCE codes) resolves group membership via the Graph SDK", async () => {
  const cryptoProvider = new CryptoProvider();
  const { verifier, challenge } = await cryptoProvider.generatePkceCodes();
  const nonce = cryptoProvider.createNewGuid();

  const authorizeResp = await authorize({
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    response_type: "code",
    scope: "openid profile",
    nonce,
    code_challenge: challenge,
    code_challenge_method: "S256",
    login_hint: DEV_USER_UPN,
  });
  const code = codeFrom(authorizeResp);

  const tokenResp = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      redirect_uri: REDIRECT_URI,
      code,
      code_verifier: verifier,
    }),
  });
  expect(tokenResp.status).toBe(200);
  const { access_token, id_token } = (await tokenResp.json()) as { access_token: string; id_token: string };
  expect(access_token).toBeTruthy();
  const idClaims = decodeJwtPayload(id_token);
  expect(idClaims.nonce).toBe(nonce);
  expect(idClaims.aud).toBe(CLIENT_ID); // ID token audience is always the client id, not the resource
  expect(idClaims.preferred_username).toBe(DEV_USER_UPN);

  const graphClient = Client.init({
    baseUrl: BASE,
    customHosts: new Set([new URL(BASE).host]),
    authProvider: (done) => done(null, access_token),
  });

  const memberGroups = (await graphClient
    .api(`/users/${encodeURIComponent(DEV_USER_UPN)}/getMemberGroups`)
    .post({ securityEnabledOnly: false })) as { value: string[] };
  expect(memberGroups.value).toContain(DEV_GROUP_ID);
});

test("graph getMemberGroups uses the token's own oid claim", async () => {
  const tokenResp = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: CLIENT_ID,
      username: DEV_USER_UPN,
      password: "whatever",
      scope: "openid",
    }),
  });
  const { access_token } = (await tokenResp.json()) as { access_token: string };
  const oid = decodeJwtPayload(access_token).oid as string;

  const resp = await fetch(`${BASE}/v1.0/users/${oid}/getMemberGroups`, {
    method: "POST",
    headers: { Authorization: `Bearer ${access_token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ securityEnabledOnly: true }),
  });
  expect(resp.status).toBe(200);
  const body = (await resp.json()) as { value: string[] };
  expect(body.value).toContain(DEV_GROUP_ID);
});
