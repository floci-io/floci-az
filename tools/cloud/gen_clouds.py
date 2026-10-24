#!/usr/bin/env python3
"""Regenerate src/main/resources/azure/clouds.json from the local Azure reference checkouts.

floci-az serves the public Azure cloud, Azure US Government and Azure China from one instance, and
the Java side (`io.floci.az.core.cloud.CloudCatalog`) reads one vendored file for all of it: each
cloud's endpoints, ARM audiences, login hosts, the DNS suffix of every host-addressed service
(keyed by the `CloudSuffix` constant names), its locations with their availability zones, and the
`/metadata/endpoints` payloads for api-versions 2022-09-01, 2019-05-01 and 2018-01-01. Nothing in
that file is hand-typed; every value is read from a file under `local/azure`, a directory of upstream
checkouts that is not part of this repository (`tools/cloud/README.md` says how to recreate it):

- azure-cli `cloud.py`: the hardcoded clouds (endpoints, suffixes, audiences) and the
  `ARM_METADATA_INDEX` tables that map them to `/metadata/endpoints` keys.
- go-azure-sdk `sdk/environments/azure_{public,gov,china}.go`: endpoints, audiences and service
  suffixes, cross-checked against `cloud.py` (a disagreement fails the generation).
- go-azure-sdk `sdk/internal/metadata/client_test.go`: verbatim `/metadata/endpoints` payloads for
  the three api-versions. The public cloud's 2022-09-01 payload is served as captured; the US Gov
  and China payloads start from their verbatim 2019-05-01 entries and take the keys those lack
  from `cloud.py`.
- Azure SDK for Java `Region.java` (cloud membership from its section comments) and Azure SDK for
  .NET `AzureLocation.cs` (every name and display name must agree): the locations.
- reliability-docs `regions-list.md` (public zones; a count that includes preview zones is left
  unsettled) and azure-docs
  `documentation-government-welcome.md` (US Gov zones).
- azure-docs `compare-azure-government-global-azure.md`, entra-docs
  `authentication-national-cloud.md`, the azure-cli storage client factory and a few REST spec
  files: the suffixes and hosts the SDK tables do not carry. Each such value must appear verbatim
  in its file or the generation fails. The same comparison table also cross-checks the API
  Management suffix go-azure-sdk gives for the public and US Gov clouds.

A cell no local file settles is emitted as `{"value": null, "source": "UNSETTLED: <what to
verify>"}` and never as fact. A metadata key with no source (or with sources that disagree) is
left out of the payload and listed under `metadataUnsettled`.

Run from anywhere in the repo:
    python3 tools/cloud/gen_clouds.py            # rewrite the vendored file in place
    python3 tools/cloud/gen_clouds.py --check    # exit 1 when the vendored file is stale

Without the checkouts (CI has no `local/`), `--check` only verifies that the vendored file parses
and keeps its shape; a regeneration needs `local/azure`.
"""
from __future__ import annotations

import argparse
import ast
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/azure/clouds.json"
LOCAL_AZURE = REPO_ROOT / "local/azure"

CLI_CLOUD = "azure-cli/src/azure-cli-core/azure/cli/core/cloud.py"
CLI_STORAGE_FACTORY = "azure-cli/src/azure-cli/azure/cli/command_modules/storage/_client_factory.py"
CLI_CONTAINER_TEST = "azure-cli/src/azure-cli/azure/cli/command_modules/container/tests/latest/test_container_commands.py"
GO_ENV = {
    "AzureCloud": "go-azure-sdk/sdk/environments/azure_public.go",
    "AzureUSGovernment": "go-azure-sdk/sdk/environments/azure_gov.go",
    "AzureChinaCloud": "go-azure-sdk/sdk/environments/azure_china.go",
}
GO_METADATA_TEST = "go-azure-sdk/sdk/internal/metadata/client_test.go"
JAVA_REGION = "azure-sdk-for-java/sdk/core/azure-core-management/src/main/java/com/azure/core/management/Region.java"
JAVA_AUTHORITY_HOSTS = "azure-sdk-for-java/sdk/identity/azure-identity/src/main/java/com/azure/identity/AzureAuthorityHosts.java"
NET_LOCATION = "azure-sdk-for-net/sdk/core/Azure.Core/src/AzureLocation.cs"
REGIONS_LIST = "reliability-docs/articles/reliability/regions-list.md"
GOV_WELCOME = "azure-docs/articles/azure-government/documentation-government-welcome.md"
GOV_COMPARE = "azure-docs/articles/azure-government/compare-azure-government-global-azure.md"
ENTRA_NATIONAL = "entra-docs/docs/identity-platform/authentication-national-cloud.md"
SPEC_SIGNALR = ("azure-rest-api-specs/specification/signalr/resource-manager/Microsoft.SignalRService/"
                "SignalRService/stable/2020-05-01/signalr.json")
SPEC_EVENTGRID = "azure-rest-api-specs/specification/eventgrid/data-plane/EventGrid/stable/2018-01-01/EventGrid.json"
SPEC_COMMUNICATION = ("azure-rest-api-specs/specification/communication/data-plane/Messages/stable/2024-08-30/"
                      "communicationservicesmessages.json")
SPEC_CONTAINERAPPS = ("azure-rest-api-specs/specification/app/resource-manager/Microsoft.App/ContainerApps/stable/"
                      "2024-03-01/examples/Revisions_Get.json")
SPEC_AKS = ("azure-rest-api-specs/specification/containerservice/resource-manager/Microsoft.ContainerService/aks/"
            "stable/2026-02-01/examples/ManagedClustersCreate_Update.json")

REQUIRED_SOURCES = [CLI_CLOUD, CLI_STORAGE_FACTORY, CLI_CONTAINER_TEST, *GO_ENV.values(), GO_METADATA_TEST,
                    JAVA_REGION, JAVA_AUTHORITY_HOSTS, NET_LOCATION, REGIONS_LIST, GOV_WELCOME, GOV_COMPARE,
                    ENTRA_NATIONAL, SPEC_SIGNALR, SPEC_EVENTGRID, SPEC_COMMUNICATION, SPEC_CONTAINERAPPS, SPEC_AKS]

CLOUDS = ["AzureCloud", "AzureUSGovernment", "AzureChinaCloud"]
CLI_CLOUD_VARS = {"AzureCloud": "AZURE_PUBLIC_CLOUD", "AzureUSGovernment": "AZURE_US_GOV_CLOUD",
                  "AzureChinaCloud": "AZURE_CHINA_CLOUD"}
JAVA_AUTHORITY_CONSTANTS = {"AzureCloud": "AZURE_PUBLIC_CLOUD", "AzureUSGovernment": "AZURE_GOVERNMENT",
                            "AzureChinaCloud": "AZURE_CHINA"}
ENTRA_NATIONAL_ROWS = {"AzureCloud": "Microsoft Entra ID (global service)",
                       "AzureUSGovernment": "Microsoft Entra ID for US Government",
                       "AzureChinaCloud": "Microsoft Entra China operated by 21Vianet"}
REGION_SECTIONS = {"Azure Cloud": "AzureCloud", "Azure China Cloud": "AzureChinaCloud",
                   "Azure Government Cloud": "AzureUSGovernment", "Azure German Cloud": None}
METADATA_2018_CLOUD_KEYS = {"AzureCloud": "public", "AzureChinaCloud": "chinaCloud",
                            "AzureUSGovernment": "usGovCloud"}
API_VERSIONS = ["2018-01-01", "2019-05-01", "2022-09-01"]

# Must stay in step with io.floci.az.core.cloud.CloudSuffix (CloudCatalogTest checks the Java side).
SUFFIX_KEYS = ["BLOB", "DFS", "QUEUE", "TABLE", "KEYVAULT", "MHSM", "ACR", "SERVICEBUS", "SIGNALR", "APPCONFIG",
               "SQL", "POSTGRES", "MYSQL", "MARIADB", "REDIS", "EVENTGRID", "CONTAINERAPPS", "COMMUNICATION", "AKS",
               "ACI", "APIM", "WEB"]
STORAGE_SERVICES = {"BLOB": "blob", "DFS": "dfs", "QUEUE": "queue", "TABLE": "table"}
# CloudSuffix -> (cloud.py CloudSuffixes attribute, go-azure-sdk environment field). Either may be None.
SDK_SUFFIXES = {
    "KEYVAULT": ("keyvault_dns", "KeyVault"),
    "MHSM": ("mhsm_dns", "ManagedHSM"),
    "ACR": ("acr_login_server_endpoint", "ContainerRegistry"),
    "SERVICEBUS": (None, "ServiceBus"),
    "APPCONFIG": (None, "AppConfiguration"),
    "SQL": ("sql_server_hostname", "Sql"),
    "POSTGRES": ("postgresql_server_endpoint", "Postgresql"),
    "MYSQL": ("mysql_server_endpoint", "MySql"),
    "MARIADB": ("mariadb_server_endpoint", "MariaDB"),
    "APIM": (None, "ApiManagement"),
}
# CloudSuffix -> compare-azure-government-global-azure.md endpoint row that must agree with the SDK value
# in the public and US Gov clouds (the doc has no China column).
GOV_CROSSCHECK_SUFFIXES = {
    "APIM": "API Management Gateway",
}
# Suffixes no SDK table carries, read from compare-azure-government-global-azure.md: CloudSuffix -> endpoint row.
# The doc has no China column, so AzureChinaCloud stays unsettled for each.
GOV_DOC_ONLY_SUFFIXES = {
    "REDIS": "Azure Cache for Redis",
    "WEB": "App Service",
}
# Public-cloud-only suffixes read from one REST spec or test file each: (file, literal that must appear).
PUBLIC_EVIDENCE = {
    "SIGNALR": (SPEC_SIGNALR, "service.signalr.net", "FQDN format xxx.service.signalr.net"),
    "EVENTGRID": (SPEC_EVENTGRID, "eventgrid.azure.net", "topic host {topic}.{region}-1.eventgrid.azure.net"),
    "COMMUNICATION": (SPEC_COMMUNICATION, "communication.azure.com", "endpoint https://{resource}.communication.azure.com"),
    "CONTAINERAPPS": (SPEC_CONTAINERAPPS, "azurecontainerapps.io", "fqdn {app}.{env}.{location}.azurecontainerapps.io"),
    "AKS": (SPEC_AKS, "azmk8s.io", "fqdn {dnsPrefix}-{hash}.hcp.{location}.azmk8s.io"),
    "ACI": (CLI_CONTAINER_TEST, "azurecontainer.io", "fqdn {label}.{location}.azurecontainer.io"),
}
UNSETTLED_SUFFIX_HINTS = {
    "EVENTGRID": "the .NET Event Grid WebJobs extension accepts webhook origins eventgrid.azure.us and "
                 "eventgrid.azure.cn, which is not evidence for the topic endpoint suffix",
}
GOV_COMPARE_WITNESSES = {"gallery": "Gallery URL", "portal": "Microsoft Azure portal",
                         "resourceManager": "Azure Resource Manager"}

GO_STRING_CALL_RE = re.compile(r'env\.(\w+)\s*=\s*\w+\(((?:"[^"]*"(?:,\s*)?)*)\)(?:\.WithResourceIdentifier\("([^"]*)"\))?')
GO_AUDIENCES_RE = re.compile(r"Audiences:\s*\[\]string\{(.*?)\}", re.DOTALL)
GO_LOGIN_RE = re.compile(r'LoginEndpoint:\s*"([^"]+)"')
GO_BACKTICK_RE = re.compile(r"(metadata\d{8})\s*=\s*`([^`]*)`")
JAVA_REGION_RE = re.compile(r'new Region\("([a-z0-9]+)", "([^"]+)"\)')
JAVA_SECTION_RE = re.compile(r"^\s*\*\s*(Azure [A-Za-z ]+?)(?:\s*-.*)?$")
NET_LOCATION_RE = re.compile(r'CreateStaticReference\("([a-z0-9]+)", "([^"]+)"\)')
JAVA_CONSTANT_RE = re.compile(r'public static final String (\w+) = "([^"]+)";')
TABLE_ROW_RE = re.compile(r"^\|(.*)\|\s*$")
ICON_RE = re.compile(r":::image[^:]*:::")
PREVIEW_ZONES_ICON = "icon-region-coming-soon.svg"


class SourceError(ValueError):
    """A source file does not say what the generator expects, or two sources disagree."""


# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #
def read(root: Path, rel: str) -> str:
    return (root / rel).read_text(encoding="utf-8")


def host_of(url: str) -> str:
    """`https://login.microsoftonline.com/` -> `login.microsoftonline.com`."""
    return re.sub(r"^https?://", "", url).split("/")[0].split(":")[0]


def norm_url(value: str) -> str:
    """A URL without its trailing slash, for comparisons and for catalog endpoints."""
    return value.rstrip("/")


def norm_suffix(value: str) -> str:
    """A DNS suffix without a scheme, a leading dot or a trailing slash."""
    return re.sub(r"^https?://", "", value).strip("/").lstrip(".")


def cell(value, source: str) -> dict:
    return {"source": source, "value": value}


def unsettled(what: str) -> dict:
    return cell(None, f"UNSETTLED: {what}")


def git_commit(root: Path, rel: str) -> str:
    repo = root / rel.split("/")[0]
    try:
        result = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True,
                                check=False)
    except OSError:
        return "unknown"
    return result.stdout.strip() if result.returncode == 0 and result.stdout.strip() else "unknown"


def table_rows(text: str, start_marker: str | None = None) -> list[list[str]]:
    """Rows of the first Markdown table after `start_marker` (or in the text), header and rule skipped."""
    lines = text.splitlines()
    index = 0
    if start_marker is not None:
        index = next((i for i, line in enumerate(lines) if start_marker in line), None)
        if index is None:
            raise SourceError(f"no '{start_marker}' marker")
    rows: list[list[str]] = []
    inside = False
    for line in lines[index:]:
        match = TABLE_ROW_RE.match(line.strip())
        if match is None:
            if inside:
                break
            continue
        inside = True
        cells = [c.strip() for c in match.group(1).split("|")]
        if all(re.fullmatch(r":?-+:?", c) for c in cells if c):
            continue
        rows.append(cells)
    return rows[1:]


# --------------------------------------------------------------------------- #
# Parsers
# --------------------------------------------------------------------------- #
def parse_cli_cloud(text: str) -> dict:
    """azure-cli `cloud.py`: the hardcoded clouds' endpoint and suffix kwargs, plus both metadata indexes.

    Read with `ast`, never imported: the module's imports are not available here and must not run.
    """
    tree = ast.parse(text)
    clouds: dict[str, dict] = {}
    indexes: dict[str, dict] = {}
    for node in tree.body:
        if isinstance(node, ast.ClassDef) and node.name in ("CloudEndpoints", "CloudSuffixes"):
            for item in node.body:
                if (isinstance(item, ast.Assign) and isinstance(item.targets[0], ast.Name)
                        and item.targets[0].id == "ARM_METADATA_INDEX"):
                    indexes[node.name] = ast.literal_eval(item.value)
        if not (isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
                and isinstance(node.value, ast.Call) and getattr(node.value.func, "id", None) == "Cloud"):
            continue
        parts: dict[str, dict] = {"endpoints": {}, "suffixes": {}}
        for keyword in node.value.keywords:
            if keyword.arg in parts and isinstance(keyword.value, ast.Call):
                parts[keyword.arg] = {k.arg: ast.literal_eval(k.value) for k in keyword.value.keywords}
        clouds[node.targets[0].id] = parts
    if set(indexes) != {"CloudEndpoints", "CloudSuffixes"}:
        raise SourceError(f"{CLI_CLOUD}: ARM_METADATA_INDEX tables not found")
    return {"clouds": clouds, "endpointIndex": indexes["CloudEndpoints"], "suffixIndex": indexes["CloudSuffixes"]}


def parse_go_environment(text: str) -> dict:
    """go-azure-sdk `azure_*.go`: audiences, login endpoint and every `env.X = XAPI(...)` assignment.

    Only the file's first function is read: `azure_gov.go` follows `AzureUSGovernment()` with
    `AzureUSGovernmentL5()`, which reassigns Microsoft Graph for a different environment."""
    start = text.find("\nfunc ")
    end = text.find("\n}\n", start)
    if start < 0 or end < 0:
        raise SourceError("go environment: no function body")
    text = text[start:end]
    audiences = GO_AUDIENCES_RE.search(text)
    login = GO_LOGIN_RE.search(text)
    if audiences is None or login is None:
        raise SourceError("go environment: no Authorization block")
    fields: dict[str, dict] = {}
    for match in GO_STRING_CALL_RE.finditer(text):
        args = re.findall(r'"([^"]*)"', match.group(2))
        fields[match.group(1)] = {"args": args, "resourceIdentifier": match.group(3)}
    return {
        "audiences": re.findall(r'"([^"]+)"', audiences.group(1)),
        "loginEndpoint": login.group(1),
        "fields": fields,
    }


def go_suffix(go_env: dict, field: str) -> str | None:
    """The DNS suffix argument of a go environment field: the last string argument (a few take an
    endpoint URL first, e.g. `ServiceBusAPI("https://servicebus.windows.net", "servicebus.windows.net")`)."""
    entry = go_env["fields"].get(field)
    if entry is None or not entry["args"]:
        return None
    return norm_suffix(entry["args"][-1])


def parse_go_metadata_fixtures(text: str) -> dict:
    """go-azure-sdk `client_test.go`: the verbatim payload per api-version."""
    found = {name: json.loads(body) for name, body in GO_BACKTICK_RE.findall(text)}
    wanted = {"2018-01-01": "metadata20180101", "2019-05-01": "metadata20190501", "2022-09-01": "metadata20220901"}
    missing = [name for name in wanted.values() if name not in found]
    if missing:
        raise SourceError(f"{GO_METADATA_TEST}: missing fixtures {missing}")
    return {version: found[name] for version, name in wanted.items()}


def parse_java_regions(text: str) -> list[tuple[str, str, str | None]]:
    """`Region.java`: (name, displayName, cloud) in file order; cloud is None for the retired German cloud."""
    regions: list[tuple[str, str, str | None]] = []
    section: str | None = None
    seen_section = False
    for line in text.splitlines():
        heading = JAVA_SECTION_RE.match(line)
        if heading and heading.group(1).strip() in REGION_SECTIONS:
            section = heading.group(1).strip()
            seen_section = True
            continue
        match = JAVA_REGION_RE.search(line)
        if match:
            if not seen_section:
                raise SourceError(f"{JAVA_REGION}: region {match.group(1)} precedes any cloud section comment")
            regions.append((match.group(1), match.group(2), REGION_SECTIONS[section]))
    if not regions:
        raise SourceError(f"{JAVA_REGION}: no regions found")
    return regions


def parse_net_locations(text: str) -> dict[str, str]:
    return dict(NET_LOCATION_RE.findall(text))


def parse_public_zones(text: str) -> dict[str, int | None]:
    """regions-list.md, the "All" tab: programmatic name -> availability zone count (0 for N/A).

    A count carrying the coming-soon icon includes zones still in preview, and the file does not say
    how many are available, so that region maps to None.
    """
    zones: dict[str, int | None] = {}
    for row in table_rows(text, "#### [All]"):
        if len(row) < 6:
            continue
        name = row[5]
        count = ICON_RE.sub("", row[1]).strip()
        if PREVIEW_ZONES_ICON in row[1]:
            if not count.isdigit():
                raise SourceError(f"{REGIONS_LIST}: unexpected zone cell for {name}: {row[1]!r}")
            zones[name] = None
        elif count == "N/A":
            zones[name] = 0
        elif count.isdigit():
            zones[name] = int(count)
        else:
            raise SourceError(f"{REGIONS_LIST}: unexpected zone cell for {name}: {row[1]!r}")
    if not zones:
        raise SourceError(f"{REGIONS_LIST}: no rows in the All tab")
    return zones


def parse_gov_zones(text: str) -> dict[str, bool]:
    """documentation-government-welcome.md, "List of regions": programmatic name -> has zones."""
    support: dict[str, bool] = {}
    for row in table_rows(text, "## List of regions"):
        if len(row) >= 5:
            support[row[4]] = "check-mark" in row[1] or 'alt-text="Yes"' in row[1]
    if not support:
        raise SourceError(f"{GOV_WELCOME}: no region rows")
    return support


def parse_gov_compare(text: str) -> dict[str, tuple[str, str]]:
    """compare-azure-government-global-azure.md endpoint table: service name -> (public, gov) host."""
    pairs: dict[str, tuple[str, str]] = {}
    for row in table_rows(text, "|Service category|Service name|"):
        if len(row) >= 4 and row[1]:
            pairs.setdefault(row[1], (row[2], row[3]))
    if not pairs:
        raise SourceError(f"{GOV_COMPARE}: no endpoint rows")
    return pairs


def parse_entra_national(text: str) -> dict[str, str]:
    """authentication-national-cloud.md: national cloud row label -> authentication endpoint URL."""
    endpoints: dict[str, str] = {}
    for row in table_rows(text, "## Microsoft Entra authentication endpoints"):
        if len(row) >= 2:
            endpoints[row[0]] = row[1].strip("`")
    return endpoints


def parse_java_constants(text: str) -> dict[str, str]:
    return dict(JAVA_CONSTANT_RE.findall(text))


def require_literal(root: Path, rel: str, literal: str) -> None:
    if literal not in read(root, rel):
        raise SourceError(f"{rel}: expected to find {literal!r}")


# --------------------------------------------------------------------------- #
# Builders
# --------------------------------------------------------------------------- #
def agree(label: str, *values: str | None) -> str:
    """The one value every non-None witness gives, or a SourceError naming the disagreement."""
    present = [v for v in values if v is not None]
    if not present:
        raise SourceError(f"{label}: no source gives a value")
    if len(set(present)) != 1:
        raise SourceError(f"{label}: sources disagree: {present}")
    return present[0]


def build_endpoints(cloud: str, cli: dict, go_env: dict, java_hosts: dict[str, str],
                    entra: dict[str, str]) -> tuple[dict, list[str]]:
    cli_endpoints = cli["clouds"][CLI_CLOUD_VARS[cloud]]["endpoints"]
    login = agree(f"{cloud} login endpoint", norm_url(go_env["loginEndpoint"]),
                  norm_url(cli_endpoints["active_directory"]),
                  norm_url(java_hosts[JAVA_AUTHORITY_CONSTANTS[cloud]]))
    resource_manager = agree(f"{cloud} resource manager",
                             norm_url(go_env["fields"]["ResourceManager"]["args"][0]),
                             norm_url(cli_endpoints["resource_manager"]))
    graph = agree(f"{cloud} Microsoft Graph", norm_url(go_env["fields"]["MicrosoftGraph"]["args"][0]),
                  norm_url(cli_endpoints["microsoft_graph_resource_id"]))
    audiences = [norm_url(a) for a in go_env["audiences"]]
    agree(f"{cloud} management audience", audiences[0], norm_url(cli_endpoints["management"]),
          norm_url(cli_endpoints["active_directory_resource_id"]))
    if len(audiences) != 2 or audiences[1] != resource_manager:
        raise SourceError(f"{cloud}: expected the audiences to be [management.core..., the ARM host]: {audiences}")
    authority_hosts = [host_of(login)]
    entra_host = host_of(entra[ENTRA_NATIONAL_ROWS[cloud]])
    if entra_host not in authority_hosts:
        authority_hosts.append(entra_host)
    endpoints = {
        "activeDirectory": login,
        "authorityHosts": authority_hosts,
        "microsoftGraph": graph,
        "portal": norm_url(cli_endpoints["portal"]),
        "resourceManager": resource_manager,
    }
    return endpoints, audiences


def build_suffixes(cloud: str, cli: dict, go_env: dict, gov_compare: dict[str, tuple[str, str]]) -> dict:
    cli_suffixes = cli["clouds"][CLI_CLOUD_VARS[cloud]]["suffixes"]
    storage = agree(f"{cloud} storage suffix", norm_suffix(cli_suffixes["storage_endpoint"]),
                    go_suffix(go_env, "Storage"))
    result: dict[str, dict] = {}
    for key, service in STORAGE_SERVICES.items():
        result[key] = cell(f"{service}.{storage}",
                           f"{CLI_CLOUD} storage_endpoint + {CLI_STORAGE_FACTORY} get_account_url "
                           f"('https://{{account}}.{{service}}.{{storage_endpoint}}')")
    for key, (cli_attr, go_field) in SDK_SUFFIXES.items():
        cli_value = norm_suffix(cli_suffixes[cli_attr]) if cli_attr and cli_suffixes.get(cli_attr) else None
        go_value = go_suffix(go_env, go_field) if go_field else None
        witnesses = [name for name, value in ((CLI_CLOUD, cli_value), (GO_ENV[cloud], go_value)) if value]
        if not witnesses:
            result[key] = unsettled(f"{key} suffix in {cloud}: neither cloud.py nor go-azure-sdk names one")
            continue
        result[key] = cell(agree(f"{cloud} {key} suffix", cli_value, go_value), "; ".join(witnesses))
    for key, row in GOV_CROSSCHECK_SUFFIXES.items():
        if cloud not in ("AzureCloud", "AzureUSGovernment"):
            continue
        pair = gov_compare.get(row)
        if pair is None:
            raise SourceError(f"{GOV_COMPARE}: no {row} row")
        column = "Azure Public" if cloud == "AzureCloud" else "Azure Government"
        doc_value = norm_suffix(pair[0] if cloud == "AzureCloud" else pair[1])
        sdk_value = result[key]["value"]
        sources = [result[key]["source"]] if sdk_value is not None else []
        sources.append(f"{GOV_COMPARE} ({row}, {column})")
        result[key] = cell(agree(f"{cloud} {key} suffix", sdk_value, doc_value), "; ".join(sources))
    for key, row in GOV_DOC_ONLY_SUFFIXES.items():
        pair = gov_compare.get(row)
        if pair is None:
            raise SourceError(f"{GOV_COMPARE}: no {row} row")
        if cloud == "AzureCloud":
            result[key] = cell(norm_suffix(pair[0]), f"{GOV_COMPARE} ({row}, Azure Public)")
        elif cloud == "AzureUSGovernment":
            result[key] = cell(norm_suffix(pair[1]), f"{GOV_COMPARE} ({row}, Azure Government)")
        else:
            result[key] = unsettled(f"{row} host suffix in AzureChinaCloud; no local source")
    for key, (path, literal, shape) in PUBLIC_EVIDENCE.items():
        if cloud == "AzureCloud":
            result[key] = cell(literal, f"{path} ({shape})")
        else:
            hint = UNSETTLED_SUFFIX_HINTS.get(key)
            what = f"{key} host suffix in {cloud}; no local source"
            result[key] = unsettled(f"{what} ({hint})" if hint else what)
    missing = [k for k in SUFFIX_KEYS if k not in result]
    if missing:
        raise SourceError(f"{cloud}: suffix keys not derived: {missing}")
    return {k: result[k] for k in SUFFIX_KEYS}


def build_locations(cloud: str, regions: list[tuple[str, str, str | None]], net: dict[str, str],
                    public_zones: dict[str, int | None], gov_zones: dict[str, bool]) -> list[dict]:
    locations: list[dict] = []
    for name, display, owner in regions:
        if net.get(name) != display:
            raise SourceError(f"{name}: Region.java says {display!r}, AzureLocation.cs says {net.get(name)!r}")
        if owner != cloud:
            continue
        if cloud == "AzureCloud":
            if name not in public_zones:
                zones = unsettled(f"zones for {name}; not listed in {REGIONS_LIST}")
            elif public_zones[name] is None:
                zones = unsettled(f"zone count for {name}; {REGIONS_LIST} counts zones still in preview")
            else:
                zones = cell([str(z) for z in range(1, public_zones[name] + 1)], REGIONS_LIST)
        elif cloud == "AzureUSGovernment":
            if name not in gov_zones:
                zones = unsettled(f"zones for {name}; not listed in {GOV_WELCOME}")
            elif gov_zones[name]:
                zones = unsettled(f"zone count for {name}; {GOV_WELCOME} marks zone support without a count")
            else:
                zones = cell([], GOV_WELCOME)
        else:
            zones = unsettled(f"zones for {name}; no local source covers AzureChinaCloud zones")
        locations.append({"displayName": display, "name": name, "zones": zones})
    if not locations:
        raise SourceError(f"{cloud}: no locations in {JAVA_REGION}")
    return locations


def cli_metadata_value(cli: dict, cloud: str, key: str) -> str | None:
    """The cloud.py value behind a `/metadata/endpoints` key, through its ARM_METADATA_INDEX tables."""
    parts = cli["clouds"][CLI_CLOUD_VARS[cloud]]
    lookup = "ossrdbmsResourceId" if key == "ossrDbmsResourceId" else key
    if lookup == "graph":
        lookup = "graphAudience"
    for attr, path in cli["endpointIndex"].items():
        if path == lookup and parts["endpoints"].get(attr) is not None:
            return parts["endpoints"][attr]
    for attr, path in cli["suffixIndex"].items():
        if path == f"suffixes.{lookup}" and parts["suffixes"].get(attr) is not None:
            return norm_suffix(parts["suffixes"][attr])
    return None


def comparable(value) -> str:
    return norm_suffix(value) if isinstance(value, str) else json.dumps(value, sort_keys=True)


def cli_local_keys(cli: dict, fixtures: dict) -> set[str]:
    """Keys where cloud.py's public value differs from the verbatim public ARM payload. cloud.py is not
    a mirror of ARM for those (`vmImageAliasDoc` points at the CLI's own copy), so it is no witness."""
    public = {**fixtures["2019-05-01"][0], **fixtures["2022-09-01"]}
    local: set[str] = set()
    for key, value in public.items():
        if isinstance(value, str):
            cli_value = cli_metadata_value(cli, "AzureCloud", key)
            if cli_value is not None and comparable(cli_value) != comparable(value):
                local.add(key)
    return local


def build_metadata(cloud: str, cli: dict, fixtures: dict, gov_compare: dict[str, tuple[str, str]],
                   cloud_endpoint: dict) -> tuple[dict, list[dict]]:
    """The three `/metadata/endpoints` payloads for one cloud and the keys left out as unsettled."""
    template = fixtures["2022-09-01"]
    entries = {entry["name"]: entry for entry in fixtures["2019-05-01"]}
    if cloud not in entries:
        raise SourceError(f"{GO_METADATA_TEST}: no 2019-05-01 entry named {cloud}")
    entry = entries[cloud]
    problems: list[dict] = []

    if cloud == "AzureCloud":
        payload_2022 = json.loads(json.dumps(template))
        merged = {**entry, **payload_2022, "suffixes": {**entry.get("suffixes", {}), **payload_2022["suffixes"]}}
    else:
        merged = synthesize(cloud, cli, fixtures, entry, problems)
        if cloud == "AzureUSGovernment":
            check_gov_compare(cloud, merged, gov_compare, problems)
        payload_2022 = {k: v for k, v in merged.items() if k in template and k != "suffixes"}
        payload_2022["suffixes"] = {k: v for k, v in merged["suffixes"].items() if k in template["suffixes"]}
    payload_2019 = merged
    for payload in (payload_2019, payload_2022):
        if "ossrDbmsResourceId" in payload:
            payload["ossrdbmsResourceId"] = payload["ossrDbmsResourceId"]

    if cloud == "AzureCloud":
        payload_2018 = json.loads(json.dumps(fixtures["2018-01-01"]))
    else:
        payload_2018 = {}
        for target, source in (("galleryEndpoint", "gallery"), ("graphEndpoint", "graph"),
                               ("portalEndpoint", "portal"), ("graphAudience", "graphAudience")):
            if source in payload_2019:
                payload_2018[target] = payload_2019[source]
        payload_2018["authentication"] = payload_2019["authentication"]
    payload_2018["cloudEndpoint"] = cloud_endpoint

    return {"2018-01-01": payload_2018, "2019-05-01": payload_2019, "2022-09-01": payload_2022}, problems


def synthesize(cloud: str, cli: dict, fixtures: dict, entry: dict, problems: list[dict]) -> dict:
    """Every key of the public 2022-09-01 payload and of this cloud's verbatim 2019-05-01 entry, resolved
    through `resolve`; a key no source settles is left out and recorded in `problems`."""
    template = fixtures["2022-09-01"]
    ignored = cli_local_keys(cli, fixtures)
    merged: dict = {}
    keys = list(template) + [k for k in entry if k not in template]
    for key in keys:
        if key == "suffixes":
            suffixes = {}
            suffix_keys = list(template["suffixes"]) + [k for k in entry.get("suffixes", {})
                                                        if k not in template["suffixes"]]
            for suffix_key in suffix_keys:
                value = entry.get("suffixes", {}).get(suffix_key)
                cli_value = cli_metadata_value(cli, cloud, suffix_key)
                resolved = resolve(cloud, f"suffixes.{suffix_key}", value, cli_value, problems)
                if resolved is not None:
                    suffixes[suffix_key] = resolved
            merged["suffixes"] = suffixes
            continue
        cli_value = None if key in ignored else cli_metadata_value(cli, cloud, key)
        resolved = resolve(cloud, key, entry.get(key), cli_value, problems)
        if resolved is not None:
            merged[key] = resolved
    return merged


def check_gov_compare(cloud: str, merged: dict, gov_compare: dict[str, tuple[str, str]],
                      problems: list[dict]) -> None:
    """Drops a host the Azure Government comparison table contradicts (the Gallery URL, at the time of
    writing) and records it as unsettled."""
    for key, row in GOV_COMPARE_WITNESSES.items():
        if key in merged and row in gov_compare:
            doc_host = gov_compare[row][1]
            if host_of(merged[key]) != doc_host:
                problems.append({"field": key, "source": (
                    f"UNSETTLED: {cloud} {key}: {GO_METADATA_TEST} and {CLI_CLOUD} say "
                    f"{merged[key]!r}, {GOV_COMPARE} ({row}) says {doc_host!r}")})
                del merged[key]


def resolve(cloud: str, key: str, fixture_value, cli_value, problems: list[dict]):
    """A synthesized payload value: the verbatim 2019-05-01 entry first, then cloud.py; a key where the
    two disagree (beyond a trailing slash or leading dot) or neither has a value is left out."""
    if fixture_value is not None and cli_value is not None and comparable(fixture_value) != comparable(cli_value):
        problems.append({"field": key, "source": (
            f"UNSETTLED: {cloud} {key}: {GO_METADATA_TEST} (2019-05-01) says {fixture_value!r}, "
            f"{CLI_CLOUD} says {cli_value!r}")})
        return None
    if fixture_value is not None:
        return fixture_value
    if cli_value is not None:
        return cli_value
    problems.append({"field": key, "source": f"UNSETTLED: {cloud} {key}: no local source"})
    return None


def build(root: Path) -> dict:
    cli = parse_cli_cloud(read(root, CLI_CLOUD))
    go_envs = {cloud: parse_go_environment(read(root, path)) for cloud, path in GO_ENV.items()}
    fixtures = parse_go_metadata_fixtures(read(root, GO_METADATA_TEST))
    regions = parse_java_regions(read(root, JAVA_REGION))
    net = parse_net_locations(read(root, NET_LOCATION))
    public_zones = parse_public_zones(read(root, REGIONS_LIST))
    gov_zones = parse_gov_zones(read(root, GOV_WELCOME))
    gov_compare = parse_gov_compare(read(root, GOV_COMPARE))
    entra = parse_entra_national(read(root, ENTRA_NATIONAL))
    java_hosts = parse_java_constants(read(root, JAVA_AUTHORITY_HOSTS))

    require_literal(root, CLI_STORAGE_FACTORY, 'return "https://{}.{}.{}".format(account_name, service, storage_endpoint)')
    for path, literal, _ in PUBLIC_EVIDENCE.values():
        require_literal(root, path, literal)

    locations = {cloud: build_locations(cloud, regions, net, public_zones, gov_zones) for cloud in CLOUDS}
    cloud_endpoint = {
        METADATA_2018_CLOUD_KEYS[cloud]: {
            "endpoint": host_of(go_envs[cloud]["fields"]["ResourceManager"]["args"][0]),
            "locations": [location["name"] for location in locations[cloud]],
        }
        for cloud in CLOUDS
    }

    clouds = []
    for cloud in CLOUDS:
        endpoints, audiences = build_endpoints(cloud, cli, go_envs[cloud], java_hosts, entra)
        metadata, metadata_unsettled = build_metadata(cloud, cli, fixtures, gov_compare, cloud_endpoint)
        clouds.append({
            "audiences": audiences,
            "defaultLocation": unsettled(
                f"default location for {cloud}; no local source names one (CloudCatalog falls back to "
                f"the first listed location)"),
            "endpoints": endpoints,
            "locations": locations[cloud],
            "metadataEndpoints": metadata,
            "metadataUnsettled": metadata_unsettled,
            "name": cloud,
            "suffixes": build_suffixes(cloud, cli, go_envs[cloud], gov_compare),
        })

    used = sorted({CLI_CLOUD, CLI_STORAGE_FACTORY, CLI_CONTAINER_TEST, *GO_ENV.values(), GO_METADATA_TEST, JAVA_REGION,
                   JAVA_AUTHORITY_HOSTS, NET_LOCATION, REGIONS_LIST, GOV_WELCOME, GOV_COMPARE, ENTRA_NATIONAL,
                   SPEC_SIGNALR, SPEC_EVENTGRID, SPEC_COMMUNICATION, SPEC_CONTAINERAPPS, SPEC_AKS})
    return {
        "_source": {
            "generator": "tools/cloud/gen_clouds.py",
            "root": "local/azure",
            "files": {path: git_commit(root, path) for path in used},
        },
        "clouds": clouds,
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def strip_source(text: str) -> str:
    """The document without its provenance block, so the check compares data, not commits."""
    if not text.strip():
        return ""
    document = json.loads(text)
    document.pop("_source", None)
    return json.dumps(document, sort_keys=True)


# --------------------------------------------------------------------------- #
# Shape check (no sources needed)
# --------------------------------------------------------------------------- #
LOCATION_NAME_RE = re.compile(r"[a-z0-9]+")
SUFFIX_RE = re.compile(r"[a-z0-9-]+(\.[a-z0-9-]+)+")
URL_RE = re.compile(r"https://[a-z0-9.-]+(:\d+)?(/[^\s]*)?")


def check_cell(problems: list[str], where: str, value, kind) -> None:
    if not isinstance(value, dict) or set(value) != {"source", "value"} or not isinstance(value["source"], str):
        problems.append(f"{where}: expected {{source, value}}, got {value!r}")
        return
    if value["value"] is None:
        if not value["source"].startswith("UNSETTLED: "):
            problems.append(f"{where}: a null value must carry an UNSETTLED source")
    elif value["source"].startswith("UNSETTLED"):
        problems.append(f"{where}: an UNSETTLED cell must not carry a value")
    elif not kind(value["value"]):
        problems.append(f"{where}: unexpected value {value['value']!r}")


def self_check(path: Path) -> list[str]:
    """Problems with the vendored file that need no sources. It cannot tell a stale value from a current
    one; only a regeneration against local/azure can."""
    if not path.exists():
        return [f"{path} is missing"]
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        return [f"{path}: not valid JSON ({e})"]
    problems: list[str] = []
    clouds = document.get("clouds")
    if not isinstance(clouds, list) or [c.get("name") for c in clouds if isinstance(c, dict)] != CLOUDS:
        return [f"{path}: 'clouds' must list {CLOUDS} in that order"]
    for cloud in clouds:
        name = cloud["name"]
        expected = {"audiences", "defaultLocation", "endpoints", "locations", "metadataEndpoints",
                    "metadataUnsettled", "name", "suffixes"}
        if set(cloud) != expected:
            problems.append(f"{name}: keys {sorted(cloud)} differ from {sorted(expected)}")
            continue
        audiences = cloud["audiences"]
        if not (isinstance(audiences, list) and len(audiences) == 2 and all(URL_RE.fullmatch(a) for a in audiences)):
            problems.append(f"{name}.audiences: expected two https URLs, got {audiences!r}")
        endpoints = cloud["endpoints"]
        for key in ("activeDirectory", "microsoftGraph", "portal", "resourceManager"):
            if not isinstance(endpoints.get(key), str) or not URL_RE.fullmatch(endpoints[key]):
                problems.append(f"{name}.endpoints.{key}: expected an https URL, got {endpoints.get(key)!r}")
        hosts = endpoints.get("authorityHosts")
        if not (isinstance(hosts, list) and hosts and all(isinstance(h, str) and SUFFIX_RE.fullmatch(h) for h in hosts)):
            problems.append(f"{name}.endpoints.authorityHosts: expected a non-empty list of hosts, got {hosts!r}")
        suffixes = cloud["suffixes"]
        if not isinstance(suffixes, dict) or list(suffixes) != sorted(SUFFIX_KEYS):
            problems.append(f"{name}.suffixes: keys must be exactly {sorted(SUFFIX_KEYS)}")
        else:
            for key, value in suffixes.items():
                check_cell(problems, f"{name}.suffixes.{key}", value,
                           lambda v: isinstance(v, str) and SUFFIX_RE.fullmatch(v) is not None)
        location_names = set()
        locations = cloud["locations"]
        if not isinstance(locations, list) or not locations:
            problems.append(f"{name}.locations: expected a non-empty list")
            locations = []
        for location in locations:
            if not (isinstance(location, dict) and set(location) == {"displayName", "name", "zones"}
                    and isinstance(location["name"], str) and LOCATION_NAME_RE.fullmatch(location["name"])
                    and isinstance(location["displayName"], str) and location["displayName"]):
                problems.append(f"{name}.locations: malformed entry {location!r}")
                continue
            location_names.add(location["name"])
            check_cell(problems, f"{name}.locations.{location['name']}.zones", location["zones"],
                       lambda v: isinstance(v, list) and all(isinstance(z, str) and z.isdigit() for z in v))
        check_cell(problems, f"{name}.defaultLocation", cloud["defaultLocation"],
                   lambda v: isinstance(v, str) and v in location_names)
        metadata = cloud["metadataEndpoints"]
        if not isinstance(metadata, dict) or sorted(metadata) != API_VERSIONS:
            problems.append(f"{name}.metadataEndpoints: expected api-versions {API_VERSIONS}")
        else:
            latest = metadata["2022-09-01"]
            for version in ("2019-05-01", "2022-09-01"):
                payload = metadata[version]
                if payload.get("name") != name:
                    problems.append(f"{name}.metadataEndpoints.{version}.name must be {name}")
                auth = payload.get("authentication", {})
                if not auth.get("loginEndpoint") or not auth.get("audiences"):
                    problems.append(f"{name}.metadataEndpoints.{version}: authentication.loginEndpoint and "
                                    f"audiences are required (az cloud register reads them)")
                if not isinstance(payload.get("suffixes"), dict):
                    problems.append(f"{name}.metadataEndpoints.{version}: suffixes are required")
            if ("ossrDbmsResourceId" in latest) != ("ossrdbmsResourceId" in latest):
                problems.append(f"{name}.metadataEndpoints.2022-09-01: ossrDbmsResourceId and ossrdbmsResourceId "
                                f"must appear together")
            cloud_endpoint = metadata["2018-01-01"].get("cloudEndpoint")
            if not isinstance(cloud_endpoint, dict) or sorted(cloud_endpoint) != sorted(METADATA_2018_CLOUD_KEYS.values()):
                problems.append(f"{name}.metadataEndpoints.2018-01-01.cloudEndpoint: expected "
                                f"{sorted(METADATA_2018_CLOUD_KEYS.values())}")
        for entry in cloud["metadataUnsettled"]:
            if not (isinstance(entry, dict) and set(entry) == {"field", "source"}
                    and str(entry["source"]).startswith("UNSETTLED: ")):
                problems.append(f"{name}.metadataUnsettled: malformed entry {entry!r}")
    return problems


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def sources_present(root: Path) -> bool:
    return all((root / rel).is_file() for rel in REQUIRED_SOURCES)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--local-root", type=Path, default=LOCAL_AZURE,
                        help="directory holding the reference checkouts (default: local/azure)")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    rel = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output

    if not sources_present(args.local_root):
        if args.check:
            problems = self_check(args.output)
            for problem in problems:
                print(f"error: {problem}")
            if problems:
                return 1
            print(f"{rel}: sources not checked out under {args.local_root}, shape verified only")
            return 0
        missing = [r for r in REQUIRED_SOURCES if not (args.local_root / r).is_file()]
        raise SystemExit(f"error: regenerating needs the reference checkouts under {args.local_root}; missing: {missing}")

    try:
        document = build(args.local_root)
    except SourceError as e:
        print(f"error: {e}")
        return 1
    rendered = render(document)
    summary = (f"{len(document['clouds'])} clouds, "
               f"{sum(len(c['locations']) for c in document['clouds'])} locations")

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(rendered):
            print(f"error: {rel} differs from a fresh generation. Run 'make az-data-sync' and commit the result.")
            return 1
        problems = self_check(args.output)
        for problem in problems:
            print(f"error: {problem}")
        if problems:
            return 1
        print(f"{rel} is up to date ({summary})")
        return 0

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    problems = self_check(args.output)
    for problem in problems:
        print(f"error: {problem}")
    if problems:
        return 1
    print(f"wrote {rel}: {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
