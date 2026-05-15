import pytest
import uuid
from azure.cosmos import PartitionKey
from azure.cosmos.exceptions import CosmosResourceNotFoundError


def db_name():
    return f"testdb-{uuid.uuid4().hex[:12]}"


# --- Golden path ---

def test_database_lifecycle(cosmos_client):
    name = db_name()

    cosmos_client.create_database(name)

    dbs = [d["id"] for d in cosmos_client.list_databases()]
    assert name in dbs

    cosmos_client.delete_database(name)

    dbs_after = [d["id"] for d in cosmos_client.list_databases()]
    assert name not in dbs_after


def test_container_lifecycle(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)

    db.create_container("items", partition_key=PartitionKey(path="/category"))

    containers = [c["id"] for c in db.list_containers()]
    assert "items" in containers

    db.delete_container("items")

    containers_after = [c["id"] for c in db.list_containers()]
    assert "items" not in containers_after

    cosmos_client.delete_database(db_id)


def test_document_crud(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    # Create
    created = container.create_item({
        "id": "laptop-1", "category": "electronics", "name": "Laptop Pro", "price": 1299
    })
    assert created["id"] == "laptop-1"
    assert "_etag" in created
    assert "_ts" in created

    # Read
    item = container.read_item("laptop-1", partition_key="electronics")
    assert item["name"] == "Laptop Pro"
    assert item["price"] == 1299

    # Replace
    item["price"] = 999
    replaced = container.replace_item("laptop-1", item)
    assert replaced["price"] == 999

    refreshed = container.read_item("laptop-1", partition_key="electronics")
    assert refreshed["price"] == 999

    # Delete
    container.delete_item("laptop-1", partition_key="electronics")

    with pytest.raises(CosmosResourceNotFoundError):
        container.read_item("laptop-1", partition_key="electronics")

    cosmos_client.delete_database(db_id)


def test_document_upsert(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    # Upsert creates
    container.upsert_item({"id": "item-1", "category": "tools", "stock": 10})
    assert container.read_item("item-1", partition_key="tools")["stock"] == 10

    # Upsert overwrites
    container.upsert_item({"id": "item-1", "category": "tools", "stock": 5})
    assert container.read_item("item-1", partition_key="tools")["stock"] == 5

    cosmos_client.delete_database(db_id)


def test_document_list(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    for i in range(3):
        container.create_item({"id": f"item-{i}", "category": "books", "title": f"Book {i}"})

    items = list(container.read_all_items())
    assert len(items) == 3
    ids = {item["id"] for item in items}
    assert ids == {"item-0", "item-1", "item-2"}

    cosmos_client.delete_database(db_id)


def test_query_select_all(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    container.create_item({"id": "a", "category": "food", "name": "Apple"})
    container.create_item({"id": "b", "category": "food", "name": "Banana"})

    results = list(container.query_items("SELECT * FROM c", enable_cross_partition_query=True))
    assert len(results) == 2

    cosmos_client.delete_database(db_id)


def test_query_where_with_parameter(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    container.create_item({"id": "cheap", "category": "misc", "price": 10})
    container.create_item({"id": "expensive", "category": "misc", "price": 500})

    results = list(container.query_items(
        "SELECT * FROM c WHERE c.price > @minPrice",
        parameters=[{"name": "@minPrice", "value": 100}],
        enable_cross_partition_query=True,
    ))
    assert len(results) == 1
    assert results[0]["id"] == "expensive"

    cosmos_client.delete_database(db_id)


def test_query_order_by(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    container.create_item({"id": "c", "category": "sort", "rank": 3})
    container.create_item({"id": "a", "category": "sort", "rank": 1})
    container.create_item({"id": "b", "category": "sort", "rank": 2})

    results = list(container.query_items(
        "SELECT * FROM c ORDER BY c.rank ASC",
        enable_cross_partition_query=True,
    ))
    assert [r["rank"] for r in results] == [1, 2, 3]

    cosmos_client.delete_database(db_id)


def test_query_count(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    for i in range(4):
        container.create_item({"id": f"item-{i}", "category": "count-test"})

    results = list(container.query_items(
        "SELECT VALUE COUNT(1) FROM c",
        enable_cross_partition_query=True,
    ))
    assert results[0] == 4

    cosmos_client.delete_database(db_id)


def test_database_cascade_delete(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))
    container.create_item({"id": "orphan", "category": "misc"})

    cosmos_client.delete_database(db_id)

    dbs_after = [d["id"] for d in cosmos_client.list_databases()]
    assert db_id not in dbs_after


# --- Error cases ---

def test_document_not_found(cosmos_client):
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    with pytest.raises(CosmosResourceNotFoundError):
        container.read_item("no-such-doc", partition_key="misc")

    cosmos_client.delete_database(db_id)


def test_database_not_found(cosmos_client):
    with pytest.raises(CosmosResourceNotFoundError):
        cosmos_client.get_database_client("no-such-db-xyz").read()
