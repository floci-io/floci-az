"""Tests for gen_clouds.

Run with: pytest tools/cloud -q  (or: make az-data-test)
"""
from __future__ import annotations

import json
import shutil

import pytest

import gen_clouds as g


CLI_CLOUD = '''
class CloudEndpoints:
    ARM_METADATA_INDEX = {
        "active_directory": "authentication.loginEndpoint",
        "active_directory_graph_resource_id": "graphAudience",
        "gallery": "gallery",
        "ossrdbms_resource_id": "ossrdbmsResourceId",
        "vm_image_alias_doc": "vmImageAliasDoc",
    }


class CloudSuffixes:
    ARM_METADATA_INDEX = {
        "keyvault_dns": "suffixes.keyVaultDns",
        "storage_endpoint": "suffixes.storage",
    }


AZURE_PUBLIC_CLOUD = Cloud(
    CloudNameEnum.AzureCloud,
    endpoints=CloudEndpoints(
        active_directory='https://login.microsoftonline.com',
        gallery='https://gallery.azure.com/',
        vm_image_alias_doc='https://azcliprod.blob.core.windows.net/cli/vm/aliases.json'),
    suffixes=CloudSuffixes(
        storage_endpoint='core.windows.net',
        keyvault_dns='.vault.azure.net'))

AZURE_US_GOV_CLOUD = Cloud(
    CloudNameEnum.AzureUSGovernment,
    endpoints=CloudEndpoints(
        active_directory='https://login.microsoftonline.us',
        active_directory_graph_resource_id='https://graph.microsoftazure.us/',
        gallery='https://gallery.usgovcloudapi.net/',
        ossrdbms_resource_id='https://ossrdbms-aad.database.usgovcloudapi.net',
        vm_image_alias_doc='https://azcliprod.blob.core.windows.net/cli/vm/aliases.json'),
    suffixes=CloudSuffixes(
        storage_endpoint='core.usgovcloudapi.net',
        keyvault_dns='.vault.usgovcloudapi.net'))
'''

GO_GOV = '''
package environments

const AzureUSGovernmentCloud = "USGovernment"

func AzureUSGovernment() *Environment {
    env := baseEnvironmentWithName(AzureUSGovernmentCloud)

    env.Authorization = &Authorization{
        Audiences: []string{
            "https://management.core.usgovcloudapi.net",
            "https://management.usgovcloudapi.net",
        },
        LoginEndpoint:    "https://login.microsoftonline.us",
    }
    env.MicrosoftGraph = MicrosoftGraphAPI("https://graph.microsoft.us")
    env.KeyVault = KeyVaultAPI("vault.usgovcloudapi.net").WithResourceIdentifier("https://vault.usgovcloudapi.net")
    env.ServiceBus = ServiceBusAPI("https://servicebus.usgovcloudapi.net", "servicebus.usgovcloudapi.net")

    return &env
}

func AzureUSGovernmentL5() *Environment {
    env := AzureUSGovernment()
    env.MicrosoftGraph = MicrosoftGraphAPI("https://dod-graph.microsoft.us")
    return env
}
'''

GO_METADATA = '''
var (
    metadata20180101 = `{"authentication":{"loginEndpoint":"https://login.microsoftonline.com/"}}`
    metadata20190501 = `[{"name":"AzureCloud","gallery":"https://gallery.azure.com/","vmImageAliasDoc":"https://raw.example/aliases.json"},{"name":"AzureUSGovernment","authentication":{"loginEndpoint":"https://login.microsoftonline.us"},"graphAudience":"https://graph.windows.net","gallery":"https://gallery.usgovcloudapi.net","vmImageAliasDoc":"https://raw.example/aliases.json","suffixes":{"keyVaultDns":"vault.usgovcloudapi.net"}}]`
    metadata20220901 = `{"name":"AzureCloud","authentication":{"loginEndpoint":"https://login.microsoftonline.com"},"graphAudience":"https://graph.windows.net/","ossrDbmsResourceId":"https://ossrdbms-aad.database.windows.net","vmImageAliasDoc":"https://raw.example/aliases.json","suffixes":{"keyVaultDns":"vault.azure.net","storage":"core.windows.net"}}`
)
'''

JAVA_REGION = '''
public final class Region {
    /*
     * Azure Cloud - Americas
     */
    /**
     * East US (US) (recommended)
     */
    public static final Region US_EAST = new Region("eastus", "East US");
    /*
     * Azure Cloud - Europe
     */
    public static final Region EUROPE_NORTH = new Region("northeurope", "North Europe");
    /*
     * Azure China Cloud
     */
    public static final Region CHINA_NORTH = new Region("chinanorth", "China North");
    /*
     * Azure German Cloud
     */
    public static final Region GERMANY_CENTRAL = new Region("germanycentral", "Germany Central");
    /*
     * Azure Government Cloud
     */
    public static final Region GOV_US_VIRGINIA = new Region("usgovvirginia", "US Gov Virginia");
    public static final Region GOV_US_TEXAS = new Region("usgovtexas", "US Gov Texas");
}
'''

NET_LOCATION = '''
        public static AzureLocation EastUS { get; } = CreateStaticReference("eastus", "East US");
        public static AzureLocation NorthEurope { get; } = CreateStaticReference("northeurope", "North Europe");
        public static AzureLocation ChinaNorth { get; } = CreateStaticReference("chinanorth", "China North");
        public static AzureLocation GermanyCentral { get; } = CreateStaticReference("germanycentral", "Germany Central");
        public static AzureLocation USGovVirginia { get; } = CreateStaticReference("usgovvirginia", "US Gov Virginia");
        public static AzureLocation USGovTexas { get; } = CreateStaticReference("usgovtexas", "US Gov Texas");
        public static AzureLocation DenmarkEast { get; } = CreateStaticReference("denmarkeast", "Denmark East");
'''

ICON = ':::image type="content" source="media/icon-region-coming-soon.svg" alt-text="soon" border="false":::'
REGIONS_LIST = f'''
#### [All](#tab/all)

| Region | Availability zones | Paired region | Physical location | Geography | Programmatic name |
|--------|----------------------------|---------------|-------------------|-----------|-------------------|
| East US | 3 | West US | Virginia | United States | eastus |
| East US 2 | 4 {ICON} | Central US | Virginia | United States | eastus2 |
| West US | N/A | East US | California | United States | westus |

#### [Americas](#tab/americas)

| Region | Availability zones | Paired region | Physical location | Geography | Programmatic name |
|---|---|---|---|---|---|
| North Europe | 3 | West Europe | Ireland | Europe | northeurope |
'''

GOV_WELCOME = '''
## List of regions

| Region          | Availability zone support | Paired region | Geography | Programmatic name |
|-----------------|---------------------------|---------------|-|-|
| US Gov Texas    |                           | US Gov Arizona | US Government | usgovtexas |
| US Gov Virginia | :::image  alt-text="Yes"  type="content" source="media/check-mark.svg"  border="false"::: | US Gov Texas  | US Government | usgovvirginia |
'''

ENTRA_NATIONAL = '''
| National cloud | Azure portal endpoint |
| --- | --- |
| Azure portal for US Government | `https://portal.azure.us` |

## Microsoft Entra authentication endpoints

| National cloud                      | Microsoft Entra authentication endpoint           |
| ----------------------------------- | ------------------------------------------ |
| Microsoft Entra ID for US Government          | `https://login.microsoftonline.us`         |
| Microsoft Entra China operated by 21Vianet | `https://login.partner.microsoftonline.cn` |
'''


# --------------------------------------------------------------------------- #
# Parsers
# --------------------------------------------------------------------------- #
def test_cli_cloud_is_read_without_importing_it():
    cli = g.parse_cli_cloud(CLI_CLOUD)
    assert cli["clouds"]["AZURE_PUBLIC_CLOUD"]["suffixes"]["keyvault_dns"] == ".vault.azure.net"
    assert cli["endpointIndex"]["gallery"] == "gallery"
    assert cli["suffixIndex"]["storage_endpoint"] == "suffixes.storage"


def test_cli_cloud_without_metadata_indexes_is_rejected():
    with pytest.raises(g.SourceError, match="ARM_METADATA_INDEX"):
        g.parse_cli_cloud("AZURE_PUBLIC_CLOUD = Cloud('AzureCloud')\n")


def test_go_environment_reads_only_the_first_function():
    env = g.parse_go_environment(GO_GOV)
    assert env["audiences"] == ["https://management.core.usgovcloudapi.net", "https://management.usgovcloudapi.net"]
    assert env["loginEndpoint"] == "https://login.microsoftonline.us"
    assert env["fields"]["MicrosoftGraph"]["args"] == ["https://graph.microsoft.us"]
    assert env["fields"]["KeyVault"]["resourceIdentifier"] == "https://vault.usgovcloudapi.net"


def test_go_suffix_takes_the_last_argument():
    env = g.parse_go_environment(GO_GOV)
    assert g.go_suffix(env, "ServiceBus") == "servicebus.usgovcloudapi.net"
    assert g.go_suffix(env, "KeyVault") == "vault.usgovcloudapi.net"
    assert g.go_suffix(env, "Redis") is None


def test_go_metadata_fixtures_need_all_three_versions():
    fixtures = g.parse_go_metadata_fixtures(GO_METADATA)
    assert sorted(fixtures) == ["2018-01-01", "2019-05-01", "2022-09-01"]
    assert fixtures["2019-05-01"][1]["name"] == "AzureUSGovernment"
    with pytest.raises(g.SourceError, match="metadata20220901"):
        g.parse_go_metadata_fixtures(GO_METADATA.replace("metadata20220901", "metadataXXXXXXXX"))


def test_java_regions_follow_the_section_comments():
    regions = g.parse_java_regions(JAVA_REGION)
    assert regions == [
        ("eastus", "East US", "AzureCloud"),
        ("northeurope", "North Europe", "AzureCloud"),
        ("chinanorth", "China North", "AzureChinaCloud"),
        ("germanycentral", "Germany Central", None),
        ("usgovvirginia", "US Gov Virginia", "AzureUSGovernment"),
        ("usgovtexas", "US Gov Texas", "AzureUSGovernment"),
    ]


def test_a_java_region_before_any_section_is_rejected():
    with pytest.raises(g.SourceError, match="precedes"):
        g.parse_java_regions('public static final Region X = new Region("x", "X");')


def test_public_zones_read_the_all_tab_only_and_ignore_icons():
    assert g.parse_public_zones(REGIONS_LIST) == {"eastus": 3, "eastus2": 4, "westus": 0}


def test_gov_zones_read_the_check_mark():
    assert g.parse_gov_zones(GOV_WELCOME) == {"usgovtexas": False, "usgovvirginia": True}


def test_entra_national_reads_the_authentication_table_not_the_portal_one():
    hosts = g.parse_entra_national(ENTRA_NATIONAL)
    assert hosts["Microsoft Entra China operated by 21Vianet"] == "https://login.partner.microsoftonline.cn"
    assert "Azure portal for US Government" not in hosts


# --------------------------------------------------------------------------- #
# Builders
# --------------------------------------------------------------------------- #
def test_locations_carry_sourced_and_unsettled_zones():
    regions = g.parse_java_regions(JAVA_REGION)
    net = g.parse_net_locations(NET_LOCATION)
    zones = g.parse_public_zones(REGIONS_LIST)
    gov = g.parse_gov_zones(GOV_WELCOME)
    public = g.build_locations("AzureCloud", regions, net, zones, gov)
    assert [loc["name"] for loc in public] == ["eastus", "northeurope"]
    assert public[0]["zones"] == {"source": g.REGIONS_LIST, "value": ["1", "2", "3"]}
    assert public[1]["zones"]["value"] is None
    assert public[1]["zones"]["source"].startswith("UNSETTLED: ")
    usgov = {loc["name"]: loc["zones"] for loc in g.build_locations("AzureUSGovernment", regions, net, zones, gov)}
    assert usgov["usgovtexas"]["value"] == []
    assert usgov["usgovvirginia"]["value"] is None and "count" in usgov["usgovvirginia"]["source"]
    china = g.build_locations("AzureChinaCloud", regions, net, zones, gov)
    assert all(loc["zones"]["source"].startswith("UNSETTLED: ") for loc in china)


def test_locations_require_java_and_dotnet_to_agree():
    regions = g.parse_java_regions(JAVA_REGION)
    net = g.parse_net_locations(NET_LOCATION.replace('"East US")', '"US East")'))
    with pytest.raises(g.SourceError, match="eastus"):
        g.build_locations("AzureCloud", regions, net, {}, {})


def test_agree_rejects_disagreeing_witnesses():
    assert g.agree("x", "a", None, "a") == "a"
    with pytest.raises(g.SourceError, match="disagree"):
        g.agree("x", "a", "b")
    with pytest.raises(g.SourceError, match="no source"):
        g.agree("x", None)


def test_resolve_leaves_out_disputed_and_unsourced_keys():
    problems: list[dict] = []
    assert g.resolve("C", "k", "https://a/", "https://a", problems) == "https://a/"
    assert g.resolve("C", "k", None, "https://b", problems) == "https://b"
    assert problems == []
    assert g.resolve("C", "graph", "https://graph.windows.net", "https://graph.microsoftazure.us/", problems) is None
    assert g.resolve("C", "none", None, None, problems) is None
    assert [p["field"] for p in problems] == ["graph", "none"]
    assert all(p["source"].startswith("UNSETTLED: ") for p in problems)


def test_synthesized_metadata_skips_cli_local_keys_and_records_disputes():
    cli = g.parse_cli_cloud(CLI_CLOUD)
    fixtures = g.parse_go_metadata_fixtures(GO_METADATA)
    gov_compare = {"Gallery URL": ("gallery.azure.com", "gallery.azure.us")}
    payloads, problems = g.build_metadata("AzureUSGovernment", cli, fixtures, gov_compare, {"usGovCloud": {}})
    latest = payloads["2022-09-01"]
    # vmImageAliasDoc: cloud.py's public value is the CLI's own copy, so it is no witness against ARM
    assert latest["vmImageAliasDoc"] == "https://raw.example/aliases.json"
    assert latest["ossrDbmsResourceId"] == latest["ossrdbmsResourceId"]
    assert latest["suffixes"] == {"keyVaultDns": "vault.usgovcloudapi.net", "storage": "core.usgovcloudapi.net"}
    assert "graphAudience" not in latest
    assert "gallery" not in payloads["2019-05-01"]
    assert {p["field"] for p in problems} == {"graphAudience", "gallery"}
    assert payloads["2018-01-01"]["cloudEndpoint"] == {"usGovCloud": {}}


# --------------------------------------------------------------------------- #
# Shape check and CLI
# --------------------------------------------------------------------------- #
def write_repo_copy(tmp_path):
    output = tmp_path / "clouds.json"
    shutil.copy(g.OUTPUT, output)
    return output


def test_the_vendored_file_passes_the_shape_check():
    assert g.self_check(g.OUTPUT) == []


def test_shape_check_rejects_unsettled_cells_that_carry_a_value(tmp_path):
    good = json.loads(g.OUTPUT.read_text(encoding="utf-8"))
    output = tmp_path / "clouds.json"

    def problems(mutate):
        document = json.loads(json.dumps(good))
        mutate(document)
        output.write_text(json.dumps(document))
        return g.self_check(output)

    rejected = {
        "unsettled with a value": lambda d: d["clouds"][1]["suffixes"]["SIGNALR"].update(value="x.example.net"),
        "null without UNSETTLED": lambda d: d["clouds"][0]["suffixes"]["BLOB"].update(value=None),
        "missing suffix": lambda d: d["clouds"][0]["suffixes"].pop("ACI"),
        "dotted suffix": lambda d: d["clouds"][0]["suffixes"]["ACR"].update(value=".azurecr.io"),
        "cloud order": lambda d: d["clouds"].reverse(),
        "missing api-version": lambda d: d["clouds"][0]["metadataEndpoints"].pop("2018-01-01"),
        "lone ossrDbmsResourceId": lambda d: d["clouds"][0]["metadataEndpoints"]["2022-09-01"].pop("ossrdbmsResourceId"),
        "default not a location": lambda d: d["clouds"][0].update(defaultLocation={"source": "x", "value": "mars"}),
        "zone not a digit": lambda d: d["clouds"][0]["locations"][0]["zones"].update(value=["a"]),
    }
    assert problems(lambda d: None) == []
    for label, mutate in rejected.items():
        assert problems(mutate), label


def test_check_without_sources_verifies_the_shape_only(tmp_path, capsys):
    output = write_repo_copy(tmp_path)
    empty = tmp_path / "nowhere"
    assert g.main(["--check", "--local-root", str(empty), "--output", str(output)]) == 0
    assert "shape verified only" in capsys.readouterr().out
    output.write_text('{"clouds": []}')
    assert g.main(["--check", "--local-root", str(empty), "--output", str(output)]) == 1
    output.write_text("not json")
    assert g.main(["--check", "--local-root", str(empty), "--output", str(output)]) == 1


def test_regenerating_without_sources_fails(tmp_path):
    with pytest.raises(SystemExit, match="reference checkouts"):
        g.main(["--local-root", str(tmp_path / "nowhere"), "--output", str(tmp_path / "out.json")])


def local_sources_present() -> bool:
    return g.sources_present(g.LOCAL_AZURE)


def test_repo_file_matches_the_local_checkouts_when_present():
    if not local_sources_present():
        pytest.skip("local/azure checkouts not present")
    assert g.strip_source(g.render(g.build(g.LOCAL_AZURE))) == g.strip_source(g.OUTPUT.read_text(encoding="utf-8"))


def test_cli_write_then_check_round_trips_and_detects_drift(tmp_path, capsys):
    if not local_sources_present():
        pytest.skip("local/azure checkouts not present")
    output = tmp_path / "out" / "clouds.json"
    assert g.main(["--check", "--output", str(output)]) == 1
    assert g.main(["--output", str(output)]) == 0
    assert g.main(["--check", "--output", str(output)]) == 0
    assert "is up to date" in capsys.readouterr().out
    document = json.loads(output.read_text(encoding="utf-8"))
    document["clouds"][0]["endpoints"]["portal"] = "https://portal.example.com"
    output.write_text(json.dumps(document))
    assert g.main(["--check", "--output", str(output)]) == 1


def test_no_unsettled_cell_is_emitted_as_fact():
    document = json.loads(g.OUTPUT.read_text(encoding="utf-8"))

    def walk(node):
        if isinstance(node, dict):
            if "source" in node and str(node["source"]).startswith("UNSETTLED"):
                assert node.get("value") is None, node
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(document["clouds"])
