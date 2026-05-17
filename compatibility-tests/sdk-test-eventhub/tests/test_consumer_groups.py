"""Consumer group isolation tests.

Artemis is configured with ANYCAST addresses and exclusive diverts: each
consumer group has its own durable queue that receives an independent copy of
every message sent to the entity address. Tests verify that two consumer groups
both receive all events and that consuming from one does not advance the other.
"""
import uamqp
from .conftest import recv_address, send_messages, DEFAULT_CONSUMER_GROUP, _make_auth

SECONDARY_GROUP = "my-consumer-group"


def test_two_consumer_groups_read_independently():
    """Both consumer groups should receive the same events independently."""
    payloads = [f"shared-{i}" for i in range(5)]

    send_messages(payloads)

    with uamqp.ReceiveClient(recv_address(DEFAULT_CONSUMER_GROUP), auth=_make_auth(), debug=False, prefetch=20) as recv_a:
        batch_a = recv_a.receive_message_batch(max_batch_size=20, timeout=10000)
    with uamqp.ReceiveClient(recv_address(SECONDARY_GROUP), auth=_make_auth(), debug=False, prefetch=20) as recv_b:
        batch_b = recv_b.receive_message_batch(max_batch_size=20, timeout=10000)

    received_a = {list(m.get_data())[0].decode() for m in batch_a}
    received_b = {list(m.get_data())[0].decode() for m in batch_b}

    for p in payloads:
        assert p in received_a, f"Group A missing '{p}'"
        assert p in received_b, f"Group B missing '{p}'"


def test_consumer_group_offsets_are_independent():
    """Consuming from group A should not advance group B's read position."""
    payloads = [f"offset-test-{i}" for i in range(3)]

    send_messages(payloads)

    # Group A reads
    with uamqp.ReceiveClient(recv_address(DEFAULT_CONSUMER_GROUP), auth=_make_auth(), debug=False, prefetch=10) as recv_a:
        batch_a = recv_a.receive_message_batch(max_batch_size=10, timeout=10000)

    # Group B reads independently — should still see all messages unaffected by A
    with uamqp.ReceiveClient(recv_address(SECONDARY_GROUP), auth=_make_auth(), debug=False, prefetch=10) as recv_b:
        batch_b = recv_b.receive_message_batch(max_batch_size=10, timeout=10000)

    received_a = {list(m.get_data())[0].decode() for m in batch_a}
    received_b = {list(m.get_data())[0].decode() for m in batch_b}

    assert len(received_a) >= len(payloads), f"Group A got {len(received_a)}/{len(payloads)}"
    assert len(received_b) >= len(payloads), f"Group B got {len(received_b)}/{len(payloads)}"
    for p in payloads:
        assert p in received_a, f"Group A missing '{p}'"
        assert p in received_b, f"Group B missing '{p}'"
