import os
import pytest
from azure.appconfiguration import AzureAppConfigurationClient
from azure.core.pipeline.transport import RequestsTransport

# Same dev key used across all floci-az emulator tests
DEV_KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMh0=="
ACCOUNT_NAME = "devstoreaccount1"

EMULATOR_BASE = os.environ.get("FLOCI_AZ_ENDPOINT", "http://localhost:4577")


class ForceHttpTransport(RequestsTransport):
    """Rewrites https:// → http:// so plain-HTTP emulator connections work.

    The App Configuration SDK connection string parser assumes https:// (8 chars)
    when extracting the host. Passing an https:// endpoint satisfies that assumption;
    this transport then converts back to http:// before the actual connection.
    """

    def send(self, request, **kwargs):
        request.url = request.url.replace("https://", "http://", 1)
        return super().send(request, **kwargs)


@pytest.fixture(scope="session")
def appconfig_client():
    # Use https:// scheme so the SDK parses the hostname correctly, then
    # ForceHttpTransport converts back to http:// for the actual connection.
    https_base = EMULATOR_BASE.replace("http://", "https://", 1)
    endpoint = f"{https_base}/{ACCOUNT_NAME}-appconfig"
    conn_str = f"Endpoint={endpoint};Id={ACCOUNT_NAME};Secret={DEV_KEY}"
    return AzureAppConfigurationClient.from_connection_string(
        conn_str,
        transport=ForceHttpTransport()
    )
