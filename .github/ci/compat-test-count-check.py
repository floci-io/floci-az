#!/usr/bin/env python3
"""Fail a compatibility suite that executed no tests.

Usage: compat-test-count-check.py <results-dir> [--min N] [--newer-than FILE]

Reads every JUnit XML under <results-dir> and counts the test cases that actually ran, that is,
every <testcase> without a <skipped> child and not marked as not run. Exits 1 when no report
exists, or when fewer than --min (default 1) test cases ran.

A suite whose setup aborts, whose test classes all skip themselves, or whose runner discovers
nothing still exits 0, so the container's exit code alone reports it as a pass. Counting is per
suite, not per class: some classes skip by design unless an environment variable enables them,
and that is fine as long as the rest of the suite ran.

--newer-than ignores reports older than FILE, so a stale report left in a reused local results
directory cannot stand in for a run that wrote none.
"""
import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

NOT_RUN_STATUSES = {'notrun', 'skipped', 'disabled'}
NOT_RUN_RESULTS = {'skipped', 'suppressed'}


def was_executed(case):
    if case.find('skipped') is not None:
        return False
    if (case.get('status') or '').lower() in NOT_RUN_STATUSES:
        return False
    return (case.get('result') or '').lower() not in NOT_RUN_RESULTS


def count_report(path):
    """Returns (total, executed) for one JUnit file."""
    root = ET.parse(path).getroot()
    cases = list(root.iter('testcase'))
    if cases:
        return len(cases), sum(1 for c in cases if was_executed(c))
    # No <testcase> elements: fall back to the suite attributes.
    suites = [root] if root.tag == 'testsuite' else list(root.iter('testsuite'))
    total = executed = 0
    for suite in suites:
        tests = int(suite.get('tests') or 0)
        not_run = int(suite.get('skipped') or 0) + int(suite.get('disabled') or 0)
        total += tests
        executed += max(tests - not_run, 0)
    return total, executed


def fail(message):
    prefix = '::error::' if os.environ.get('GITHUB_ACTIONS') else 'error: '
    print(f'{prefix}{message}')
    sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('results_dir')
    parser.add_argument('--min', type=int, default=1, dest='minimum',
                        help='minimum number of executed test cases (default 1)')
    parser.add_argument('--newer-than', dest='newer_than',
                        help='ignore reports last modified before this file')
    args = parser.parse_args()

    cutoff = os.path.getmtime(args.newer_than) if args.newer_than else None
    reports = sorted(glob.glob(os.path.join(args.results_dir, '**', '*.xml'), recursive=True))
    if cutoff is not None:
        reports = [r for r in reports if os.path.getmtime(r) >= cutoff]
    if not reports:
        fail(f'no JUnit report in {args.results_dir}: the suite ran no tests or did not write its results')

    total = executed = 0
    for report in reports:
        try:
            report_total, report_executed = count_report(report)
        except (ET.ParseError, OSError, ValueError) as exc:
            print(f'warning: skipping unreadable JUnit report {report}: {exc}', file=sys.stderr)
            continue
        total += report_total
        executed += report_executed

    summary = f'{executed} of {total} test cases ran ({len(reports)} report(s) in {args.results_dir})'
    if executed == 0:
        fail(f'{summary}. A suite that skips every test is not a pass.')
    if executed < args.minimum:
        fail(f'{summary}; at least {args.minimum} must run.')
    print(summary)


if __name__ == '__main__':
    main()
