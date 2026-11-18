#!/usr/bin/env bash
# Compile libxfarchive (libarchive + pont JNI).
# Usage : build.sh <dossier de sortie> <SDK Android> <ABI…|host>
#   ABI : arm64-v8a, armeabi-v7a, x86_64 → <sortie>/<abi>/libxfarchive.so (NDK, Android 11+)
#   host : <sortie>/host/libxfarchive.so pour les tests JVM (compilateur et JDK de la machine)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$(mkdir -p "$1" && cd "$1" && pwd)"
sdk="${2:-${ANDROID_HOME:-}}"
shift 2

# Sources figées : étiquette ET commit vérifié (BSD-2, voir third_party/libarchive/COPYING).
tag="v3.8.9"
commit="27cbc7827172698143e440801fc0ba39ccb4f1f5"
src="$here/third_party/libarchive"
if [ "$(git -C "$src" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]; then
  rm -rf "$src"
  mkdir -p "$here/third_party"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "$tag" https://github.com/libarchive/libarchive.git "$src"
  actual="$(git -C "$src" rev-parse HEAD)"
  if [ "$actual" != "$commit" ]; then
    echo "libarchive $tag : commit inattendu $actual (attendu $commit)" >&2
    rm -rf "$src"
    exit 1
  fi
fi

ndk="${ANDROID_NDK_HOME:-}"
if [ -z "$ndk" ]; then
  ndk="$(ls -d "$sdk"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
  ndk="${ndk%/}"
fi

for abi in "$@"; do
  work="$(dirname "$out")/libarchive-obj/$abi"
  mkdir -p "$work"
  if [ "$abi" = "host" ]; then
    cmake -S "$here" -B "$work" -G Ninja -DCMAKE_BUILD_TYPE=Release > "$work.log" 2>&1 || { cat "$work.log" >&2; exit 1; }
  else
    [ -d "$ndk" ] || { echo "NDK Android introuvable (ANDROID_NDK_HOME)" >&2; exit 1; }
    cmake -S "$here" -B "$work" -G Ninja -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-30 > "$work.log" 2>&1 || { cat "$work.log" >&2; exit 1; }
  fi
  cmake --build "$work" --target xfarchive >> "$work.log" 2>&1 || { tail -40 "$work.log" >&2; exit 1; }
  mkdir -p "$out/$abi"
  cp "$work/libxfarchive.so" "$out/$abi/libxfarchive.so"
  if [ "$abi" != "host" ]; then
    "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$out/$abi/libxfarchive.so"
  fi
done
