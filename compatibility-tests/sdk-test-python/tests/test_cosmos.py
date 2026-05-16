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


def test_aggregate_sum_avg_min_max(cosmos_client):
    """SELECT VALUE SUM/AVG/MIN/MAX(c.field) returns the correct scalar."""
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    prices = [10, 20, 30, 40]   # sum=100, avg=25, min=10, max=40
    for i, price in enumerate(prices):
        container.create_item({"id": f"item-{i}", "category": "agg", "price": price})

    def scalar(sql):
        return list(container.query_items(sql, enable_cross_partition_query=True))[0]

    assert scalar("SELECT VALUE SUM(c.price) FROM c") == 100
    assert scalar("SELECT VALUE AVG(c.price) FROM c") == 25.0
    assert scalar("SELECT VALUE MIN(c.price) FROM c") == 10
    assert scalar("SELECT VALUE MAX(c.price) FROM c") == 40

    cosmos_client.delete_database(db_id)


def test_distinct(cosmos_client):
    """SELECT DISTINCT returns unique projected documents."""
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    for i in range(3):
        container.create_item({"id": f"food-{i}", "category": "food"})
    for i in range(2):
        container.create_item({"id": f"book-{i}", "category": "books"})

    results = list(container.query_items(
        "SELECT DISTINCT c.category FROM c",
        enable_cross_partition_query=True,
    ))
    categories = sorted(r["category"] for r in results)
    assert categories == ["books", "food"]

    cosmos_client.delete_database(db_id)


def test_group_by(cosmos_client):
    """GROUP BY groups documents and COUNT(1) aggregates per group."""
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    for i in range(3):
        container.create_item({"id": f"food-{i}", "category": "food"})
    for i in range(2):
        container.create_item({"id": f"book-{i}", "category": "books"})

    results = list(container.query_items(
        "SELECT c.category, COUNT(1) as count FROM c GROUP BY c.category",
        enable_cross_partition_query=True,
    ))
    counts = {r["category"]: r["count"] for r in results}
    assert counts == {"food": 3, "books": 2}

    cosmos_client.delete_database(db_id)


def test_string_functions(cosmos_client):
    """LOWER/UPPER/LENGTH in WHERE; LOWER/LENGTH/CONCAT in SELECT projection."""
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    container.create_item({"id": "item-1", "category": "Electronics",
                           "name": "Laptop Pro", "first": "John", "last": "Doe"})
    container.create_item({"id": "item-2", "category": "books",
                           "name": "Guide", "first": "Jane", "last": "Smith"})

    # WHERE with LOWER
    r1 = list(container.query_items(
        "SELECT * FROM c WHERE LOWER(c.category) = 'electronics'",
        enable_cross_partition_query=True,
    ))
    assert len(r1) == 1 and r1[0]["id"] == "item-1"

    # WHERE with UPPER
    r2 = list(container.query_items(
        "SELECT * FROM c WHERE UPPER(c.category) = 'BOOKS'",
        enable_cross_partition_query=True,
    ))
    assert len(r2) == 1 and r2[0]["id"] == "item-2"

    # WHERE with LENGTH  ("Laptop Pro"=10 > 5; "Guide"=5 is NOT > 5)
    r3 = list(container.query_items(
        "SELECT * FROM c WHERE LENGTH(c.name) > 5",
        enable_cross_partition_query=True,
    ))
    assert len(r3) == 1 and r3[0]["id"] == "item-1"

    # SELECT with LOWER and LENGTH
    r4 = list(container.query_items(
        "SELECT LOWER(c.category) AS cat, LENGTH(c.name) AS nlen FROM c WHERE c.id = 'item-1'",
        enable_cross_partition_query=True,
    ))
    assert len(r4) == 1
    assert r4[0]["cat"] == "electronics"
    assert r4[0]["nlen"] == 10

    # SELECT with CONCAT
    r5 = list(container.query_items(
        "SELECT CONCAT(c.first, ' ', c.last) AS full_name FROM c WHERE c.id = 'item-1'",
        enable_cross_partition_query=True,
    ))
    assert len(r5) == 1
    assert r5[0]["full_name"] == "John Doe"

    cosmos_client.delete_database(db_id)


def test_patch_document(cosmos_client):
    """PATCH applies partial updates (add, set, replace, remove, incr) to a document."""
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    container.create_item({
        "id": "patch-1", "category": "misc",
        "name": "Original", "counter": 10, "status": "draft", "removable": True
    })

    container.patch_item(
        item="patch-1",
        partition_key="misc",
        patch_operations=[
            {"op": "add",     "path": "/newField", "value": "added"},
            {"op": "set",     "path": "/name",     "value": "Patched"},
            {"op": "replace", "path": "/status",   "value": "active"},
            {"op": "remove",  "path": "/removable"},
            {"op": "incr",    "path": "/counter",  "value": 5},
        ],
    )

    result = container.read_item("patch-1", partition_key="misc")
    assert result["newField"] == "added"
    assert result["name"] == "Patched"
    assert result["status"] == "active"
    assert "removable" not in result
    assert result["counter"] == 15

    cosmos_client.delete_database(db_id)


def test_transactional_batch(cosmos_client):
    """Transactional batch executes Create/Read/Replace/Delete/Upsert atomically."""
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    # Batch 1: Create × 2 + Upsert
    r1 = container.execute_item_batch(
        batch_operations=[
            ("create", ({"id": "b1", "category": "test", "v": 1},), {}),
            ("create", ({"id": "b2", "category": "test", "v": 2},), {}),
            ("upsert", ({"id": "b3", "category": "test", "v": 3},), {}),
        ],
        partition_key="test",
    )
    assert r1[0]["statusCode"] == 201
    assert r1[1]["statusCode"] == 201
    assert r1[2]["statusCode"] in (200, 201)  # upsert of new doc → 201

    assert container.read_item("b1", partition_key="test")["v"] == 1

    # Batch 2: Read + Replace + Delete
    r2 = container.execute_item_batch(
        batch_operations=[
            ("read",    ("b1",),                                                  {}),
            ("replace", ("b2", {"id": "b2", "category": "test", "v": 99}),       {}),
            ("delete",  ("b3",),                                                  {}),
        ],
        partition_key="test",
    )
    assert r2[0]["statusCode"] == 200   # read
    assert r2[1]["statusCode"] == 200   # replace
    assert r2[2]["statusCode"] == 204   # delete

    assert container.read_item("b2", partition_key="test")["v"] == 99
    with pytest.raises(CosmosResourceNotFoundError):
        container.read_item("b3", partition_key="test")

    cosmos_client.delete_database(db_id)


def test_pagination_by_page(cosmos_client):
    """x-ms-max-item-count is respected; x-ms-continuation lets SDK iterate pages."""
    db_id = db_name()
    db = cosmos_client.create_database(db_id)
    container = db.create_container("items", partition_key=PartitionKey(path="/category"))

    total = 10
    for i in range(total):
        container.create_item({"id": f"item-{i:02d}", "category": "page-test", "rank": i})

    PAGE = 3
    all_ids = []
    page_count = 0
    pager = container.query_items(
        "SELECT * FROM c",
        enable_cross_partition_query=True,
        max_item_count=PAGE,
    ).by_page()

    for page in pager:
        items = list(page)
        assert len(items) <= PAGE, f"Page {page_count} has {len(items)} items, expected <= {PAGE}"
        all_ids.extend(item["id"] for item in items)
        page_count += 1

    assert page_count >= 2, "Expected at least 2 pages"
    assert len(all_ids) == total
    assert set(all_ids) == {f"item-{i:02d}" for i in range(total)}

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
