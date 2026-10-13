#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: ./install.sh [--no-startup]"
}

start_with_ubuntu=true
if [[ ${1:-} == "--no-startup" ]]; then
    start_with_ubuntu=false
    shift
fi
if [[ $# -ne 0 ]]; then
    usage >&2
    exit 2
fi

if [[ $(uname -s) != "Linux" ]]; then
    echo "install.sh supports Linux. Use install.ps1 on Windows." >&2
    exit 1
fi

project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source_path="$project_root/linux/codex_usage_tray.py"
data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}
config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
cache_home=${XDG_CACHE_HOME:-"$HOME/.cache"}
bin_root="$HOME/.local/bin"
installed_path="$bin_root/codex-usage-tray"
support_root="$data_home/codex-usage-tray"
application_path="$data_home/applications/codex-usage-tray.desktop"
autostart_path="$config_home/autostart/codex-usage-tray.desktop"
pid_path="$cache_home/codex-usage-tray/codex-usage-tray.pid"

python3 "$source_path" --check-dependencies

stop_installed_copy() {
    [[ -f "$pid_path" ]] || return 0
    local process_id
    process_id=$(head -n 1 -- "$pid_path" 2>/dev/null || true)
    [[ $process_id =~ ^[0-9]+$ ]] || return 0
    [[ -r "/proc/$process_id/cmdline" ]] || return 0
    if tr '\0' '\n' < "/proc/$process_id/cmdline" | grep -Fqx -- "$installed_path"; then
        kill -TERM "$process_id"
        for _attempt in {1..50}; do
            kill -0 "$process_id" 2>/dev/null || return 0
            sleep 0.1
        done
        echo "The installed tray process did not exit within five seconds." >&2
        exit 1
    fi
}

stop_installed_copy
install -d -- "$bin_root" "$(dirname -- "$application_path")"
install -m 0755 -- "$source_path" "$installed_path"
install -d -- "$support_root"
install -m 0644 -- "$project_root/linux/codex_usage_history.py" "$project_root/linux/codex_usage_chart.py" "$support_root/"

desktop_exec=${installed_path//\\/\\\\}
desktop_exec=${desktop_exec//\"/\\\"}
desktop_exec=${desktop_exec//\`/\\\`}
desktop_exec=${desktop_exec//\$/\\\$}

write_desktop_entry() {
    local destination=$1
    install -d -- "$(dirname -- "$destination")"
    {
        echo "[Desktop Entry]"
        echo "Type=Application"
        echo "Name=Codex Usage Tray"
        echo "Comment=Show remaining weekly Codex usage in the system tray"
        echo "Exec=\"$desktop_exec\""
        echo "Terminal=false"
        echo "Categories=Utility;"
        echo "X-GNOME-Autostart-enabled=true"
    } > "$destination"
}

write_desktop_entry "$application_path"
if $start_with_ubuntu; then
    write_desktop_entry "$autostart_path"
else
    rm -f -- "$autostart_path"
fi

# A transient user service survives the installer shell exiting (including
# package managers and other managed launchers that reap background children).
# Plain nohup remains the fallback for desktops without a user systemd session.
if command -v systemd-run >/dev/null 2>&1 &&
    systemd-run --user --unit=codex-usage-tray --collect --quiet "$installed_path"; then
    :
else
    nohup "$installed_path" >/dev/null 2>&1 &
fi

echo "Installed and launched $installed_path"
if $start_with_ubuntu; then
    echo "Start with Ubuntu: on"
else
    echo "Start with Ubuntu: off"
fi
if [[ :$PATH: != *":$bin_root:"* ]]; then
    echo "Note: add $bin_root to PATH to run codex-usage-tray from a terminal."
fi
