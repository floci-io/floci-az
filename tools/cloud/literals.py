#!/usr/bin/env python3
"""Keep hardcoded public-cloud assumptions in src/main from growing back.

floci-az is moving from an emulator of the public Azure cloud to one that serves every cloud in
`src/main/resources/azure/clouds.json` (`AzureCloud`, `AzureUSGovernment`, `AzureChinaCloud`),
with each request carrying its own tenant, subscription and location. The blockers are code paths
that bake one cloud or one scope into the source:

- `cloud-literal`: a string literal naming a host or DNS suffix of one cloud (`.vault.azure.net`,
  `blob.core.windows.net`, `login.microsoftonline.com`). The patterns are derived from
  `clouds.json` (every service suffix, endpoint host and audience host of every cloud, plus the
  parent zones they share), so this check and the catalog cannot drift.
- `location-literal`: the literal `"eastus"`, the hand-picked default location.
- `scope-parse`: a service re-parsing the ARM path for its subscription or resource group
  (`ArmPaths.subscription(`, `ArmPaths.resourceGroup(`, `ArmScope.of(`, or
  `ArmPaths.segmentAfter(..., "subscriptions"|"resourcegroups", ...)`) under `services/`, instead
  of reading the scope the request gate resolved.
- `name-guard`: a per-service copy of the global-name guard: a declaration (method, field or
  local) named `ownedElsewhere`, `foreignServer` or `nameClaims`.

This script inventories them in `src/main/java` and gates them against a checked-in per-file
baseline (`literals-baseline.tsv`):

- an occurrence in a file the baseline does not know fails (new files start clean);
- a file whose count grew fails;
- a file whose count dropped also fails, until the baseline is regenerated, so the "N remaining"
  number stays exact and the drop is visible in the PR diff.

Comments never count. An occurrence that is genuinely cloud-invariant is excused in one of two
ways, both of which must state a reason: an entry in `literals-allowlist.yaml` (a whole file, or a
regex matched against the source line), or a trailing `// cloud-literal: <reason>` comment on the
line. The audit always prints the escapes so they are reviewed, not forgotten.

Run from anywhere in the repo:
    python3 tools/cloud/literals.py                   # per-package audit
    python3 tools/cloud/literals.py --check           # CI gate against literals-baseline.tsv
    python3 tools/cloud/literals.py --write-baseline  # regenerate literals-baseline.tsv
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = REPO_ROOT / "src/main/java"
PACKAGE_PREFIX = "io/floci/az"
CLOUDS_JSON = REPO_ROOT / "src/main/resources/azure/clouds.json"
TOOL_DIR = Path(__file__).resolve().parent
ALLOWLIST = TOOL_DIR / "literals-allowlist.yaml"
BASELINE = TOOL_DIR / "literals-baseline.tsv"

ESCAPE_RE = re.compile(r"//\s*cloud-literal:\s*(?P<reason>\S.*?)\s*$")

# Parent zones of catalog hosts that also hold hosts which are not cloud endpoints, so a literal
# under them says nothing about the cloud: mcr.microsoft.com (container images) and the
# microsoft.com documentation and DNS contact hosts. The catalog hosts themselves
# (graph.microsoft.com) still count.
SHARED_PARENT_ZONES = frozenset({"microsoft.com"})


# --------------------------------------------------------------------------- #
# Domains from the catalog
# --------------------------------------------------------------------------- #
def _host(value: str) -> str | None:
    value = value.strip().lower()
    if not value:
        return None
    host = urlparse(value).hostname if "://" in value else value.split("/")[0]
    return host.strip(".") if host else None


def catalog_hosts(clouds: dict) -> set[str]:
    """Every service suffix, endpoint host and audience host of every cloud in clouds.json."""
    hosts: set[str] = set()
    for cloud in clouds["clouds"]:
        for entry in cloud["suffixes"].values():
            if entry.get("value"):
                hosts.add(entry["value"].lower())
        for value in cloud["endpoints"].values():
            for item in value if isinstance(value, list) else [value]:
                host = _host(item)
                if host:
                    hosts.add(host)
        for audience in cloud.get("audiences", []):
            host = _host(audience)
            if host:
                hosts.add(host)
    return hosts


def cloud_domains(clouds: dict) -> list[str]:
    """Catalog hosts plus each parent zone of two or more labels, longest first.

    The parents catch a literal that spells only part of a host (`core.windows.net`,
    `microsoftonline.com`) or another host in a zone only one cloud uses (`documents.azure.com`).
    """
    domains: set[str] = set()
    for host in catalog_hosts(clouds):
        labels = host.split(".")
        domains.add(host)
        for start in range(1, len(labels) - 1):
            parent = ".".join(labels[start:])
            if parent not in SHARED_PARENT_ZONES:
                domains.add(parent)
    return sorted(domains, key=lambda d: (-len(d), d))


def cloud_literal_regex(domains: list[str]) -> re.Pattern:
    # A dot may be escaped (vault\\.azure\\.net in a Java regex), so a hand-written host pattern is
    # held to the same rule as a host. Longest first, so one occurrence counts once.
    alternatives = "|".join(r"\\*\.".join(re.escape(label) for label in domain.split(".")) for domain in domains)
    return re.compile(r"(?<![A-Za-z0-9-])(?:" + alternatives + r")(?![A-Za-z0-9-])", re.IGNORECASE)


def load_clouds(path: Path = CLOUDS_JSON) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


# --------------------------------------------------------------------------- #
# Categories
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class Category:
    name: str
    regex: re.Pattern
    description: str
    # "literal" runs the regex over each string literal's content; "code" over the whole source with
    # comments and text blocks blanked, for a shape that is a call or a declaration, even one split
    # across lines.
    scope: str = "literal"
    # Only files under this directory (relative to the source root) are scanned; None means all.
    path_prefix: str | None = None


SERVICES_PREFIX = PACKAGE_PREFIX + "/services/"

LOCATION_LITERAL = re.compile(r'(?:^|")eastus(?:\\?"|$)')

SCOPE_PARSE = re.compile(
    r"\bArmPaths\s*\.\s*(?:subscription|resourceGroup)\s*\("
    r"|\bArmScope\s*\.\s*of\s*\("
    # The path argument may itself be a call (request.resourcePath()), two levels of parentheses deep.
    r"|\bsegmentAfter\s*\(\s*(?:[^,;()]|\((?:[^()]|\([^()]*\))*\))+,"
    r'\s*"(?i:subscriptions|resourcegroups)"'
)

GUARD_NAMES = r"(?:ownedElsewhere|foreignServer|nameClaims)"
NAME_GUARD = re.compile(
    # A method declaration: the parameter list (one level of nested parentheses for annotations) is
    # followed by a body, which a call site never is.
    r"\b" + GUARD_NAMES + r"\s*\((?:[^()]|\([^()]*\))*\)\s*(?:throws\s[^{;]*)?\{"
    # A field or local declaration (or assignment): the APIM inline guard, the nameClaims monitors.
    + r"|(?<![.\w])" + GUARD_NAMES + r"\s*=(?!=)"
)


def build_categories(clouds: dict) -> tuple[Category, ...]:
    return (
        Category(
            "cloud-literal",
            cloud_literal_regex(cloud_domains(clouds)),
            "a host or DNS suffix of one cloud; resolve it through CloudCatalog/CloudSuffix for the "
            "request's cloud",
        ),
        Category(
            "location-literal",
            LOCATION_LITERAL,
            'the default location "eastus"; take the location from the request, the resource or the '
            "cloud's default location",
        ),
        Category(
            "scope-parse",
            SCOPE_PARSE,
            "a service parsing the subscription or resource group out of the ARM path; read the scope "
            "the request gate resolved",
            "code",
            SERVICES_PREFIX,
        ),
        Category(
            "name-guard",
            NAME_GUARD,
            "a per-service copy of the global-name guard; reserve names through the shared global-name "
            "registry",
            "code",
        ),
    )


# --------------------------------------------------------------------------- #
# Lexer
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class Literal:
    line: int
    text: str


def scan_java(text: str) -> tuple[list[Literal], dict[int, str]]:
    """Extracts string literals (one per line for text blocks) and per-line escape comments.

    A tiny lexer rather than a regex: `//` inside a string is a URL, not a comment, and a `"` inside
    a comment or char literal is not a string. Block comments and text blocks span lines, so line
    numbers are tracked by hand.
    """
    literals: list[Literal] = []
    escapes: dict[int, str] = {}
    i = 0
    n = len(text)
    line = 1
    while i < n:
        char = text[i]
        if char == "\n":
            line += 1
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            match = ESCAPE_RE.match(text[i:end])
            if match:
                escapes[line] = match.group("reason")
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            line += text.count("\n", i, end)
            i = end
        elif text.startswith('"""', i):
            j = i + 3
            while j < n and not text.startswith('"""', j):
                j += 2 if text[j] == "\\" else 1
            block = text[i + 3:j]
            for offset, part in enumerate(block.split("\n")):
                if part.strip():
                    literals.append(Literal(line + offset, part))
            line += block.count("\n")
            i = min(j + 3, n)
        elif char == '"':
            j = i + 1
            buffer: list[str] = []
            while j < n and text[j] not in '"\n':
                if text[j] == "\\":
                    buffer.append(text[j:j + 2])
                    j += 2
                else:
                    buffer.append(text[j])
                    j += 1
            literals.append(Literal(line, "".join(buffer)))
            i = j + 1 if j < n and text[j] == '"' else j
        elif char == "'":
            j = i + 1
            while j < n and text[j] not in "'\n":
                j += 2 if text[j] == "\\" else 1
            i = j + 1 if j < n and text[j] == "'" else j
        else:
            i += 1
    return literals, escapes


def code_text(text: str) -> str:
    """The source with every comment and text block blanked, string literals kept as written.

    Newlines survive, so an offset maps back to its line. Uses the same lexing rules as scan_java.
    """
    return code_text_and_strings(text)[0]


def code_text_and_strings(text: str) -> tuple[str, list[tuple[int, int]]]:
    """code_text plus the [start, end) offsets of every string and char literal it kept.

    A code pattern that starts inside one of those spans is text, not code: a message that
    mentions ArmScope.of( parses no scope. A match that starts outside may still run through a
    string, which is how segmentAfter(path, "subscriptions") is recognised.
    """
    out: list[str] = []
    strings: list[tuple[int, int]] = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
        elif text.startswith("/*", i) or text.startswith('"""', i):
            closer = "*/" if text[i + 1] == "*" else '"""'
            j = i + len(closer)
            while j < n and not text.startswith(closer, j):
                j += 2 if closer == '"""' and text[j] == "\\" else 1
            end = min(j + len(closer), n)
            out.append("".join(c if c == "\n" else " " for c in text[i:end]))
            i = end
        elif text[i] in "\"'":
            quote = text[i]
            j = i + 1
            while j < n and text[j] not in quote + "\n":
                j += 2 if text[j] == "\\" else 1
            end = j + 1 if j < n and text[j] == quote else j
            out.append(text[i:end])
            strings.append((i, end))
            i = end
        else:
            out.append(text[i])
            i += 1
    return "".join(out), strings


def inside_any(offset: int, spans: list[tuple[int, int]]) -> bool:
    return any(start <= offset < end for start, end in spans)


# --------------------------------------------------------------------------- #
# Allow-list
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class AllowRule:
    kind: str
    value: str
    reason: str


def _strip_inline_comment(raw: str) -> str:
    """Drops a YAML comment: a '#' at the start or after whitespace, outside quotes."""
    quote: str | None = None
    for index, char in enumerate(raw):
        if quote is not None:
            if char == quote:
                quote = None
        elif char in "'\"":
            quote = char
        elif char == "#" and (index == 0 or raw[index - 1] in " \t"):
            return raw[:index]
    return raw


def _scalar(raw: str) -> str:
    raw = _strip_inline_comment(raw).strip()
    if len(raw) >= 2 and raw[0] == raw[-1] == "'":
        return raw[1:-1].replace("''", "'")
    if len(raw) >= 2 and raw[0] == raw[-1] == '"':
        return json.loads(raw)
    return raw


def parse_allowlist_yaml(text: str) -> dict:
    """Reads the allow-list's own shape without PyYAML, so `make cloud-literals-check` needs no deps.

    Supports exactly what literals-allowlist.yaml uses: the top-level keys `files` and `patterns`,
    each a list (or `[]`) of mappings whose scalars are plain, single- or double-quoted, and plain
    scalars continued on more-indented lines, and `#` comments on their own line or after a value.
    PyYAML is used instead whenever it is installed, so both must read a file the same way.
    """
    data: dict = {}
    current_list: list | None = None
    current_item: dict | None = None
    last_key: str | None = None
    for raw in text.splitlines():
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if indent == 0:
            key, _, rest = stripped.partition(":")
            rest = rest.strip()
            current_list = [] if rest in ("", "[]") else None
            if current_list is None:
                raise ValueError(f"allowlist: unsupported top-level value {raw!r}")
            data[key.strip()] = current_list
            current_item = None
            last_key = None
            continue
        if current_list is None:
            raise ValueError(f"allowlist: entry outside a list {raw!r}")
        if stripped.startswith("- "):
            current_item = {}
            current_list.append(current_item)
            stripped = stripped[2:].strip()
        match = re.match(r"^([A-Za-z_]+):(?:\s+(.*))?$", stripped)
        if match and current_item is not None:
            last_key = match.group(1)
            current_item[last_key] = _scalar(match.group(2) or "")
        elif current_item is not None and last_key is not None:
            continued = _strip_inline_comment(stripped).strip()
            current_item[last_key] = (current_item[last_key] + " " + continued).strip()
        else:
            raise ValueError(f"allowlist: cannot parse {raw!r}")
    return data


def load_allowlist(path: Path) -> list[AllowRule]:
    if not path.exists():
        return []
    text = path.read_text(encoding="utf-8")
    try:
        import yaml
        data = yaml.safe_load(text) or {}
    except ImportError:
        data = parse_allowlist_yaml(text)
    rules: list[AllowRule] = []
    for entry in data.get("files") or []:
        rules.append(AllowRule("file", str(entry["path"]), str(entry.get("reason") or "")))
    for entry in data.get("patterns") or []:
        rules.append(AllowRule("pattern", str(entry["regex"]), str(entry.get("reason") or "")))
    for rule in rules:
        if not rule.reason.strip():
            raise ValueError(f"allowlist entry {rule.value!r} has no reason")
    return rules


def excuse_for(rel_path: str, source_line: str, rules: list[AllowRule]) -> str | None:
    for rule in rules:
        if rule.kind == "file" and rule.value == rel_path:
            return f"allowlist file: {rule.reason}"
        if rule.kind == "pattern" and re.search(rule.value, source_line):
            return f"allowlist pattern {rule.value!r}: {rule.reason}"
    return None


# --------------------------------------------------------------------------- #
# Findings
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class Finding:
    category: str
    path: str
    line: int
    source: str
    excuse: str | None = None

    @property
    def counted(self) -> bool:
        return self.excuse is None


def collect_findings(source_root: Path, rules: list[AllowRule],
                     categories: tuple[Category, ...]) -> list[Finding]:
    findings: list[Finding] = []
    for java in sorted(source_root.rglob("*.java")):
        package_path = java.relative_to(source_root).as_posix()
        rel_path = java.relative_to(REPO_ROOT).as_posix() if java.is_relative_to(REPO_ROOT) else package_path
        applicable = [c for c in categories if c.path_prefix is None or package_path.startswith(c.path_prefix)]
        if not applicable:
            continue
        text = java.read_text(encoding="utf-8")
        lines = text.split("\n")
        literals, escapes = scan_java(text)
        hits: list[tuple[str, int]] = []
        for literal in literals:
            for category in applicable:
                if category.scope == "literal":
                    hits += [(category.name, literal.line)] * len(category.regex.findall(literal.text))
        if any(category.scope == "code" for category in applicable):
            code, strings = code_text_and_strings(text)
            for category in applicable:
                if category.scope == "code":
                    hits += [(category.name, code.count("\n", 0, match.start()) + 1)
                             for match in category.regex.finditer(code)
                             if not inside_any(match.start(), strings)]
        for name, line_number in hits:
            source_line = lines[line_number - 1] if line_number - 1 < len(lines) else ""
            excuse = excuse_for(rel_path, source_line, rules)
            if excuse is None and line_number in escapes:
                excuse = f"escape: {escapes[line_number]}"
            findings.append(Finding(name, rel_path, line_number, source_line.strip(), excuse))
    return findings


def count(findings: list[Finding]) -> Counter:
    counter: Counter = Counter()
    for finding in findings:
        if finding.counted:
            counter[(finding.category, finding.path)] += 1
    return counter


# --------------------------------------------------------------------------- #
# Baseline and the gate
# --------------------------------------------------------------------------- #
def read_baseline(path: Path, category_names: set[str]) -> Counter:
    counter: Counter = Counter()
    if not path.exists():
        return counter
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) != 3:
            raise ValueError(f"{path}: malformed baseline row {raw!r}")
        rel_path, category, value = parts
        if category not in category_names:
            raise ValueError(f"{path}: unknown category {category!r}")
        counter[(category, rel_path)] = int(value)
    return counter


def format_baseline(counter: Counter) -> str:
    rows = ["# path\tcategory\tcount",
            "# Generated by tools/cloud/literals.py --write-baseline; do not hand-edit."]
    for (category, rel_path), value in sorted(counter.items(), key=lambda item: (item[0][1], item[0][0])):
        rows.append(f"{rel_path}\t{category}\t{value}")
    return "\n".join(rows) + "\n"


def write_baseline(path: Path, findings: list[Finding]) -> Counter:
    counter = count(findings)
    path.write_text(format_baseline(counter), encoding="utf-8")
    return counter


def check(findings: list[Finding], baseline: Counter, categories: tuple[Category, ...]) -> list[str]:
    """Returns one problem per (category, file) that drifted from the baseline; empty when green."""
    by_name = {category.name: category for category in categories}
    current = count(findings)
    by_key: dict[tuple[str, str], list[Finding]] = defaultdict(list)
    for finding in findings:
        if finding.counted:
            by_key[(finding.category, finding.path)].append(finding)
    problems: list[str] = []
    for key in sorted(set(current) | set(baseline), key=lambda k: (k[1], k[0])):
        category, rel_path = key
        now = current.get(key, 0)
        before = baseline.get(key, 0)
        if now == before:
            continue
        if key not in baseline:
            head = f"{rel_path}: {now} {category} occurrence(s) with no baseline entry for this file"
        elif now > before:
            head = f"{rel_path}: {category} grew from {before} to {now}"
        else:
            head = (f"{rel_path}: {category} dropped from {before} to {now}; "
                    "run 'make cloud-literals-baseline' and commit literals-baseline.tsv")
        lines = [head]
        if now > before:
            lines.extend(f"    {f.path}:{f.line}: {f.source}" for f in by_key[key])
            lines.append(f"    {by_name[category].description}")
        problems.append("\n".join(lines))
    return problems


# --------------------------------------------------------------------------- #
# Audit
# --------------------------------------------------------------------------- #
def package_of(rel_path: str) -> str:
    marker = PACKAGE_PREFIX + "/"
    index = rel_path.find(marker)
    if index < 0:
        return Path(rel_path).parent.as_posix()
    parts = Path(rel_path[index + len(marker):]).parts[:-1]
    return "/".join(parts[:2]) if parts else "(root)"


def format_audit(findings: list[Finding], baseline: Counter, categories: tuple[Category, ...]) -> str:
    names = [category.name for category in categories]
    per_package: dict[str, Counter] = defaultdict(Counter)
    totals: Counter = Counter()
    for finding in findings:
        if finding.counted:
            per_package[package_of(finding.path)][finding.category] += 1
            totals[finding.category] += 1
    width = max([len("package"), len("total")] + [len(p) for p in per_package]) + 2
    column = max(len(name) for name in names) + 2
    header = f"{'package':<{width}}" + "".join(f"{name:>{column}}" for name in names) + f"{'total':>8}"
    out = [header, "-" * len(header)]
    for package, counter in sorted(per_package.items(), key=lambda item: (-sum(item[1].values()), item[0])):
        out.append(
            f"{package:<{width}}"
            + "".join(f"{counter.get(name, 0):>{column}}" for name in names)
            + f"{sum(counter.values()):>8}"
        )
    out.append("-" * len(header))
    out.append(
        f"{'total':<{width}}"
        + "".join(f"{totals.get(name, 0):>{column}}" for name in names)
        + f"{sum(totals.values()):>8}"
    )
    if baseline:
        baseline_totals: Counter = Counter()
        for (category, _), value in baseline.items():
            baseline_totals[category] += value
        out.append("")
        out.append("literals-baseline.tsv: " + ", ".join(
            f"{name} {baseline_totals.get(name, 0)} -> {totals.get(name, 0)}" for name in names
        ))
    excused = [finding for finding in findings if not finding.counted]
    escapes = sorted({(f.path, f.line, f.excuse) for f in excused if f.excuse and f.excuse.startswith("escape:")})
    allowed: Counter = Counter(f.excuse for f in excused if f.excuse and f.excuse.startswith("allowlist"))
    out.append("")
    out.append(f"escapes (// cloud-literal: <reason>): {len(escapes)}")
    out.extend(f"  {path}:{line}  {excuse[len('escape: '):]}" for path, line, excuse in escapes)
    out.append(f"allow-listed occurrences: {sum(allowed.values())}")
    out.extend(f"  {value:>4}  {excuse}" for excuse, value in sorted(allowed.items()))
    return "\n".join(out)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def _display(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix() if path.is_relative_to(REPO_ROOT) else str(path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="exit 1 when a count drifted from the baseline")
    mode.add_argument("--write-baseline", action="store_true", help="regenerate the baseline from the current tree")
    mode.add_argument("--audit", action="store_true", help="print the per-package table (the default)")
    parser.add_argument("--source", type=Path, default=SOURCE_ROOT, help=argparse.SUPPRESS)
    parser.add_argument("--allowlist", type=Path, default=ALLOWLIST, help=argparse.SUPPRESS)
    parser.add_argument("--baseline", type=Path, default=BASELINE, help=argparse.SUPPRESS)
    parser.add_argument("--clouds", type=Path, default=CLOUDS_JSON, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    categories = build_categories(load_clouds(args.clouds))
    rules = load_allowlist(args.allowlist)
    findings = collect_findings(args.source, rules, categories)

    if args.write_baseline:
        counter = write_baseline(args.baseline, findings)
        print(f"wrote {_display(args.baseline)}: {sum(counter.values())} occurrence(s) "
              f"across {len({p for _, p in counter})} file(s)")
        return 0

    baseline = read_baseline(args.baseline, {category.name for category in categories})
    if args.check:
        problems = check(findings, baseline, categories)
        for problem in problems:
            print(problem)
        if problems:
            print("")
            print(f"error: {len(problems)} file(s) drifted from tools/cloud/literals-baseline.tsv. "
                  "Resolve hosts through CloudCatalog, take locations and scopes from the request, and "
                  "reserve global names through the shared registry; when an occurrence is genuinely "
                  "cloud-invariant, add it to tools/cloud/literals-allowlist.yaml or end the line with "
                  "'// cloud-literal: <reason>'.")
            return 1
        print(f"cloud literals match literals-baseline.tsv ({sum(count(findings).values())} occurrence(s) remaining)")
        return 0

    print(format_audit(findings, baseline, categories))
    return 0


if __name__ == "__main__":
    sys.exit(main())
