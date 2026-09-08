import os
import re
import time
import pytest
from azure.core.credentials import AccessToken, TokenCredential
from azure.core.pipeline.transport import RequestsTransport
from azure.keyvault.secrets import SecretClient
from azure.keyvault.keys import KeyClient


class FakeCredential(TokenCredential):
    """Returns a static fake Bearer token — no AAD needed for the local emulator."""
    def get_token(self, *scopes, **kwargs):
        return AccessToken("fake-token-for-local-emulator", int(time.time()) + 3600)


class ForceHttpTransport(RequestsTransport):
    """Rewrites https:// → http:// so the SDK's TLS-enforcement check passes
    (it inspects the URL before the transport layer) while traffic actually
    goes to the plain-HTTP emulator."""
    def send(self, request, **kwargs):
        request.url = request.url.replace("https://", "http://", 1)
        return super().send(request, **kwargs)


ENDPOINT = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")
# The SDK requires an https:// vault URL; ForceHttpTransport converts it back to http://
VAULT_URL = re.sub(r"^http://", "https://", ENDPOINT) + "/devstoreaccount1-keyvault"


@pytest.fixture
def client():
    return SecretClient(
        vault_url=VAULT_URL,
        credential=FakeCredential(),
        transport=ForceHttpTransport(),
        verify_challenge_resource=False,
    )


@pytest.fixture
def keys_client():
    return KeyClient(
        vault_url=VAULT_URL,
        credential=FakeCredential(),
        transport=ForceHttpTransport(),
        verify_challenge_resource=False,
    )


@pytest.fixture
def hsm_keys_client():
    hsm_url = re.sub(r"^http://", "https://", ENDPOINT) + "/devstoreaccount1-managedhsm"
    return KeyClient(
        vault_url=hsm_url,
        credential=FakeCredential(),
        transport=ForceHttpTransport(),
        verify_challenge_resource=False,
    )
