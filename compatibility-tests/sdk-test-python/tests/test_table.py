import pytest
import uuid
from azure.core.exceptions import ResourceNotFoundError, ResourceExistsError, HttpResponseError
from azure.data.tables import UpdateMode
from azure.core import MatchConditions


def make_table_name():
    return f"test{uuid.uuid4().hex[:8]}"


# --- Golden path ---

def test_table_lifecycle(table_service_client):
    name = make_table_name()

    table_service_client.create_table(name)

    tables = [t.name for t in table_service_client.list_tables()]
    assert name in tables

    table = table_service_client.get_table_client(name)
    entity = {"PartitionKey": "p1", "RowKey": "r1", "Value": "hello"}
    table.create_entity(entity=entity)

    received = table.get_entity(partition_key="p1", row_key="r1")
    assert received["Value"] == "hello"

    entities = list(table.list_entities())
    assert len(entities) == 1
    assert entities[0]["PartitionKey"] == "p1"

    table.delete_entity(partition_key="p1", row_key="r1")
    table_service_client.delete_table(name)

    tables = [t.name for t in table_service_client.list_tables()]
    assert name not in tables


def test_entity_upsert(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    entity = {"PartitionKey": "p1", "RowKey": "r1", "Value": "original"}
    table.create_entity(entity=entity)

    entity["Value"] = "updated"
    table.upsert_entity(entity=entity)

    received = table.get_entity(partition_key="p1", row_key="r1")
    assert received["Value"] == "updated"

    table_service_client.delete_table(name)


def test_multiple_entities(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    for i in range(5):
        table.create_entity({"PartitionKey": "p1", "RowKey": f"r{i}", "Index": i})

    entities = list(table.list_entities())
    assert len(entities) == 5

    table_service_client.delete_table(name)


def test_entity_delete(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    table.create_entity({"PartitionKey": "p1", "RowKey": "r1", "Value": "temp"})
    table.delete_entity(partition_key="p1", row_key="r1")

    entities = list(table.list_entities())
    assert len(entities) == 0

    table_service_client.delete_table(name)


# --- Error cases ---

def test_entity_not_found(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    with pytest.raises(ResourceNotFoundError):
        table.get_entity(partition_key="no-pk", row_key="no-rk")

    table_service_client.delete_table(name)


def test_table_already_exists(table_service_client):
    name = make_table_name()
    table_service_client.create_table(name)

    with pytest.raises(ResourceExistsError):
        table_service_client.create_table(name)

    table_service_client.delete_table(name)


# --- Query / filter tests ---

def test_filter_by_partition_key(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    table.create_entity({"PartitionKey": "p1", "RowKey": "r1", "Value": "alpha"})
    table.create_entity({"PartitionKey": "p1", "RowKey": "r2", "Value": "beta"})
    table.create_entity({"PartitionKey": "p2", "RowKey": "r1", "Value": "gamma"})

    results = list(table.query_entities(query_filter="PartitionKey eq 'p1'"))
    assert len(results) == 2
    assert all(e["PartitionKey"] == "p1" for e in results)

    table_service_client.delete_table(name)


def test_filter_by_numeric_field(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    table.create_entity({"PartitionKey": "p1", "RowKey": "r1", "Score": 10})
    table.create_entity({"PartitionKey": "p1", "RowKey": "r2", "Score": 50})
    table.create_entity({"PartitionKey": "p1", "RowKey": "r3", "Score": 80})

    results = list(table.query_entities(query_filter="Score gt 20"))
    assert len(results) == 2
    assert {e["Score"] for e in results} == {50, 80}

    table_service_client.delete_table(name)


def test_select_fields(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    table.create_entity({"PartitionKey": "p1", "RowKey": "r1", "Name": "Alice", "Age": 30, "City": "NYC"})

    results = list(table.query_entities(query_filter="PartitionKey eq 'p1'", select=["Name", "Age"]))
    assert len(results) == 1
    entity = results[0]
    assert entity["Name"] == "Alice"
    assert entity["Age"] == 30
    assert entity.get("City") is None or "City" not in entity
    assert "PartitionKey" in entity

    table_service_client.delete_table(name)


def test_pagination(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    for i in range(10):
        table.create_entity({"PartitionKey": "p1", "RowKey": f"r{i:02d}", "Index": i})

    paged = table.query_entities(query_filter="PartitionKey eq 'p1'", results_per_page=3)
    all_items = []
    page_count = 0
    for page in paged.by_page():
        page_items = list(page)
        all_items.extend(page_items)
        page_count += 1

    assert len(all_items) == 10
    assert page_count >= 2

    table_service_client.delete_table(name)


# --- ETag / concurrency tests ---

def test_etag_optimistic_concurrency(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    table.create_entity({"PartitionKey": "p1", "RowKey": "r1", "Value": "v1"})

    entity = table.get_entity("p1", "r1")
    old_etag = entity.metadata["etag"]

    # Update the entity — this changes the etag on the server
    entity["Value"] = "v2"
    table.update_entity(entity, mode=UpdateMode.REPLACE)

    # Now try to delete using the OLD etag — should fail with 412
    with pytest.raises((HttpResponseError,)) as exc_info:
        table.delete_entity(
            partition_key="p1",
            row_key="r1",
            etag=old_etag,
            match_condition=MatchConditions.IfNotModified,
        )
    assert exc_info.value.status_code == 412

    table_service_client.delete_table(name)


# --- Batch / transaction tests ---

def test_batch_transaction(table_service_client):
    name = make_table_name()
    table = table_service_client.create_table(name)

    # Submit a batch that creates two entities
    operations = [
        ("create", {"PartitionKey": "p1", "RowKey": "r1", "Value": "hello"}),
        ("create", {"PartitionKey": "p1", "RowKey": "r2", "Value": "world"}),
    ]
    table.submit_transaction(operations)

    r1 = table.get_entity("p1", "r1")
    assert r1["Value"] == "hello"
    r2 = table.get_entity("p1", "r2")
    assert r2["Value"] == "world"

    # Submit a batch that upserts r1 and deletes r2
    operations2 = [
        ("upsert", {"PartitionKey": "p1", "RowKey": "r1", "Value": "updated"}),
        ("delete", {"PartitionKey": "p1", "RowKey": "r2"}),
    ]
    table.submit_transaction(operations2)

    r1_updated = table.get_entity("p1", "r1")
    assert r1_updated["Value"] == "updated"

    with pytest.raises(ResourceNotFoundError):
        table.get_entity("p1", "r2")

    table_service_client.delete_table(name)
