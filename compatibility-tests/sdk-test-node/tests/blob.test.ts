import { BlobServiceClient, StorageSharedKeyCredential } from "@azure/storage-blob";
import { BLOB_CONN, randomSuffix } from "./config";

const client = BlobServiceClient.fromConnectionString(BLOB_CONN);

function containerName(): string {
  return `test-${randomSuffix()}`;
}

// --- Golden path ---

test("container lifecycle: create → list → delete", async () => {
  const name = containerName();

  await client.createContainer(name);

  const containers: string[] = [];
  for await (const c of client.listContainers()) containers.push(c.name);
  expect(containers).toContain(name);

  await client.deleteContainer(name);

  const after: string[] = [];
  for await (const c of client.listContainers()) after.push(c.name);
  expect(after).not.toContain(name);
});

test("blob lifecycle: upload → download → list → delete", async () => {
  const name = containerName();
  const { containerClient } = await client.createContainer(name);
  const blob = containerClient.getBlockBlobClient("hello.txt");

  const content = Buffer.from("Hello from Azure SDK Node!");
  await blob.upload(content, content.length);

  const downloaded = await blob.downloadToBuffer();
  expect(downloaded).toEqual(content);

  const blobs: string[] = [];
  for await (const b of containerClient.listBlobsFlat()) blobs.push(b.name);
  expect(blobs).toEqual(["hello.txt"]);

  await blob.delete();

  const after: string[] = [];
  for await (const b of containerClient.listBlobsFlat()) after.push(b.name);
  expect(after).toHaveLength(0);

  await client.deleteContainer(name);
});

test("multiple blobs: upload 5 → list → count matches", async () => {
  const name = containerName();
  const { containerClient } = await client.createContainer(name);

  for (let i = 0; i < 5; i++) {
    const data = Buffer.from(`content-${i}`);
    await containerClient.getBlockBlobClient(`file-${i}.txt`).upload(data, data.length);
  }

  let count = 0;
  for await (const _ of containerClient.listBlobsFlat()) count++;
  expect(count).toBe(5);

  await client.deleteContainer(name);
});

test("blob overwrite: second upload replaces content", async () => {
  const name = containerName();
  const { containerClient } = await client.createContainer(name);
  const blob = containerClient.getBlockBlobClient("overwrite.txt");

  await blob.upload(Buffer.from("original"), 8);
  await blob.upload(Buffer.from("updated"), 7);

  const downloaded = await blob.downloadToBuffer();
  expect(downloaded.toString()).toBe("updated");

  await client.deleteContainer(name);
});

// --- Error cases ---

test("download missing blob → 404 BlobNotFound", async () => {
  const name = containerName();
  await client.createContainer(name);
  const blob = client.getContainerClient(name).getBlockBlobClient("no-such.txt");

  await expect(blob.downloadToBuffer()).rejects.toMatchObject({
    statusCode: 404,
  });

  await client.deleteContainer(name);
});

test("create duplicate container → 409 ContainerAlreadyExists", async () => {
  const name = containerName();
  await client.createContainer(name);

  await expect(client.createContainer(name)).rejects.toMatchObject({
    statusCode: 409,
    code: "ContainerAlreadyExists",
  });

  await client.deleteContainer(name);
});
