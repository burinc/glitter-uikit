#!/usr/bin/env bash
# Record a GIF per demo, driving each through cgevent's ACCESSIBILITY API.
#
# MAINTAINER-ONLY. Both tools this needs — screen-grab and cgevent — are not
# public yet, so this script will not run for most readers. It is committed
# because the images it produces ARE committed, and how they were made should be
# inspectable rather than folklore. Nothing else in the repo depends on it.
#
# Why not screen-grab, which captures the stills: its :input timeline offers
# :key/:type/:click but no accessibility tap, and its own README states that a
# synthetic :click "cannot actuate in-window controls in any app" — it moves the
# cursor, but the press is never processed. cgevent's :tap-by-role sends AXPress,
# which does actuate. So stills come from scripts/demo_manifest.edn and GIFs come
# from here; the two are different tools for different jobs, not duplication.
#
# Usage: scripts/record_gifs.sh [demo ...]      (default: every flow in scripts/flows)
set -uo pipefail
cd "$(dirname "$0")/.."

demos=("$@")
if [ ${#demos[@]} -eq 0 ]; then
  demos=()
  for f in scripts/flows/*.edn; do demos+=("$(basename "$f" .edn)"); done
fi

fail=0
for d in "${demos[@]}"; do
  flow="scripts/flows/$d.edn"
  [ -f "$flow" ] || { echo "!! no flow for $d ($flow)"; fail=1; continue; }

  pkill -f "jolt -M:$d" 2>/dev/null
  nohup jolt -M:"$d" >/dev/null 2>&1 &
  disown 2>/dev/null || true

  # Wait for the accessibility TREE, not just the window. A window exists before
  # glitter has mounted anything into it, and a flow that starts then fails with
  # "no element found matching ..." against a window that is merely empty —
  # measured, and the reason this loop checks for a child element rather than
  # for the window bounds it originally checked.
  pid=""
  waited=0
  for _ in $(seq 1 240); do
    pid=$(ps -eo pid,comm,args | awk -v d="$d" '$2 ~ /jolt$/ && $0 ~ ("-M:" d) {print $1; exit}')
    if [ -n "$pid" ] \
       && cgevent windows --pid "$pid" 2>/dev/null | grep -q 'x[0-9]' \
       && cgevent inspect --pid "$pid" --tree --depth 4 2>/dev/null \
            | grep -qE 'AXButton "|AXTextField|AXSlider|AXCheckBox'; then
      break
    fi
    if [ -n "$pid" ] && [ $((waited % 5)) -eq 0 ] && [ "$waited" -gt 0 ]; then
      echo "   ... waiting for $d's accessibility tree — CLICK THE WINDOW (${waited}s)"
    fi
    pid=""
    sleep 1
    waited=$((waited + 1))
  done

  if [ -z "$pid" ]; then echo "!! $d: window/tree never appeared"; fail=1; continue; fi

  echo "== $d (pid $pid)"
  if cgevent test-flow "$flow" --pid "$pid" --record-gif "docs/demos/$d.gif" 2>&1 \
       | grep -E '^\[|PASSED|FAILED|error' ; then :; fi
  pkill -f "jolt -M:$d" 2>/dev/null

  if [ -f "docs/demos/$d.gif" ]; then
    echo "   -> docs/demos/$d.gif ($(wc -c < "docs/demos/$d.gif" | tr -d ' ') bytes)"
  else
    echo "!! $d: no gif produced"; fail=1
  fi
done
exit $fail
