#!/usr/bin/env python3
"""Deterministic group-by-profile test partitioner for CI sharding.

Usage: partition.sh <all-classes.txt> <shard-total> <shard-index | docker>

<all-classes.txt> holds one test class per line in surefire includesFile path
format (io/floci/az/.../FooTest, no extension). Classes whose name ends in DockerTest start
real sidecar containers; they run in their own `docker` shard and never in a numbered one.
Classes sharing a @TestProfile are assigned to the SAME shard atomically, except known slow
classes: each profile a shard holds costs one Quarkus app build during test discovery
(~3.1s) plus one app start (~0.8s), so splitting a profile group pays both once per shard
it touches. Profile-less classes fill the remainder.
A class counts as one, but for the few in SLOW_CLASSES, whose run time would
otherwise be invisible to a partition that balances class counts. These are
separate groups even when they share a profile: saving one augmentation must
not force minutes of Docker work onto the same shard.

Deterministic: sorted inputs, greedy least-loaded bin-packing, no RNG/timestamps.
Exit 2 on bad arguments or an unreadable input file.
"""
import json
import os
import re
import sys

# One profile ~ app build at discovery (~3.1s) + app start (~0.8s) ~ 3 average test classes.
# Measured on CI job 137371342366 (2026-11-22): 357s between the surefire banner and the first
# test for 113 profiles, then 112 app starts at ~0.83s each.
AUGMENTATION_WEIGHT = 3

# Classes that take far longer than the suite's average, keyed by class name so package renames
# retain their weight, as {"ClassName": seconds}. Measured on CI job 137371342366 (2026-11-22).
# The tests pass their own through PARTITION_SLOW_CLASSES, a JSON object of the same shape.
DEFAULT_SLOW_CLASSES = {
    'QueueServiceTest': 19,
    'ArtemisReadinessProbeTest': 13,
    'BlobServiceTest': 10,
    'PasswordGrantIntegrationTest': 10,
}
SLOW_CLASSES = json.loads(os.environ['PARTITION_SLOW_CLASSES']) if 'PARTITION_SLOW_CLASSES' in os.environ \
    else DEFAULT_SLOW_CLASSES
SECONDS_PER_CLASS = 1.4  # the suite's mean: 646s over 458 classes on CI job 137371342366 (2026-11-22)
DOCKER_SHARD = 'docker'

def is_docker(cls):
    """A class that starts real sidecar containers, by the *DockerTest naming convention."""
    return cls.rsplit('/', 1)[-1].endswith('DockerTest')

def weight(cls):
    """How many average test classes a class is worth."""
    seconds = SLOW_CLASSES.get(cls.rsplit('/', 1)[-1])
    return 1 if seconds is None else round(seconds / SECONDS_PER_CLASS)

def warn_of_stale_slow_classes(classes):
    """A renamed class would quietly lose its weight, so name it where the run's annotations show it."""
    known = {c.rsplit('/', 1)[-1] for c in classes}
    for name in sorted(set(SLOW_CLASSES) - known):
        print(f'::warning title=partition.sh::stale SLOW_CLASSES key: no test class named {name}', file=sys.stderr)

def profile_groups(test_root):
    """Map class-path -> profile key from @TestProfile usages in src/test."""
    ann = re.compile(r'@TestProfile\(\s*([A-Za-z0-9_.]+?)(?:\.class)?\s*\)')
    mapping = {}
    for dirpath, _, files in sorted(os.walk(test_root)):
        for f in sorted(files):
            if not f.endswith('.java'):
                continue
            path = os.path.join(dirpath, f)
            try:
                src = open(path, encoding='utf-8', errors='replace').read()
            except OSError:
                continue
            m = ann.search(src)
            if not m:
                continue
            cls = os.path.relpath(path, test_root)[:-len('.java')]
            # Key on the referenced profile token; identical tokens merge groups,
            # which is always safe (merging groups never splits an augmentation).
            mapping[cls] = m.group(1)
    return mapping

def outer_of(token):
    """The simple name of the class a nested profile is declared in, or None for a top-level profile."""
    if '.' not in token:
        return None
    return token.rsplit('.', 1)[0].rsplit('.', 1)[-1]

def holder_of(token, cls):
    """The class a nested profile is declared in, when that is not the annotated test class itself."""
    outer = outer_of(token)
    return None if outer is None or outer == cls.rsplit('/', 1)[-1] else outer

def main():
    if len(sys.argv) != 4:
        sys.exit('usage: partition.sh <all-classes.txt> <shard-total> <shard-index | docker>')
    try:
        classes = [l.strip() for l in open(sys.argv[1]) if l.strip()]
        total = int(sys.argv[2])
        if sys.argv[3] == DOCKER_SHARD:
            if total < 1:
                raise ValueError(f'shard total {total} below 1')
            docker = sorted({c for c in classes if is_docker(c)})
            if not docker:
                sys.exit('partition.sh: docker shard would be empty')
            print('\n'.join(docker))
            return
        index = int(sys.argv[3])
    except (OSError, ValueError) as e:
        sys.exit(f'partition.sh: {e}')
    if not (1 <= index <= total):
        sys.exit(f'partition.sh: shard index {index} outside 1..{total}')

    warn_of_stale_slow_classes(classes)
    prof = profile_groups('src/test/java')
    groups = {}
    tokens = {}
    plain = []
    for c in sorted(c for c in set(classes) if not is_docker(c)):
        token = prof.get(c)
        if token is None:
            plain.append(c)
            continue
        holder = holder_of(token, c)
        if holder is not None:
            # Profiles declared together in a holder class that is not the test itself belong to tests
            # that may run in order in one JVM (phase 2 reading what phase 1 wrote), so the whole set
            # stays on one shard.
            key = ('holder:' + holder, '')
        elif weight(c) > 1:
            key = (token, c)
        elif outer_of(token) is not None:
            # A test that declares its own nested profile joins the classes that borrow it
            # (VmResourceStoreTest on VmHandlerTest.MockedProfile), or the profile is built twice.
            key = ('holder:' + outer_of(token), '')
        else:
            key = (token, '')
        groups.setdefault(key, []).append(c)
        tokens.setdefault(key, set()).add(token)

    load = [0] * total
    shard_of = {}
    # Heaviest first; ties broken by name for determinism.
    for key, members in sorted(groups.items(), key=lambda kv: (-sum(map(weight, kv[1])), kv[0])):
        target = min(range(total), key=lambda s: (load[s], s))
        load[target] += AUGMENTATION_WEIGHT * len(tokens[key]) + sum(map(weight, members))
        for c in members:
            shard_of[c] = target
    for c in sorted(plain, key=lambda c: (-weight(c), c)):
        target = min(range(total), key=lambda s: (load[s], s))
        load[target] += weight(c)
        shard_of[c] = target

    out = sorted(c for c, s in shard_of.items() if s == index - 1)
    if not out:
        sys.exit(f'partition.sh: shard {index}/{total} would be empty')
    print('\n'.join(out))

if __name__ == '__main__':
    main()
