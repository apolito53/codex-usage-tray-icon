import datetime as dt
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).parents[1] / "linux"))
from codex_usage_history import (
    INTERVAL_MINUTES, Sample, UsageHistory, allowance_cycles, bucket_samples, chart_values, local_midnight,
)
import codex_usage_tray as tray


BASE = dt.datetime(2026, 10, 13, 12).astimezone().timestamp()


def snapshot(at=BASE, used=10.25, reset=BASE + 86400, duration=10080):
    return SimpleNamespace(
        checked_at_local=dt.datetime.fromtimestamp(at).astimezone(),
        measured_used_percent=used,
        used_percent=int(used) if used == used and abs(used) != float("inf") else 0,
        reset_at_local=dt.datetime.fromtimestamp(reset).astimezone() if reset else None,
        window_duration_minutes=duration,
    )


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "history" / "usage.sqlite3"
        self.history = UsageHistory(self.path)

    def test_fractional_samples_persist_privately_and_restart_breaks_continuity(self):
        self.history.record(snapshot())
        self.history.record(snapshot(BASE + 60, 10.75))
        rows = self.history.read(BASE + 60)
        self.assertEqual([10.25, 10.75], [row.used for row in rows])
        self.assertEqual([True, False], [row.break_before for row in rows])
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)
        self.assertEqual(0o700, self.path.parent.stat().st_mode & 0o777)
        restarted = UsageHistory(self.path)
        restarted.record(snapshot(BASE + 120, 12))
        self.assertTrue(restarted.read(BASE + 120)[-1].break_before)
        with sqlite3.connect(str(self.path)) as database:
            columns = [row[1] for row in database.execute("PRAGMA table_info(samples)")]
        self.assertEqual(["at", "used", "reset_at", "window_minutes", "break_before"], columns)

    def test_failures_resets_decreases_window_changes_and_long_gaps_break(self):
        cases = (
            (snapshot(BASE + 60, 20), True),
            (snapshot(BASE + 60, 20, reset=BASE + 90000), False),
            (snapshot(BASE + 60, 5), False),
            (snapshot(BASE + 60, 20, duration=300), False),
            (snapshot(BASE + 151, 20), False),
            (snapshot(BASE + 60, 20, reset=None), False),
        )
        for index, (second, failed_poll) in enumerate(cases):
            with self.subTest(index=index):
                history = UsageHistory(self.path.with_name(f"case-{index}.sqlite3"))
                history.record(snapshot())
                if failed_poll:
                    history.mark_gap()
                history.record(second)
                rows = history.read(BASE + 200)
                self.assertEqual(2, len(rows))
                self.assertTrue(rows[-1].break_before)

    def test_duplicate_or_backwards_clock_does_not_overwrite_and_marks_gap(self):
        self.history.record(snapshot())
        self.assertFalse(self.history.record(snapshot(BASE, 20)))
        self.assertFalse(self.history.record(snapshot(BASE - 10, 30)))
        self.history.record(snapshot(BASE + 60, 40))
        rows = self.history.read(BASE + 60)
        self.assertEqual([10.25, 40], [row.used for row in rows])
        self.assertTrue(rows[-1].break_before)

    def test_retention_is_bounded_and_expired_data_is_not_shown_when_idle(self):
        self.history.record(snapshot())
        self.assertEqual((), self.history.read(BASE + 91 * 86400))
        self.history.record(snapshot(BASE + 91 * 86400))
        with sqlite3.connect(str(self.path)) as database:
            self.assertEqual(1, database.execute("SELECT COUNT(*) FROM samples").fetchone()[0])

    def test_reading_missing_history_does_not_create_a_database(self):
        self.assertEqual((), self.history.read(BASE))
        self.assertFalse(self.path.exists())

    def test_invalid_numbers_and_corruption_are_not_silently_replaced(self):
        for value in (float("nan"), float("inf"), -1, 101):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.history.record(snapshot(used=value))
        self.path.parent.mkdir()
        self.path.write_bytes(b"corrupt database")
        with self.assertRaises(sqlite3.DatabaseError):
            self.history.record(snapshot())
        self.assertEqual(b"corrupt database", self.path.read_bytes())

    def test_tray_does_not_turn_a_storage_failure_into_an_offline_reading(self):
        app = tray.TrayApplication.__new__(tray.TrayApplication)
        app.client, app.history, app.GLib, app.logger = mock.Mock(), mock.Mock(), mock.Mock(), mock.Mock()
        reading = snapshot()
        app.client.get_weekly_usage.return_value = reading
        app.history.record.side_effect = sqlite3.OperationalError("disk full")
        app._refresh_worker()
        args = app.GLib.idle_add.call_args.args
        self.assertIs(reading, args[1])
        self.assertIsNone(args[2])
        self.assertIn("Could not save", args[3])
        app.history.mark_gap.assert_called_once()

    def test_failed_poll_is_never_recorded(self):
        app = tray.TrayApplication.__new__(tray.TrayApplication)
        app.client, app.history, app.GLib = mock.Mock(), mock.Mock(), mock.Mock()
        app.client.get_weekly_usage.side_effect = tray.CodexUsageError("offline")
        app._refresh_worker()
        app.history.record.assert_not_called()
        app.history.mark_gap.assert_called_once()
        self.assertIsNone(app.GLib.idle_add.call_args.args[1])


class AllowanceCycleTests(unittest.TestCase):
    def sample(self, at, used, reset, gap=False):
        return Sample(at, used, reset, 10080, gap)

    def test_first_reading_anchors_to_reported_seven_day_window(self):
        reset = BASE + 2 * 86400
        cycles = allowance_cycles((self.sample(BASE, 30, reset, True),))
        self.assertEqual(1, len(cycles))
        self.assertEqual(reset - 7 * 86400, cycles[0].start.timestamp())
        self.assertEqual(reset, cycles[0].end.timestamp())

    def test_outages_and_restarts_do_not_create_allowance_cycles(self):
        reset = BASE + 2 * 86400
        samples = (self.sample(BASE, 30, reset, True), self.sample(BASE + 60, 31, reset, True),
                   self.sample(BASE + 86400, 40, reset, True))
        self.assertEqual(1, len(allowance_cycles(samples)))

    def test_scheduled_reset_starts_at_deadline_even_if_first_poll_is_late(self):
        samples = (self.sample(BASE - 30, 99, BASE),
                   self.sample(BASE + 30, 1, BASE + 7 * 86400, True))
        cycles = allowance_cycles(samples)
        self.assertEqual(2, len(cycles))
        self.assertEqual(BASE, cycles[0].end.timestamp())
        self.assertEqual(BASE, cycles[1].start.timestamp())
        self.assertEqual(7 * 86400, cycles[1].end.timestamp() - cycles[1].start.timestamp())

    def test_early_reset_closes_old_cycle_and_starts_a_fresh_week(self):
        for reset in (BASE + 2 * 86400, BASE + 60 + 7 * 86400):
            with self.subTest(reset=reset):
                samples = (self.sample(BASE, 60, BASE + 2 * 86400),
                           self.sample(BASE + 60, 0, reset, True))
                cycles = allowance_cycles(samples)
                self.assertEqual(BASE + 60, cycles[0].end.timestamp())
                self.assertEqual(BASE + 60, cycles[1].start.timestamp())
                self.assertEqual(BASE + 60 + 7 * 86400, cycles[1].end.timestamp())

    def test_changed_reset_deadline_splits_cycles_even_if_usage_did_not_decrease(self):
        samples = (self.sample(BASE, 0, BASE + 2 * 86400),
                   self.sample(BASE + 60, 1, BASE + 60 + 7 * 86400))
        self.assertEqual(2, len(allowance_cycles(samples)))


class BucketTests(unittest.TestCase):
    def setUp(self):
        self.start = local_midnight(dt.date(2026, 10, 13))
        self.at = self.start.timestamp()

    def samples(self, rows):
        return tuple(Sample(self.at + offset, used, self.at + 86400, 10080, gap)
                     for offset, used, gap in rows)

    def buckets(self, rows, minutes=1, end=600):
        return bucket_samples(self.samples(rows), self.start,
                              dt.datetime.fromtimestamp(self.at + end).astimezone(), minutes)

    def test_rate_uses_elapsed_time_and_retains_fractional_increases(self):
        buckets = self.buckets(((10, 10.25, True), (80, 10.95, False)))
        self.assertIsNone(buckets[0].rate)
        self.assertAlmostEqual(0.6, buckets[1].rate)
        self.assertAlmostEqual(0.7, buckets[1].points)

    def test_remaining_uses_latest_balance_and_needs_only_one_reading(self):
        buckets = self.buckets(((10, 30.25, True), (40, 31, False), (190, 0, True)))
        values = chart_values(buckets, remaining=True)
        self.assertEqual([69, None, None, 100, None, None, None, None, None, None], values)
        self.assertEqual(self.at + 40, buckets[0].checked_at.timestamp())
        self.assertTrue(buckets[3].break_before)
        self.assertIsNone(buckets[3].points)
        self.assertEqual(100, values[3])

    def test_zero_remaining_is_a_real_balance_and_is_not_an_empty_bucket(self):
        buckets = self.buckets(((10, 100, True),))
        self.assertEqual(0, chart_values(buckets, remaining=True)[0])
        self.assertIsNone(chart_values(buckets, remaining=True)[1])

    def test_coarser_rates_are_weighted_by_observed_seconds_not_bucket_width(self):
        buckets = self.buckets(((10, 10, True), (40, 11, False), (100, 11, False)), 5)
        self.assertAlmostEqual(2 / 3, buckets[0].rate)
        self.assertEqual(90, buckets[0].seconds)
        self.assertTrue(buckets[0].break_before)

    def test_unknown_gaps_are_not_idle_zeros_or_invented_burn(self):
        buckets = self.buckets(((10, 10, True), (70, 10, False), (130, 12, False),
                                (310, 50, True), (370, 51, False)), end=420)
        self.assertEqual([None, 0, 2, None, None, None, 1], chart_values(buckets))
        self.assertEqual([None, 0, 2, None, None, None, 3], chart_values(buckets, True))
        self.assertTrue(buckets[5].break_before)

    def test_resets_and_legacy_unmarked_long_gaps_are_defensively_excluded(self):
        buckets = self.buckets(((10, 95, True), (70, 99, False), (130, 0, False),
                                (190, 2, False), (400, 90, False)), end=420)
        self.assertEqual(6, sum(bucket.points or 0 for bucket in buckets))
        self.assertTrue(buckets[2].break_before)
        self.assertTrue(buckets[6].break_before)

    def test_daily_and_intraday_totals_match_across_every_interval(self):
        samples = self.samples(((10, 10, True), (70, 12, False), (130, 13, False),
                                (190, 13, False), (250, 14, False)))
        end = dt.datetime.fromtimestamp(self.at + 250).astimezone()
        for minutes in (None,) + INTERVAL_MINUTES:
            with self.subTest(minutes=minutes):
                buckets = bucket_samples(samples, self.start, end, minutes, include_end=True)
                self.assertEqual(4, sum(bucket.points or 0 for bucket in buckets))
                self.assertEqual(4, chart_values(buckets, True)[-1])
                self.assertEqual(end, buckets[-1].end)

    def test_midnight_sample_belongs_to_new_day_only(self):
        samples = self.samples(((-60, 10, True), (0, 11, False), (60, 12, False)))
        previous_start = local_midnight(self.start.date() - dt.timedelta(days=1))
        previous = bucket_samples(samples, previous_start, self.start, 60)
        today = bucket_samples(samples, self.start,
                               dt.datetime.fromtimestamp(self.at + 61).astimezone(), 1)
        self.assertEqual(0, sum(bucket.points or 0 for bucket in previous))
        self.assertEqual(2, sum(bucket.points or 0 for bucket in today))

    def test_exact_live_boundary_does_not_merge_two_minutes_into_one(self):
        samples = self.samples(((0, 10, True), (60, 11, False), (120, 13, False)))
        end = dt.datetime.fromtimestamp(self.at + 120).astimezone()
        buckets = bucket_samples(samples, self.start, end, 1, include_end=True)
        self.assertEqual([None, 1, 2], chart_values(buckets))
        self.assertEqual([0, 60, 60], [bucket.seconds for bucket in buckets])
        self.assertEqual(end, buckets[-1].at)

    def test_partial_day_is_marked_even_without_a_recovery_sample(self):
        buckets = self.buckets(((10, 10, False), (70, 11, False)), 60, end=3600)
        self.assertTrue(buckets[0].break_before)

    def test_dst_days_keep_real_hours_and_distinct_fall_back_buckets(self):
        self.addCleanup(time.tzset)
        with mock.patch.dict(os.environ, {"TZ": "America/New_York"}):
            time.tzset()
            for day, hours in ((dt.date(2026, 3, 8), 23), (dt.date(2026, 11, 1), 25)):
                start, end = local_midnight(day), local_midnight(day + dt.timedelta(days=1))
                buckets = bucket_samples((), start, end, 60)
                self.assertEqual(hours, len(buckets))
                self.assertEqual(hours, len({bucket.at.timestamp() for bucket in buckets}))


if __name__ == "__main__":
    unittest.main()
