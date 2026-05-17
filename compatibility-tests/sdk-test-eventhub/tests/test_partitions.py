"""Partition-related tests.

NOTE: Artemis is a plain AMQP 1.0 broker and does not implement the Azure
Event Hubs AMQP management extension (com.microsoft:eventhub management
requests). As a result, partition-specific features of the azure-eventhub
SDK (partition metadata queries, partition_key routing) are not available
with this emulator.

The tests below verify the AMQP connectivity and that message delivery
works without requiring partition management, which is what the Artemis
sidecar provides.
"""
import uamqp
from .conftest import SEND_ADDRESS, RECV_DEFAULT, send_messages, _make_auth


def test_messages_are_delivered_without_partition_key():
    """Events sent without a partition key are delivered to the consumer queue."""
    payloads = [f"no-key-{i}" for i in range(4)]

    send_messages(payloads)
    with uamqp.ReceiveClient(RECV_DEFAULT, auth=_make_auth(), debug=False, prefetch=10) as receiver:
        batch = receiver.receive_message_batch(max_batch_size=10, timeout=10000)

    received = {list(m.get_data())[0].decode() for m in batch}
    for p in payloads:
        assert p in received, f"Expected '{p}' in received messages"


def test_high_throughput_delivery():
    """Verify that a larger batch of messages is reliably delivered."""
    payloads = [f"bulk-{i}" for i in range(50)]

    with uamqp.SendClient(SEND_ADDRESS, auth=_make_auth(), debug=False) as sender:
        for p in payloads:
            sender.send_message(uamqp.Message(p.encode()))

    with uamqp.ReceiveClient(RECV_DEFAULT, auth=_make_auth(), debug=False, prefetch=60) as receiver:
        batch = receiver.receive_message_batch(max_batch_size=60, timeout=15000)

    received = {list(m.get_data())[0].decode() for m in batch}
    assert len(received) >= len(payloads), (
        f"Expected at least {len(payloads)} messages, got {len(received)}"
    )
