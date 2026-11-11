#!/usr/bin/env python3
"""Regenerate src/main/resources/azure/role-definitions.json from the local iam-dataset checkout.

`io.floci.az.services.authorization.RoleDefinitionCatalog` reads this file for Azure's built-in role definitions,
which `GET {scope}/providers/Microsoft.Authorization/roleDefinitions` answers. Clients look a role up by its name
(`$filter=roleName eq 'AcrPull'`) and take the GUID from the answer, so the GUIDs, names and permissions must be
Azure's own, never hand-typed. iam-dataset `azure/built-in-roles-raw.json` is the role list as the Azure API returns
it; this script keeps the fields the role definition resource carries and sorts the roles by GUID.

Each role is one line, so a role Azure adds or changes is a one-line diff.

Run from anywhere in the repo:
    python3 tools/arm/gen_roles.py            # rewrite the vendored file in place
    python3 tools/arm/gen_roles.py --check    # exit 1 when the vendored file is stale

Without the checkout (CI has no `local/`), `--check` only verifies that the vendored file parses and keeps its shape;
a regeneration needs `local/azure/iam-dataset`.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/azure/role-definitions.json"
LOCAL_AZURE = REPO_ROOT / "local/azure"
SOURCE = "iam-dataset/azure/built-in-roles-raw.json"

GUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
ROLE_KEYS = ["name", "roleName", "description", "roleType", "assignableScopes", "permissions"]
PERMISSION_KEYS = ["actions", "notActions", "dataActions", "notDataActions", "condition", "conditionVersion"]


class SourceError(Exception):
    pass


def role(raw: dict) -> dict:
    """The fields of one role definition, in a fixed order; permission lists keep Azure's order."""
    missing = [key for key in ROLE_KEYS if key not in raw]
    if missing:
        raise SourceError(f"role {raw.get('name')!r} has no {missing}")
    entry = {key: raw[key] for key in ROLE_KEYS}
    entry["permissions"] = [{key: permission.get(key) for key in PERMISSION_KEYS} for permission in raw["permissions"]]
    for key in ("actions", "notActions", "dataActions", "notDataActions"):
        for permission in entry["permissions"]:
            if permission[key] is None:
                permission[key] = []
    return entry


def roles(raw: list) -> list[dict]:
    entries = sorted((role(item) for item in raw), key=lambda entry: entry["name"])
    names: set[str] = set()
    for entry in entries:
        lowered = entry["roleName"].lower()
        if lowered in names:
            raise SourceError(f"role name {entry['roleName']!r} is used twice")
        names.add(lowered)
    return entries


def git_commit(root: Path, rel: str) -> str:
    repo = root / rel.split("/")[0]
    try:
        result = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True,
                                check=False)
    except OSError:
        return "unknown"
    return result.stdout.strip() if result.returncode == 0 and result.stdout.strip() else "unknown"


def build(root: Path) -> dict:
    raw = json.loads((root / SOURCE).read_text(encoding="utf-8"))
    if not isinstance(raw, list) or not raw:
        raise SourceError(f"{SOURCE} must be a non-empty list")
    return {
        "_source": {
            "generator": "tools/arm/gen_roles.py",
            "root": "local/azure",
            "files": {SOURCE: git_commit(root, SOURCE)},
        },
        "roleDefinitions": roles(raw),
    }


def render(document: dict) -> str:
    """The provenance block indented, then one role per line."""
    source = json.dumps({"_source": document["_source"]}, indent=2, ensure_ascii=False)
    lines = [json.dumps(entry, ensure_ascii=False, separators=(",", ":")) for entry in document["roleDefinitions"]]
    return (source[:-2] + ',\n  "roleDefinitions": [\n    ' + ",\n    ".join(lines) + "\n  ]\n}\n")


def strip_source(text: str) -> str:
    """The document without its provenance block, so the check compares data, not commits."""
    if not text.strip():
        return ""
    document = json.loads(text)
    document.pop("_source", None)
    return json.dumps(document)


def self_check(path: Path) -> list[str]:
    """Problems with the vendored file that need no sources."""
    if not path.exists():
        return [f"{path} is missing"]
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        return [f"{path}: not valid JSON ({e})"]
    entries = document.get("roleDefinitions")
    if not isinstance(entries, list) or not entries:
        return [f"{path}: 'roleDefinitions' must be a non-empty list"]
    problems: list[str] = []
    names: set[str] = set()
    previous = ""
    for entry in entries:
        if not (isinstance(entry, dict) and list(entry) == ROLE_KEYS):
            problems.append(f"malformed role definition {entry!r:.120}")
            continue
        guid = entry["name"]
        if not (isinstance(guid, str) and GUID_RE.fullmatch(guid)):
            problems.append(f"{guid!r}: the name must be a lowercase GUID")
        elif guid <= previous:
            problems.append(f"{guid}: role definitions must be sorted by GUID, without duplicates")
        else:
            previous = guid
        role_name = entry["roleName"]
        if not (isinstance(role_name, str) and role_name.strip()):
            problems.append(f"{guid}: empty roleName")
        elif role_name.lower() in names:
            problems.append(f"{guid}: role name {role_name!r} is used twice")
        else:
            names.add(role_name.lower())
        if not (isinstance(entry["assignableScopes"], list) and entry["assignableScopes"]):
            problems.append(f"{guid}: assignableScopes must be a non-empty list")
        permissions = entry["permissions"]
        if not (isinstance(permissions, list)
                and all(isinstance(p, dict) and list(p) == PERMISSION_KEYS for p in permissions)):
            problems.append(f"{guid}: malformed permissions")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--local-root", type=Path, default=LOCAL_AZURE,
                        help="directory holding the reference checkouts (default: local/azure)")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    rel = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output

    if not (args.local_root / SOURCE).is_file():
        if args.check:
            problems = self_check(args.output)
            for problem in problems:
                print(f"error: {problem}")
            if problems:
                return 1
            print(f"{rel}: iam-dataset not checked out under {args.local_root}, shape verified only")
            return 0
        raise SystemExit(f"error: regenerating needs {args.local_root / SOURCE}")

    try:
        document = build(args.local_root)
    except SourceError as e:
        print(f"error: {e}")
        return 1
    rendered = render(document)
    summary = f"{len(document['roleDefinitions'])} built-in role definitions"

    if args.check:
        current = args.output.read_text(encoding="utf-8") if args.output.exists() else ""
        if strip_source(current) != strip_source(rendered):
            print(f"error: {rel} differs from a fresh generation. Run 'make arm-data-sync' and commit the result.")
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
    print(f"wrote {rel} ({summary})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
