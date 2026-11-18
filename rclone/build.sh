#!/usr/bin/env bash
# Compile le pont rclone en bibliothèque Android (.aar) avec gomobile.
# Usage : build.sh <fichier .aar de sortie> <dossier du SDK Android>
# Pré-requis : Go (version de go.mod) ; NDK : $ANDROID_NDK_HOME, sinon le plus récent du SDK.
set -euo pipefail
out="$1"
sdk="${2:-${ANDROID_HOME:-}}"
here="$(cd "$(dirname "$0")" && pwd)"
bin="$(dirname "$out")/bin"
mkdir -p "$bin"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(ls -d "$sdk"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
  ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
fi
if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "NDK Android introuvable : installez-le (sdkmanager \"ndk;29.0.14206865\") ou définissez ANDROID_NDK_HOME." >&2
  exit 1
fi
export ANDROID_HOME="$sdk" ANDROID_NDK_HOME

cd "$here"
# gomobile et gobind à la version de golang.org/x/mobile fixée dans go.mod.
version="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
if [ ! -x "$bin/gomobile" ] || [ "$(cat "$bin/version" 2>/dev/null)" != "$version" ]; then
  GOBIN="$bin" go install "golang.org/x/mobile/cmd/gomobile@$version" "golang.org/x/mobile/cmd/gobind@$version"
  echo "$version" > "$bin/version"
fi
export PATH="$bin:$PATH"
gomobile bind -target=android/arm64,android/arm -androidapi 30 \
  -javapkg io.github.thomaszimmer13.xfiles.rclone -trimpath -ldflags="-s -w" \
  -o "$out" ./bridge
rm -f "${out%.aar}-sources.jar"
