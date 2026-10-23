#!/bin/zsh
# Generates the 20 PLACEHOLDER built-in voice clips from macOS voices so the built-in voice
# feature can be developed and tested. These clips are not licensed for distribution:
# catalog.json marks them "placeholder": true and release builds refuse to package them.
# Replace each clip with a consented, licensed recording (same id) before release.
# Usage: scripts/make-placeholder-voices.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/app/src/main/assets/voices"; mkdir -p "$out"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
text=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["placeholderTranscript"])' "$out/catalog.json")
python3 -I -c 'import json,sys
for v in json.load(open(sys.argv[1]))["voices"]: print(v["id"]+"|"+v["placeholderSource"])' "$out/catalog.json" |
while IFS='|' read -r id source; do
  # "Voice@rate" picks a speaking rate in words per minute; some voices clone better slower.
  voice=${source%@*}; rate=(); [[ $source == *@* ]] && rate=(-r "${source##*@}")
  say -v "$voice" "${rate[@]}" -o "$work/$id.aiff" "$text"
  # 16 kHz mono 16-bit PCM, the format the app records and the model reads.
  afconvert -f WAVE -d LEI16@16000 -c 1 "$work/$id.aiff" "$out/$id.wav"
  echo "$id <- $source ($(( $(stat -f%z "$out/$id.wav") / 32000 )) s)"
done
