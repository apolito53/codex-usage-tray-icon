import datetime as dt
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock


MODULE_PATH = Path(__file__).parents[1] / "linux" / "codex_usage_tray.py"
SPEC = importlib.util.spec_from_file_location("codex_usage_tray", MODULE_PATH)
tray = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(tray)


def make_executable(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("#!/bin/sh\n", encoding="utf-8")
    path.chmod(0o755)


class PrepaidCreditTests(unittest.TestCase):
    def test_menu_stale_missing_and_recovery(self):
        app = object.__new__(tray.TrayApplication)
        for name in ("summary_item", "reset_item", "prepaid_credits_item", "status_item", "_replace_icon", "_apply_reset_credit_menu"):
            setattr(app, name, mock.Mock())
        snapshot = tray.parse_usage_response({"result": {"rateLimits": {
            "primary": {"usedPercent": 25}, "credits": {"balance": "12.50"}
        }}})
        snapshot = tray.dataclasses.replace(snapshot, checked_at_local=mock.Mock())
        snapshot.checked_at_local.strftime.return_value = "12:00:00 PM"
        app.latest_snapshot = snapshot
        app._apply_snapshot(snapshot)
        app.prepaid_credits_item.set_label.assert_called_with("Prepaid credits: 12.50")
        app._apply_error(RuntimeError("offline"))
        app.prepaid_credits_item.set_label.assert_called_with("Prepaid credits: 12.50 - STALE")
        app._apply_snapshot(tray.dataclasses.replace(snapshot, prepaid_balance=None))
        app.prepaid_credits_item.set_label.assert_called_with("Prepaid credits: unavailable")
        app._apply_snapshot(snapshot)
        app.prepaid_credits_item.set_label.assert_called_with("Prepaid credits: 12.50")

    def test_credit_states_and_bucket_selection(self):
        cases = [
            ({"balance": "1234.5670", "hasCredits": True}, "1234.5670"),
            ({"balance": "0", "hasCredits": False}, "0"),
            ({"balance": None}, "unavailable"),
            ({"hasCredits": True}, "unavailable"),
            (None, "unavailable"),
            ({"balance": "NaN"}, "unavailable"),
            ({"balance": 123}, "unavailable"),
            ({"balance": "3", "unlimited": "true"}, "3"),
            ({"balance": "3", "unlimited": True}, "unlimited"),
            ({"balance": "3", "hasCredits": False}, "3"),
        ]
        for credits, expected in cases:
            for mapped in (False, True):
                with self.subTest(credits=credits, mapped=mapped):
                    bucket = {"primary": {"usedPercent": 25, "windowDurationMins": 10080}, "credits": credits}
                    result = {"rateLimits": bucket}
                    if mapped:
                        result = {"rateLimitsByLimitId": {"codex": bucket}, "rateLimits": {"primary": {"usedPercent": 99}}}
                    snapshot = tray.parse_usage_response({"result": result})
                    self.assertEqual("Prepaid credits: " + expected, tray.format_prepaid_credits(snapshot))
                    self.assertEqual(75, snapshot.remaining_percent)


class CodexPathResolverTests(unittest.TestCase):
    def test_prefers_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path_codex = root / "path" / "codex"
            nvm_codex = root / ".nvm" / "versions" / "node" / "v24" / "bin" / "codex"
            make_executable(path_codex)
            make_executable(nvm_codex)

            resolved = tray.resolve_codex_path(
                {"PATH": str(path_codex.parent)},
                root,
            )

            self.assertEqual(path_codex.resolve(), resolved)

    def test_finds_nvm_codex_when_desktop_path_omits_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            expected = root / ".nvm" / "versions" / "node" / "v24" / "bin" / "codex"
            make_executable(expected)

            resolved = tray.resolve_codex_path({"PATH": "/usr/bin"}, root)

            self.assertEqual(expected.resolve(), resolved)

    def test_preserves_nvm_symlink_so_its_node_bin_can_be_added_to_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            node_bin = root / ".nvm" / "versions" / "node" / "v24" / "bin"
            launcher = root / ".nvm" / "versions" / "node" / "v24" / "lib" / "codex.js"
            make_executable(launcher)
            node_bin.mkdir(parents=True)
            (node_bin / "codex").symlink_to(launcher)

            resolved = tray.resolve_codex_path({"PATH": "/usr/bin"}, root)
            environment = tray.app_server_environment(resolved, {"PATH": "/usr/bin"})

            self.assertEqual(node_bin / "codex", resolved)
            self.assertTrue(environment["PATH"].startswith(str(node_bin) + os.pathsep))

    def test_nvm_default_alias_beats_a_different_installed_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            default_codex = root / ".nvm" / "versions" / "node" / "v22.22.0" / "bin" / "codex"
            other_codex = root / ".nvm" / "versions" / "node" / "v24.15.0" / "bin" / "codex"
            make_executable(default_codex)
            make_executable(other_codex)
            alias = root / ".nvm" / "alias" / "default"
            alias.parent.mkdir(parents=True)
            alias.write_text("22.22.0\n", encoding="utf-8")

            resolved = tray.resolve_codex_path({"PATH": "/usr/bin"}, root)

            self.assertEqual(default_codex, resolved)

    def test_nvm_fallback_uses_highest_version_not_modification_time(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            old_codex = root / ".nvm" / "versions" / "node" / "v16.20.2" / "bin" / "codex"
            expected = root / ".nvm" / "versions" / "node" / "v24.15.0" / "bin" / "codex"
            make_executable(old_codex)
            make_executable(expected)
            os.utime(old_codex, (2_000_000_000, 2_000_000_000))
            os.utime(expected, (1_000_000_000, 1_000_000_000))

            resolved = tray.resolve_codex_path({"PATH": "/usr/bin"}, root)

            self.assertEqual(expected, resolved)

    def test_nvm_beats_system_only_desktop_path(self):
        with tempfile.TemporaryDirectory() as temporary, tempfile.TemporaryDirectory() as system_temporary:
            root = Path(temporary)
            system_codex = Path(system_temporary) / "bin" / "codex"
            expected = root / ".nvm" / "versions" / "node" / "v24.15.0" / "bin" / "codex"
            make_executable(system_codex)
            make_executable(expected)

            resolved = tray.resolve_codex_path(
                {"PATH": str(system_codex.parent)},
                root,
            )

            self.assertEqual(expected, resolved)

    def test_rejects_bad_override(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(tray.CodexUsageError, "non-executable"):
                tray.resolve_codex_path(
                    {tray.OVERRIDE_VARIABLE: str(Path(temporary) / "missing")},
                    Path(temporary),
                )

    def test_app_server_path_includes_nvm_node_directory(self):
        codex = Path("/home/test/.nvm/versions/node/v24/bin/codex")
        environment = tray.app_server_environment(codex, {"PATH": "/usr/bin"})
        self.assertEqual(
            "/home/test/.nvm/versions/node/v24/bin:/usr/bin",
            environment["PATH"],
        )


class UsageParserTests(unittest.TestCase):
    def test_preserves_fractional_readings_for_history_but_keeps_integer_tray(self):
        snapshot = tray.parse_usage_response({"result": {"rateLimits": {
            "primary": {"usedPercent": 20.75, "windowDurationMins": 10080},
        }}})
        self.assertEqual(20, snapshot.used_percent)
        self.assertEqual(20.75, snapshot.measured_used_percent)

    def test_rejects_non_finite_readings(self):
        for value in (float("nan"), float("inf"), "NaN", True):
            with self.subTest(value=value), self.assertRaises(tray.CodexUsageError):
                tray.parse_usage_response({"result": {"rateLimits": {
                    "primary": {"usedPercent": value},
                }}})

    def test_selects_longest_codex_window_and_available_credits(self):
        response = {
            "result": {
                "rateLimits": {"primary": {"usedPercent": 99}},
                "rateLimitsByLimitId": {
                    "codex": {
                        "limitId": "codex",
                        "primary": {
                            "usedPercent": 10,
                            "windowDurationMins": 300,
                            "resetsAt": 1_800_000_000,
                        },
                        "secondary": {
                            "usedPercent": 42,
                            "windowDurationMins": 10_080,
                            "resetsAt": 1_900_000_000,
                        },
                    }
                },
                "rateLimitResetCredits": {
                    "availableCount": 3,
                    "credits": [
                        {"status": "redeemed", "expiresAt": 1_700_000_000},
                        {"status": "available", "expiresAt": None},
                        {"status": "available", "expiresAt": 1_800_000_000},
                    ],
                },
            }
        }

        snapshot = tray.parse_usage_response(response)

        self.assertEqual(42, snapshot.used_percent)
        self.assertEqual(58, snapshot.remaining_percent)
        self.assertTrue(snapshot.is_weekly)
        self.assertEqual(3, snapshot.available_reset_credits)
        self.assertEqual(2, len(snapshot.reset_credit_expiries))
        self.assertIsInstance(snapshot.reset_credit_expiries[0], dt.datetime)
        self.assertIsNone(snapshot.reset_credit_expiries[1])

    def test_falls_back_to_legacy_bucket_and_clamps_percent(self):
        response = {
            "result": {
                "rateLimits": {
                    "primary": {
                        "usedPercent": 112,
                        "windowDurationMins": 300,
                    }
                }
            }
        }

        snapshot = tray.parse_usage_response(response)

        self.assertEqual(100, snapshot.used_percent)
        self.assertEqual(0, snapshot.remaining_percent)
        self.assertIsNone(snapshot.available_reset_credits)

    def test_rejects_response_without_active_window(self):
        with self.assertRaisesRegex(tray.CodexUsageError, "no active"):
            tray.parse_usage_response({"result": {"rateLimits": {}}})

    def test_protocol_error_detail_omits_backend_response_body(self):
        detail = (
            "\x1b[31mDecode error\x1b[0m; content-type=application/json; "
            'body={"account_id":"sensitive"}'
        )

        sanitized = tray.sanitize_server_detail(detail)

        self.assertIn("Decode error", sanitized)
        self.assertIn("response body omitted", sanitized)
        self.assertNotIn("account_id", sanitized)
        self.assertNotIn("\x1b", sanitized)


class IconTests(unittest.TestCase):
    def test_writes_offline_svg_without_unsafe_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            old_cache = os.environ.get("XDG_CACHE_HOME")
            os.environ["XDG_CACHE_HOME"] = temporary
            try:
                root, name = tray.write_status_icon("<", "#cf222e", True)
                icon = root / (name + ".svg")
                contents = icon.read_text(encoding="utf-8")
                self.assertIn("&lt;", contents)
                self.assertIn("stroke=\"#ffffff\"", contents)
                self.assertNotIn("dominant-baseline", contents)
                self.assertIn('r="30.5"', contents)
                self.assertIn("codex-usage-v3-", name)
            finally:
                if old_cache is None:
                    os.environ.pop("XDG_CACHE_HOME", None)
                else:
                    os.environ["XDG_CACHE_HOME"] = old_cache


class DesktopIntegrationTests(unittest.TestCase):
    def test_startup_entry_quotes_executable_and_can_be_disabled(self):
        with tempfile.TemporaryDirectory() as temporary:
            executable = Path(temporary) / "folder with spaces" / "codex-usage-tray"
            with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": temporary}):
                tray.set_startup_enabled(True, executable)
                contents = tray.autostart_path().read_text(encoding="utf-8")
                self.assertIn('Exec="{}"'.format(executable), contents)
                self.assertTrue(tray.startup_is_enabled())

                tray.set_startup_enabled(False, executable)
                self.assertFalse(tray.autostart_path().exists())


class VersionTests(unittest.TestCase):
    def test_platform_versions_stay_in_sync(self):
        root = MODULE_PATH.parents[1]
        expected = (root / "VERSION").read_text(encoding="utf-8").strip().lstrip("v")
        assembly = (root / "src" / "AssemblyInfo.cs").read_text(encoding="utf-8")
        client = (root / "src" / "CodexUsageClient.cs").read_text(encoding="utf-8")

        self.assertEqual(expected, tray.APP_VERSION)
        self.assertIn('AssemblyVersion("{}")'.format(expected), assembly)
        self.assertIn('"version":"{}"'.format(expected).replace('"', '\\"'), client)


if __name__ == "__main__":
    unittest.main()
