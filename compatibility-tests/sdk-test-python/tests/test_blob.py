import pytest
import uuid
from azure.core.exceptions import ResourceNotFoundError, ResourceExistsError


def make_container_name():
    return f"test-{uuid.uuid4().hex[:12]}"


# --- Golden path ---

def test_container_lifecycle(blob_service_client):
    name = make_container_name()

    blob_service_client.create_container(name)

    containers = [c.name for c in blob_service_client.list_containers()]
    assert name in containers

    blob_service_client.delete_container(name)

    containers = [c.name for c in blob_service_client.list_containers()]
    assert name not in containers


def test_blob_lifecycle(blob_service_client):
    container_name = make_container_name()
    blob_name = "hello.txt"
    content = b"Hello from Azure SDK Python!"

    blob_service_client.create_container(container_name)
    container = blob_service_client.get_container_client(container_name)

    blob = container.get_blob_client(blob_name)
    blob.upload_blob(content, blob_type="BlockBlob", overwrite=True)

    downloaded = blob.download_blob().readall()
    assert downloaded == content

    blobs = list(container.list_blobs())
    assert len(blobs) == 1
    assert blobs[0].name == blob_name

    blob.delete_blob()
    assert list(container.list_blobs()) == []

    blob_service_client.delete_container(container_name)


def test_multiple_blobs(blob_service_client):
    container_name = make_container_name()
    blob_service_client.create_container(container_name)
    container = blob_service_client.get_container_client(container_name)

    names = [f"file-{i}.txt" for i in range(5)]
    for name in names:
        container.get_blob_client(name).upload_blob(b"data", overwrite=True)

    blobs = [b.name for b in container.list_blobs()]
    assert sorted(blobs) == sorted(names)

    blob_service_client.delete_container(container_name)


# --- Error cases ---

def test_blob_not_found(blob_service_client):
    container_name = make_container_name()
    blob_service_client.create_container(container_name)
    container = blob_service_client.get_container_client(container_name)

    blob = container.get_blob_client("does-not-exist.txt")
    with pytest.raises(ResourceNotFoundError) as exc_info:
        blob.download_blob().readall()
    assert exc_info.value.error_code == "BlobNotFound"

    blob_service_client.delete_container(container_name)


def test_container_already_exists(blob_service_client):
    name = make_container_name()
    blob_service_client.create_container(name)

    with pytest.raises(ResourceExistsError) as exc_info:
        blob_service_client.create_container(name)
    assert exc_info.value.error_code == "ContainerAlreadyExists"

    blob_service_client.delete_container(name)


def test_blob_overwrite(blob_service_client):
    container_name = make_container_name()
    blob_service_client.create_container(container_name)
    container = blob_service_client.get_container_client(container_name)

    blob = container.get_blob_client("overwrite.txt")
    blob.upload_blob(b"original", overwrite=True)
    blob.upload_blob(b"updated", overwrite=True)

    assert blob.download_blob().readall() == b"updated"

    blob_service_client.delete_container(container_name)
