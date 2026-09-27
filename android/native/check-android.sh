#!/usr/bin/env bash
set -euo pipefail

# The caller supplies toolchain locations; no credentials or account state are read.
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK 28.2.13676358}"
probe_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$probe_root/prepare-upstream.sh"
ndk_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC_aarch64_linux_android="$ndk_bin/aarch64-linux-android26-clang"
export CXX_aarch64_linux_android="$ndk_bin/aarch64-linux-android26-clang++"
export AR_aarch64_linux_android="$ndk_bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
export CC_x86_64_linux_android="$ndk_bin/x86_64-linux-android26-clang"
export CXX_x86_64_linux_android="$ndk_bin/x86_64-linux-android26-clang++"
export AR_x86_64_linux_android="$ndk_bin/llvm-ar"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$CC_x86_64_linux_android"
export CARGO_PROFILE_DEV_DEBUG=0
export CARGO_INCREMENTAL=0
export CARGO_BUILD_JOBS=2
export PATH="$ndk_bin:$PATH"
cd "$probe_root"
lock_args=(--locked)
if [[ "${PROBE_UPDATE_LOCK:-0}" == "1" ]]; then
    lock_args=()
fi
probe_target="${PROBE_TARGET:-aarch64-linux-android}"
probe_action="${PROBE_ACTION:-check}"
case "$probe_target" in
    aarch64-linux-android|x86_64-linux-android) ;;
    *) echo "Unsupported probe target: $probe_target" >&2; exit 2 ;;
esac
case "$probe_action" in
    check|build) ;;
    *) echo "Unsupported probe action: $probe_action" >&2; exit 2 ;;
esac
cargo +1.95.0 "$probe_action" "${lock_args[@]}" --target "$probe_target" "$@"
