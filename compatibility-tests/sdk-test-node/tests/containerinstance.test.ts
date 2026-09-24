// Azure Container Instances compatibility test.
//
// Drives the Microsoft.ContainerInstance/containerGroups ARM surface through the
// real Azure REST wire protocol with fetch (mirroring managedidentity.test.ts —
// no fluent mgmt SDK). Pins the azurerm/az-CLI read-back contract: container
// ports and resource requests always present, canonical enum casing, secrets
// never echoed, list responses without instanceView, and the spec's exact LRO
// shapes for start/stop/restart and delete.

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";
const SUB = process.env.FLOCI_AZ_SUBSCRIPTION ?? "00000000-0000-0000-0000-000000000001";
const RG = "sdk-test-rg-aci-node";
const GROUP = "sdktestcgnode";

const ACI_API = "2023-05-01";
const RG_API = "2021-04-01";

const HEADERS = { Authorization: "Bearer fake", "Content-Type": "application/json" };

const RG_BASE = `${BASE}/subscriptions/${SUB}/resourceGroups/${RG}`;
const GROUP_URL = `${RG_BASE}/providers/Microsoft.ContainerInstance/containerGroups/${GROUP}`;

const CREATE_BODY = {
  location: "eastus",
  tags: { env: "compat" },
  properties: {
    containers: [
      {
        name: "web",
        properties: {
          image: "hashicorp/http-echo:latest",
          command: ["/http-echo", "-text=hi"],
          ports: [{ port: 5678, protocol: "tcp" }],
          environmentVariables: [
            { name: "PLAIN", value: "visible" },
            { name: "SECRET", secureValue: "hunter2" },
          ],
        },
      },
    ],
    osType: "linux",
    imageRegistryCredentials: [
      { server: "example.azurecr.io", username: "admin", password: "p" },
    ],
    ipAddress: { type: "Public", ports: [{ port: 5678 }], dnsNameLabel: "nodecompat" },
  },
};

interface ContainerGroup {
  name: string;
  type: string;
  properties: Record<string, any>;
}

let group: ContainerGroup;

beforeAll(async () => {
  await fetch(`${RG_BASE}?api-version=${RG_API}`, {
    method: "PUT",
    headers: HEADERS,
    body: JSON.stringify({ location: "eastus" }),
  });
  const resp = await fetch(`${GROUP_URL}?api-version=${ACI_API}`, {
    method: "PUT",
    headers: HEADERS,
    body: JSON.stringify(CREATE_BODY),
  });
  expect([200, 201]).toContain(resp.status);
  group = (await resp.json()) as ContainerGroup;
});

afterAll(async () => {
  await fetch(`${GROUP_URL}?api-version=${ACI_API}`, { method: "DELETE", headers: HEADERS });
});

test("create normalizes the read-back for azurerm", () => {
  expect(group.type).toBe("Microsoft.ContainerInstance/containerGroups");
  const props = group.properties;
  expect(props.provisioningState).toBe("Succeeded");
  expect(props.osType).toBe("Linux");
  expect(props.restartPolicy).toBe("Always");
  const container = props.containers[0].properties;
  expect(container.ports[0].protocol).toBe("TCP");
  expect(container.resources.requests.cpu).toBeGreaterThan(0);
  expect(container.resources.requests.memoryInGB).toBeGreaterThan(0);
  const secretVar = container.environmentVariables[1];
  expect(secretVar.name).toBe("SECRET");
  expect(secretVar.secureValue).toBeUndefined();
  expect(props.imageRegistryCredentials[0].password).toBeUndefined();
  expect(props.ipAddress.fqdn).toBe("nodecompat.eastus.azurecontainer.io");
});

test("single GET includes instanceView; list omits it", async () => {
  const get = await fetch(`${GROUP_URL}?api-version=${ACI_API}`, { headers: HEADERS });
  expect(get.status).toBe(200);
  const body = (await get.json()) as ContainerGroup;
  expect(body.properties.instanceView).toBeDefined();

  const list = await fetch(
    `${RG_BASE}/providers/Microsoft.ContainerInstance/containerGroups?api-version=${ACI_API}`,
    { headers: HEADERS },
  );
  expect(list.status).toBe(200);
  const listBody = (await list.json()) as { value: ContainerGroup[] };
  const entry = listBody.value.find((v) => v.name === GROUP);
  expect(entry).toBeDefined();
  expect(entry!.properties.instanceView).toBeUndefined();
});

test("actions match the spec's LRO shapes", async () => {
  const start = await fetch(`${GROUP_URL}/start?api-version=${ACI_API}`, {
    method: "POST",
    headers: HEADERS,
  });
  expect(start.status).toBe(202);
  const location = start.headers.get("Location") ?? "";
  expect(location).toContain("/providers/Microsoft.ContainerInstance/locations/");

  const op = await fetch(location, { headers: HEADERS });
  expect(op.status).toBe(200);
  expect(((await op.json()) as { status: string }).status).toBe("Succeeded");

  const restart = await fetch(`${GROUP_URL}/restart?api-version=${ACI_API}`, {
    method: "POST",
    headers: HEADERS,
  });
  expect(restart.status).toBe(204); // restart signals its LRO on a 204 per spec
  expect(restart.headers.get("Location")).toBeTruthy();

  const stop = await fetch(`${GROUP_URL}/stop?api-version=${ACI_API}`, {
    method: "POST",
    headers: HEADERS,
  });
  expect(stop.status).toBe(204); // the spec's only synchronous action
  expect(stop.headers.get("Location")).toBeNull();
});

test("container logs return the content envelope", async () => {
  const logs = await fetch(`${GROUP_URL}/containers/web/logs?api-version=${ACI_API}`, {
    headers: HEADERS,
  });
  expect(logs.status).toBe(200);
  const body = (await logs.json()) as { content: string };
  expect(typeof body.content).toBe("string");
});

test("exec returns an honest 501", async () => {
  const exec = await fetch(`${GROUP_URL}/containers/web/exec?api-version=${ACI_API}`, {
    method: "POST",
    headers: HEADERS,
    body: JSON.stringify({ command: "/bin/sh", terminalSize: { rows: 24, cols: 80 } }),
  });
  expect(exec.status).toBe(501);
  const body = (await exec.json()) as { error: { code: string } };
  expect(body.error.code).toBe("NotImplemented");
});

test("delete returns the group body, then GET 404, then 204 once absent", async () => {
  const del = await fetch(`${GROUP_URL}?api-version=${ACI_API}`, {
    method: "DELETE",
    headers: HEADERS,
  });
  expect(del.status).toBe(200);
  const get = await fetch(`${GROUP_URL}?api-version=${ACI_API}`, { headers: HEADERS });
  expect(get.status).toBe(404);
  const again = await fetch(`${GROUP_URL}?api-version=${ACI_API}`, { method: "DELETE", headers: HEADERS });
  expect(again.status).toBe(204);
});
