"""Tests for gen_roles.

Run with: pytest tools/arm -q  (or: make arm-data-test)
"""
from __future__ import annotations

import json

import pytest

import gen_roles as g

READER = {
    "assignableScopes": ["/"],
    "description": "View all resources, but does not allow you to make any changes.",
    "name": "acdd72a7-3385-48ef-bd42-f606fba81ae7",
    "permissions": [{"actions": ["*/read"], "condition": None, "conditionVersion": None, "dataActions": [],
                     "notActions": [], "notDataActions": []}],
    "roleName": "Reader",
    "roleType": "BuiltInRole",
    "type": "Microsoft.Authorization/roleDefinitions",
}
ACR_PULL = {
    "assignableScopes": ["/"],
    "description": "acr pull",
    "name": "7f951dda-4ed3-4680-a7ca-43fe172d538d",
    "permissions": [{"actions": ["Microsoft.ContainerRegistry/registries/pull/read"], "condition": None,
                     "conditionVersion": None, "dataActions": None, "notActions": [], "notDataActions": []}],
    "roleName": "AcrPull",
    "roleType": "BuiltInRole",
    "type": "Microsoft.Authorization/roleDefinitions",
}


def test_roles_keep_the_resource_fields_in_a_fixed_order_sorted_by_guid():
    entries = g.roles([READER, ACR_PULL])
    assert [entry["name"] for entry in entries] == [ACR_PULL["name"], READER["name"]]
    assert list(entries[0]) == g.ROLE_KEYS
    assert list(entries[0]["permissions"][0]) == g.PERMISSION_KEYS


def test_a_missing_permission_list_becomes_empty():
    assert g.roles([ACR_PULL])[0]["permissions"][0]["dataActions"] == []


def test_a_role_name_used_twice_in_any_case_is_refused():
    twin = dict(READER, name="00000000-0000-0000-0000-000000000001", roleName="reader")
    with pytest.raises(g.SourceError):
        g.roles([READER, twin])


def test_rendered_file_is_one_role_per_line_and_round_trips(tmp_path):
    document = {"_source": {"generator": "tools/arm/gen_roles.py"}, "roleDefinitions": g.roles([READER, ACR_PULL])}
    text = g.render(document)
    assert json.loads(text) == document
    role_lines = [line for line in text.splitlines() if line.lstrip().startswith('{"name"')]
    assert len(role_lines) == 2
    path = tmp_path / "role-definitions.json"
    path.write_text(text, encoding="utf-8")
    assert g.self_check(path) == []


def test_self_check_reports_an_unsorted_or_malformed_file(tmp_path):
    entries = g.roles([READER, ACR_PULL])
    path = tmp_path / "role-definitions.json"
    path.write_text(json.dumps({"roleDefinitions": list(reversed(entries))}), encoding="utf-8")
    assert any("sorted" in problem for problem in g.self_check(path))
    path.write_text(json.dumps({"roleDefinitions": [{"name": "x"}]}), encoding="utf-8")
    assert g.self_check(path)


def test_check_without_the_checkout_verifies_shape_only(tmp_path):
    path = tmp_path / "role-definitions.json"
    path.write_text(g.render({"_source": {}, "roleDefinitions": g.roles([READER])}), encoding="utf-8")
    assert g.main(["--check", "--local-root", str(tmp_path / "absent"), "--output", str(path)]) == 0
