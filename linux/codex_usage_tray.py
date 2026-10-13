#!/usr/bin/env python3
"""Ubuntu tray host for Codex Usage Tray.

The protocol and presentation intentionally mirror the Windows implementation,
while GTK/AppIndicator and XDG paths provide the Linux desktop integration.
"""

import argparse
import dataclasses
import datetime as dt
import fcntl
import glob
import html
import json
import logging
from logging.handlers import RotatingFileHandler
import math
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import threading
import time
from typing import Any, Dict, List, Mapping, Optional, Sequence, TextIO, Tuple


APP_ID = "codex-usage-tray"
APP_NAME = "Codex Usage Tray"
APP_VERSION = "0.2.0.0"
ICON_LAYOUT_VERSION = 3
OVERRIDE_VARIABLE = "CODEX_USAGE_TRAY_CODEX_PATH"
RESPONSE_TIMEOUT_SECONDS = 12
REFRESH_INTERVAL_SECONDS = 60
OFFLINE_RETRY_INTERVALS = (60, 2 * 60, 5 * 60)
ANSI_ESCAPE_PATTERN = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


class CodexUsageError(RuntimeError):
    """A user-facing failure at the Codex app-server boundary."""


@dataclasses.dataclass(frozen=True)
class UsageSnapshot:
    used_percent: int
    remaining_percent: int
    reset_at_local: Optional[dt.datetime]
    window_duration_minutes: int
    is_weekly: bool
    available_reset_credits: Optional[int]
    reset_credit_expiries: Tuple[Optional[dt.datetime], ...]
    limit_id: Optional[str]
    limit_name: Optional[str]
    checked_at_local: dt.datetime
    measured_used_percent: Optional[float] = None


def _xdg_path(variable: str, fallback: Path) -> Path:
    configured = os.environ.get(variable)
    return Path(configured).expanduser() if configured else fallback


def cache_directory() -> Path:
    return _xdg_path("XDG_CACHE_HOME", Path.home() / ".cache") / APP_ID


def state_directory() -> Path:
    return _xdg_path("XDG_STATE_HOME", Path.home() / ".local" / "state") / APP_ID


def autostart_path() -> Path:
    return _xdg_path("XDG_CONFIG_HOME", Path.home() / ".config") / "autostart" / (APP_ID + ".desktop")


def _usable_executable(path: Path) -> bool:
    return path.is_file() and os.access(str(path), os.X_OK)


def _nvm_version_key(candidate: Path) -> Tuple[int, ...]:
    version_name = candidate.parents[1].name
    match = re.fullmatch(r"v?(\d+)(?:\.(\d+))?(?:\.(\d+))?", version_name)
    if match is None:
        return (-1,)
    return tuple(int(part or 0) for part in match.groups())


def resolve_codex_path(
    environment: Optional[Mapping[str, str]] = None,
    home: Optional[Path] = None,
) -> Path:
    """Find Codex in both login-session PATHs and common CLI install roots."""

    env = dict(os.environ if environment is None else environment)
    user_home = Path.home() if home is None else Path(home)

    override = env.get(OVERRIDE_VARIABLE)
    if override:
        candidate = Path(override).expanduser()
        if _usable_executable(candidate):
            # Preserve executable symlinks. NVM's codex entry points at a JS
            # launcher, while /snap/bin entries dispatch by their symlink name.
            return candidate.absolute()
        raise CodexUsageError(
            OVERRIDE_VARIABLE + " points to a missing or non-executable file: " + str(candidate)
        )

    path_match = shutil.which("codex", path=env.get("PATH", os.defpath))
    deferred_system_match = Path(path_match).absolute() if path_match else None
    if deferred_system_match is not None:
        try:
            deferred_system_match.relative_to(user_home.absolute())
            return deferred_system_match
        except ValueError:
            # A GNOME login PATH commonly finds /snap/bin/codex while omitting
            # the user's NVM tree. Defer system-wide matches until after the
            # configured user-level installations below.
            pass

    user_candidates = (
        user_home / ".local" / "bin" / "codex",
        user_home / "bin" / "codex",
        user_home / ".npm-global" / "bin" / "codex",
        user_home / ".asdf" / "shims" / "codex",
    )
    for candidate in user_candidates:
        if _usable_executable(candidate):
            return candidate.absolute()

    # Desktop autostart does not normally source .bashrc, so an NVM-managed
    # CLI can be absent from PATH even though it works in an interactive shell.
    nvm_candidates = [
        Path(candidate)
        for candidate in glob.glob(
            str(user_home / ".nvm" / "versions" / "node" / "*" / "bin" / "codex")
        )
        if _usable_executable(Path(candidate))
    ]
    if nvm_candidates:
        default_alias = user_home / ".nvm" / "alias" / "default"
        try:
            default_version = default_alias.read_text(encoding="utf-8").strip()
        except OSError:
            default_version = ""
        if re.fullmatch(r"v?\d+(?:\.\d+){0,2}", default_version):
            normalized = default_version if default_version.startswith("v") else "v" + default_version
            default_codex = (
                user_home / ".nvm" / "versions" / "node" / normalized / "bin" / "codex"
            )
            if _usable_executable(default_codex):
                return default_codex.absolute()

        # Modification time is a poor proxy here: installing an old package
        # globally can make an obsolete Node tree look newest. Prefer the
        # highest semantic Node version that actually contains Codex.
        newest = max(nvm_candidates, key=_nvm_version_key)
        return newest.absolute()

    if deferred_system_match is not None:
        return deferred_system_match

    for candidate in (
        Path("/usr/local/bin/codex"),
        Path("/usr/bin/codex"),
        Path("/snap/bin/codex"),
    ):
        if _usable_executable(candidate):
            return candidate.absolute()

    raise CodexUsageError(
        "Could not find a runnable Codex CLI. Install Codex CLI, sign in with ChatGPT, "
        "or set " + OVERRIDE_VARIABLE + "."
    )


def app_server_environment(
    codex_path: Path,
    environment: Optional[Mapping[str, str]] = None,
) -> Dict[str, str]:
    env = dict(os.environ if environment is None else environment)
    executable_directory = str(codex_path.parent)
    current_path = env.get("PATH", os.defpath)
    entries = current_path.split(os.pathsep)
    if executable_directory not in entries:
        env["PATH"] = executable_directory + os.pathsep + current_path
    return env


def _required_dict(parent: Mapping[str, Any], name: str) -> Dict[str, Any]:
    value = parent.get(name)
    if not isinstance(value, dict):
        if name not in parent:
            raise CodexUsageError("Codex usage data omitted '" + name + "'.")
        raise CodexUsageError("Codex usage field '" + name + "' had an unexpected shape.")
    return value


def _optional_int(value: Any, fallback: int = 0) -> int:
    if value is None or isinstance(value, bool):
        return fallback
    try:
        return int(value)
    except (TypeError, ValueError, OverflowError):
        return fallback


def _local_time(unix_seconds: Any) -> Optional[dt.datetime]:
    seconds = _optional_int(unix_seconds)
    if seconds <= 0:
        return None
    try:
        return dt.datetime.fromtimestamp(seconds, tz=dt.timezone.utc).astimezone()
    except (OSError, OverflowError, ValueError):
        return None


def parse_usage_response(response: Mapping[str, Any]) -> UsageSnapshot:
    result = _required_dict(response, "result")

    bucket = None
    buckets = result.get("rateLimitsByLimitId")
    if isinstance(buckets, dict) and isinstance(buckets.get("codex"), dict):
        bucket = buckets["codex"]
    if bucket is None:
        bucket = _required_dict(result, "rateLimits")

    windows = [
        candidate
        for candidate in (bucket.get("primary"), bucket.get("secondary"))
        if isinstance(candidate, dict)
    ]
    if not windows:
        raise CodexUsageError("Codex returned no active rate-limit window.")

    selected = max(
        windows,
        key=lambda candidate: _optional_int(candidate.get("windowDurationMins")),
    )
    used_value = selected.get("usedPercent")
    if used_value is None or isinstance(used_value, bool):
        raise CodexUsageError("Codex usage data omitted numeric 'usedPercent'.")
    try:
        measured_used_percent = float(used_value)
        if not math.isfinite(measured_used_percent):
            raise ValueError("Non-finite usage")
        measured_used_percent = max(0.0, min(100.0, measured_used_percent))
        used_percent = int(measured_used_percent)
    except (TypeError, ValueError, OverflowError):
        raise CodexUsageError("Codex usage data omitted numeric 'usedPercent'.")

    duration = _optional_int(selected.get("windowDurationMins"))
    reset_at = _local_time(selected.get("resetsAt"))

    available_count = None
    expiries: List[Optional[dt.datetime]] = []
    reset_summary = result.get("rateLimitResetCredits")
    if isinstance(reset_summary, dict):
        raw_count = reset_summary.get("availableCount")
        if raw_count is not None and not isinstance(raw_count, bool):
            try:
                available_count = max(0, int(raw_count))
            except (TypeError, ValueError, OverflowError):
                available_count = None

        raw_credits = reset_summary.get("credits")
        if isinstance(raw_credits, list):
            for raw_credit in raw_credits:
                if not isinstance(raw_credit, dict):
                    continue
                status = raw_credit.get("status")
                if status and str(status).lower() != "available":
                    continue
                expiries.append(_local_time(raw_credit.get("expiresAt")))

    expiries.sort(
        key=lambda expiry: (
            expiry is None,
            expiry.timestamp() if expiry is not None else float("inf"),
        )
    )

    return UsageSnapshot(
        used_percent=used_percent,
        remaining_percent=100 - used_percent,
        reset_at_local=reset_at,
        window_duration_minutes=duration,
        is_weekly=duration >= (7 * 24 * 60) - 60,
        available_reset_credits=available_count,
        reset_credit_expiries=tuple(expiries),
        limit_id=str(bucket["limitId"]) if bucket.get("limitId") is not None else None,
        limit_name=str(bucket["limitName"]) if bucket.get("limitName") is not None else None,
        checked_at_local=dt.datetime.now().astimezone(),
        measured_used_percent=measured_used_percent,
    )


class _LineReader:
    def __init__(self, stream: TextIO) -> None:
        self._stream = stream
        self._lines: "queue.Queue[Optional[str]]" = queue.Queue()
        self._thread = threading.Thread(target=self._read, daemon=True)
        self._thread.start()

    def _read(self) -> None:
        try:
            for line in self._stream:
                self._lines.put(line)
        finally:
            self._lines.put(None)

    def get(self, timeout: float) -> Optional[str]:
        return self._lines.get(timeout=timeout)


class _ErrorReader:
    def __init__(self, stream: TextIO) -> None:
        self._stream = stream
        self._lines: List[str] = []
        self._lock = threading.Lock()
        self._thread = threading.Thread(target=self._read, daemon=True)
        self._thread.start()

    def _read(self) -> None:
        for line in self._stream:
            cleaned = line.strip()
            if not cleaned:
                continue
            with self._lock:
                self._lines.append(cleaned)
                self._lines = self._lines[-12:]

    def detail(self) -> str:
        with self._lock:
            detail = " ".join(self._lines)
        return sanitize_server_detail(detail)


def sanitize_server_detail(detail: Any) -> str:
    cleaned = ANSI_ESCAPE_PATTERN.sub("", str(detail or ""))
    cleaned = " ".join(cleaned.split())
    body_marker = "; body="
    marker_index = cleaned.find(body_marker)
    if marker_index >= 0:
        cleaned = cleaned[:marker_index] + "; response body omitted"
    return cleaned if len(cleaned) <= 300 else cleaned[:297] + "…"


class CodexUsageClient:
    def __init__(self) -> None:
        self._process: Optional[subprocess.Popen] = None
        self._process_lock = threading.Lock()

    def get_weekly_usage(self) -> UsageSnapshot:
        codex_path = resolve_codex_path()
        process = subprocess.Popen(
            [str(codex_path), "app-server", "--listen", "stdio://"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
            env=app_server_environment(codex_path),
        )
        with self._process_lock:
            self._process = process

        assert process.stdin is not None
        assert process.stdout is not None
        assert process.stderr is not None
        output = _LineReader(process.stdout)
        errors = _ErrorReader(process.stderr)
        failure: Optional[BaseException] = None
        snapshot: Optional[UsageSnapshot] = None

        try:
            self._write_request(
                process,
                {
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "clientInfo": {
                            "name": APP_ID,
                            "title": APP_NAME,
                            "version": APP_VERSION,
                        },
                        "capabilities": {"experimentalApi": True},
                    },
                },
            )
            self._read_response(output, 1)

            self._write_request(
                process,
                {"id": 2, "method": "account/rateLimits/read", "params": None},
            )
            snapshot = parse_usage_response(self._read_response(output, 2))
        except BaseException as exception:
            failure = exception
        finally:
            self._stop_process(process)
            with self._process_lock:
                if self._process is process:
                    self._process = None

        if failure is not None:
            detail = errors.detail()
            message = str(failure)
            if detail:
                message += " Codex said: " + detail
            if isinstance(failure, CodexUsageError) and not detail:
                raise failure
            raise CodexUsageError(message) from failure

        assert snapshot is not None
        return snapshot

    @staticmethod
    def _write_request(process: subprocess.Popen, request: Mapping[str, Any]) -> None:
        assert process.stdin is not None
        process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
        process.stdin.flush()

    @staticmethod
    def _read_response(reader: _LineReader, request_id: int) -> Dict[str, Any]:
        deadline = time.monotonic() + RESPONSE_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            remaining = max(0.001, deadline - time.monotonic())
            try:
                line = reader.get(remaining)
            except queue.Empty:
                raise CodexUsageError("Codex did not answer the usage request in time.")
            if line is None:
                raise CodexUsageError(
                    "The Codex app server closed before returning usage."
                )
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(message, dict) or message.get("id") != request_id:
                continue
            if message.get("error") is not None:
                error = message["error"]
                detail = error.get("message") if isinstance(error, dict) else None
                raise CodexUsageError(
                    "Codex rejected the usage request: "
                    + sanitize_server_detail(detail or "unknown protocol error")
                )
            return message
        raise CodexUsageError("Codex did not answer the usage request in time.")

    def cancel(self) -> None:
        with self._process_lock:
            process = self._process
        if process is not None:
            self._stop_process(process)

    @staticmethod
    def _stop_process(process: subprocess.Popen) -> None:
        try:
            if process.poll() is not None:
                return
            if process.stdin is not None:
                process.stdin.close()
            try:
                process.wait(timeout=0.5)
                return
            except subprocess.TimeoutExpired:
                process.terminate()
            try:
                process.wait(timeout=0.5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=1)
        except (BrokenPipeError, OSError, subprocess.SubprocessError):
            pass


def configure_logging() -> logging.Logger:
    log_root = state_directory()
    log_root.mkdir(parents=True, exist_ok=True)
    log_root.chmod(0o700)
    log_path = log_root / "usage-tray.log"
    logger = logging.getLogger(APP_ID)
    logger.setLevel(logging.INFO)
    if not logger.handlers:
        handler = RotatingFileHandler(
            str(log_path),
            maxBytes=1024 * 1024,
            backupCount=1,
            encoding="utf-8",
        )
        handler.setFormatter(
            logging.Formatter("%(asctime)s [%(levelname)s] %(message)s")
        )
        logger.addHandler(handler)
    log_path.chmod(0o600)
    return logger


def _desktop_exec_path(executable_path: Path) -> str:
    escaped = str(executable_path).replace("\\", "\\\\").replace('"', '\\"')
    escaped = escaped.replace("`", "\\`").replace("$", "\\$")
    return '"' + escaped + '"'


def startup_is_enabled() -> bool:
    path = autostart_path()
    if not path.is_file():
        return False
    try:
        contents = path.read_text(encoding="utf-8")
    except OSError:
        return False
    return "X-GNOME-Autostart-enabled=false" not in contents


def set_startup_enabled(enabled: bool, executable_path: Path) -> None:
    path = autostart_path()
    if not enabled:
        try:
            path.unlink()
        except FileNotFoundError:
            pass
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    contents = "\n".join(
        (
            "[Desktop Entry]",
            "Type=Application",
            "Name=" + APP_NAME,
            "Comment=Show remaining weekly Codex usage in the system tray",
            "Exec=" + _desktop_exec_path(executable_path),
            "Terminal=false",
            "X-GNOME-Autostart-enabled=true",
            "",
        )
    )
    temporary = path.with_suffix(".desktop.tmp")
    temporary.write_text(contents, encoding="utf-8")
    temporary.replace(path)


class SingleInstanceLock:
    def __init__(self) -> None:
        self._stream: Optional[TextIO] = None
        self.path = cache_directory() / (APP_ID + ".pid")

    def acquire(self) -> bool:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        stream = self.path.open("a+", encoding="ascii")
        try:
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            stream.close()
            return False
        stream.seek(0)
        stream.truncate()
        stream.write(str(os.getpid()) + "\n")
        stream.flush()
        self._stream = stream
        return True

    def release(self) -> None:
        if self._stream is None:
            return
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass
        fcntl.flock(self._stream.fileno(), fcntl.LOCK_UN)
        self._stream.close()
        self._stream = None


def write_status_icon(text: str, color: str, offline: bool) -> Tuple[Path, str]:
    icon_root = cache_directory() / "icons"
    icon_root.mkdir(parents=True, exist_ok=True)
    safe_text = "".join(character for character in text if character.isalnum()) or "status"
    state = "offline" if offline else "online"
    icon_name = "codex-usage-v{}-{}-{}-{}".format(
        ICON_LAYOUT_VERSION,
        safe_text,
        color.lstrip("#"),
        state,
    )
    icon_path = icon_root / (icon_name + ".svg")
    if not icon_path.exists():
        font_size = 25 if len(text) >= 3 else 38 if len(text) == 2 else 42
        badge = ""
        if offline:
            badge = (
                '<circle cx="55" cy="8" r="7" fill="#cf222e" '
                'stroke="#ffffff" stroke-width="2.5"/>'
            )
        # Use an explicit alphabetic baseline instead of dominant-baseline.
        # Ubuntu 20.04's librsvg treats the latter inconsistently and pins the
        # glyphs against the top half of the circle.
        baseline_y = 31 + (font_size * 0.35)
        svg = """<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
  <circle cx="32" cy="33" r="30" fill="#000000" fill-opacity="0.35"/>
  <circle cx="32" cy="32" r="30.5" fill="{color}" stroke="#ffffff" stroke-opacity="0.86" stroke-width="1.5"/>
  <text x="32" y="{baseline_y:.2f}" fill="#ffffff" font-family="DejaVu Sans, sans-serif" font-size="{font_size}" font-weight="bold" text-anchor="middle">{text}</text>
  {badge}
</svg>
""".format(
            color=color,
            font_size=font_size,
            baseline_y=baseline_y,
            text=html.escape(text),
            badge=badge,
        )
        temporary = icon_path.with_suffix(".svg.tmp")
        temporary.write_text(svg, encoding="utf-8")
        temporary.replace(icon_path)
    return icon_root, icon_name


def _load_indicator_dependencies():
    try:
        import gi

        gi.require_version("Gtk", "3.0")
        from gi.repository import GLib, Gtk

        try:
            gi.require_version("AyatanaAppIndicator3", "0.1")
            from gi.repository import AyatanaAppIndicator3 as AppIndicator
        except (ImportError, ValueError):
            gi.require_version("AppIndicator3", "0.1")
            from gi.repository import AppIndicator3 as AppIndicator
    except (ImportError, ValueError) as exception:
        raise CodexUsageError(
            "GTK AppIndicator support is missing. Install python3-gi and either "
            "gir1.2-ayatanaappindicator3-0.1 or gir1.2-appindicator3-0.1."
        ) from exception
    return GLib, Gtk, AppIndicator


class TrayApplication:
    def __init__(self, executable_path: Path) -> None:
        from codex_usage_history import UsageHistory

        self.GLib, self.Gtk, self.AppIndicator = _load_indicator_dependencies()
        self.executable_path = executable_path
        self.logger = configure_logging()
        self.client = CodexUsageClient()
        self.history = UsageHistory(state_directory() / "usage-history.sqlite3")
        self.history_error: Optional[str] = None
        self.graph_window = None
        self.latest_snapshot: Optional[UsageSnapshot] = None
        self.last_error: Optional[str] = None
        self.consecutive_failures = 0
        self.refresh_in_progress = False
        self.refresh_started_at = 0.0
        self.timer_source: Optional[int] = None
        self.exiting = False
        self.changing_startup = False

        icon_root, icon_name = write_status_icon("?", "#6e7781", False)
        self.indicator = self.AppIndicator.Indicator.new(
            APP_ID,
            icon_name,
            self.AppIndicator.IndicatorCategory.APPLICATION_STATUS,
        )
        self.indicator.set_icon_theme_path(str(icon_root))
        self.indicator.set_status(self.AppIndicator.IndicatorStatus.ACTIVE)
        if hasattr(self.indicator, "set_title"):
            self.indicator.set_title(APP_NAME)

        self.menu = self.Gtk.Menu()
        self.summary_item = self._disabled_item("Codex usage: loading…")
        self.reset_item = self._disabled_item("Reset time: loading…")
        self.reset_credits_item = self.Gtk.MenuItem(label="Free resets: loading…")
        self.reset_credits_item.set_sensitive(False)
        self.status_item = self._disabled_item("Connection: loading…")
        for item in (
            self.summary_item,
            self.reset_item,
            self.reset_credits_item,
            self.status_item,
        ):
            self.menu.append(item)
        self.menu.append(self.Gtk.SeparatorMenuItem())

        graphs_item = self.Gtk.MenuItem(label="Usage graphs…")
        graphs_item.connect("activate", self.open_graphs)
        self.menu.append(graphs_item)

        self.refresh_item = self.Gtk.MenuItem(label="Refresh now")
        self.refresh_item.connect("activate", lambda _item: self.refresh())
        self.menu.append(self.refresh_item)

        self.startup_item = self.Gtk.CheckMenuItem(label="Start with Ubuntu")
        self.startup_item.set_active(startup_is_enabled())
        self.startup_item.connect("toggled", self._toggle_startup)
        self.menu.append(self.startup_item)

        logs_item = self.Gtk.MenuItem(label="Open diagnostic logs")
        logs_item.connect("activate", self._open_logs)
        self.menu.append(logs_item)
        self.menu.append(self.Gtk.SeparatorMenuItem())

        exit_item = self.Gtk.MenuItem(label="Exit")
        exit_item.connect("activate", lambda _item: self.exit())
        self.menu.append(exit_item)

        self.menu.show_all()
        self.indicator.set_menu(self.menu)
        self._schedule_refresh(0.25)

    def _disabled_item(self, label: str):
        item = self.Gtk.MenuItem(label=label)
        item.set_sensitive(False)
        return item

    def run(self) -> None:
        self.logger.info("%s %s starting on Linux.", APP_NAME, APP_VERSION)
        self.Gtk.main()

    def refresh(self) -> None:
        if self.refresh_in_progress or self.exiting:
            return
        self.refresh_in_progress = True
        self.refresh_started_at = time.monotonic()
        if self.graph_window is not None:
            self.graph_window.set_status("Refreshing…", True)
        self.refresh_item.set_sensitive(False)
        if self.latest_snapshot is None:
            self.summary_item.set_label("Codex usage: refreshing…")
            self.status_item.set_label("Connecting…")
        else:
            self.summary_item.set_label(self._format_summary(self.latest_snapshot, " - checking…"))
            self.status_item.set_label("Checking connection…")
        threading.Thread(target=self._refresh_worker, daemon=True).start()

    def _refresh_worker(self) -> None:
        try:
            snapshot = self.client.get_weekly_usage()
        except BaseException as exception:
            self.history.mark_gap()
            self.GLib.idle_add(self._finish_refresh, None, exception)
            return
        history_error = None
        try:
            self.history.record(snapshot)
        except (OSError, sqlite3.Error, ValueError) as exception:
            self.history.mark_gap()
            history_error = "Could not save usage history. See diagnostic logs."
            self.logger.error("Could not save usage history: %s", exception)
        self.GLib.idle_add(self._finish_refresh, snapshot, None, history_error)

    def _finish_refresh(
        self,
        snapshot: Optional[UsageSnapshot],
        exception: Optional[BaseException],
        history_error: Optional[str] = None,
    ) -> bool:
        if self.exiting:
            return False
        self.refresh_in_progress = False
        self.refresh_item.set_sensitive(True)
        if exception is None and snapshot is not None:
            self.history_error = history_error
            self.latest_snapshot = snapshot
            self.last_error = None
            self.consecutive_failures = 0
            self._apply_snapshot(snapshot)
            self.logger.info(
                "Usage refreshed: %s%% remaining; reset %s; free resets %s; expiry details %s.",
                snapshot.remaining_percent,
                snapshot.reset_at_local.isoformat() if snapshot.reset_at_local else "unknown",
                snapshot.available_reset_credits
                if snapshot.available_reset_credits is not None
                else "unknown",
                len(snapshot.reset_credit_expiries),
            )
            self._schedule_refresh(max(1, REFRESH_INTERVAL_SECONDS - (
                time.monotonic() - self.refresh_started_at
            )))
        else:
            self.last_error = str(exception)
            self.consecutive_failures += 1
            self._apply_error(exception or CodexUsageError("Unknown refresh failure."))
            self.logger.error("Usage refresh failed: %s", exception)
            index = min(self.consecutive_failures - 1, len(OFFLINE_RETRY_INTERVALS) - 1)
            self._schedule_refresh(OFFLINE_RETRY_INTERVALS[index])
        self._update_graphs()
        return False

    def open_graphs(self, _item=None) -> None:
        if self.graph_window is None:
            try:
                from codex_usage_chart import UsageGraphWindow

                self.graph_window = UsageGraphWindow(self.Gtk, lambda _button: self.refresh())
            except (ImportError, ValueError) as exception:
                self.logger.error("Could not open usage graphs: %s", exception)
                self.status_item.set_label("Charts require python3-gi-cairo. Install it and try again.")
                return
        self._update_graphs()
        self.graph_window.present()

    def _update_graphs(self) -> None:
        if self.history_error and self.last_error is None:
            self.status_item.set_label(self.history_error)
        if self.graph_window is None:
            return
        message = "OFFLINE — history stops at the last reading." if self.last_error else ""
        if self.history_error:
            message = self.history_error
        try:
            self.graph_window.set_usage(self.history.read(), dt.datetime.now().astimezone())
        except (OSError, sqlite3.Error, ValueError) as exception:
            message = "Could not read usage history. See diagnostic logs."
            self.logger.error("Could not read usage history: %s", exception)
        self.graph_window.set_status(message, self.refresh_in_progress)

    def _apply_snapshot(self, snapshot: UsageSnapshot) -> None:
        self.summary_item.set_label(self._format_summary(snapshot, ""))
        self.reset_item.set_label(
            "Resets " + self._format_datetime(snapshot.reset_at_local)
            if snapshot.reset_at_local
            else "Reset time unavailable"
        )
        self._apply_reset_credit_menu(snapshot)
        self.status_item.set_label(
            "Online - updated " + snapshot.checked_at_local.strftime("%-I:%M:%S %p")
        )
        self._replace_icon(
            str(snapshot.remaining_percent),
            self._usage_color(snapshot.remaining_percent),
            False,
        )

    def _apply_error(self, exception: BaseException) -> None:
        if self.latest_snapshot is not None:
            snapshot = self.latest_snapshot
            self.summary_item.set_label(self._format_summary(snapshot, " - STALE"))
            self.status_item.set_label(
                "OFFLINE - showing " + snapshot.checked_at_local.strftime("%-I:%M:%S %p") + " reading"
            )
            self._replace_icon(
                str(snapshot.remaining_percent),
                self._usage_color(snapshot.remaining_percent),
                True,
            )
            return

        self.summary_item.set_label("Codex usage unavailable")
        message = str(exception)
        self.reset_item.set_label(message if len(message) <= 80 else message[:77] + "…")
        self._clear_reset_credit_menu()
        self.reset_credits_item.set_label("Free reset count unavailable")
        self.reset_credits_item.set_sensitive(False)
        self.status_item.set_label("OFFLINE - last attempt " + dt.datetime.now().strftime("%-I:%M:%S %p"))
        self._replace_icon("!", "#cf222e", False)

    def _apply_reset_credit_menu(self, snapshot: UsageSnapshot) -> None:
        self._clear_reset_credit_menu()
        count = snapshot.available_reset_credits
        self.reset_credits_item.set_label(
            "Free resets available: " + str(count)
            if count is not None
            else "Free reset count unavailable"
        )
        submenu = self.Gtk.Menu()
        for index, expiry in enumerate(snapshot.reset_credit_expiries, start=1):
            label = "Reset {}: expires {}".format(
                index,
                self._format_datetime(expiry) if expiry else "unavailable",
            )
            submenu.append(self._disabled_item(label))
        known_count = count if count is not None else len(snapshot.reset_credit_expiries)
        missing = max(0, known_count - len(snapshot.reset_credit_expiries))
        if missing:
            submenu.append(
                self._disabled_item(
                    "{} more reset{}: expiry unavailable".format(
                        missing,
                        "" if missing == 1 else "s",
                    )
                )
            )
        has_details = bool(submenu.get_children())
        if has_details:
            submenu.show_all()
            self.reset_credits_item.set_submenu(submenu)
        self.reset_credits_item.set_sensitive(has_details)

    def _clear_reset_credit_menu(self) -> None:
        existing = self.reset_credits_item.get_submenu()
        if existing is not None:
            self.reset_credits_item.set_submenu(None)
            existing.destroy()

    def _replace_icon(self, text: str, color: str, offline: bool) -> None:
        icon_root, icon_name = write_status_icon(text, color, offline)
        self.indicator.set_icon_theme_path(str(icon_root))
        description = "Codex usage " + text + (" percent remaining" if text.isdigit() else "")
        if offline:
            description += ", offline"
        self.indicator.set_icon_full(icon_name, description)

    def _schedule_refresh(self, delay_seconds: float) -> None:
        if self.timer_source is not None:
            try:
                self.GLib.source_remove(self.timer_source)
            except self.GLib.Error:
                pass
        milliseconds = max(1, int(delay_seconds * 1000))
        self.timer_source = self.GLib.timeout_add(milliseconds, self._on_timer)

    def _on_timer(self) -> bool:
        self.timer_source = None
        self.refresh()
        return False

    def _toggle_startup(self, item) -> None:
        if self.changing_startup:
            return
        requested = item.get_active()
        try:
            set_startup_enabled(requested, self.executable_path)
            self.logger.info("Start with Ubuntu set to %s.", "on" if requested else "off")
        except OSError as exception:
            self.logger.error("Could not change startup registration: %s", exception)
            self.changing_startup = True
            item.set_active(not requested)
            self.changing_startup = False
            self.status_item.set_label("Startup setting failed: " + str(exception))

    def _open_logs(self, _item) -> None:
        log_root = state_directory()
        log_root.mkdir(parents=True, exist_ok=True)
        try:
            subprocess.Popen(
                ["xdg-open", str(log_root)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
        except OSError as exception:
            self.logger.error("Could not open the diagnostic log folder: %s", exception)
            self.status_item.set_label("Could not open logs: " + str(exception))

    def exit(self) -> None:
        if self.exiting:
            return
        self.exiting = True
        if self.timer_source is not None:
            self.GLib.source_remove(self.timer_source)
            self.timer_source = None
        self.client.cancel()
        if self.graph_window is not None:
            self.graph_window.window.destroy()
        self.indicator.set_status(self.AppIndicator.IndicatorStatus.PASSIVE)
        self.logger.info("%s exiting.", APP_NAME)
        self.Gtk.main_quit()

    @staticmethod
    def _format_summary(snapshot: UsageSnapshot, suffix: str) -> str:
        window = "weekly" if snapshot.is_weekly else "longest-window"
        return "Codex: {}% {} left{}".format(snapshot.remaining_percent, window, suffix)

    @staticmethod
    def _format_datetime(value: Optional[dt.datetime]) -> str:
        return value.strftime("%a, %b %-d at %-I:%M %p") if value else "unavailable"

    @staticmethod
    def _usage_color(remaining_percent: int) -> str:
        if remaining_percent > 50:
            return "#2da44e"
        if remaining_percent > 20:
            return "#d29922"
        return "#cf222e"


def check_dependencies() -> None:
    _load_indicator_dependencies()


def print_live_usage() -> int:
    try:
        path = resolve_codex_path()
        snapshot = CodexUsageClient().get_weekly_usage()
    except (CodexUsageError, OSError) as exception:
        print("Codex usage check failed: " + str(exception), file=sys.stderr)
        return 1
    reset = (
        snapshot.reset_at_local.strftime("%Y-%m-%d %H:%M:%S %Z")
        if snapshot.reset_at_local
        else "unknown"
    )
    credits = (
        str(snapshot.available_reset_credits)
        if snapshot.available_reset_credits is not None
        else "unknown"
    )
    print("Codex CLI: " + str(path))
    print("Weekly usage: {}% remaining ({}% used)".format(snapshot.remaining_percent, snapshot.used_percent))
    print("Resets: " + reset)
    print("Free resets: " + credits)
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    # Source checkouts keep helpers beside this file; installed launchers use
    # the XDG data directory so ~/.local/bin stays executable-only.
    if not Path(__file__).with_name("codex_usage_history.py").is_file():
        support_root = _xdg_path("XDG_DATA_HOME", Path.home() / ".local" / "share") / APP_ID
        sys.path.insert(0, str(support_root))
    parser = argparse.ArgumentParser(description=APP_NAME + " for Ubuntu")
    parser.add_argument(
        "--check",
        action="store_true",
        help="query the signed-in Codex CLI once without starting the tray",
    )
    parser.add_argument(
        "--check-dependencies",
        action="store_true",
        help=argparse.SUPPRESS,
    )
    arguments = parser.parse_args(argv)

    if arguments.check:
        return print_live_usage()
    if arguments.check_dependencies:
        try:
            check_dependencies()
        except CodexUsageError as exception:
            print(str(exception), file=sys.stderr)
            return 1
        return 0

    instance_lock = SingleInstanceLock()
    if not instance_lock.acquire():
        return 0

    application: Optional[TrayApplication] = None
    try:
        executable_path = Path(sys.argv[0]).expanduser().resolve()
        application = TrayApplication(executable_path)

        def handle_signal(_number, _frame) -> None:
            assert application is not None
            application.GLib.idle_add(application.exit)

        signal.signal(signal.SIGTERM, handle_signal)
        signal.signal(signal.SIGINT, handle_signal)
        application.run()
        return 0
    except (CodexUsageError, OSError) as exception:
        print(str(exception), file=sys.stderr)
        return 1
    finally:
        if application is not None and not application.exiting:
            application.exit()
        instance_lock.release()


if __name__ == "__main__":
    sys.exit(main())
