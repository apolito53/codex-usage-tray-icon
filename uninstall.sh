#!/usr/bin/env bash
set -euo pipefail

purge_logs=false
purge_history=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --purge-logs) purge_logs=true ;;
        --purge-history) purge_history=true ;;
        *) echo "Usage: ./uninstall.sh [--purge-logs] [--purge-history]" >&2; exit 2 ;;
    esac
    shift
done

data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}
config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
cache_home=${XDG_CACHE_HOME:-"$HOME/.cache"}
state_home=${XDG_STATE_HOME:-"$HOME/.local/state"}
installed_path="$HOME/.local/bin/codex-usage-tray"
application_path="$data_home/applications/codex-usage-tray.desktop"
autostart_path="$config_home/autostart/codex-usage-tray.desktop"
cache_root="$cache_home/codex-usage-tray"
pid_path="$cache_root/codex-usage-tray.pid"
log_root="$state_home/codex-usage-tray"

if [[ -f "$pid_path" ]]; then
    process_id=$(head -n 1 -- "$pid_path" 2>/dev/null || true)
    if [[ $process_id =~ ^[0-9]+$ && -r "/proc/$process_id/cmdline" ]] &&
        tr '\0' '\n' < "/proc/$process_id/cmdline" | grep -Fqx -- "$installed_path"; then
        kill -TERM "$process_id"
        for _attempt in {1..50}; do
            kill -0 "$process_id" 2>/dev/null || break
            sleep 0.1
        done
    fi
fi

rm -f -- "$installed_path" "$application_path" "$autostart_path"
rm -f -- "$data_home/codex-usage-tray/codex_usage_history.py" "$data_home/codex-usage-tray/codex_usage_chart.py"
rm -rf -- "$cache_root"

if $purge_logs; then
    rm -f -- "$log_root/usage-tray.log" "$log_root/usage-tray.log.1"
fi
if $purge_history; then
    rm -f -- "$log_root/usage-history.sqlite3" "$log_root/usage-history.sqlite3-journal"
fi

rmdir -- "$HOME/.local/bin" 2>/dev/null || true
rmdir -- "$(dirname -- "$application_path")" 2>/dev/null || true
rmdir -- "$(dirname -- "$autostart_path")" 2>/dev/null || true

echo "Codex Usage Tray was uninstalled."
if ! $purge_logs; then
    echo "Logs were preserved at $log_root"
fi
if ! $purge_history; then
    echo "Usage history was preserved at $log_root/usage-history.sqlite3"
fi
