#!/usr/bin/env python3
"""Summarise the size of the native executable a Quarkus native build just produced.

Usage: native-size-report.py <arch-label> [target-dir]

Quarkus writes GraalVM's build-output statistics next to the native source jar on every native
build. This prints the image size breakdown and the gzip size of the binary (close to the
compressed layer Docker Hub reports) and appends the same table to GITHUB_STEP_SUMMARY when that
is set, so every build leaves a size record.
"""
import glob
import json
import os
import sys
import zlib


def mib(size):
    return f'{size / 2**20:.1f} MiB'


def gzip_size(path):
    """Streams the file through one deflate stream, as `gzip -c | wc -c` would."""
    compressor = zlib.compressobj(9, zlib.DEFLATED, 31)
    compressed = 0
    with open(path, 'rb') as src:
        while chunk := src.read(1 << 24):
            compressed += len(compressor.compress(chunk))
    return compressed + len(compressor.flush())


def main():
    arch = sys.argv[1] if len(sys.argv) > 1 else 'unknown'
    target = sys.argv[2] if len(sys.argv) > 2 else 'target'
    stats_files = glob.glob(os.path.join(target, '*-native-image-source-jar', '*-build-output-stats.json'))
    runners = glob.glob(os.path.join(target, '*-runner'))
    if not stats_files or not runners:
        print(f'error: no native build output under {target}', file=sys.stderr)
        sys.exit(1)
    with open(stats_files[0]) as fh:
        stats = json.load(fh)
    details, analysis = stats['image_details'], stats['analysis_results']
    resources = details['image_heap']['resources']
    rows = [
        ('total image', mib(details['total_bytes'])),
        ('code area', mib(details['code_area']['bytes'])),
        ('image heap', mib(details['image_heap']['bytes'])),
        ('resources in heap', f"{mib(resources['bytes'])} ({resources['count']} files)"),
        ('binary gzip (Docker Hub layer estimate)', f'{gzip_size(runners[0]) / 1e6:.2f} MB'),
        ('reachable types / methods', f"{analysis['types']['reachable']} / {analysis['methods']['reachable']}"),
    ]
    print('\n'.join(f'{k}: {v}' for k, v in rows))
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as fh:
            fh.write(f'### Native build size ({arch}, quick build)\n\n| metric | value |\n|---|---|\n')
            fh.writelines(f'| {k} | {v} |\n' for k, v in rows)


if __name__ == '__main__':
    main()
