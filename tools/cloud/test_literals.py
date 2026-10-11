"""Tests for literals (the cloud literal ratchet).

Run with: pytest tools/cloud -q  (or: make az-data-test)
"""
from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

import pytest

import literals as l


CLOUDS = {
    "clouds": [
        {
            "name": "AzureCloud",
            "endpoints": {
                "activeDirectory": "https://login.microsoftonline.com",
                "authorityHosts": ["login.microsoftonline.com"],
                "microsoftGraph": "https://graph.microsoft.com",
                "resourceManager": "https://management.azure.com",
            },
            "audiences": ["https://management.core.windows.net"],
            "suffixes": {
                "BLOB": {"value": "blob.core.windows.net", "source": "s"},
                "KEYVAULT": {"value": "vault.azure.net", "source": "s"},
                "ACR": {"value": "azurecr.io", "source": "s"},
            },
        },
        {
            "name": "AzureUSGovernment",
            "endpoints": {"resourceManager": "https://management.usgovcloudapi.net"},
            "audiences": [],
            "suffixes": {
                "BLOB": {"value": "blob.core.usgovcloudapi.net", "source": "s"},
                "ACI": {"value": None, "source": "UNSETTLED: x"},
            },
        },
    ]
}
CATEGORIES = l.build_categories(CLOUDS)
BY_NAME = {c.name: c for c in CATEGORIES}
SERVICES = l.SERVICES_PREFIX


def write_java(root: Path, rel: str, body: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    return path


def findings_in(root: Path, rules: list[l.AllowRule] | None = None) -> list[l.Finding]:
    return l.collect_findings(root, rules or [], CATEGORIES)


# --------------------------------------------------------------------------- #
# Lexer
# --------------------------------------------------------------------------- #
def literal_texts(src: str) -> list[str]:
    return [literal.text for literal in l.scan_java(src)[0]]


def test_scan_java_extracts_plain_string_literals_with_line_numbers():
    literals, escapes = l.scan_java('String a = "x.vault.azure.net";\nString b = "y";\n')
    assert [(x.line, x.text) for x in literals] == [(1, "x.vault.azure.net"), (2, "y")]
    assert escapes == {}


def test_scan_java_ignores_line_and_block_comments():
    src = (
        '// "eastus" in a line comment\n'
        '/* ".blob.core.windows.net"\n   spanning lines */\n'
        '/** {@code eastus} */\n'
        'String a = "kept";\n'
    )
    assert [(x.line, x.text) for x in l.scan_java(src)[0]] == [(5, "kept")]


def test_scan_java_keeps_double_slash_inside_a_string():
    assert literal_texts('String u = "https://login.microsoftonline.com/common";') == [
        "https://login.microsoftonline.com/common"]


def test_scan_java_handles_escaped_quotes_and_char_literals():
    src = "char q = '\"'; String s = \"say \\\"hi\\\" eastus\"; char b = '\\\\';"
    assert literal_texts(src) == ['say \\"hi\\" eastus']


def test_scan_java_splits_text_blocks_per_line():
    src = (
        'String json = """\n'
        '    {"location": "eastus",\n'
        '     "host": "a.blob.core.windows.net"}\n'
        '    """;\n'
        'String after = "tail";\n'
    )
    literals, _ = l.scan_java(src)
    assert [x.line for x in literals] == [2, 3, 5]


def test_scan_java_records_the_escape_comment_by_line():
    src = (
        'String a = "eastus"; // cloud-literal: the documented sample location\n'
        'String b = "eastus"; // an ordinary comment\n'
    )
    assert l.scan_java(src)[1] == {1: "the documented sample location"}


def test_scan_java_escape_needs_a_reason():
    assert l.scan_java('String a = "eastus"; // cloud-literal:   \n')[1] == {}


# --------------------------------------------------------------------------- #
# Domains and categories
# --------------------------------------------------------------------------- #
def test_cloud_domains_come_from_every_cloud_with_their_parent_zones():
    domains = l.cloud_domains(CLOUDS)
    for expected in ("blob.core.windows.net", "core.windows.net", "windows.net", "vault.azure.net",
                     "azure.net", "azurecr.io", "login.microsoftonline.com", "microsoftonline.com",
                     "management.azure.com", "azure.com", "management.core.windows.net",
                     "blob.core.usgovcloudapi.net", "management.usgovcloudapi.net", "graph.microsoft.com"):
        assert expected in domains
    assert "microsoft.com" not in domains
    assert "io" not in domains and "net" not in domains
    assert domains == sorted(domains, key=lambda d: (-len(d), d))


def test_repo_catalog_yields_the_suffixes_the_plan_names():
    domains = l.cloud_domains(l.load_clouds())
    for expected in ("core.windows.net", "vault.azure.net", "azurecr.io", "management.azure.com",
                     "login.microsoftonline.com", "vault.usgovcloudapi.net", "azurecr.cn"):
        assert expected in domains


def cloud_hits(text: str) -> int:
    return len(BY_NAME["cloud-literal"].regex.findall(text))


@pytest.mark.parametrize("text,expected", [
    ("x.blob.core.windows.net", 1),
    (".vault.azure.net/", 1),
    ("https://login.microsoftonline.com/common", 1),
    ("core.windows.net", 1),
    ("microsoftonline.com", 1),
    ("*.azurecr.io", 1),
    ("https://documents.azure.com", 1),
    ("x.blob.core.usgovcloudapi.net", 1),
    ("a.vault.azure.net and b.azurecr.io", 2),
    ("[a-z]+\\\\.vault\\\\.azure\\\\.net", 1),
    ("X.BLOB.CORE.WINDOWS.NET", 1),
    ("mcr.microsoft.com/azure-functions/java", 0),
    ("https://aka.ms/x", 0),
    ("notazure.net", 0),
    ("vault.azure.network", 0),
    ("localhost:4577", 0),
])
def test_cloud_literal_counts_each_host_once(text, expected):
    assert cloud_hits(text) == expected


@pytest.mark.parametrize("text,expected", [
    ("eastus", True),
    ('"location": "eastus",', True),
    ('{\\"location\\":\\"eastus\\"}', True),
    ("eastus2", False),
    ("East US", False),
    ("westus", False),
    ("x-eastus", False),
])
def test_location_literal_matches_exactly_eastus(text, expected):
    assert (BY_NAME["location-literal"].regex.search(text) is not None) is expected


@pytest.mark.parametrize("code,expected", [
    ('ArmPaths.subscription(path, "unknown")', True),
    ("ArmPaths.resourceGroup(fullPath)", True),
    ("ArmScope.of(request.resourcePath())", True),
    ('ArmPaths.segmentAfter(fullPath, "subscriptions", "default")', True),
    ('segmentAfter(fullPath, "resourcegroups")', True),
    ('ArmPaths.segmentAfter(path, "providers", "x")', False),
    ('ArmPaths.resourceName(path, "vaults")', False),
    ("scope.subscription()", False),
])
def test_scope_parse_matches_path_parsing_calls(code, expected):
    assert (BY_NAME["scope-parse"].regex.search(code) is not None) is expected


@pytest.mark.parametrize("code,expected", [
    ("private boolean ownedElsewhere(String sub, String rg, String name) {", True),
    ("private static Optional<Map<String, Object>> ownedElsewhere(Map<String, Map<String, Object>> store,\n"
     "        String sub, String rg, String name) {", True),
    ("private Optional<Response> foreignServer(AzureRequest request, String tail) {", True),
    ("private final Object nameClaims = new Object();", True),
    ("boolean ownedElsewhere = services.values().stream().anyMatch(s -> true);", True),
    ("if (ownedElsewhere(sub, rg, cacheName)) {", False),
    ("Optional<Response> foreign = foreignServer(request, tail);", False),
    ("synchronized (nameClaims) {", False),
    ("if (ownedElsewhere) {", False),
    ("x.ownedElsewhere == y", False),
])
def test_name_guard_matches_declarations_not_call_sites(code, expected):
    assert (BY_NAME["name-guard"].regex.search(code) is not None) is expected


def test_scope_parse_counts_only_under_services(tmp_path):
    body = 'class A { Object s = ArmPaths.subscription(path, "x"); }\n'
    write_java(tmp_path, SERVICES + "redis/A.java", body)
    write_java(tmp_path, l.PACKAGE_PREFIX + "/core/arm/B.java", body)
    assert l.count(findings_in(tmp_path)) == Counter({("scope-parse", SERVICES + "redis/A.java"): 1})


def test_code_categories_ignore_comments_and_strings_never_split_them(tmp_path):
    write_java(tmp_path, SERVICES + "sql/S.java", (
        "class S {\n"
        "    // private Optional<Response> foreignServer(AzureRequest r) {\n"
        "    /* ArmPaths.subscription(path) */\n"
        "    private Optional<Response> foreignServer(\n"
        "            AzureRequest request, String tail) {\n"
        "        return ArmScope.of(request.resourcePath()).map(x -> null);\n"
        "    }\n"
        "}\n"
    ))
    findings = findings_in(tmp_path)
    assert sorted((f.category, f.line) for f in findings) == [("name-guard", 4), ("scope-parse", 6)]


def test_cloud_literal_matches_inside_string_literals_only(tmp_path):
    write_java(tmp_path, "a/A.java", (
        "class A {\n"
        "    // https://x.vault.azure.net in a comment\n"
        "    String vaultAzureNet = suffix; // vault.azure.net\n"
        "    /** {@code core.windows.net} */\n"
        '    String host = account + ".blob.core.windows.net";\n'
        '    String loc = "eastus";\n'
        "}\n"
    ))
    assert l.count(findings_in(tmp_path)) == Counter({
        ("cloud-literal", "a/A.java"): 1,
        ("location-literal", "a/A.java"): 1,
    })


# --------------------------------------------------------------------------- #
# Allow-list and escapes
# --------------------------------------------------------------------------- #
def test_allowlist_file_and_pattern_excuse_but_still_report(tmp_path):
    write_java(tmp_path, "a/Hosts.java", 'class H { String s = "x.vault.azure.net"; }\n')
    write_java(tmp_path, "b/Err.java", (
        "class E {\n"
        '    String t = "https://azconfig.io/errors/key-not-found";\n'
        '    String h = "x.azurecr.io";\n'
        "}\n"
    ))
    clouds = json.loads(json.dumps(CLOUDS))
    clouds["clouds"][0]["suffixes"]["APPCONFIG"] = {"value": "azconfig.io", "source": "s"}
    rules = [
        l.AllowRule("file", "a/Hosts.java", "fixture"),
        l.AllowRule("pattern", r"azconfig\.io/errors/", "problem type URI"),
    ]
    findings = l.collect_findings(tmp_path, rules, l.build_categories(clouds))
    assert l.count(findings) == Counter({("cloud-literal", "b/Err.java"): 1})
    assert sorted(f.excuse for f in findings if f.excuse) == [
        "allowlist file: fixture",
        "allowlist pattern 'azconfig\\\\.io/errors/': problem type URI",
    ]


def test_escape_comment_excuses_only_its_own_line(tmp_path):
    write_java(tmp_path, "x/X.java", (
        "class X {\n"
        '    String a = "eastus"; // cloud-literal: the sample in the spec example\n'
        '    String b = "eastus";\n'
        "}\n"
    ))
    findings = findings_in(tmp_path)
    assert l.count(findings) == Counter({("location-literal", "x/X.java"): 1})
    assert [(f.line, f.excuse) for f in findings if f.excuse] == [(2, "escape: the sample in the spec example")]


def test_escape_comment_excuses_a_code_category_too(tmp_path):
    write_java(tmp_path, SERVICES + "x/X.java", (
        "class X {\n"
        "    String s = ArmPaths.subscription(id, null); // cloud-literal: parses a stored id, not the request\n"
        "}\n"
    ))
    assert l.count(findings_in(tmp_path)) == Counter()


def test_load_allowlist_rejects_an_entry_without_a_reason(tmp_path):
    allowlist = tmp_path / "allowlist.yaml"
    allowlist.write_text("patterns:\n  - regex: foo\n    reason: '  '\n")
    with pytest.raises(ValueError, match="no reason"):
        l.load_allowlist(allowlist)


ALLOWLIST_TEXT = (
    "# comment\n"
    "files:\n"
    "  - path: a/B.java\n"
    "    reason: why it is\n"
    "      invariant\n"
    "patterns:\n"
    "  - regex: 'x\\.y'\n"
    "    reason: \"because\"\n"
)


def test_load_allowlist_reads_files_and_patterns(tmp_path):
    allowlist = tmp_path / "allowlist.yaml"
    allowlist.write_text(ALLOWLIST_TEXT)
    assert l.load_allowlist(allowlist) == [
        l.AllowRule("file", "a/B.java", "why it is invariant"),
        l.AllowRule("pattern", "x\\.y", "because"),
    ]


def test_fallback_parser_agrees_with_pyyaml():
    yaml = pytest.importorskip("yaml")
    for text in (ALLOWLIST_TEXT, l.ALLOWLIST.read_text(encoding="utf-8"), "files: []\npatterns: []\n"):
        assert l.parse_allowlist_yaml(text) == (yaml.safe_load(text) or {})


def test_repo_allowlist_loads_and_every_pattern_compiles():
    for rule in l.load_allowlist(l.ALLOWLIST):
        if rule.kind == "pattern":
            re.compile(rule.value)


# --------------------------------------------------------------------------- #
# Baseline and the gate
# --------------------------------------------------------------------------- #
def test_baseline_round_trips(tmp_path):
    write_java(tmp_path / "src", "a/A.java", 'class A { String r = "eastus"; String h = "x.azurecr.io"; }\n')
    findings = findings_in(tmp_path / "src")
    baseline = tmp_path / "baseline.tsv"
    written = l.write_baseline(baseline, findings)
    assert written == Counter({("cloud-literal", "a/A.java"): 1, ("location-literal", "a/A.java"): 1})
    assert l.read_baseline(baseline, set(BY_NAME)) == written
    assert baseline.read_text().startswith("# path\tcategory\tcount\n")


def test_read_baseline_rejects_malformed_rows(tmp_path):
    baseline = tmp_path / "baseline.tsv"
    baseline.write_text("a/A.java\tcloud-literal\n")
    with pytest.raises(ValueError, match="malformed"):
        l.read_baseline(baseline, set(BY_NAME))
    baseline.write_text("a/A.java\tnope\t1\n")
    with pytest.raises(ValueError, match="unknown category"):
        l.read_baseline(baseline, set(BY_NAME))


def test_check_is_green_when_counts_match(tmp_path):
    write_java(tmp_path, "a/A.java", 'class A { String a = "eastus"; }\n')
    assert l.check(findings_in(tmp_path), Counter({("location-literal", "a/A.java"): 1}), CATEGORIES) == []


def test_check_fails_an_occurrence_in_a_file_the_baseline_does_not_know(tmp_path):
    write_java(tmp_path, "n/New.java", 'class N { String a = "x.blob.core.windows.net"; }\n')
    problems = l.check(findings_in(tmp_path), Counter(), CATEGORIES)
    assert len(problems) == 1
    assert "1 cloud-literal occurrence(s) with no baseline entry for this file" in problems[0]
    assert "n/New.java:1:" in problems[0]
    assert "CloudCatalog" in problems[0]


def test_check_fails_growth_and_lists_every_occurrence(tmp_path):
    write_java(tmp_path, "a/A.java", 'class A {\n String a = "eastus";\n String c = "eastus";\n}\n')
    problems = l.check(findings_in(tmp_path), Counter({("location-literal", "a/A.java"): 1}), CATEGORIES)
    assert len(problems) == 1
    assert "location-literal grew from 1 to 2" in problems[0]
    assert "a/A.java:2:" in problems[0] and "a/A.java:3:" in problems[0]


def test_check_fails_a_drop_until_the_baseline_is_regenerated(tmp_path):
    write_java(tmp_path, "a/A.java", 'class A { String a = "clean"; }\n')
    problems = l.check(findings_in(tmp_path), Counter({("cloud-literal", "a/A.java"): 3}), CATEGORIES)
    assert len(problems) == 1
    assert "dropped from 3 to 0" in problems[0]
    assert "make cloud-literals-baseline" in problems[0]


def test_check_treats_an_escaped_occurrence_as_absent(tmp_path):
    write_java(tmp_path, "a/A.java", 'class A { String a = "eastus"; // cloud-literal: fixture\n}\n')
    assert l.check(findings_in(tmp_path), Counter(), CATEGORIES) == []


# --------------------------------------------------------------------------- #
# Audit and CLI
# --------------------------------------------------------------------------- #
def test_package_of_groups_by_service_and_falls_back_to_the_parent():
    assert l.package_of("src/main/java/io/floci/az/services/keyvault/KeyVaultHandler.java") == "services/keyvault"
    assert l.package_of("src/main/java/io/floci/az/core/tls/TlsConfigSource.java") == "core/tls"
    assert l.package_of("src/main/java/io/floci/az/config/EmulatorConfig.java") == "config"
    assert l.package_of("src/main/java/io/floci/az/Main.java") == "(root)"
    assert l.package_of("elsewhere/x/Y.java") == "elsewhere/x"


def test_format_audit_lists_totals_escapes_and_allowlist_hits(tmp_path):
    write_java(tmp_path, "a/A.java", (
        "class A {\n"
        ' String a = "eastus";\n'
        ' String e = "eastus"; // cloud-literal: fixture value\n'
        ' String g = "x.vault.azure.net";\n'
        "}\n"
    ))
    rules = [l.AllowRule("pattern", r"vault\.azure\.net", "fixture host")]
    report = l.format_audit(findings_in(tmp_path, rules), Counter({("location-literal", "a/A.java"): 4}), CATEGORIES)
    assert "escapes (// cloud-literal: <reason>): 1" in report
    assert "fixture value" in report
    assert "allow-listed occurrences: 1" in report
    assert "location-literal 4 -> 1" in report


def test_cli_check_and_write_baseline(tmp_path, capsys):
    write_java(tmp_path / "src", "a/A.java", 'class A { String a = "x.azurecr.io"; }\n')
    allowlist = tmp_path / "allowlist.yaml"
    allowlist.write_text("files: []\npatterns: []\n")
    clouds = tmp_path / "clouds.json"
    clouds.write_text(json.dumps(CLOUDS))
    baseline = tmp_path / "baseline.tsv"
    common = ["--source", str(tmp_path / "src"), "--allowlist", str(allowlist), "--baseline", str(baseline),
              "--clouds", str(clouds)]

    assert l.main(["--check"] + common) == 1
    assert "no baseline entry" in capsys.readouterr().out

    assert l.main(["--write-baseline"] + common) == 0
    assert "1 occurrence(s) across 1 file(s)" in capsys.readouterr().out

    assert l.main(["--check"] + common) == 0
    assert "match literals-baseline.tsv (1 occurrence(s) remaining)" in capsys.readouterr().out

    assert l.main(["--audit"] + common) == 0
    out = capsys.readouterr().out
    assert "cloud-literal" in out and "total" in out

