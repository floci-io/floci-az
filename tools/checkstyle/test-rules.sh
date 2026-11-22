#!/usr/bin/env bash
# Checks that the rules in checkstyle.xml still do what they say. A clean run over src/main only proves the code
# passes; a rule broken so that it matches nothing would pass too. So:
#   - fixtures/accepted must raise no violation (every exemption the rules make), and
#   - each file in fixtures/rejected must raise the rule its first line names ("// expect: <rule id>").
# The fixtures are never compiled. Run from anywhere: tools/checkstyle/test-rules.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
fixtures="$root/tools/checkstyle/fixtures"
cd "$root"

check() {
    ./mvnw -B -ntp checkstyle:check "-Dcheckstyle.sourceDirectory=$fixtures/$1" "${@:2}" 2>&1
}

failed=0

if output="$(check accepted)"; then
    echo "ok: fixtures/accepted raises no violation"
else
    echo "FAIL: fixtures/accepted raised a violation:"
    grep -E '^\[ERROR\] .*\.java' <<<"$output" || true
    failed=1
fi

for fixture in "$fixtures"/rejected/*.java; do
    name="$(basename "$fixture")"
    expect="$(sed -n '1s#^// expect: *##p' "$fixture")"
    if [ -z "$expect" ]; then
        echo "FAIL: $name has no '// expect: <rule id>' first line"
        failed=1
        continue
    fi
    if output="$(check rejected "-Dcheckstyle.includes=$name")"; then
        echo "FAIL: $name passed, expected $expect"
        failed=1
    elif grep -q "\[$expect\]" <<<"$output"; then
        echo "ok: $name rejected by $expect"
    else
        echo "FAIL: $name failed, but not with $expect:"
        grep -E '^\[ERROR\] .*\.java' <<<"$output" || true
        failed=1
    fi
done

exit "$failed"
