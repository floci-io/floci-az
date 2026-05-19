export const DEV_KEY =
  "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0==";
export const ACCOUNT = "devstoreaccount1";

const BASE = process.env.FLOCI_AZ_ENDPOINT ?? "http://localhost:4577";

export const BLOB_CONN =
  `DefaultEndpointsProtocol=http;AccountName=${ACCOUNT};AccountKey=${DEV_KEY};` +
  `BlobEndpoint=${BASE}/${ACCOUNT};`;

export const QUEUE_CONN =
  `DefaultEndpointsProtocol=http;AccountName=${ACCOUNT};AccountKey=${DEV_KEY};` +
  `QueueEndpoint=${BASE}/${ACCOUNT}-queue;`;

export const TABLE_CONN =
  `DefaultEndpointsProtocol=http;AccountName=${ACCOUNT};AccountKey=${DEV_KEY};` +
  `TableEndpoint=${BASE}/${ACCOUNT}-table;`;

export const COSMOS_ENDPOINT = `${BASE}/${ACCOUNT}-cosmos`;
export const COSMOS_KEY =
  "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

export function randomSuffix(): string {
  return Math.random().toString(36).substring(2, 10);
}
