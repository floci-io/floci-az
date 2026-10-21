"""Tests for gen_providers.

Run with: pytest tools/arm -q  (or: make arm-data-test)
"""
from __future__ import annotations

import json

import pytest

import gen_providers as g


def test_api_versions_are_newest_first_with_stable_before_preview():
    keys = [
        "Microsoft.Cache/redis@2023-08-01",
        "Microsoft.Cache/redis@2024-03-01-preview",
        "Microsoft.Cache/redis@2024-03-01",
        "Microsoft.Cache/redis@2020-06-01",
    ]
    grouped = g.group(keys, ["Microsoft.Cache"])
    assert grouped["Microsoft.Cache"]["redis"] == ["2024-03-01", "2024-03-01-preview", "2023-08-01", "2020-06-01"]


def test_types_group_case_insensitively_and_keep_the_newest_spelling():
    keys = [
        "Microsoft.Cache/Redis@2018-03-01",
        "Microsoft.Cache/redis@2024-11-01",
        "microsoft.cache/REDIS@2019-07-01",
    ]
    grouped = g.group(keys, ["Microsoft.Cache"])
    assert list(grouped["Microsoft.Cache"]) == ["redis"]
    assert grouped["Microsoft.Cache"]["redis"] == ["2024-11-01", "2019-07-01", "2018-03-01"]


def test_the_namespace_keeps_the_declared_spelling_and_others_are_ignored():
    keys = ["microsoft.insights/diagnosticSettings@2021-05-01-preview", "Microsoft.Other/things@2020-01-01"]
    grouped = g.group(keys, ["Microsoft.Insights"])
    assert grouped == {"Microsoft.Insights": {"diagnosticSettings": ["2021-05-01-preview"]}}


def test_a_namespace_with_no_type_fails_the_generation():
    with pytest.raises(g.SourceError, match="Microsoft.Missing"):
        g.group(["Microsoft.Cache/redis@2024-11-01"], ["Microsoft.Cache", "Microsoft.Missing"])


def test_an_unexpected_api_version_fails_the_generation():
    with pytest.raises(g.SourceError, match="latest"):
        g.group(["Microsoft.Cache/redis@latest", "Microsoft.Cache/redis@2024-11-01"], ["Microsoft.Cache"])


def test_render_keeps_each_version_list_on_one_line_and_round_trips():
    document = {"providers": [{"namespace": "Microsoft.Cache", "resourceTypes": [
        {"resourceType": "redis", "apiVersions": ["2024-11-01", "2023-08-01"]}]}]}
    text = g.render(document)
    assert '"apiVersions": ["2024-11-01", "2023-08-01"]' in text
    assert json.loads(text) == document


def test_the_vendored_file_passes_the_shape_check():
    assert g.self_check(g.OUTPUT) == []


def test_the_shape_check_rejects_versions_out_of_order(tmp_path):
    vendored = json.loads(g.OUTPUT.read_text(encoding="utf-8"))
    vendored["providers"][0]["resourceTypes"][0]["apiVersions"].reverse()
    vendored["providers"][0]["resourceTypes"][0]["apiVersions"].append("2000-01-01")
    path = tmp_path / "providers.json"
    path.write_text(json.dumps(vendored), encoding="utf-8")
    assert any("newest first" in problem for problem in g.self_check(path))
