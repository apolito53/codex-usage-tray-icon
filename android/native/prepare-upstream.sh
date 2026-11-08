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
    verify_account_patch() {
        local path="" expected="" actual="" hashes=0 line="" changed=""
        # Full blob IDs in the committed patch make idempotent preparation as
        # strict as preparation of the original unmodified upstream source.
        while IFS= read -r line; do
            case "$line" in
                'diff --git '*) path="${line##* b/}" ;;
                'index '*)
                    expected="${line#*..}"
                    expected="${expected%% *}"
                    if [[ ! "$expected" =~ ^[0-9a-f]{40}$ ]]; then
                        echo "Account patch must contain full Git blob hashes." >&2
                        return 1
                    fi
                    actual="$(git -C "$destination" hash-object -- "$path")"
                    if [[ "$actual" != "$expected" ]]; then
                        echo "Patched upstream differs from the committed account patch: $path" >&2
                        return 1
                    fi
                    hashes=$((hashes + 1))
                    ;;
            esac
        done < "$patch"
        if [[ "$hashes" != 4 ]]; then
            echo "Account patch file count differs from the reviewed patch." >&2
            return 1
        fi
        while IFS= read -r changed; do
            case "$changed" in
                codex-rs/http-client/src/android_platform.rs|codex-rs/http-client/src/client.rs|codex-rs/http-client/src/lib.rs|codex-rs/http-client/src/route_aware_client_pool.rs) ;;
                '') ;;
                *) echo "Unexpected upstream change beside the account patch: $changed" >&2; return 1 ;;
            esac
        done < <(git -C "$destination" diff --name-only HEAD; git -C "$destination" ls-files --others --exclude-standard)
    }
    if git -C "$destination" apply --reverse --check "$patch" 2>/dev/null; then
        # A previous account build already installed this exact patch.
        verify_account_patch
    elif [[ -z "$(git -C "$destination" status --porcelain)" ]]; then
        git -C "$destination" apply --check "$patch"
        git -C "$destination" apply "$patch"
        verify_account_patch
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
