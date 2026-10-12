#!/usr/bin/env bash
# Regenerates published orbits with large boards (LARGE profile), redrawing the levels the solver cannot prove.
# Usage: tools/regenerate_orbit.sh <orbits> <seed> [rounds] [start-round] [jobs]
#   <orbits>: "2", "3,5" or "3-12". Each round redraws only levels without a complete certificate;
#   the others are kept and the solver reuses their certificates (checked against the semantic hash).
# Requires JAVA_HOME (JDK 17+) to be set by the caller.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
orbits=${1:?orbits}; seed=${2:?seed}; rounds=${3:-3}; start=${4:-0}; jobs=${5:-1}
export JAVA_OPTS="${JAVA_OPTS:--Xmx2g}"
./gradlew :leveltool:installDist --console=plain --max-workers=2 > logs/leveltool-install.log 2>&1
./gradlew --stop >/dev/null 2>&1 || true
leveltool=leveltool/build/install/leveltool/bin/leveltool
work="content/candidates/orbits-${orbits//[,]/_}-large-s$seed"
retry_file="$work-retry.txt"
declare -A redraw=()   # cumulative redraw count per level: a level that already passed keeps its version
prev=""
if [[ "$start" -gt 0 ]]; then
  # Resume after a finished round: that round already updated the retry file.
  prev="$work-r$((start - 1))"
  while IFS='=' read -r ord count; do [[ -n "$ord" ]] && redraw[$ord]=$count; done < "$retry_file"
else
  : > "$retry_file"
fi
for round in $(seq "$start" "$rounds"); do
  pack="$work-r$round"
  rm -rf "$pack"
  "$leveltool" regenerate --catalog content/catalog.json --orbit "$orbits" --seed "$seed" --output "$pack" --retry "$retry_file" > "logs/regenerate-$orbits-r$round.log" 2>&1
  mkdir -p "$pack/content/certificates"
  if [[ -n "$prev" ]]; then
    # Reuse proven certificates; the solver re-checks each one against its semantic hash.
    cp -r "$prev"/content/certificates/level-0* "$pack/content/certificates/" 2>/dev/null || true
  fi
  "$leveltool" solve --catalog "$pack/content/catalog.json" --output "$pack/content/certificates" --resume --beam 256 --max-expanded 200000 --timeout-ms 60000 --jobs "$jobs" > "logs/solve-$orbits-large-r$round.log" 2>&1 || true
  failing=$(python3 -I -c "
import json
r=json.load(open('$pack/content/certificates/solve-report.json'))
bad=sorted({int(v['baseId'].split('-')[1]) for v in r['variants'] if v['status'] not in ('certified-and-replayed','reused')})
print(' '.join(map(str,bad)))")
  echo "round $round: levels without proof: ${failing:-none}"
  if [[ -z "$failing" ]]; then echo "OK: pack $pack"; exit 0; fi
  for ord in $failing; do redraw[$ord]=$((round + 1)); done
  : > "$retry_file"
  for ord in "${!redraw[@]}"; do echo "$ord=${redraw[$ord]}" >> "$retry_file"; done
  if [[ "$round" -eq "$rounds" ]]; then echo "PENDING: $pack (levels: $failing); resume with start-round $((round + 1))"; exit 1; fi
  prev="$pack"
done
