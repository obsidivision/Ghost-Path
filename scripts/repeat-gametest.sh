#!/usr/bin/env bash
# Usage: scripts/repeat-gametest.sh <N> [<test selector, e.g. 'ghostpath-gametest:*stuck_arrow*'>]
# Runs the headless game tests N times (all tests when no selector), prints one line per run, keeps the log of each failed
# run in build/repeat/<tag>-<i>.log. Exit code 0 only if every run passed.
set -u
n=${1:?count}; sel=${2:-}
tag=$(echo "${sel:-all}" | tr -c 'A-Za-z0-9\n' '_')
mkdir -p build/repeat
pass=0; fail=0
for i in $(seq 1 "$n"); do
  if [ -n "$sel" ]; then ./gradlew runGameTest -PgtFilter="$sel" >build/repeat/cur.out 2>&1; else ./gradlew runGameTest >build/repeat/cur.out 2>&1; fi
  rc=$?
  if [ $rc -eq 0 ]; then pass=$((pass+1)); echo "run $i: pass"
  else fail=$((fail+1)); cp build/run/gameTest/logs/latest.log "build/repeat/$tag-$i.log"
       echo "run $i: FAIL: $(grep -E '^\[.*\]\s+- ' build/run/gameTest/logs/latest.log | sed 's/^.*- //' | cut -c1-200 | tr '\n' '|')"; fi
done
echo "$tag: $pass passed, $fail failed of $n"
[ $fail -eq 0 ]
