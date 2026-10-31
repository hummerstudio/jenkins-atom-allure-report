#!/usr/bin/env bash
# Counts, the failing test being named, the page itself, and the gate.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

ws="$(mktemp -d)"; mkdir -p "$ws/allure-results"
write_result() { # write_result <name> <status> <start> <stop> [feature] [message]
  python3 - "$ws/allure-results/$1-result.json" "$@" <<'PY'
import json, sys
path, name, status, start, stop = sys.argv[1:6]
feature = sys.argv[6] if len(sys.argv) > 6 else ""
message = sys.argv[7] if len(sys.argv) > 7 else ""
doc = {"uuid": name, "name": name.replace("-", " "), "status": status,
       "start": int(start), "stop": int(stop), "labels": [], "statusDetails": {}}
if feature:
    doc["labels"].append({"name": "feature", "value": feature})
if message:
    doc["statusDetails"] = {"message": message}
json.dump(doc, open(path, "w"))
PY
}

write_result login passed 1000 1600 "auth"
write_result logout passed 1700 1800 "auth"
write_result checkout failed 2000 5000 "pay" "expected 200, got 500"
write_result flaky broken 5100 5200 "pay" "socket closed"
write_result skipped-test skipped 0 0 "pay"

printf '{}' >"$ws/input.json"
run "$ws" >"$ws/atom.log" 2>&1 || fail "run failed: $(cat "$ws/output.json")"
[ "$(output "$ws" total)" = "5" ] || fail "total: $(cat "$ws/output.json")"
[ "$(output "$ws" passed)" = "2" ] || fail "passed: $(cat "$ws/output.json")"
[ "$(output "$ws" failed)" = "1" ] || fail "failed: $(cat "$ws/output.json")"
[ "$(output "$ws" broken)" = "1" ] || fail "broken: $(cat "$ws/output.json")"
[ "$(output "$ws" skipped)" = "1" ] || fail "skipped: $(cat "$ws/output.json")"
[ -f "$ws/out/allure-report/index.html" ] || fail "no report page"
grep -q "expected 200, got 500" "$ws/out/allure-report/index.html" || fail "the failure message is not in the report"
grep -q "checkout" "$ws/out/allure-report/index.html" || fail "the failing test is not named"
grep -q 'class="card"' "$ws/out/allure-report/index.html" || fail "the report has no cards"
[ "$(output "$ws" report)" = "out/allure-report/index.html" ] || fail "report output: $(cat "$ws/output.json")"

# the gate
printf '{"failOnFailure": "true"}' >"$ws/input.json"
run "$ws" >"$ws/atom.log" 2>&1 && fail "failOnFailure must fail when a test did not pass"
grep -q "2 test(s) did not pass" "$ws/output.json" || fail "gate message: $(cat "$ws/output.json")"

# all green passes the gate
ws2="$(mktemp -d)"; mkdir -p "$ws2/allure-results"
write_result() { :; }
python3 - "$ws2" <<'PY'
import json, os, sys
root = sys.argv[1] + "/allure-results"
for name in ("a", "b"):
    json.dump({"name": name, "status": "passed", "start": 0, "stop": 10}, open(os.path.join(root, name + "-result.json"), "w"))
PY
printf '{"failOnFailure": "true"}' >"$ws2/input.json"
run "$ws2" >"$ws2/atom.log" 2>&1 || fail "an all-green run must pass the gate: $(cat "$ws2/output.json")"

# no results directory
ws3="$(mktemp -d)"; printf '{}' >"$ws3/input.json"
run "$ws3" >"$ws3/atom.log" 2>&1 && fail "a missing results directory must be refused"
grep -q "no allure results at" "$ws3/output.json" || fail "message: $(cat "$ws3/output.json")"

echo "smoke ok: counts, named failures, report page, gate, all-green, missing results"
