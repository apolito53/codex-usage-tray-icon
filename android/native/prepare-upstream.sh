#!/usr/bin/env bash
set -euo pipefail
native_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
destination="$native_root/.upstream/codex"
revision="$(tr -d '\r\n' < "$native_root/upstream-revision.txt")"
mode="${1:-}"
if [[ -n "$mode" && "$mode" != "--account" ]]; then
    echo "Usage: $0 [--account]" >&2
    exit 2
fi
if [[ ! -d "$destination/.git" ]]; then
    mkdir -p "$native_root/.upstream"
    git clone --filter=blob:none --no-checkout https://github.com/openai/codex.git "$destination"
    git -C "$destination" sparse-checkout init --cone
    git -C "$destination" sparse-checkout set codex-rs
    git -C "$destination" checkout --detach "$revision"
fi
if [[ "$(git -C "$destination" rev-parse HEAD)" != "$revision" ]]; then
    echo "Upstream revision differs from upstream-revision.txt; use a fresh checkout." >&2
    exit 1
fi
if [[ "$mode" == "--account" ]]; then
    patch="$native_root/patches/android-account.patch"
    if git -C "$destination" apply --reverse --check "$patch" 2>/dev/null; then
        # A previous account build already installed this exact patch.
        :
    elif [[ -z "$(git -C "$destination" status --porcelain)" ]]; then
        git -C "$destination" apply --check "$patch"
        git -C "$destination" apply "$patch"
    else
        echo "Upstream has unexpected edits; refusing the Android account patch." >&2
        exit 1
    fi
else
    if [[ -n "$(git -C "$destination" status --porcelain)" ]]; then
        echo "Offline probe requires an unmodified pinned upstream checkout." >&2
        exit 1
    fi
fi
echo "Pinned Codex checkout ready: $revision"
