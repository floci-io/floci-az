import { SecretClient } from "@azure/keyvault-secrets";
import { TokenCredential } from "@azure/core-auth";
import { PipelinePolicy } from "@azure/core-rest-pipeline";
import { ACCOUNT } from "./config";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
// SDK requires https:// vault URL; ForceHttpPolicy rewrites it back before sending
const VAULT_URL = BASE.replace("http://", "https://") + `/${ACCOUNT}-keyvault`;

const fakeCredential: TokenCredential = {
  getToken: async () => ({
    token: "fake-token-for-local-emulator",
    expiresOnTimestamp: Date.now() + 3_600_000,
  }),
};

const forceHttpPolicy: PipelinePolicy = {
  name: "ForceHttpPolicy",
  sendRequest(request, next) {
    request.url = request.url.replace(/^https:\/\//, "http://");
    request.allowInsecureConnection = true;
    return next(request);
  },
};

const client = new SecretClient(VAULT_URL, fakeCredential, {
  disableChallengeResourceVerification: true,
  additionalPolicies: [{ policy: forceHttpPolicy, position: "perCall" }],
});

function uid(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).substring(2, 10)}`;
}

// --- Lifecycle ---

test("set and get secret", async () => {
  const n = uid("get");
  await client.setSecret(n, "hello-world");
  const s = await client.getSecret(n);
  expect(s.name).toBe(n);
  expect(s.value).toBe("hello-world");
  expect(s.properties.id).toBeTruthy();
  await client.beginDeleteSecret(n);
});

test("secret with content type", async () => {
  const n = uid("ct");
  await client.setSecret(n, "data", { contentType: "text/plain" });
  const s = await client.getSecret(n);
  expect(s.properties.contentType).toBe("text/plain");
  await client.beginDeleteSecret(n);
});

test("secret with tags", async () => {
  const n = uid("tags");
  await client.setSecret(n, "value", { tags: { env: "test", owner: "ci" } });
  const s = await client.getSecret(n);
  expect(s.properties.tags?.env).toBe("test");
  expect(s.properties.tags?.owner).toBe("ci");
  await client.beginDeleteSecret(n);
});

test("update overwrites value", async () => {
  const n = uid("upd");
  await client.setSecret(n, "v1");
  await client.setSecret(n, "v2");
  const s = await client.getSecret(n);
  expect(s.value).toBe("v2");
  await client.beginDeleteSecret(n);
});

test("get nonexistent secret throws", async () => {
  await expect(client.getSecret(`no-such-secret-${uid("x")}`)).rejects.toThrow();
});

test("delete secret and get deleted", async () => {
  const n = uid("del");
  await client.setSecret(n, "to-delete");
  await client.beginDeleteSecret(n);
  const deleted = await client.getDeletedSecret(n);
  expect(deleted.name).toBe(n);
});

test("recover deleted secret", async () => {
  const n = uid("rec");
  await client.setSecret(n, "recover-me");
  await client.beginDeleteSecret(n);
  await client.beginRecoverDeletedSecret(n).then((p) => p.pollUntilDone());
  const s = await client.getSecret(n);
  expect(s.value).toBe("recover-me");
  await client.beginDeleteSecret(n);
});

test("purge deleted secret", async () => {
  const n = uid("purge");
  await client.setSecret(n, "to-purge");
  await client.beginDeleteSecret(n);
  await client.purgeDeletedSecret(n);
  const names: string[] = [];
  for await (const p of client.listDeletedSecrets()) names.push(p.name);
  expect(names).not.toContain(n);
});

test("list secrets includes created ones", async () => {
  const n1 = uid("lst-a");
  const n2 = uid("lst-b");
  await Promise.all([client.setSecret(n1, "v1"), client.setSecret(n2, "v2")]);
  const names: string[] = [];
  for await (const p of client.listPropertiesOfSecrets()) names.push(p.name);
  expect(names).toContain(n1);
  expect(names).toContain(n2);
  await Promise.all([client.beginDeleteSecret(n1), client.beginDeleteSecret(n2)]);
});

test("list deleted secrets", async () => {
  const n = uid("ldel");
  await client.setSecret(n, "value");
  await client.beginDeleteSecret(n);
  const names: string[] = [];
  for await (const p of client.listDeletedSecrets()) names.push(p.name);
  expect(names).toContain(n);
});

test("disabled secret attribute", async () => {
  const n = uid("dis");
  await client.setSecret(n, "hidden", { enabled: false });
  // A disabled secret's value is inaccessible; verify the flag via the properties list.
  let foundEnabled: boolean | undefined;
  for await (const p of client.listPropertiesOfSecrets()) {
    if (p.name === n) { foundEnabled = p.enabled; break; }
  }
  expect(foundEnabled).toBe(false);
  await client.beginDeleteSecret(n);
});

// --- Versions ---

test("multiple versions created", async () => {
  const n = uid("ver");
  await client.setSecret(n, "v1");
  await client.setSecret(n, "v2");
  await client.setSecret(n, "v3");
  const versions: string[] = [];
  for await (const p of client.listPropertiesOfSecretVersions(n)) versions.push(p.version!);
  expect(versions.length).toBe(3);
  const latest = await client.getSecret(n);
  expect(latest.value).toBe("v3");
  await client.beginDeleteSecret(n);
});

test("get specific version", async () => {
  const n = uid("sv");
  await client.setSecret(n, "first");
  const v1 = (await client.getSecret(n)).properties.version!;
  await client.setSecret(n, "second");
  expect((await client.getSecret(n, { version: v1 })).value).toBe("first");
  expect((await client.getSecret(n)).value).toBe("second");
  await client.beginDeleteSecret(n);
});

test("version ID embedded in secret URL", async () => {
  const n = uid("vid");
  await client.setSecret(n, "value");
  const s = await client.getSecret(n);
  expect(s.properties.id).toContain(s.properties.version);
  await client.beginDeleteSecret(n);
});
