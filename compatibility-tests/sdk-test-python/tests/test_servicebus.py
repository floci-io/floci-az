"""Service Bus over AMQP with the Python SDK.

The Python SDK names every entity by URI (``amqps://{host}/{path}``), unlike the Java and .NET
SDKs, which send bare paths. These tests cover the receive paths only URI addressing reaches:
topic subscriptions and dead-letter queues.

Needs azure-servicebus 7.15.0 or later. Earlier releases can't decode AMQP performatives whose
trailing fields are omitted, which is how Artemis sends them, and fail before reaching these paths.
"""
import os
import time
import uuid

import pytest
import requests
from azure.servicebus import ServiceBusClient, ServiceBusMessage, ServiceBusSubQueue

BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
ACCOUNT = os.environ.get("FLOCI_AZ_ACCOUNT", "devstoreaccount1")
HOST = os.environ.get("SERVICEBUS_HOST", "localhost")
PORT = int(os.environ.get("SERVICEBUS_AMQP_PORT", "5673"))
NAMESPACE = os.environ.get("SERVICEBUS_NAMESPACE", "default")
CONNECTION_STRING = (
    f"Endpoint=sb://{HOST}:{PORT};"
    "SharedAccessKeyName=RootManageSharedAccessKey;"
    "SharedAccessKey=devkey;UseDevelopmentEmulator=true;"
)
WAIT_SECONDS = 10

pytestmark = pytest.mark.skipif(
    not os.environ.get("SERVICEBUS_HOST"),
    reason="needs SERVICEBUS_HOST: a floci-az with FLOCI_AZ_SERVICES_SERVICE_BUS_MOCKED=false",
)


@pytest.fixture(scope="module", autouse=True)
def servicebus_namespace():
    response = requests.put(
        f"{BASE}/{ACCOUNT}-servicebus/namespaces/{NAMESPACE}", json={}, timeout=120
    )
    response.raise_for_status()
    assert response.json().get("mocked") is False, (
        "Service Bus compatibility tests require mocked=false"
    )


def create_entity(path):
    response = requests.put(
        f"{BASE}/{ACCOUNT}-servicebus/{NAMESPACE}/{path}",
        data="",
        headers={"Content-Type": "application/atom+xml;charset=utf-8"},
        timeout=30,
    )
    response.raise_for_status()


def unique(prefix):
    return f"{prefix}-{int(time.time() * 1000)}-{uuid.uuid4().hex[:8]}"


def receive_one(receiver):
    messages = receiver.receive_messages(max_message_count=1, max_wait_time=WAIT_SECONDS)
    assert len(messages) == 1, "expected one message"
    return messages[0]


def test_receive_from_topic_subscription():
    topic = unique("py-topic")
    create_entity(f"topics/{topic}")
    create_entity(f"topics/{topic}/subscriptions/worker")
    body = unique("subscription-body")

    with ServiceBusClient.from_connection_string(CONNECTION_STRING) as client:
        with client.get_topic_sender(topic) as sender:
            sender.send_messages(ServiceBusMessage(body))
        with client.get_subscription_receiver(topic, "worker", max_wait_time=WAIT_SECONDS) as receiver:
            message = receive_one(receiver)
            assert str(message) == body
            receiver.complete_message(message)


def test_receive_from_queue_dead_letter_queue():
    queue = unique("py-dlq-queue")
    create_entity(f"queues/{queue}")
    body = unique("dead-letter-body")

    with ServiceBusClient.from_connection_string(CONNECTION_STRING) as client:
        with client.get_queue_sender(queue) as sender:
            sender.send_messages(ServiceBusMessage(body))
        with client.get_queue_receiver(queue, max_wait_time=WAIT_SECONDS) as receiver:
            receiver.dead_letter_message(receive_one(receiver), reason="compat-test")
        with client.get_queue_receiver(
            queue, sub_queue=ServiceBusSubQueue.DEAD_LETTER, max_wait_time=WAIT_SECONDS
        ) as dead_letters:
            message = receive_one(dead_letters)
            assert str(message) == body
            dead_letters.complete_message(message)


def test_receive_from_subscription_dead_letter_queue():
    topic = unique("py-dlq-topic")
    create_entity(f"topics/{topic}")
    create_entity(f"topics/{topic}/subscriptions/worker")
    body = unique("subscription-dead-letter-body")

    with ServiceBusClient.from_connection_string(CONNECTION_STRING) as client:
        with client.get_topic_sender(topic) as sender:
            sender.send_messages(ServiceBusMessage(body))
        with client.get_subscription_receiver(topic, "worker", max_wait_time=WAIT_SECONDS) as receiver:
            receiver.dead_letter_message(receive_one(receiver), reason="compat-test")
        with client.get_subscription_receiver(
            topic, "worker", sub_queue=ServiceBusSubQueue.DEAD_LETTER, max_wait_time=WAIT_SECONDS
        ) as dead_letters:
            message = receive_one(dead_letters)
            assert str(message) == body
            dead_letters.complete_message(message)
