"""Azure AI Search compatibility test: index management.

Creates a search service through the ARM management plane, reads its admin key, and
manages indexes on the service's data plane with ``SearchIndexClient``.
"""
import os

import pytest
import requests
from azure.core import MatchConditions
from azure.core.credentials import AzureKeyCredential
from azure.core.exceptions import (
    HttpResponseError,
    ResourceModifiedError,
    ResourceNotFoundError,
)
from azure.search.documents.indexes import SearchIndexClient
from azure.search.documents.indexes.models import (
    ComplexField,
    CorsOptions,
    HnswAlgorithmConfiguration,
    ScoringProfile,
    SearchableField,
    SearchField,
    SearchFieldDataType,
    SearchIndex,
    SearchSuggester,
    SimpleField,
    TextWeights,
    VectorSearch,
    VectorSearchProfile,
)

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
SUB = os.environ.get("FLOCI_AZ_SUBSCRIPTION", "00000000-0000-0000-0000-000000000001")
RG = "sdk-test-rg-search"
SERVICE = "sdk-test-search"
ARM_API = "2025-05-01"

ARM_SERVICE = (
    f"{EMULATOR_BASE}/subscriptions/{SUB}/resourceGroups/{RG}"
    f"/providers/Microsoft.Search/searchServices/{SERVICE}"
)
HEADERS = {"Authorization": "Bearer fake", "Content-Type": "application/json"}


@pytest.fixture(scope="module")
def index_client():
    created = requests.put(
        f"{ARM_SERVICE}?api-version={ARM_API}",
        json={"location": "eastus", "sku": {"name": "basic"}},
        headers=HEADERS,
        timeout=30,
    )
    assert created.status_code in (200, 201), created.text
    try:
        keys = requests.post(f"{ARM_SERVICE}/listAdminKeys?api-version={ARM_API}", headers=HEADERS, timeout=30)
        assert keys.status_code == 200, keys.text

        client = SearchIndexClient(f"{EMULATOR_BASE}/{SERVICE}-search", AzureKeyCredential(keys.json()["primaryKey"]))
        try:
            for name in list(client.list_index_names()):
                client.delete_index(name)
            yield client
        finally:
            client.close()
    finally:
        # A failed delete would leave the service behind for the next run, so it fails the suite.
        deleted = requests.delete(f"{ARM_SERVICE}?api-version={ARM_API}", headers=HEADERS, timeout=30)
        assert deleted.status_code in (200, 204), deleted.text


def hotels(name):
    return SearchIndex(
        name=name,
        fields=[
            SimpleField(name="hotelId", type=SearchFieldDataType.STRING, key=True),
            SearchableField(name="hotelName", type=SearchFieldDataType.STRING, sortable=True),
            SimpleField(name="rating", type=SearchFieldDataType.DOUBLE, filterable=True, facetable=True),
            SearchableField(name="tags", collection=True, filterable=True),
            ComplexField(
                name="address",
                fields=[
                    SearchableField(name="street", type=SearchFieldDataType.STRING),
                    SimpleField(name="city", type=SearchFieldDataType.STRING, filterable=True),
                ],
            ),
            SearchField(
                name="embedding",
                type=SearchFieldDataType.Collection(SearchFieldDataType.SINGLE),
                searchable=True,
                vector_search_dimensions=4,
                vector_search_profile_name="vector-profile",
            ),
        ],
        scoring_profiles=[ScoringProfile(name="boost-name", text_weights=TextWeights(weights={"hotelName": 3.0}))],
        cors_options=CorsOptions(allowed_origins=["*"], max_age_in_seconds=60),
        suggesters=[SearchSuggester(name="sg", source_fields=["hotelName"])],
        vector_search=VectorSearch(
            profiles=[VectorSearchProfile(name="vector-profile", algorithm_configuration_name="hnsw")],
            algorithms=[HnswAlgorithmConfiguration(name="hnsw")],
        ),
    )


def test_create_and_get_index(index_client):
    created = index_client.create_index(hotels("hotels-create"))
    assert created.name == "hotels-create"
    assert created.e_tag

    fetched = index_client.get_index("hotels-create")
    fields = {field.name: field for field in fetched.fields}
    assert list(fields) == ["hotelId", "hotelName", "rating", "tags", "address", "embedding"]
    assert fields["hotelId"].key is True
    assert fields["hotelName"].searchable is True
    assert fields["hotelName"].sortable is True
    assert fields["rating"].searchable is False
    assert fields["rating"].facetable is True
    assert fields["tags"].type == "Collection(Edm.String)"
    assert [sub.name for sub in fields["address"].fields] == ["street", "city"]
    assert fields["embedding"].vector_search_dimensions == 4
    assert fields["embedding"].vector_search_profile_name == "vector-profile"
    assert fetched.scoring_profiles[0].name == "boost-name"
    assert fetched.scoring_profiles[0].text_weights.weights == {"hotelName": 3.0}
    assert fetched.cors_options.allowed_origins == ["*"]
    assert fetched.suggesters[0].source_fields == ["hotelName"]
    assert fetched.vector_search.profiles[0].algorithm_configuration_name == "hnsw"
    assert fetched.e_tag == created.e_tag


def test_a_taken_name_is_a_bad_request_and_a_missing_index_is_not_found(index_client):
    index_client.create_index(hotels("hotels-twice"))
    # Azure answers 400 ResourceNameAlreadyInUse here, not 409.
    with pytest.raises(HttpResponseError) as taken:
        index_client.create_index(hotels("hotels-twice"))
    assert taken.value.status_code == 400
    with pytest.raises(ResourceNotFoundError):
        index_client.get_index("hotels-missing")


def test_list_indexes_and_names(index_client):
    index_client.create_index(hotels("hotels-list-b"))
    index_client.create_index(hotels("hotels-list-a"))

    names = list(index_client.list_index_names())
    assert "hotels-list-a" in names and "hotels-list-b" in names
    assert names == sorted(names)
    listed = {index.name: index for index in index_client.list_indexes()}
    assert len(listed["hotels-list-a"].fields) == 6


def test_create_or_update_adds_a_field_but_cannot_change_one(index_client):
    index = index_client.create_or_update_index(hotels("hotels-update"))
    index.fields.append(SimpleField(name="stars", type=SearchFieldDataType.INT32, filterable=True))

    updated = index_client.create_or_update_index(index)

    assert [field.name for field in updated.fields][-1] == "stars"
    assert updated.e_tag != index.e_tag
    assert len(index_client.get_index("hotels-update").fields) == 7

    updated.fields[2] = SimpleField(name="rating", type=SearchFieldDataType.INT32)
    with pytest.raises(HttpResponseError) as refused:
        index_client.create_or_update_index(updated)
    assert refused.value.status_code == 400


def test_etag_conditions(index_client):
    first = index_client.create_index(hotels("hotels-etag"))
    # An update that changes nothing keeps the ETag, as on Azure, so this one adds a field.
    first.fields.append(SimpleField(name="stars", type=SearchFieldDataType.INT32, filterable=True))
    second = index_client.create_or_update_index(first, match_condition=MatchConditions.IfNotModified)
    assert second.e_tag != first.e_tag

    # The first copy is stale now.
    with pytest.raises(ResourceModifiedError):
        index_client.create_or_update_index(first, match_condition=MatchConditions.IfNotModified)
    with pytest.raises(ResourceModifiedError):
        index_client.delete_index(first, match_condition=MatchConditions.IfNotModified)

    index_client.delete_index(second, match_condition=MatchConditions.IfNotModified)
    with pytest.raises(ResourceNotFoundError):
        index_client.get_index("hotels-etag")


def test_invalid_index_is_refused(index_client):
    without_key = SearchIndex(
        name="hotels-no-key",
        fields=[SimpleField(name="hotelId", type=SearchFieldDataType.STRING)],
    )
    with pytest.raises(HttpResponseError) as refused:
        index_client.create_index(without_key)
    assert refused.value.status_code == 400


def test_statistics(index_client):
    index_client.create_index(hotels("hotels-stats"))

    stats = index_client.get_index_statistics("hotels-stats")
    assert stats.document_count == 0
    assert stats.storage_size == 0

    service = index_client.get_service_statistics()
    assert service.counters.index_counter.usage >= 1
    # The index quota of the basic SKU the fixture created the service with.
    assert service.counters.index_counter.quota == 15
    assert service.limits.max_fields_per_index == 1000


def test_delete_index(index_client):
    index_client.create_index(hotels("hotels-delete"))

    index_client.delete_index("hotels-delete")

    assert "hotels-delete" not in list(index_client.list_index_names())
    with pytest.raises(ResourceNotFoundError):
        index_client.get_index("hotels-delete")
