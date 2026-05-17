"""Basic send/receive tests for the Event Hubs AMQP endpoint (via uamqp)."""
import uamqp
from .conftest import SEND_ADDRESS, RECV_DEFAULT, send_messages, recv_messages, _make_auth


def test_send_and_receive():
    """Send 10 events; all should be received from the $Default consumer group."""
    payloads = [f"message-{i}" for i in range(10)]

    send_messages(payloads)
    with uamqp.ReceiveClient(RECV_DEFAULT, auth=_make_auth(), debug=False, prefetch=20) as receiver:
        batch = receiver.receive_message_batch(max_batch_size=20, timeout=10000)

    received = [list(m.get_data())[0].decode() for m in batch]
    for p in payloads:
        assert p in received, f"Expected '{p}' in received messages"


def test_send_single_events():
    """Send three individual events; each should be received."""
    payloads = ["alpha", "beta", "gamma"]

    send_messages(payloads)
    with uamqp.ReceiveClient(RECV_DEFAULT, auth=_make_auth(), debug=False, prefetch=10) as receiver:
        batch = receiver.receive_message_batch(max_batch_size=10, timeout=10000)

    received = [list(m.get_data())[0].decode() for m in batch]
    for p in payloads:
        assert p in received, f"Expected '{p}' in received messages"
