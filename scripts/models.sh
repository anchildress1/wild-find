#!/usr/bin/env bash
# Usage: scripts/models.sh fetch|push
#   fetch  download pinned models from Hugging Face into .models/, resuming and verifying
#   push   stream verified models into the installed debug app's no_backup/models over adb
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/core/src/main/resources/models.properties"
CACHE="$ROOT/.models"
PACKAGE="${PACKAGE:-dev.anchildress1.wildfind.debug}"
# Matches Context.noBackupFilesDir/models, relative to the app data dir run-as starts in.
REMOTE_DIR="no_backup/models"
MODELS=(gemma bioclip)

die() { echo "❌ $*" >&2; exit 1; }
prop() { sed -n "s/^$1\.$2=//p" "$MANIFEST"; }
sha_of() { shasum -a 256 "$1" | cut -d' ' -f1; }
size_of() { wc -c < "$1" | tr -d " "; }

verify_local() {
  local model="$1" path
  path="$CACHE/$(prop "$model" file)"
  [ -f "$path" ] || return 1
  [ "$(size_of "$path")" = "$(prop "$model" bytes)" ] || return 1
  [ "$(sha_of "$path")" = "$(prop "$model" sha256)" ]
}

fetch() {
  mkdir -p "$CACHE"
  for model in "${MODELS[@]}"; do
    local file url part
    file="$(prop "$model" file)"
    if verify_local "$model"; then echo "✓ $file already verified"; continue; fi
    url="https://huggingface.co/$(prop "$model" repo)/resolve/$(prop "$model" revision)/$file"
    part="$CACHE/$file.part"
    echo "→ fetching $file"
    # The CDN host behind the redirect moves; trust comes from bytes + SHA-256, never the host.
    curl -fL --proto '=https' --proto-redir '=https' --retry 3 -C - -o "$part" "$url"
    mv "$part" "$CACHE/$file"
    verify_local "$model" || { rm -f "$CACHE/$file"; die "$file failed size/SHA-256 check; deleted"; }
    echo "✓ $file verified"
  done
}

device() { adb "$@"; }
runas() { device shell "run-as $PACKAGE $*"; }

push() {
  command -v adb >/dev/null || die "adb not found"
  [ "$(adb devices | grep -c $'\tdevice$')" = 1 ] || [ -n "${ANDROID_SERIAL:-}" ] \
    || die "need exactly one device, or set ANDROID_SERIAL"
  device shell pm path "$PACKAGE" >/dev/null 2>&1 || die "$PACKAGE not installed; run make install"
  runas true 2>/dev/null || die "$PACKAGE is not debuggable"
  runas mkdir -p "$REMOTE_DIR"

  for model in "${MODELS[@]}"; do
    local file bytes sha remote
    file="$(prop "$model" file)"; bytes="$(prop "$model" bytes)"; sha="$(prop "$model" sha256)"
    verify_local "$model" || die "$file missing or unverified in .models/; run make fetch-models"
    remote="$REMOTE_DIR/$file"
    if [ "$(runas stat -c %s "$remote" 2>/dev/null | tr -d '\r')" = "$bytes" ] \
      && [ "$(runas sha256sum "$remote" | cut -d' ' -f1)" = "$sha" ]; then
      echo "✓ $file already on device"; continue
    fi
    echo "→ streaming $file ($bytes bytes)"
    # exec-in streams straight into app storage: one copy on device, no /data/local/tmp staging.
    device exec-in "run-as $PACKAGE sh -c 'umask 077 && cat > $remote.part'" < "$CACHE/$file"
    if [ "$(runas sha256sum "$remote.part" | cut -d' ' -f1)" != "$sha" ]; then
      runas rm -f "$remote.part"; die "$file SHA-256 mismatch on device; removed partial"
    fi
    runas mv "$remote.part" "$remote"
    echo "✓ $file on device and verified"
  done
}

case "${1:-}" in
  fetch) fetch ;;
  push) push ;;
  *) die "usage: $0 fetch|push" ;;
esac
