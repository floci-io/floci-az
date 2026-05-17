import os
import tempfile
import urllib.request
import uamqp
from uamqp import authentication

HOST = os.environ.get("EVENTHUB_HOST", "localhost")
# uamqp requires TLS and defaults to port 5671; Artemis exposes AMQPS on 5671.
AMQPS_PORT = int(os.environ.get("EVENTHUB_AMQPS_PORT", "5671"))
NAMESPACE = os.environ.get("EVENTHUB_NAMESPACE", "emulatorNs1")
EVENTHUB_NAME = os.environ.get("EVENTHUB_NAME", "eh1")
FLOCI_AZ_ENDPOINT = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
ACCOUNT = os.environ.get("EVENTHUB_ACCOUNT", "devstoreaccount1")
DEFAULT_CONSUMER_GROUP = "$Default"

# Producers send to the entity address; Artemis diverts messages to per-CG ANYCAST
# queues. Consumers attach to the consumer-group address to receive from a durable
# queue that persists messages independently of connection timing.
ENTITY_ADDRESS = f"amqp://{HOST}/{NAMESPACE}/{EVENTHUB_NAME}"
SEND_ADDRESS = ENTITY_ADDRESS
RECV_DEFAULT = f"{ENTITY_ADDRESS}/{DEFAULT_CONSUMER_GROUP}"


def recv_address(consumer_group: str = DEFAULT_CONSUMER_GROUP) -> str:
    return f"{ENTITY_ADDRESS}/{consumer_group}"


# --- TLS cert setup ----------------------------------------------------------

def _fetch_cert_file() -> str | None:
    """Download Artemis self-signed cert from the emulator and write to a temp file."""
    url = f"{FLOCI_AZ_ENDPOINT}/{ACCOUNT}-eventhub/tls-cert"
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            pem = resp.read().decode()
        f = tempfile.NamedTemporaryFile(mode="w", suffix=".pem", delete=False)
        f.write(pem)
        f.close()
        return f.name
    except Exception as e:
        print(f"Warning: could not fetch Artemis TLS cert from {url}: {e}")
        return None


_CERT_FILE: str | None = _fetch_cert_file()


def _make_auth() -> authentication.SASLAnonymous:
    """Create a SASLAnonymous auth that connects to Artemis via TLS on port 5671."""
    return authentication.SASLAnonymous(HOST, port=AMQPS_PORT, verify=_CERT_FILE)


# --- Helpers -----------------------------------------------------------------

def send_messages(texts: list[str]) -> None:
    """Send a list of string messages to the event hub via AMQPS."""
    auth = _make_auth()
    with uamqp.SendClient(SEND_ADDRESS, auth=auth, debug=False) as sender:
        for text in texts:
            sender.send_message(uamqp.Message(text.encode()))


def recv_messages(consumer_group: str = DEFAULT_CONSUMER_GROUP,
                  count: int = 10, timeout_ms: int = 10000) -> list[str]:
    """Receive up to count messages from the given consumer group via AMQPS."""
    auth = _make_auth()
    addr = recv_address(consumer_group)
    with uamqp.ReceiveClient(addr, auth=auth, debug=False, prefetch=count) as receiver:
        batch = receiver.receive_message_batch(max_batch_size=count, timeout=timeout_ms)
    return [_decode(msg) for msg in batch]


def _decode(msg) -> str:
    data = list(msg.get_data())
    return data[0].decode() if data else ""
