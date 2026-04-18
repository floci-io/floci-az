import pytest
import uuid
from azure.core.exceptions import ResourceNotFoundError, ResourceExistsError


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


# --- Error cases ---

def test_queue_not_found(queue_service_client):
    queue = queue_service_client.get_queue_client("nonexistent-queue-xyz")
    with pytest.raises(ResourceNotFoundError):
        list(queue.receive_messages())
