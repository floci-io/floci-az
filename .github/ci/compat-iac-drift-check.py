#!/usr/bin/env python3
"""Fail when the Terraform and OpenTofu compat suites stop exercising the same resources.

Usage: compat-iac-drift-check.py

compat-terraform and compat-opentofu run the same azurerm configuration through two tools, so
their main.tf files are meant to declare the same resources. The files are not compared line by
line: they legitimately differ in values (the secret text names the tool) and in outputs. What
is compared is the set of top-level `resource "TYPE" "NAME"` addresses.

An address declared in one file only must be listed in that direction's allowlist:
  .github/ci/compat-iac-allowlist-terraform-only.txt
  .github/ci/compat-iac-allowlist-opentofu-only.txt
(one "type.name" per line; `#` starts a comment). The allowlists are directional, so a resource
that moves from one file to the other is reported rather than matched by a stale entry. An
allowlisted address that is no longer one-sided also fails, so a closed gap gets its entry
removed instead of lingering.
"""
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CI_DIR = REPO_ROOT / '.github/ci'
TERRAFORM_MAIN = REPO_ROOT / 'compatibility-tests/compat-terraform/main.tf'
OPENTOFU_MAIN = REPO_ROOT / 'compatibility-tests/compat-opentofu/main.tf'
TERRAFORM_ONLY = CI_DIR / 'compat-iac-allowlist-terraform-only.txt'
OPENTOFU_ONLY = CI_DIR / 'compat-iac-allowlist-opentofu-only.txt'

RESOURCE_RE = re.compile(r'^resource\s+"([A-Za-z0-9_]+)"\s+"([A-Za-z0-9_-]+)"', re.MULTILINE)


def resource_addresses(path):
    return {f'{kind}.{name}' for kind, name in RESOURCE_RE.findall(path.read_text())}


def load_allowlist(path):
    if not path.exists():
        return set()
    entries = (line.split('#', 1)[0].strip() for line in path.read_text().splitlines())
    return {entry for entry in entries if entry}


def check_direction(label, one_sided, allowlist_path):
    """Prints unexpected and stale entries for one direction; returns True when clean."""
    allowed = load_allowlist(allowlist_path)
    unexpected = sorted(one_sided - allowed)
    stale = sorted(allowed - one_sided)
    allowlist_name = allowlist_path.relative_to(REPO_ROOT)
    if unexpected:
        print(f'::error::resources only in {label}, not listed in {allowlist_name}:')
        for address in unexpected:
            print(f'  {address}')
    if stale:
        print(f'::error::stale entries in {allowlist_name}, no longer only in {label}; remove them:')
        for address in stale:
            print(f'  {address}')
    return not unexpected and not stale


def main():
    terraform = resource_addresses(TERRAFORM_MAIN)
    opentofu = resource_addresses(OPENTOFU_MAIN)
    terraform_ok = check_direction('compat-terraform/main.tf', terraform - opentofu, TERRAFORM_ONLY)
    opentofu_ok = check_direction('compat-opentofu/main.tf', opentofu - terraform, OPENTOFU_ONLY)
    if not (terraform_ok and opentofu_ok):
        print('\nAdd the resource to the other suite as well, or list it in the matching allowlist '
              'if the gap is intentional.')
        sys.exit(1)
    print(f'compat-terraform and compat-opentofu resources match the allowlists: {len(terraform & opentofu)} shared, '
          f'{len(terraform - opentofu)} terraform-only and {len(opentofu - terraform)} opentofu-only allowlisted.')


if __name__ == '__main__':
    main()
