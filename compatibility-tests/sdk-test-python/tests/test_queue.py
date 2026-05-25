import time
import pytest
import uuid
from azure.core.exceptions import HttpResponseError, ResourceNotFoundError, ResourceExistsError


def make_queue_name():
    return f"test-{uuid.uuid4().hex[:12]}"


# --- Golden path ---

def test_queue_lifecycle(queue_service_client):
    name = make_queue_name()

    queue_service_client.create_queue(name)

    queues = [q.name for q in queue_service_client.list_queues()]
    assert name in queues

    queue = queue_service_client.get_queue_client(name)
    queue.send_message("Hello Queue!")

    messages = list(queue.receive_messages())
    assert len(messages) == 1
    assert messages[0].content == "Hello Queue!"

    queue.clear_messages()
    assert list(queue.peek_messages()) == []

    queue_service_client.delete_queue(name)

    queues = [q.name for q in queue_service_client.list_queues()]
    assert name not in queues


def test_multiple_messages(queue_service_client):
    name = make_queue_name()
    queue = queue_service_client.create_queue(name)

    payloads = [f"msg-{i}" for i in range(5)]
    for p in payloads:
        queue.send_message(p)

    peeked = list(queue.peek_messages(max_messages=5))
    assert len(peeked) == 5

    queue_service_client.delete_queue(name)


def test_peek_does_not_consume(queue_service_client):
    name = make_queue_name()
    queue = queue_service_client.create_queue(name)

    queue.send_message("persistent")

    queue.peek_messages()
    queue.peek_messages()

    received = list(queue.receive_messages())
    assert len(received) == 1
    assert received[0].content == "persistent"

    queue_service_client.delete_queue(name)


def test_message_delete(queue_service_client):
    name = make_queue_name()
    queue = queue_service_client.create_queue(name)

    queue.send_message("to-delete")
    messages = list(queue.receive_messages())
    assert len(messages) == 1

    queue.delete_message(messages[0])

    assert list(queue.peek_messages()) == []

    queue_service_client.delete_queue(name)


def test_queue_metadata_round_trip(queue_service_client):
    name = make_queue_name()
    metadata = {"owner": "compat", "purpose": "queue-parity"}

    queue = queue_service_client.create_queue(name, metadata=metadata)

    props = queue.get_queue_properties()
    assert props.metadata == metadata

    listed = list(queue_service_client.list_queues(name_starts_with=name, include_metadata=True))
    assert len(listed) == 1
    assert listed[0].metadata == metadata

    queue_service_client.delete_queue(name)


def test_send_message_visibility_timeout(queue_service_client):
    name = make_queue_name()
    queue = queue_service_client.create_queue(name)

    queue.send_message("delayed", visibility_timeout=1, time_to_live=5)

    assert list(queue.peek_messages()) == []
    time.sleep(2)
    messages = list(queue.peek_messages())
    assert len(messages) == 1
    assert messages[0].content == "delayed"

    queue_service_client.delete_queue(name)


def test_message_ttl_expiration(queue_service_client):
    name = make_queue_name()
    queue = queue_service_client.create_queue(name)

    queue.send_message("short-lived", time_to_live=1)

    time.sleep(2)
    assert list(queue.peek_messages()) == []

    queue_service_client.delete_queue(name)


def test_wrong_pop_receipt_rejected(queue_service_client):
    name = make_queue_name()
    queue = queue_service_client.create_queue(name)

    queue.send_message("guarded")
    message = next(queue.receive_messages())

    with pytest.raises(HttpResponseError) as exc:
        queue.delete_message(message.id, "wrong-receipt")
    assert exc.value.status_code == 400

    queue.delete_message(message)
    queue_service_client.delete_queue(name)


def test_update_message_replaces_content_and_rotates_pop_receipt(queue_service_client):
    name = make_queue_name()
    queue = queue_service_client.create_queue(name)

    queue.send_message("before")
    message = next(queue.receive_messages())
    updated = queue.update_message(message, content="after", visibility_timeout=0)

    with pytest.raises(HttpResponseError) as exc:
        queue.delete_message(message.id, message.pop_receipt)
    assert exc.value.status_code == 400

    peeked = list(queue.peek_messages())
    assert len(peeked) == 1
    assert peeked[0].content == "after"

    queue.delete_message(message.id, updated.pop_receipt)
    queue_service_client.delete_queue(name)


# --- Error cases ---

def test_queue_not_found(queue_service_client):
    queue = queue_service_client.get_queue_client("nonexistent-queue-xyz")
    with pytest.raises(ResourceNotFoundError):
        list(queue.receive_messages())
