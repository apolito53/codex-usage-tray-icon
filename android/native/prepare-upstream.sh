#!/usr/bin/env bash
set -euo pipefail
native_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
destination="$native_root/.upstream/codex"
revision="$(tr -d '\r\n' < "$native_root/upstream-revision.txt")"
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
if [[ -n "$(git -C "$destination" status --porcelain)" ]]; then
    echo "Upstream checkout is modified; this probe requires the pinned original source." >&2
    exit 1
fi
echo "Pinned Codex checkout ready: $revision"
