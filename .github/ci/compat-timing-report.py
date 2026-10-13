#!/usr/bin/env python3
"""Print per-class test timings from a suite's JUnit reports, slowest first.

Usage: compat-timing-report.py <results-dir>

The suites run their build tools quietly inside the container, so the job log carries no
per-class timings. This reads the reports the suites already write and also appends the table
to GITHUB_STEP_SUMMARY when that is set. Measurement only: unreadable or missing reports give
an empty table, never a failure.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

SLOWEST = 25


def suite_rows(path):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == 'testsuite' else list(root.iter('testsuite'))
    for suite in suites:
        yield (float(suite.get('time') or 0), int(suite.get('tests') or 0),
               suite.get('name') or os.path.basename(path))


def main():
    results_dir = sys.argv[1] if len(sys.argv) > 1 else 'test-results'
    rows = []
    for path in sorted(glob.glob(os.path.join(results_dir, '**', '*.xml'), recursive=True)):
        try:
            rows.extend(suite_rows(path))
        except (ET.ParseError, OSError, ValueError) as exc:
            print(f'warning: skipping unreadable JUnit report {path}: {exc}', file=sys.stderr)
    rows.sort(reverse=True)
    total_time = sum(r[0] for r in rows)
    total_tests = sum(r[1] for r in rows)
    lines = [f'{len(rows)} test classes, {total_tests} tests, {total_time / 60:.1f} min total test time',
             f'slowest {min(SLOWEST, len(rows))}:']
    lines += [f'{t:8.1f}s {n:5d} tests  {name}' for t, n, name in rows[:SLOWEST]]
    report = '\n'.join(lines)
    print(report)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as fh:
            fh.write(f'### Test timings\n\n```\n{report}\n```\n')


if __name__ == '__main__':
    main()
