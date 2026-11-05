#!/usr/bin/env bash
set -euo pipefail
# The operator opens the exact worksheet case. No app data/logcat or automatic uploads.
[[ $# == 2 && "$2" =~ ^[A-Za-z0-9._-]{1,100}$ && "$2" != '.' && "$2" != '..' ]] || { printf '%s\n' 'Usage: capture-tv-ui.sh OUTPUT_DIR CASE_ID'; exit 2; }
command -v adb >/dev/null
command -v python3 >/dev/null
capture_root="$1"
capture_case="$2"
mkdir -p "$capture_root"
adb get-state >/dev/null
capture_temp="$(mktemp "$capture_root/.capture-XXXXXX")"
trap 'rm -f -- "$capture_temp"' EXIT
adb exec-out screencap -p > "$capture_temp"
python3 - "$capture_temp" <<'PY'
import pathlib,sys
with pathlib.Path(sys.argv[1]).open('rb') as f:
    if f.read(8) != b'\x89PNG\r\n\x1a\n':
        raise SystemExit('Device did not return a PNG screenshot.')
    f.seek(0,2)
    if f.tell()<45:
        raise SystemExit('Device returned an incomplete PNG screenshot.')
    f.seek(-12,2)
    if f.read()!=b'\x00\x00\x00\x00IEND\xaeB`\x82':
        raise SystemExit('Device returned an incomplete PNG screenshot.')
PY
mv -- "$capture_temp" "$capture_root/$capture_case.png"
printf 'Saved local screenshot for case %s. Visual acceptance is still pending reviewer inspection.\n' "$capture_case"
