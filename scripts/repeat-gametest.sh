#!/usr/bin/env bash
# Usage: scripts/repeat-gametest.sh <N> [<test selector, e.g. 'ghostpath-gametest:*stuck_arrow*'>]
# Runs the headless game tests N times (all tests when no selector), prints one line per run, keeps the log of each failed
# run in build/repeat/<tag>-<i>.log. Exit code 0 only if every run passed. A run that takes longer than 15 minutes is a hang
# (a test whose structure chunks never tick does not count ticks, so it never times out) and counts as a failure.
# The vanilla progress line "[+++ ]" is printed on every tick of a hung run; it is filtered out so the log stays small.
set -u
n=${1:?count}; sel=${2:-}
tag=$(echo "${sel:-all}" | tr -c 'A-Za-z0-9\n' '_')
mkdir -p build/repeat
pass=0; fail=0
args=(runGameTest)
[ -n "$sel" ] && args+=("-PgtFilter=$sel")
for i in $(seq 1 "$n"); do
  timeout 900 ./gradlew "${args[@]}" 2>&1 | grep -av '(Minecraft) \[[+_ x]*\]$' | head -c 30000000 >build/repeat/cur.out
  rc=${PIPESTATUS[0]}
  if [ $rc -eq 0 ]; then pass=$((pass+1)); echo "run $i: pass"
  else fail=$((fail+1)); grep -av '(Minecraft) \[[+_ x]*\]$' build/run/gameTest/logs/latest.log | head -c 30000000 >"build/repeat/$tag-$i.log"
       why=$([ $rc -eq 124 ] && echo "HANG (15 min)" || echo "rc $rc")
       echo "run $i: FAIL ($why): $(grep -aE '^\[.*\]\s+- ' "build/repeat/$tag-$i.log" | sed 's/^.*- //' | cut -c1-200 | tr '\n' '|')"; fi
done
echo "$tag: $pass passed, $fail failed of $n"
[ $fail -eq 0 ]
