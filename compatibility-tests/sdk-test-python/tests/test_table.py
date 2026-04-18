import pytest
import uuid
from azure.core.exceptions import ResourceNotFoundError, ResourceExistsError


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
