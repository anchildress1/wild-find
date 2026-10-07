#!/usr/bin/env bash
# S07 on-device proof: full pull, kill mid-pull + resume, Wi-Fi drop with the app closed + resume,
# bad hash (deleted, then pulled again on the next launch). Needs Wi-Fi on and the debug app installed.
# Deletes the on-phone Gemma model; restore it afterwards with make push-models.
# Usage: docs/results/day-1/s07-download-proof.sh > docs/results/day-1/s07-download.log 2>&1
set -euo pipefail

P="${WILDFIND_PACKAGE:-dev.anchildress1.wildfind.debug}"
DIR=no_backup/models
FILE=gemma-4-E2B-it.litertlm
PART="$FILE.b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1.part"
BYTES=2588147712
SHA=181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c

log() { echo "$(date +%T) $*"; }
size() { adb shell run-as "$P" stat -c %s "$DIR/$1" 2>/dev/null | tr -d '\r' || true; }
# -S force-stops first, so every launch is a cold start that runs the activity's start check.
launch() { adb shell am start -S -n "$P/dev.anchildress1.wildfind.MainActivity" >/dev/null; log "launched app (cold)"; }
trace() { adb logcat -d -v time -s GemmaDownload:I | grep -E "job (started|stopped|finished)" | tail -n "${1:-3}" || true; }
wifi_state() { adb shell dumpsys wifi | grep -m1 -E "^Wi-Fi is" | tr -d '\r'; }

# Polls the part until it reaches $1 percent or the final file appears; prints progress every 5 s.
wait_for() {
  local target=$1 n
  while :; do
    n=$(size "$PART"); n=${n:-0}
    if [ -n "$(size "$FILE")" ]; then log "final file present"; return; fi
    log "part $n bytes ($((n * 100 / BYTES))%)"
    [ $((n * 100 / BYTES)) -ge "$target" ] && return
    sleep 5
  done
}

wait_final() {
  while [ -z "$(size "$FILE")" ]; do sleep 5; log "part $(size "$PART") bytes"; done
  log "final $(size "$FILE") bytes, marker $(adb shell run-as "$P" cat "$DIR/$FILE.sha256" | tr -d '\r')"
  log "device sha256 $(adb shell run-as "$P" sha256sum "$DIR/$FILE" | cut -d' ' -f1)"
}

log "== setup: delete the model, start clean; $(wifi_state)"
adb shell am force-stop "$P"
adb shell run-as "$P" rm -f "$DIR/$FILE" "$DIR/$FILE.sha256" "$DIR/$PART"
adb logcat -c

log "== 1. full pull, killed at 30%"
launch
start=$(date +%s)
wait_for 30
adb shell am force-stop "$P"; log "force-stopped at $(size "$PART") bytes"
sleep 5; log "after kill: part $(size "$PART") bytes"

log "== 2. relaunch resumes from the part"
launch
wait_for 60

log "== 3. Wi-Fi drop with the app closed"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am kill "$P"; log "app backgrounded and process killed at $(size "$PART") bytes"
adb shell cmd wifi set-wifi-enabled disabled; sleep 5
log "wifi: $(wifi_state); part $(size "$PART") bytes"
sleep 20
log "after 25 s off: $(wifi_state); part $(size "$PART") bytes"
log "trace while off: $(trace 2 | tr '\n' ' ')"
adb shell cmd wifi set-wifi-enabled enabled; log "wifi on; waiting without opening the app"
wait_final
log "full pull wall time $(( $(date +%s) - start )) s including the deliberate pauses"

log "== 4. bad hash: corrupt a part, expect delete + no auto retry"
adb shell run-as "$P" rm -f "$DIR/$FILE" "$DIR/$FILE.sha256"
launch
wait_for 10
adb shell am force-stop "$P"; log "force-stopped at $(size "$PART") bytes"
adb shell run-as "$P" dd if=/dev/zero of="$DIR/$PART" bs=1024 count=1024 conv=notrunc 2>/dev/null
log "zeroed the first MiB of the part"
launch
while [ -n "$(size "$PART")" ]; do sleep 5; log "part $(size "$PART") bytes"; done
log "part gone; final present: '$(size "$FILE")'"
sleep 10; log "trace after mismatch: $(trace 2 | tr '\n' ' ')"
adb shell dumpsys notification --noredact | grep -A2 "pkg=$P" | grep -E 'android.text=|android.title=' | head -4 || true

log "== 5. next launch pulls again"
launch
wait_final

log "== logcat"
adb logcat -d | grep -E "GemmaDownload|JobScheduler.*$P" || true
[ "$(adb shell run-as "$P" sha256sum "$DIR/$FILE" | cut -d' ' -f1)" = "$SHA" ] && log "PASS final hash matches pin"
