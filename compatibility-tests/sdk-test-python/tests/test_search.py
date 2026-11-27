"""Azure AI Search compatibility test: index management, documents and queries.

Creates a search service through the ARM management plane, reads its admin key, manages
indexes on the service's data plane with ``SearchIndexClient``, and searches them with
``SearchClient``; the emulator's Solr sidecar runs the queries.
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
from azure.search.documents import SearchIndexingBufferedSender
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


def test_create_get_list_and_delete_an_index(index_client):
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

    index_client.create_index(hotels("hotels-another"))
    names = list(index_client.list_index_names())
    assert {"hotels-another", "hotels-create"} <= set(names) and names == sorted(names)
    listed = {index.name: index for index in index_client.list_indexes()}
    assert len(listed["hotels-another"].fields) == 6

    index_client.delete_index("hotels-another")
    assert "hotels-another" not in list(index_client.list_index_names())
    with pytest.raises(ResourceNotFoundError):
        index_client.get_index("hotels-another")


def test_a_taken_name_is_a_bad_request(index_client):
    index_client.create_index(hotels("hotels-twice"))
    # Azure answers 400 ResourceNameAlreadyInUse here, not 409.
    with pytest.raises(HttpResponseError) as taken:
        index_client.create_index(hotels("hotels-twice"))
    assert taken.value.status_code == 400



def test_create_or_update_adds_a_field_under_its_etag_but_cannot_change_one(index_client):
    first = index_client.create_index(hotels("hotels-etag"))
    # An update that changes nothing keeps the ETag, as on Azure, so this one adds a field.
    first.fields.append(SimpleField(name="stars", type=SearchFieldDataType.INT32, filterable=True))
    second = index_client.create_or_update_index(first, match_condition=MatchConditions.IfNotModified)
    assert [field.name for field in second.fields][-1] == "stars"
    assert second.e_tag != first.e_tag
    assert len(index_client.get_index("hotels-etag").fields) == 7

    # The first copy is stale now.
    with pytest.raises(ResourceModifiedError):
        index_client.create_or_update_index(first, match_condition=MatchConditions.IfNotModified)
    with pytest.raises(ResourceModifiedError):
        index_client.delete_index(first, match_condition=MatchConditions.IfNotModified)

    # An existing field cannot change its type.
    second.fields[2] = SimpleField(name="rating", type=SearchFieldDataType.INT32)
    with pytest.raises(HttpResponseError) as refused:
        index_client.create_or_update_index(second)
    assert refused.value.status_code == 400

    current = index_client.get_index("hotels-etag")
    index_client.delete_index(current, match_condition=MatchConditions.IfNotModified)
    with pytest.raises(ResourceNotFoundError):
        index_client.get_index("hotels-etag")


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



def test_upload_lookup_and_count_documents(index_client):
    index_client.create_index(hotels("hotels-docs"))
    client = index_client.get_search_client("hotels-docs")

    results = client.upload_documents([
        {"hotelId": "1", "hotelName": "Fancy", "rating": 4.5, "tags": ["pool"],
         "address": {"street": "Main", "city": "Helsinki"}, "embedding": [0.1, 0.2, 0.3, 0.4]},
        {"hotelId": "2", "hotelName": "Plain", "rating": 2.0},
    ])
    assert [(r.key, r.succeeded, r.status_code) for r in results] == [("1", True, 201), ("2", True, 201)]

    fetched = client.get_document("1")
    assert fetched["hotelName"] == "Fancy"
    assert fetched["address"] == {"street": "Main", "city": "Helsinki"}
    # A vector field is not retrievable unless the index says so.
    assert "embedding" not in fetched
    assert client.get_document("2")["tags"] == []
    selected = client.get_document("1", selected_fields=["hotelName"])
    assert selected["hotelName"] == "Fancy" and "rating" not in selected
    assert client.get_document_count() == 2
    with pytest.raises(ResourceNotFoundError):
        client.get_document("missing")


def test_merge_merge_or_upload_and_delete_documents(index_client):
    index_client.create_index(hotels("hotels-merge"))
    client = index_client.get_search_client("hotels-merge")
    client.upload_documents([{"hotelId": "1", "hotelName": "Fancy", "rating": 4.5,
                              "address": {"street": "Main", "city": "Helsinki"}}])

    merged = client.merge_documents([{"hotelId": "1", "rating": 5.0, "address": {"city": "Turku"}}])
    assert merged[0].status_code == 200
    fetched = client.get_document("1")
    assert fetched["hotelName"] == "Fancy" and fetched["rating"] == 5.0
    # A complex field merges field by field, as on Azure.
    assert fetched["address"] == {"street": "Main", "city": "Turku"}

    upserted = client.merge_or_upload_documents([{"hotelId": "1", "hotelName": "Fancier"},
                                                 {"hotelId": "3", "hotelName": "New"}])
    assert [r.status_code for r in upserted] == [200, 201]

    # A merge of a missing key fails that action alone (207); the SDK hands back every result.
    mixed = client.merge_documents([{"hotelId": "3", "rating": 1.0}, {"hotelId": "missing", "rating": 1.0}])
    assert [(r.key, r.succeeded, r.status_code) for r in mixed] == [("3", True, 200), ("missing", False, 404)]
    assert mixed[1].error_message == "Document not found."

    deleted = client.delete_documents([{"hotelId": "1"}, {"hotelId": "never"}])
    assert [r.status_code for r in deleted] == [200, 200]
    assert client.get_document_count() == 1


def test_a_point_round_trips_with_azures_crs(index_client):
    index = hotels("hotels-geo")
    index.fields.append(SimpleField(name="location", type=SearchFieldDataType.GEOGRAPHY_POINT))
    index_client.create_index(index)
    client = index_client.get_search_client("hotels-geo")

    client.upload_documents([{"hotelId": "1", "location": {"type": "Point", "coordinates": [24.94, 60.17]}}])

    location = client.get_document("1")["location"]
    assert location["coordinates"] == [24.94, 60.17]
    assert location["crs"]["properties"]["name"] == "EPSG:4326"



def test_buffered_sender_flushes_documents(index_client):
    index_client.create_index(hotels("hotels-buffered"))
    keys = requests.post(f"{ARM_SERVICE}/listAdminKeys?api-version={ARM_API}", headers=HEADERS, timeout=30)
    credential = AzureKeyCredential(keys.json()["primaryKey"])

    with SearchIndexingBufferedSender(f"{EMULATOR_BASE}/{SERVICE}-search", "hotels-buffered", credential) as sender:
        sender.upload_documents([{"hotelId": str(i), "hotelName": f"Hotel {i}"} for i in range(50)])

    assert index_client.get_search_client("hotels-buffered").get_document_count() == 50


@pytest.fixture(scope="module")
def query_client(index_client):
    """One index for every query test, loaded once."""
    index = hotels("hotels-query")
    index.fields += [
        SimpleField(name="opened", type=SearchFieldDataType.DATE_TIME_OFFSET, filterable=True, sortable=True,
                    facetable=True),
        SimpleField(name="location", type=SearchFieldDataType.GEOGRAPHY_POINT, filterable=True, sortable=True),
    ]
    index_client.create_index(index)
    client = index_client.get_search_client("hotels-query")
    client.upload_documents([
        {"hotelId": "1", "hotelName": "Fancy seaside hotel", "rating": 4.5, "tags": ["pool", "wifi"],
         "address": {"street": "Harbour road", "city": "Helsinki"}, "opened": "2020-05-01T00:00:00Z",
         "location": {"type": "Point", "coordinates": [24.94, 60.17]}},
        {"hotelId": "2", "hotelName": "Plain hotel", "rating": 2.0, "tags": ["wifi"], "opened": "2021-06-01T00:00:00Z"},
        {"hotelId": "3", "hotelName": "Fancy resort", "rating": 5.0, "tags": ["spa"], "opened": "2022-07-01T00:00:00Z",
         "location": {"type": "Point", "coordinates": [2.35, 48.85]}},
    ] + [{"hotelId": f"p{i}", "hotelName": "Paged inn"} for i in range(60)])
    return client


def test_search_ranks_highlights_and_pages(query_client):
    results = query_client.search("fancy", include_total_count=True, select=["hotelId", "hotelName"])
    hits = list(results)
    assert results.get_count() == 2
    assert sorted(hit["hotelId"] for hit in hits) == ["1", "3"]
    assert all(hit["@search.score"] > 0 for hit in hits)
    assert set(hits[0].keys()) >= {"hotelId", "hotelName"} and "rating" not in hits[0]
    assert [hit["hotelId"] for hit in query_client.search("harbour", search_fields=["address/street"])] == ["1"]

    assert [hit["hotelId"] for hit in query_client.search("fancy hotel", search_mode="all")] == ["1"]
    fuzzy = query_client.search("hotelName:fanci~1", query_type="full")
    assert sorted(hit["hotelId"] for hit in fuzzy) == ["1", "3"]
    highlighted = list(query_client.search("seaside", highlight_fields="hotelName"))
    assert highlighted[0]["@search.highlights"]["hotelName"] == ["Fancy <em>seaside</em> hotel"]

    # Sixty results take two pages: the SDK follows the next page parameters.
    paged = query_client.search("paged", include_total_count=True)
    assert len(list(paged)) == 60
    assert paged.get_count() == 60


def test_search_filters_sorts_and_facets(query_client):
    ids = [hit["hotelId"] for hit in query_client.search("*", filter="rating ge 4 and tags/any(t: t eq 'pool')")]
    assert ids == ["1"]
    ordered = query_client.search("*", filter="opened ne null", order_by=["opened desc"])
    assert [hit["hotelId"] for hit in ordered] == ["3", "2", "1"]
    near = query_client.search("*", filter="geo.distance(location, geography'POINT(24.9 60.2)') lt 10")
    assert [hit["hotelId"] for hit in near] == ["1"]
    assert [hit["hotelId"] for hit in query_client.search("*", filter="opened lt 2021-01-01T00:00:00Z")] == ["1"]

    faceted = query_client.search("*", facets=["rating,values:3", "opened,interval:year"], top=0)
    facets = faceted.get_facets()
    assert [(b.get("from"), b.get("to"), b["count"]) for b in facets["rating"]] == [(None, 3, 1), (3, None, 2)]
    assert [b["count"] for b in facets["opened"]] == [1, 1, 1]
