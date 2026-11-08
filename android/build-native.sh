#!/usr/bin/env bash
set -euo pipefail
android=$(cd -- "$(dirname -- "$0")" && pwd)
target=${1:?Specify aarch64-linux-android or x86_64-linux-android}
output=${2:?Specify output directory}
case "$target" in
  aarch64-linux-android) abi=arm64-v8a ;;
  x86_64-linux-android) abi=x86_64 ;;
  *) echo "Unsupported Android target: $target" >&2; exit 2 ;;
esac
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}
export PROBE_TARGET=$target PROBE_ACTION=build
bash "$android/native/check-android.sh" --release
mkdir -p "$output/$abi"
cp "$android/native/target/$target/release/libcodex_android_native_probe.so" "$output/$abi/"
