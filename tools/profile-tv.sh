#!/usr/bin/env bash
set -euo pipefail

# Choose the app's profile first. ANDROID_SERIAL selects one connected TV.
# No account data, app files or logcat are collected. --cold force-stops Ember.
if [[ "${1:-}" == "--help" ]]; then
  printf '%s\n' 'Usage: tools/profile-tv.sh OUTPUT_DIR [auto|low|high|custom|fast|balanced|cinematic] [RUNS=5] [--cold] [--custom-config FILE.json] [--device-class older|modern] [--interface modern|light|ember_x] [--player-interface basic|modern|ember_x]' \
    'Choose the matching profile in Ember before running. --cold force-stops Ember.' \
    'Startup/frame/memory statistics are saved; browse the requested pages when prompted.'
  exit 0
fi
profile_dir="${1:-/tmp/ember-tv-profile}"
profile_label="${2:-auto}"
profile_runs="${3:-5}"
profile_cold=""
profile_config=""
profile_device="unspecified"
profile_interface="unspecified"
profile_player="unspecified"
profile_extra=("${@:4}")
profile_arg=0
while ((profile_arg<${#profile_extra[@]})); do
  case "${profile_extra[profile_arg]}" in
    --cold) profile_cold="--cold" ;;
    --custom-config)
      ((profile_arg+=1))
      ((profile_arg<${#profile_extra[@]})) || { printf '%s\n' '--custom-config requires a JSON file.' >&2; exit 2; }
      profile_config="${profile_extra[profile_arg]}" ;;
    --device-class|--interface|--player-interface)
      profile_option="${profile_extra[profile_arg]}"
      ((profile_arg+=1))
      ((profile_arg<${#profile_extra[@]})) || { printf '%s\n' 'A comparison label needs a value.' >&2; exit 2; }
      case "$profile_option" in
        --device-class) profile_device="${profile_extra[profile_arg]}" ;;
        --interface) profile_interface="${profile_extra[profile_arg]}" ;;
        --player-interface) profile_player="${profile_extra[profile_arg]}" ;;
      esac ;;
    *) printf '%s\n' 'Unknown option.' >&2; exit 2 ;;
  esac
  ((profile_arg+=1))
done
[[ "$profile_label" =~ ^(auto|low|high|custom|fast|balanced|cinematic)$ ]] || { printf '%s\n' 'Profile must be auto, low, high, custom, fast, balanced or cinematic.' >&2; exit 2; }
[[ "$profile_runs" =~ ^[0-9]+$ ]] && ((profile_runs>=1 && profile_runs<=20)) || { printf '%s\n' 'RUNS must be 1–20.' >&2; exit 2; }
[[ -z "$profile_cold" || "$profile_cold" == "--cold" ]] || { printf '%s\n' 'Last option must be --cold.' >&2; exit 2; }
[[ "$profile_device" =~ ^(unspecified|older|modern)$ && "$profile_interface" =~ ^(unspecified|modern|light|ember_x)$ && "$profile_player" =~ ^(unspecified|basic|modern|ember_x)$ ]] || { printf '%s\n' 'Invalid device or interface comparison label.' >&2; exit 2; }
profile_tools_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
command -v python3 >/dev/null
profile_config_json="$(python3 - "$profile_label" "$profile_config" <<'PYCONFIG'
import json,pathlib,sys
try:
 config=None
 if sys.argv[2]:
  if sys.argv[1]!='custom': raise ValueError()
  path=pathlib.Path(sys.argv[2])
  if path.stat().st_size>4096: raise ValueError()
  config=json.loads(path.read_text())
  flags={'heroAnalysis','colorAnalysis','animations','progressiveArtwork','collectionMotion','navigationGlass'}
  required=flags|{'artworkQuality','prefetch'}
  if not isinstance(config,dict) or not required<=set(config) or set(config)-required-{'navigationGlassMethod'}: raise ValueError()
  if config.get('navigationGlassMethod','live') not in ('live','cached'): raise ValueError()
  if any(type(config[k]) is not bool for k in flags): raise ValueError()
  if config['artworkQuality'] not in ('economy','balanced','high'): raise ValueError()
  if type(config['prefetch']) is not int or not 0<=config['prefetch']<=2: raise ValueError()
 print(json.dumps(config))
except Exception:
 print('Invalid custom performance configuration. Supply only the documented effect/quality fields.',file=sys.stderr)
 sys.exit(2)
PYCONFIG
)"
command -v adb >/dev/null
mkdir -p "$profile_dir"
adb get-state >/dev/null
profile_package_json="$(adb shell dumpsys package app.ember.tv | python3 "$profile_tools_dir/tv_metrics.py" --package)"
adb shell getprop ro.product.model > "$profile_dir/model.txt"
adb shell getprop ro.build.version.sdk > "$profile_dir/sdk.txt"
for ((profile_run=1;profile_run<=profile_runs;profile_run++)); do
  if [[ "$profile_cold" == "--cold" ]]; then adb shell am force-stop app.ember.tv; fi
  adb shell am start -W -n app.ember.tv/.MainActivity > "$profile_dir/launch-$profile_run.txt"
done
for profile_page in home search guide; do
  printf 'On the TV, open %s. Use the same content/network for each profile.\n' "$profile_page"
  read -r -p 'Press Enter when ready to begin the scrolling test: '
  adb shell dumpsys gfxinfo app.ember.tv reset > "$profile_dir/$profile_page-reset.txt"
  read -r -p 'Browse/scroll for 30 seconds, then press Enter to capture: '
  adb shell dumpsys gfxinfo app.ember.tv framestats > "$profile_dir/$profile_page-frames.txt"
  adb shell dumpsys meminfo app.ember.tv > "$profile_dir/$profile_page-memory.txt"
done
python3 - "$profile_dir" "$profile_label" "$profile_cold" "$profile_runs" "$profile_config_json" "$profile_package_json" "$profile_device" "$profile_interface" "$profile_player" "$profile_tools_dir" <<'PY'
import json,pathlib,re,statistics,sys
sys.path.insert(0,sys.argv[10])
from tv_metrics import frame_metrics
root=pathlib.Path(sys.argv[1]);runs=int(sys.argv[4]);samples=[]
for i in range(1,runs+1):
 text=(root/f'launch-{i}.txt').read_text()
 sample={'run':i}
 for key in ('ThisTime','TotalTime','WaitTime'):
  m=re.search(rf'^{key}:\s*(\d+)',text,re.M);sample[key+'Ms']=int(m[1]) if m else None
 state=re.search(r'^LaunchState:\s*(\S+)',text,re.M);sample['launchState']=state[1] if state else 'UNKNOWN'
 status=re.search(r'^Status:\s*(\S+)',text,re.M);sample['status']=status[1] if status else 'UNKNOWN'
 samples.append(sample)
medians={k:statistics.median(values) if (values:=[s[k] for s in samples if s[k] is not None and s['status']=='ok']) else None for k in ('ThisTimeMs','TotalTimeMs','WaitTimeMs')}
by_state={state:{k:statistics.median(values) if (values:=[s[k] for s in samples if s[k] is not None and s['status']=='ok' and s['launchState']==state]) else None for k in ('ThisTimeMs','TotalTimeMs','WaitTimeMs')} for state in sorted({s['launchState'] for s in samples})}
result={'mediansByLaunchState':by_state,'selectedProfileLabel':sys.argv[2],'profileWasSetByScript':False,'deviceConfigurationVerified':False,'providedCustomConfiguration':json.loads(sys.argv[5]),'coldRequested':sys.argv[3]=='--cold','samples':samples,'medians':medians,
 'note':'App-reported launch states distinguish cold and warm starts. A default Home launcher may restart before am start. Missing timing values are unknown, not zero.'}
result['package']=json.loads(sys.argv[6])
result['deviceClassLabel']=sys.argv[7]
result['launcherInterfaceLabel']=sys.argv[8]
result['playerInterfaceLabel']=sys.argv[9]
result['model']=(root/'model.txt').read_text().strip()
result['sdk']=(root/'sdk.txt').read_text().strip()
result['frames']={page:frame_metrics((root/f'{page}-frames.txt').read_text()) for page in ('home','search','guide')}
result['nonDebuggableBuildVerified']=result['package']['debuggable'] is False
(root/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
PY
printf 'Saved device results in %s\n' "$profile_dir"
