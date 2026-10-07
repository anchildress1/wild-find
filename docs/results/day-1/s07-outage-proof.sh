#!/usr/bin/env bash
# S07 outage proof: start a fresh pull, remove the app from recents, hold the phone offline with airplane
# mode for 60 s, restore the network, and confirm the download resumes and finishes without opening the app.
# adb can't hold Wi-Fi itself off on the Samsung test phone; airplane mode drops Wi-Fi and cellular together.
# Usage: docs/results/day-1/s07-outage-proof.sh > docs/results/day-1/s07-outage.log 2>&1
set -euo pipefail

P="${WILDFIND_PACKAGE:-dev.anchildress1.wildfind.debug}"
DIR=no_backup/models
FILE=gemma-4-E2B-it.litertlm
PART="$FILE.b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1.part"
SHA=181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c

log() { echo "$(date +%T) $*"; }
size() { adb shell run-as "$P" stat -c %s "$DIR/$1" 2>/dev/null | tr -d '\r' || true; }
wifi() { adb shell dumpsys connectivity | grep -c "ni{WIFI CONNECTED" || true; }
trace() { adb logcat -d -v time -s GemmaDownload:I | grep -E "job (started|stopped|finished)" || true; }

log "== setup: delete the model, cold-start the app"
adb shell cmd connectivity airplane-mode disable
adb shell am force-stop "$P"
adb shell run-as "$P" rm -f "$DIR/$FILE" "$DIR/$FILE.sha256" "$DIR/$PART"
adb logcat -c
adb shell am start -S -n "$P/dev.anchildress1.wildfind.MainActivity" >/dev/null
until [ "$(size "$PART")" -gt 500000000 ] 2>/dev/null; do sleep 2; done
log "part $(size "$PART") bytes"

log "== remove the app from recents"
adb shell input keyevent KEYCODE_HOME
task=$(adb shell am stack list | grep "$P" | sed -n 's/.*taskId=\([0-9]*\).*/\1/p' | head -1)
[ -n "$task" ] && adb shell cmd activity task remove "$task" 2>/dev/null || adb shell am stack remove "$task" 2>/dev/null || true
log "recents entry for $P: $(adb shell am stack list | grep -c "$P" || true)"

log "== offline for 60 s"
adb shell cmd connectivity airplane-mode enable
for _ in 1 2 3 4; do sleep 15; log "airplane $(adb shell cmd connectivity airplane-mode | tr -d '\r'), wifi networks $(wifi), part $(size "$PART")"; done
log "trace while offline:"; trace

log "== network back; the app stays closed"
adb shell cmd connectivity airplane-mode disable
while [ -z "$(size "$FILE")" ]; do sleep 5; log "wifi networks $(wifi), part $(size "$PART")"; done
log "final $(size "$FILE") bytes"
log "trace:"; trace
log "activity starts (1 = the initial launch only): $(adb logcat -d | grep -c "ActivityTaskManager.*START.*$P" || true)"
sum=$(adb shell run-as "$P" sha256sum "$DIR/$FILE" | cut -d' ' -f1)
log "device sha256 $sum"
[ "$sum" = "$SHA" ] && log "PASS final hash matches pin"
