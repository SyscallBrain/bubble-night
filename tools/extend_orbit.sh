#!/usr/bin/env bash
# Generates and certifies one procedural orbit (2–12), redrawing the levels the solver cannot prove.
# Usage: tools/extend_orbit.sh <orbit> <seed> [rounds]
# Each round redraws only levels without a complete certificate; the others are kept and reused.
# Requires JAVA_HOME (JDK 17+) to be set by the caller.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
orbit=${1:?orbit}; seed=${2:?seed}; rounds=${3:-3}
first=$(( (orbit - 1) * 50 + 1 ))
work="content/candidates/orbit$(printf '%02d' "$orbit")-s$seed"
cleanup() { ./gradlew --stop >/dev/null 2>&1 || true; }
trap cleanup EXIT
retry_file="$work-retry.txt"; : > "$retry_file"
declare -A redraw=()   # cumulative redraw count per level: a level that already passed keeps its version
prev=""
for round in $(seq 0 "$rounds"); do
  pack="$work-r$round"
  rm -rf "$pack"
  ./gradlew :leveltool:run --args="extend --base content/catalog.json --orbit $orbit --count 50 --seed $seed --output $pack --retry $retry_file" --console=plain --max-workers=2 > "logs/extend-$orbit-r$round.log" 2>&1
  mkdir -p "$pack/content/certificates"
  if [[ -n "$prev" ]]; then
    # Reuse proven certificates; the solver re-checks each one against its semantic hash.
    cp -r "$prev"/content/certificates/level-00* "$pack/content/certificates/" 2>/dev/null || true
  fi
  ./gradlew :leveltool:run --args="solve --catalog $pack/content/catalog.json --output $pack/content/certificates --resume --beam 256 --max-expanded 200000 --timeout-ms 60000" --console=plain --max-workers=2 > "logs/solve-$orbit-r$round.log" 2>&1 || true
  failing=$(python3 -I -c "
import json,sys
r=json.load(open('$pack/content/certificates/solve-report.json'))
bad=sorted({int(v['baseId'].split('-')[1]) for v in r['variants'] if v['status'] not in ('certified-and-replayed','reused') and int(v['baseId'].split('-')[1])>=$first})
print(' '.join(map(str,bad)))")
  echo "round $round: levels without proof: ${failing:-none}"
  if [[ -z "$failing" ]]; then echo "OK: pack $pack"; exit 0; fi
  if [[ "$round" -eq "$rounds" ]]; then echo "PENDING: $pack (levels: $failing)"; exit 1; fi
  for ord in $failing; do redraw[$ord]=$((round + 1)); done
  : > "$retry_file"
  for ord in "${!redraw[@]}"; do echo "$ord=${redraw[$ord]}" >> "$retry_file"; done
  prev="$pack"
done
