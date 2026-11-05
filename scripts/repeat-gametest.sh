#!/usr/bin/env bash
# Usage: scripts/repeat-gametest.sh [--stress[=N]] <N> [<test selector, e.g. 'ghostpath-gametest:*stuck_arrow*'>]
# Runs the headless game tests N times (all tests when no selector), prints one line per run, keeps the log of each failed
# run in build/repeat/<tag>-<stamp>-<i>.log (the stamp keeps the logs of earlier invocations). Exit code 0 only if every
# run passed. A run that takes longer than 15 minutes is a hang (a test whose structure chunks never tick does not count
# ticks, so it never times out) and counts as a failure.
# The vanilla progress line "[+++ ]" is printed on every tick of a hung run; it is filtered out so the log stays small.
#
# --stress[=N]: N busy-loop processes (default: the number of cores) run for the whole of each test run, so the server
# thread, the chunk and light workers and the IO worker get less CPU and finish at other ticks than on an idle machine.
# Purpose: make anything in the mod that depends on how fast asynchronous work completes show up as a failure.
set -u
stress=0
case "${1:-}" in
  --stress) stress=$(nproc); shift ;;
  --stress=*) stress=${1#--stress=}; shift ;;
esac
n=${1:?count}; sel=${2:-}
tag=$(echo "${sel:-all}" | tr -c 'A-Za-z0-9\n' '_')
[ "$stress" -gt 0 ] && tag="$tag-stress$stress"
stamp=$(date +%m%d-%H%M)
mkdir -p build/repeat
pass=0; fail=0
args=(runGameTest)
[ -n "$sel" ] && args+=("-PgtFilter=$sel")
burners=()
stop_burners() { [ ${#burners[@]} -gt 0 ] && kill "${burners[@]}" 2>/dev/null; wait "${burners[@]}" 2>/dev/null; burners=(); }
trap stop_burners EXIT
for i in $(seq 1 "$n"); do
  for _ in $(seq 1 "$stress"); do (while :; do :; done) & burners+=($!); done
  timeout 900 ./gradlew "${args[@]}" 2>&1 | grep -av '(Minecraft) \[[+_ x]*\]$' | head -c 30000000 >build/repeat/cur.out
  rc=${PIPESTATUS[0]}
  stop_burners
  if [ $rc -eq 0 ]; then pass=$((pass+1)); echo "run $i: pass"
  else fail=$((fail+1)); log="build/repeat/$tag-$stamp-$i.log"
       grep -av '(Minecraft) \[[+_ x]*\]$' build/run/gameTest/logs/latest.log | head -c 30000000 >"$log"
       why=$([ $rc -eq 124 ] && echo "HANG (15 min)" || echo "rc $rc")
       echo "run $i: FAIL ($why): $(grep -aE 'required tests failed' -A40 "$log" | grep -aE '^\[.*\] \(Minecraft\) +- ' | sed 's/^.*) *- //' | cut -c1-200 | tr '\n' '|') -> $log"; fi
done
echo "$tag: $pass passed, $fail failed of $n"
[ $fail -eq 0 ]
