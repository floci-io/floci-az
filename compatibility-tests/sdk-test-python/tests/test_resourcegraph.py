"""Azure Resource Graph compatibility test.

Drives ``POST /providers/Microsoft.ResourceGraph/resources`` through the
``azure-mgmt-resourcegraph`` SDK, whose 8.1 line pins api-version ``2024-04-01``:
the query request and response models, both result formats, paging through
``$skipToken`` and the service's error shape for a query that does not parse.

The resources queried are provisioned over the ARM management plane first.
"""
import os
import time

import pytest
import requests
from azure.core.credentials import AccessToken, TokenCredential
from azure.core.exceptions import HttpResponseError
from azure.core.pipeline.policies import SansIOHTTPPolicy
from azure.mgmt.resourcegraph import ResourceGraphClient
from azure.mgmt.resourcegraph.models import QueryRequest, QueryRequestOptions

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
RG = "sdk-test-rg-resourcegraph"
ACCOUNTS = ["sdkgraphsa1", "sdkgraphsa2", "sdkgraphsa3"]
STORAGE_API = "2023-01-01"

RG_BASE = f"{EMULATOR_BASE}/subscriptions/{SUB}/resourceGroups/{RG}"
HEADERS = {"Authorization": "Bearer fake", "Content-Type": "application/json"}


class FakeCredential(TokenCredential):
    """Returns a static fake Bearer token: no Entra sign-in is needed for the local emulator."""

    def get_token(self, *scopes, **kwargs):
        return AccessToken("fake-token-for-local-emulator", int(time.time()) + 3600)


class StaticBearerPolicy(SansIOHTTPPolicy):
    """Stands in for the SDK's bearer policy, which refuses to send a token over plain HTTP."""

    def on_request(self, request):
        request.http_request.headers["Authorization"] = "Bearer fake-token-for-local-emulator"


@pytest.fixture(scope="module")
def graph():
    put = requests.put(f"{RG_BASE}?api-version=2021-04-01", json={"location": "eastus"}, headers=HEADERS, timeout=10)
    assert put.status_code in (200, 201), put.text
    for name in ACCOUNTS:
        body = {
            "location": "eastus",
            "kind": "StorageV2",
            "sku": {"name": "Standard_LRS"},
            "tags": {"suite": "resourcegraph"},
            "properties": {"minimumTlsVersion": "TLS1_2"},
        }
        url = f"{RG_BASE}/providers/Microsoft.Storage/storageAccounts/{name}?api-version={STORAGE_API}"
        created = requests.put(url, json=body, headers=HEADERS, timeout=10)
        assert created.status_code in (200, 201, 202), created.text

    client = ResourceGraphClient(
        credential=FakeCredential(),
        subscription_id=SUB,
        base_url=EMULATOR_BASE,
        authentication_policy=StaticBearerPolicy(),
    )
    yield client
    client.close()

    for name in ACCOUNTS:
        url = f"{RG_BASE}/providers/Microsoft.Storage/storageAccounts/{name}?api-version={STORAGE_API}"
        requests.delete(url, headers=HEADERS, timeout=10)
    requests.delete(f"{RG_BASE}?api-version=2021-04-01", headers=HEADERS, timeout=10)


ACCOUNTS_QUERY = (
    "resources"
    " | where type =~ 'Microsoft.Storage/storageAccounts' and resourceGroup =~ '" + RG + "'"
    " | project id, name, type, location, resourceGroup, subscriptionId"
    " | order by name asc"
)


def test_query_answers_object_array_rows(graph):
    response = graph.resources(QueryRequest(subscriptions=[SUB], query=ACCOUNTS_QUERY))

    assert response.total_records == len(ACCOUNTS)
    assert response.count == len(ACCOUNTS)
    assert response.result_truncated == "false"
    assert response.skip_token is None
    assert [row["name"] for row in response.data] == ACCOUNTS
    first = response.data[0]
    assert first["type"] == "microsoft.storage/storageaccounts"
    assert first["location"] == "eastus"
    assert first["resourceGroup"] == RG
    assert first["subscriptionId"] == SUB
    assert first["id"].lower().endswith(f"/storageaccounts/{ACCOUNTS[0]}")


def test_table_format_answers_columns_and_rows(graph):
    response = graph.resources(
        QueryRequest(
            subscriptions=[SUB],
            query=ACCOUNTS_QUERY + " | project name, location",
            options=QueryRequestOptions(result_format="table"),
        )
    )

    assert response.count == len(ACCOUNTS)
    assert [column["name"] for column in response.data["columns"]] == ["name", "location"]
    assert response.data["rows"] == [[name, "eastus"] for name in ACCOUNTS]


def test_pages_through_the_skip_token(graph):
    names, token, pages = [], None, 0
    while True:
        response = graph.resources(
            QueryRequest(
                subscriptions=[SUB],
                query=ACCOUNTS_QUERY,
                options=QueryRequestOptions(top=2, skip_token=token),
            )
        )
        pages += 1
        assert response.total_records == len(ACCOUNTS)
        names.extend(row["name"] for row in response.data)
        token = response.skip_token
        if token is None:
            break
        assert response.count == 2

    assert pages == 2
    assert names == ACCOUNTS


def test_summarize_and_resource_containers(graph):
    counted = graph.resources(
        QueryRequest(
            subscriptions=[SUB],
            query="resources | where resourceGroup =~ '" + RG + "' | summarize n = count() by type",
        )
    )
    assert counted.data == [{"type": "microsoft.storage/storageaccounts", "n": len(ACCOUNTS)}]

    containers = graph.resources(
        QueryRequest(
            subscriptions=[SUB],
            query=(
                "resourcecontainers"
                " | where type == 'microsoft.resources/subscriptions/resourcegroups' and name =~ '" + RG + "'"
                " | project name, location, subscriptionId"
            ),
        )
    )
    assert containers.data == [{"name": RG, "location": "eastus", "subscriptionId": SUB}]


def test_invalid_query_is_a_bad_request_with_details(graph):
    with pytest.raises(HttpResponseError) as raised:
        graph.resources(QueryRequest(subscriptions=[SUB], query="resources | where"))

    error = raised.value
    assert error.status_code == 400
    assert error.error.code == "BadRequest"
    assert [detail.code for detail in error.error.details][0] == "InvalidQuery"
    assert error.error.details[1].code in ("ParserFailure", "SemanticError")
