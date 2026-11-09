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
export PROBE_TARGET=$target PROBE_ACTION=build PROBE_VARIANT=account
bash "$android/native/check-android.sh" --release
mkdir -p "$output/$abi"
lib="$android/native/target/$target/release/libcodex_android_native_probe.so"
python3 "$android/verify-native-pages.py" "$lib"
symbols="$("$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" -D --defined-only "$lib")"
for method in initialize startLogin finishLogin cancelLogin readUsage signOut; do
  grep -q " Java_com_apolito_codexusage_preview_NativeAccount_${method}$" <<<"$symbols"
done
if grep -q 'Java_com_apolito_codexusage_preview_Native\(Storage\)\?Probe_' <<<"$symbols"; then
  echo "The account library includes a laboratory entry point." >&2
  exit 1
fi
cp "$lib" "$output/$abi/"
