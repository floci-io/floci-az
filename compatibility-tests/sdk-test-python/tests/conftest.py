import os
import pytest
from azure.storage.blob import BlobServiceClient
from azure.storage.queue import QueueServiceClient
from azure.data.tables import TableServiceClient
from azure.cosmos import CosmosClient

DEV_KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0=="
COSMOS_KEY = "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw=="
ACCOUNT_NAME = "devstoreaccount1"

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")


@pytest.fixture(scope="session")
def blob_service_client():
    base = f"{EMULATOR_BASE}/{ACCOUNT_NAME}"
    conn_str = (
        f"DefaultEndpointsProtocol=http;AccountName={ACCOUNT_NAME};"
        f"AccountKey={DEV_KEY};BlobEndpoint={base};"
    )
    return BlobServiceClient.from_connection_string(conn_str)


@pytest.fixture(scope="session")
def queue_service_client():
    base = f"{EMULATOR_BASE}/{ACCOUNT_NAME}-queue"
    conn_str = (
        f"DefaultEndpointsProtocol=http;AccountName={ACCOUNT_NAME};"
        f"AccountKey={DEV_KEY};QueueEndpoint={base};"
    )
    return QueueServiceClient.from_connection_string(conn_str)


@pytest.fixture(scope="session")
def table_service_client():
    base = f"{EMULATOR_BASE}/{ACCOUNT_NAME}-table"
    conn_str = (
        f"DefaultEndpointsProtocol=http;AccountName={ACCOUNT_NAME};"
        f"AccountKey={DEV_KEY};TableEndpoint={base};"
    )
    return TableServiceClient.from_connection_string(conn_str)


@pytest.fixture(scope="session")
def cosmos_client():
    url = f"{EMULATOR_BASE}/{ACCOUNT_NAME}-cosmos"
    return CosmosClient(url=url, credential=COSMOS_KEY)