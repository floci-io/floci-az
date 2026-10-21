#!/usr/bin/env python3
"""Regenerate src/main/resources/azure/providers.json from the local bicep-types-az checkout.

`io.floci.az.core.arm.ArmTypeRegistry` reads this file for the api-versions of every resource type in the
ARM provider namespaces floci-az serves. The provider endpoints (`GET /providers/{namespace}`) answer
`resourceTypes[].apiVersions` from it, and az CLI's generic `az resource` commands and the SDKs' generic
resource clients pick the api-version they send from that list, so it must be Azure's list, not a guess.
Nothing in the file is hand-typed: bicep-types-az `generated/index.json` keys every resource type it knows
as `{namespace}/{type}@{apiVersion}`, and this script groups those keys by type.

Which namespaces are served is declared once in Java, by the `ArmSurfaceProvider` beans. `NAMESPACES` below
repeats that list so the script can run without a JVM; `ArmSurfaceInventoryTest` fails when the two differ.

Casing: bicep-types-az spells some types differently across api-versions (`Microsoft.Cache/Redis` and
`Microsoft.Cache/redis`). Types are grouped case-insensitively and keep the spelling of their newest
api-version; the namespace keeps the spelling in `NAMESPACES`. The registry matches case-insensitively.

Order: api-versions newest first, as Azure lists them; a stable version sorts before a preview of the same date.

Run from anywhere in the repo:
    python3 tools/arm/gen_providers.py            # rewrite the vendored file in place
    python3 tools/arm/gen_providers.py --check    # exit 1 when the vendored file is stale

Without the checkout (CI has no `local/`), `--check` only verifies that the vendored file parses and keeps
its shape; a regeneration needs `local/azure/bicep-types-az`.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT = REPO_ROOT / "src/main/resources/azure/providers.json"
LOCAL_AZURE = REPO_ROOT / "local/azure"
INDEX = "bicep-types-az/generated/index.json"

# The ARM provider namespaces floci-az serves. Keep in step with the ArmSurfaceProvider beans
# (ArmSurfaceInventoryTest checks it).
NAMESPACES = [
    "Microsoft.ApiManagement",
    "Microsoft.App",
    "Microsoft.Authorization",
    "Microsoft.Cache",
    "Microsoft.Communication",
    "Microsoft.Compute",
    "Microsoft.ContainerInstance",
    "Microsoft.ContainerRegistry",
    "Microsoft.ContainerService",
    "Microsoft.DBforMariaDB",
    "Microsoft.DBforMySQL",
    "Microsoft.DBforPostgreSQL",
    "Microsoft.EventGrid",
    "Microsoft.Insights",
    "Microsoft.KeyVault",
    "Microsoft.ManagedIdentity",
    "Microsoft.Network",
    "Microsoft.OperationalInsights",
    "Microsoft.Resources",
    "Microsoft.Sql",
    "Microsoft.Storage",
    "Microsoft.Web",
]

API_VERSION_RE = re.compile(r"(\d{4}-\d{2}-\d{2})(-[A-Za-z]+)?")
NAMESPACE_RE = re.compile(r"Microsoft\.[A-Za-z]+")
TYPE_RE = re.compile(r"[A-Za-z0-9-]+(/[A-Za-z0-9-]+)*")


class SourceError(Exception):
    pass


def version_key(version: str) -> tuple[str, int, str]:
    """Sorts newest first when used with reverse=True: by date, then stable above any suffixed release."""
    match = API_VERSION_RE.fullmatch(version)
    if not match:
        raise SourceError(f"unexpected api-version {version!r}")
    suffix = match.group(2) or ""
    return match.group(1), 0 if suffix else 1, suffix


def group(keys: list[str], namespaces: list[str]) -> dict:
    """`{namespace: {type: [apiVersions]}}` for the given namespaces, from `{ns}/{type}@{version}` keys."""
    wanted = {ns.lower(): ns for ns in namespaces}
    versions: dict[tuple[str, str], set[str]] = {}
    spellings: dict[tuple[str, str], dict[str, str]] = {}
    for key in keys:
        resource_type, _, version = key.rpartition("@")
        namespace, _, type_path = resource_type.partition("/")
        canonical_ns = wanted.get(namespace.lower())
        if canonical_ns is None or not type_path:
            continue
        slot = (canonical_ns, type_path.lower())
        versions.setdefault(slot, set()).add(version)
        spellings.setdefault(slot, {})[version] = type_path
    result: dict[str, dict[str, list[str]]] = {ns: {} for ns in namespaces}
    for slot, found in sorted(versions.items()):
        ordered = sorted(found, key=version_key, reverse=True)
        result[slot[0]][spellings[slot][ordered[0]]] = ordered
    missing = [ns for ns, types in result.items() if not types]
    if missing:
        raise SourceError(f"bicep-types-az knows no resource type for {missing}")
    return result


def git_commit(root: Path, rel: str) -> str:
    repo = root / rel.split("/")[0]
    try:
        result = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True,
                                check=False)
    except OSError:
        return "unknown"
    return result.stdout.strip() if result.returncode == 0 and result.stdout.strip() else "unknown"


def build(root: Path) -> dict:
    index = json.loads((root / INDEX).read_text(encoding="utf-8"))
    providers = group(list(index["resources"]), NAMESPACES)
    return {
        "_source": {
            "generator": "tools/arm/gen_providers.py",
            "root": "local/azure",
            "files": {INDEX: git_commit(root, INDEX)},
        },
        "providers": [
            {"namespace": namespace, "resourceTypes": [
                {"resourceType": type_path, "apiVersions": api_versions}
                for type_path, api_versions in sorted(types.items(), key=lambda item: item[0].lower())
            ]}
            for namespace, types in providers.items()
        ],
    }


def render(document: dict) -> str:
    """Indented JSON with each apiVersions list on one line, which keeps the file small and its diffs readable."""
    text = json.dumps(document, indent=2, ensure_ascii=False)
    return re.sub(r'\[\n\s+("\d{4}[^\]]*?)\n\s+\]',
                  lambda m: "[" + re.sub(r",\n\s+", ", ", m.group(1)) + "]", text) + "\n"


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
    providers = document.get("providers")
    if not isinstance(providers, list):
        return [f"{path}: 'providers' must be a list"]
    problems: list[str] = []
    if [p.get("namespace") for p in providers if isinstance(p, dict)] != NAMESPACES:
        problems.append(f"{path}: namespaces must be exactly {NAMESPACES}, in that order")
    for provider in providers:
        namespace = provider.get("namespace") if isinstance(provider, dict) else None
        if not (isinstance(namespace, str) and NAMESPACE_RE.fullmatch(namespace)):
            problems.append(f"malformed provider entry {provider!r}")
            continue
        types = provider.get("resourceTypes")
        if set(provider) != {"namespace", "resourceTypes"} or not isinstance(types, list) or not types:
            problems.append(f"{namespace}: expected keys namespace and a non-empty resourceTypes list")
            continue
        seen: set[str] = set()
        for entry in types:
            if not (isinstance(entry, dict) and set(entry) == {"resourceType", "apiVersions"}
                    and isinstance(entry["resourceType"], str) and TYPE_RE.fullmatch(entry["resourceType"])):
                problems.append(f"{namespace}: malformed resource type {entry!r}")
                continue
            lowered = entry["resourceType"].lower()
            if lowered in seen:
                problems.append(f"{namespace}/{entry['resourceType']}: listed twice")
            seen.add(lowered)
            versions = entry["apiVersions"]
            if not (isinstance(versions, list) and versions
                    and all(isinstance(v, str) and API_VERSION_RE.fullmatch(v) for v in versions)):
                problems.append(f"{namespace}/{entry['resourceType']}: malformed apiVersions {versions!r}")
            elif versions != sorted(versions, key=version_key, reverse=True):
                problems.append(f"{namespace}/{entry['resourceType']}: apiVersions must be newest first")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 when the vendored file differs from a fresh generation")
    parser.add_argument("--local-root", type=Path, default=LOCAL_AZURE,
                        help="directory holding the reference checkouts (default: local/azure)")
    parser.add_argument("--output", type=Path, default=OUTPUT, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    rel = args.output.relative_to(REPO_ROOT) if args.output.is_relative_to(REPO_ROOT) else args.output

    if not (args.local_root / INDEX).is_file():
        if args.check:
            problems = self_check(args.output)
            for problem in problems:
                print(f"error: {problem}")
            if problems:
                return 1
            print(f"{rel}: bicep-types-az not checked out under {args.local_root}, shape verified only")
            return 0
        raise SystemExit(f"error: regenerating needs {args.local_root / INDEX}")

    try:
        document = build(args.local_root)
    except SourceError as e:
        print(f"error: {e}")
        return 1
    rendered = render(document)
    summary = (f"{len(document['providers'])} namespaces, "
               f"{sum(len(p['resourceTypes']) for p in document['providers'])} resource types")

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
