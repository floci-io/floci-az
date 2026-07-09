// Managed Identity compatibility test.
//
// Drives the Microsoft.ManagedIdentity/userAssignedIdentities ARM surface and the
// IMDS token endpoint through the real Azure REST wire protocol. The IMDS data
// plane is additionally exercised through the real @azure/identity
// ManagedIdentityCredential, which reaches the emulator when
// AZURE_POD_IDENTITY_AUTHORITY_HOST overrides http://169.254.169.254.
import { ManagedIdentityCredential } from "@azure/identity";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const SUB = process.env.FLOCI_AZ_SUBSCRIPTION ?? "00000000-0000-0000-0000-000000000001";
const RG = "sdk-test-rg-msi-node";
const IDENTITY = "sdktestmsinode";

const MSI_API = "2024-11-30";
const IMDS_API = "2018-02-01";
const RG_API = "2021-04-01";

const HEADERS = { Authorization: "Bearer fake", "Content-Type": "application/json" };
const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const RG_BASE = `${BASE}/subscriptions/${SUB}/resourceGroups/${RG}`;
const IDENTITY_URL =
  `${RG_BASE}/providers/Microsoft.ManagedIdentity/userAssignedIdentities/${IDENTITY}`;
const IMDS_URL = `${BASE}/metadata/identity/oauth2/token`;

interface IdentityResource {
  name: string;
  type: string;
  properties: { tenantId: string; principalId: string; clientId: string };
}

function decodeJwtPayload(token: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8"));
}

let identity: IdentityResource;

beforeAll(async () => {
  await fetch(`${RG_BASE}?api-version=${RG_API}`, {
    method: "PUT",
    headers: HEADERS,
    body: JSON.stringify({ location: "eastus" }),
  });
  const resp = await fetch(`${IDENTITY_URL}?api-version=${MSI_API}`, {
    method: "PUT",
    headers: HEADERS,
    body: JSON.stringify({ location: "eastus", tags: { env: "compat" } }),
  });
  expect([200, 201]).toContain(resp.status);
  identity = (await resp.json()) as IdentityResource;
});

afterAll(async () => {
  await fetch(`${IDENTITY_URL}?api-version=${MSI_API}`, {
    method: "DELETE",
    headers: HEADERS,
  });
});

test("identity create generates GUID properties", () => {
  expect(identity.name).toBe(IDENTITY);
  expect(identity.type).toBe("Microsoft.ManagedIdentity/userAssignedIdentities");
  for (const field of ["tenantId", "principalId", "clientId"] as const) {
    expect(identity.properties[field]).toMatch(GUID);
  }
});

test("IMDS requires the Metadata header", async () => {
  const resp = await fetch(
    `${IMDS_URL}?resource=https://management.azure.com/&api-version=${IMDS_API}`,
  );
  expect(resp.status).toBe(400);
  expect(((await resp.json()) as { error: string }).error).toBe("invalid_request");
});

test("IMDS token via raw HTTP carries the identity claims", async () => {
  const clientId = identity.properties.clientId;
  const resp = await fetch(
    `${IMDS_URL}?resource=https://management.azure.com/&api-version=${IMDS_API}` +
      `&client_id=${clientId}`,
    { headers: { Metadata: "true" } },
  );
  expect(resp.status).toBe(200);
  const body = (await resp.json()) as Record<string, string>;

  // Per imds spec 2023-07-01 every value in the token response is a string.
  for (const field of [
    "access_token", "client_id", "expires_in", "expires_on",
    "ext_expires_in", "not_before", "resource", "token_type",
  ]) {
    expect(typeof body[field]).toBe("string");
  }
  expect(body.token_type).toBe("Bearer");
  expect(body.client_id).toBe(clientId);

  const claims = decodeJwtPayload(body.access_token);
  expect(claims.aud).toBe("https://management.azure.com/");
  expect(claims.appid).toBe(clientId);
  expect(claims.oid).toBe(identity.properties.principalId);
  expect(claims.ver).toBe("1.0");
});

// The SDK's IMDS path targets 169.254.169.254 unless the env var redirects it here.
const sdkTest = process.env.AZURE_POD_IDENTITY_AUTHORITY_HOST ? test : test.skip;

sdkTest("ManagedIdentityCredential acquires a token via the SDK", async () => {
  const clientId = identity.properties.clientId;

  const token = await new ManagedIdentityCredential(clientId).getToken(
    "https://management.azure.com/.default",
  );
  expect(token).toBeTruthy();
  expect(decodeJwtPayload(token!.token).appid).toBe(clientId);

  // System-assigned (no client_id) also succeeds.
  const systemToken = await new ManagedIdentityCredential().getToken(
    "https://management.azure.com/.default",
  );
  expect(decodeJwtPayload(systemToken!.token).ver).toBe("1.0");
});
