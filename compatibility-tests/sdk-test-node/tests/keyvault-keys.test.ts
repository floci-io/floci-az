import { KeyClient, CryptographyClient } from "@azure/keyvault-keys";
import { TokenCredential } from "@azure/core-auth";
import { PipelinePolicy } from "@azure/core-rest-pipeline";
import { ACCOUNT } from "./config";
import * as crypto from "crypto";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
// SDK requires https:// vault URL; ForceHttpPolicy rewrites it back before sending
const VAULT_URL = BASE.replace("http://", "https://") + `/${ACCOUNT}-keyvault`;

const MHSM_URL = BASE.replace("http://", "https://") + `/${ACCOUNT}-managedhsm`;

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

function keyClient(url: string = VAULT_URL): KeyClient {
  return new KeyClient(url, fakeCredential, {
    disableChallengeResourceVerification: true,
    additionalPolicies: [{ policy: forceHttpPolicy, position: "perCall" }],
  });
}

// CRITICAL: build the crypto kid ourselves — the emulator returns ids with host
// devstoreaccount1.vault.azure.net which does NOT route back to the emulator.
function cryptoClient(keyName: string, keyVersion: string): CryptographyClient {
  const kid = `${VAULT_URL}/keys/${keyName}/${keyVersion}`;
  return new CryptographyClient(kid, fakeCredential, {
    disableChallengeResourceVerification: true,
    additionalPolicies: [{ policy: forceHttpPolicy, position: "perCall" }],
  });
}

function uid(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).substring(2, 10)}`;
}

const client = keyClient();
const hsm = keyClient(MHSM_URL);

// --- Key CRUD + lifecycle ---

test("create and get RSA key", async () => {
  const n = uid("rsa");
  const key = await client.createRsaKey(n, { keySize: 2048 });
  expect(key.name).toBe(n);
  expect(key.key?.n).toBeTruthy();
  expect((await client.getKey(n)).name).toBe(n);
  await client.beginDeleteKey(n);
});

test("create and get EC key", async () => {
  const n = uid("ec");
  const key = await client.createEcKey(n, { curve: "P-256" });
  expect(key.key?.x).toBeTruthy();
  expect(key.key?.y).toBeTruthy();
  expect((await client.getKey(n)).name).toBe(n);
  await client.beginDeleteKey(n);
});

test("create and get oct key", async () => {
  const n = uid("oct");
  const key = await client.createOctKey(n, { keySize: 256 });
  // Symmetric key material is never released in a Key Vault response.
  expect(key.key?.k).toBeUndefined();
  expect((await client.getKey(n)).name).toBe(n);
  await client.beginDeleteKey(n);
});

test("get nonexistent key throws", async () => {
  await expect(client.getKey("no-such-" + uid("x"))).rejects.toThrow();
});

test("list keys includes created one", async () => {
  const n = uid("list");
  await client.createRsaKey(n, { keySize: 2048 });
  const names: string[] = [];
  for await (const p of client.listPropertiesOfKeys()) names.push(p.name);
  expect(names).toContain(n);
  await client.beginDeleteKey(n);
});

test("list key versions", async () => {
  const n = uid("ver");
  await client.createRsaKey(n, { keySize: 2048 });
  await client.createRsaKey(n, { keySize: 2048 });
  const versions: string[] = [];
  for await (const p of client.listPropertiesOfKeyVersions(n)) versions.push(p.version!);
  expect(versions.length).toBeGreaterThanOrEqual(2);
  await client.beginDeleteKey(n);
});

test("delete recover purge lifecycle", async () => {
  const n = uid("lc");
  await client.createRsaKey(n, { keySize: 2048 });

  await client.beginDeleteKey(n);
  expect((await client.getDeletedKey(n)).name).toBe(n);

  await client.beginRecoverDeletedKey(n).then((p) => p.pollUntilDone());
  expect((await client.getKey(n)).name).toBe(n);

  await client.beginDeleteKey(n);
  await client.purgeDeletedKey(n);

  const deleted: string[] = [];
  for await (const p of client.listDeletedKeys()) deleted.push(p.name);
  expect(deleted).not.toContain(n);
});

test("backup and restore key", async () => {
  const n = uid("bak");
  await client.createRsaKey(n, { keySize: 2048 });

  const backup = await client.backupKey(n);
  expect(backup).toBeInstanceOf(Uint8Array);
  expect(backup!.length).toBeGreaterThan(0);

  await client.beginDeleteKey(n);
  await client.purgeDeletedKey(n);

  const restored = await client.restoreKeyBackup(backup!);
  expect(restored.name).toBe(n);
  await client.beginDeleteKey(n);
});

test("rotate key yields a fresh version", async () => {
  const n = uid("rot");
  const v1 = (await client.createRsaKey(n, { keySize: 2048 })).properties.version!;
  expect((await client.rotateKey(n)).properties.version).not.toBe(v1);
  expect(await client.getKeyRotationPolicy(n)).toBeTruthy();
  await client.beginDeleteKey(n);
});

// --- Cryptography ---

test("RSA-OAEP-256 encrypt/decrypt round-trip", async () => {
  const n = uid("crypt-oaep");
  const key = await client.createRsaKey(n, { keySize: 2048 });
  const c = cryptoClient(n, key.properties.version!);

  const ct = (await c.encrypt("RSA-OAEP-256", Buffer.from("hello"))).result;
  expect(Buffer.from((await c.decrypt("RSA-OAEP-256", ct)).result).toString()).toBe("hello");

  await client.beginDeleteKey(n);
});

test("RSA-OAEP encrypt/decrypt round-trip", async () => {
  const n = uid("crypt-oaep1");
  const key = await client.createRsaKey(n, { keySize: 2048 });
  const c = cryptoClient(n, key.properties.version!);

  const ct = (await c.encrypt("RSA-OAEP", Buffer.from("hello"))).result;
  expect(Buffer.from((await c.decrypt("RSA-OAEP", ct)).result).toString()).toBe("hello");

  await client.beginDeleteKey(n);
});

test("RSA-OAEP-256 wrap/unwrap round-trip", async () => {
  const n = uid("wrap");
  const key = await client.createRsaKey(n, { keySize: 2048 });
  const c = cryptoClient(n, key.properties.version!);

  const material = Buffer.from("material");
  const wrapped = (await c.wrapKey("RSA-OAEP-256", material)).result;
  expect(Buffer.from((await c.unwrapKey("RSA-OAEP-256", wrapped)).result).equals(material)).toBe(true);

  await client.beginDeleteKey(n);
});

test("RS256 sign/verify round-trip", async () => {
  const n = uid("sig-rs256");
  const key = await client.createRsaKey(n, { keySize: 2048 });
  const c = cryptoClient(n, key.properties.version!);

  const digest = crypto.createHash("sha256").update("sign-me").digest();
  const sig = (await c.sign("RS256", digest)).result;
  expect((await c.verify("RS256", digest, sig)).result).toBe(true);

  await client.beginDeleteKey(n);
});

test("ES256 sign/verify round-trip", async () => {
  const n = uid("sig-es256");
  const key = await client.createEcKey(n, { curve: "P-256" });
  const c = cryptoClient(n, key.properties.version!);

  const digest = crypto.createHash("sha256").update("sign-me").digest();
  const sig = (await c.sign("ES256", digest)).result;
  expect((await c.verify("ES256", digest, sig)).result).toBe(true);

  await client.beginDeleteKey(n);
});

test("A256GCM encrypt/decrypt round-trip", async () => {
  const n = uid("crypt-gcm");
  const key = await client.createOctKey(n, { keySize: 256 });
  const c = cryptoClient(n, key.properties.version!);

  const res = await c.encrypt("A256GCM", Buffer.from("gcm-plaintext"));
  const decrypted = await c.decrypt({
    algorithm: "A256GCM",
    ciphertext: res.result,
    iv: res.iv!,
    authenticationTag: res.authenticationTag!,
  });
  expect(Buffer.from(decrypted.result).toString()).toBe("gcm-plaintext");

  await client.beginDeleteKey(n);
});

// --- Managed HSM ---

test("managed HSM basic key CRUD", async () => {
  const n = uid("hsm");
  const created = await hsm.createRsaKey(n, { keySize: 2048 });
  expect(created.name).toBe(n);

  expect((await hsm.getKey(n)).name).toBe(n);

  const names: string[] = [];
  for await (const p of hsm.listPropertiesOfKeys()) names.push(p.name);
  expect(names).toContain(n);

  await hsm.beginDeleteKey(n);
});
