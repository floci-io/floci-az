import { AppConfigurationClient, ConfigurationSetting } from "@azure/app-configuration";
import { PipelinePolicy } from "@azure/core-rest-pipeline";
import { ACCOUNT, DEV_KEY } from "./config";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
// SDK connection-string parser expects https://; ForceHttpPolicy rewrites it before sending.
const ENDPOINT = BASE.replace("http://", "https://") + `/${ACCOUNT}-appconfig`;
const CONN = `Endpoint=${ENDPOINT};Id=${ACCOUNT};Secret=${DEV_KEY}`;

const forceHttpPolicy: PipelinePolicy = {
  name: "ForceHttpPolicy",
  sendRequest(request, next) {
    request.url = request.url.replace(/^https:\/\//, "http://");
    request.allowInsecureConnection = true;
    return next(request);
  },
};

const client = new AppConfigurationClient(CONN, {
  additionalPolicies: [{ policy: forceHttpPolicy, position: "perCall" }],
});

function uid(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).substring(2, 10)}`;
}

async function collect(
  iter: AsyncIterable<ConfigurationSetting>,
): Promise<ConfigurationSetting[]> {
  const out: ConfigurationSetting[] = [];
  for await (const s of iter) {
    out.push(s);
  }
  return out;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

test("pagination: list follows @nextLink across >100 settings", async () => {
  const prefix = uid("pg");
  for (let i = 0; i < 150; i++) {
    await client.setConfigurationSetting({ key: `${prefix}-${String(i).padStart(3, "0")}`, value: "v" });
  }
  const items = await collect(client.listConfigurationSettings({ keyFilter: `${prefix}-*` }));
  const keys = new Set(items.map((s) => s.key));
  expect(keys.size).toBe(150);
});

test("$select projects only requested fields", async () => {
  const key = uid("sel");
  await client.setConfigurationSetting({ key, value: "hello", contentType: "text/plain" });

  const items = await collect(
    client.listConfigurationSettings({ keyFilter: key, fields: ["key", "value"] }),
  );
  expect(items).toHaveLength(1);
  expect(items[0].key).toBe(key);
  expect(items[0].value).toBe("hello");
  expect(items[0].contentType).toBeUndefined();
});

test("tags filter uses AND semantics", async () => {
  const prefix = uid("tag");
  await client.setConfigurationSetting({ key: `${prefix}-a`, value: "a", tags: { env: "prod", tier: "web" } });
  await client.setConfigurationSetting({ key: `${prefix}-b`, value: "b", tags: { env: "prod" } });
  await client.setConfigurationSetting({ key: `${prefix}-c`, value: "c", tags: { env: "dev" } });

  const oneTag = await collect(
    client.listConfigurationSettings({ keyFilter: `${prefix}-*`, tagsFilter: ["env=prod"] }),
  );
  expect(new Set(oneTag.map((s) => s.key))).toEqual(new Set([`${prefix}-a`, `${prefix}-b`]));

  const twoTags = await collect(
    client.listConfigurationSettings({ keyFilter: `${prefix}-*`, tagsFilter: ["env=prod", "tier=web"] }),
  );
  expect(twoTags.map((s) => s.key)).toEqual([`${prefix}-a`]);
});

test("accept-datetime returns historical value", async () => {
  const key = uid("tt");
  // The JS SDK sends Accept-Datetime at whole-second resolution, so straddle a full second.
  await client.setConfigurationSetting({ key, value: "old" });
  await sleep(1100);
  const between = new Date();
  await sleep(1100);
  await client.setConfigurationSetting({ key, value: "new" });

  const current = await client.getConfigurationSetting({ key });
  expect(current.value).toBe("new");

  const asOf = await collect(client.listConfigurationSettings({ keyFilter: key, acceptDateTime: between }));
  expect(asOf).toHaveLength(1);
  expect(asOf[0].value).toBe("old");
});

test("snapshot create polls to a ready snapshot", async () => {
  const prefix = uid("snap");
  await client.setConfigurationSetting({ key: `${prefix}-a`, value: "1" });
  await client.setConfigurationSetting({ key: `${prefix}-b`, value: "2" });

  const name = uid("s");
  const snapshot = await client.beginCreateSnapshotAndWait({
    name,
    filters: [{ keyFilter: `${prefix}-*` }],
  });
  expect(snapshot.status).toBe("ready");

  const inSnapshot = await collect(client.listConfigurationSettingsForSnapshot(name));
  expect(inSnapshot).toHaveLength(2);

  await client.archiveSnapshot(name);
});
