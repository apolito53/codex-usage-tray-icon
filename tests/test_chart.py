from datetime import date, datetime, timedelta
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).parents[1] / "linux"))
from codex_usage_chart import UsageGraphWindow
from codex_usage_history import Sample, chart_scale


class ChartTests(unittest.TestCase):
    def graph(self):
        graph = UsageGraphWindow.__new__(UsageGraphWindow)
        graph.now = datetime(2026, 10, 13, 12, 5).astimezone()
        graph.cycles, graph.selected_cycle, graph.samples = (), None, ()
        graph.view, graph.history_day, graph.selected = "cycle", None, None
        graph.cycle_mode, graph.today_mode, graph.day_mode = "remaining", "rate", "rate"
        graph.interval_minutes, graph.series, graph.plot_bounds = 1, (), None
        graph.allowance = "Weekly allowance"
        graph.Gdk = SimpleNamespace(KEY_Return=13, KEY_KP_Enter=14, KEY_space=32,
                                    KEY_Left=37, KEY_Right=39, KEY_Escape=27)
        for name in ("detail", "area", "subtitle", "chart_note", "legend", "history_controls",
                     "ranges", "remaining_label", "today_label", "updated", "_set_cursor",
                     "cycle_caption", "previous_cycle", "next_cycle", "window"):
            setattr(graph, name, Mock())
        graph.ranges.set_visible_child_name.side_effect = lambda view: graph._select_view(
            SimpleNamespace(get_visible_child_name=lambda: view), None,
        )
        samples = []
        reset = datetime(2026, 10, 14, 12).astimezone().timestamp()
        for day in (5, 12, 13):
            start = datetime(2026, 10, day, 12).astimezone().timestamp()
            deadline = reset - 7 * 86400 if day == 5 else reset
            used = 20 if day == 13 else 10
            samples.extend(Sample(start + minute * 60, used + minute, deadline, 10080, minute == 0)
                           for minute in range(5))
        graph.set_usage(tuple(samples), graph.now)
        return graph

    def test_pointer_events_before_first_draw_are_ignored(self):
        graph = self.graph()
        self.assertFalse(graph._motion(None, None))

    def test_day_drill_down_back_and_today_keyboard_navigation(self):
        graph = self.graph()
        self.assertEqual(datetime(2026, 10, 7, 12).astimezone(), graph.series[0].at)
        self.assertEqual(7, len({bucket.at.date() for bucket in graph.series}))
        day_index = next(i for i, bucket in enumerate(graph.series)
                         if bucket.at.date().day == 12 and bucket.has_samples)
        graph.selected = day_index
        self.assertTrue(graph._chart_key(graph.area, SimpleNamespace(keyval=13)))
        self.assertEqual("day", graph.view)
        self.assertEqual(date(2026, 10, 12), graph.history_day)
        self.assertEqual(1440, len(graph.series))
        graph._back_to_cycle(None)
        self.assertEqual("cycle", graph.view)
        self.assertEqual(12, graph.series[graph.selected].at.day)
        graph.selected = next(i for i, bucket in enumerate(graph.series)
                              if bucket.at.date().day == 13 and bucket.has_samples)
        self.assertTrue(graph._chart_key(graph.area, SimpleNamespace(keyval=32)))
        self.assertEqual("today", graph.view)
        self.assertEqual(726, len(graph.series))

    def test_only_recorded_days_open_and_daily_bars_have_precise_hit_targets(self):
        graph = self.graph()
        self.assertFalse(graph._open_day(0))
        graph.cycle_mode = "usage"
        graph._update_chart()
        graph.plot_bounds = (0, 0, 700, 100)
        self.assertIsNone(graph._clickable_index(SimpleNamespace(x=510, y=95)))
        self.assertEqual(5, graph._clickable_index(SimpleNamespace(x=550, y=95)))

    def test_modes_are_independent_and_rate_units_are_explicit(self):
        graph = self.graph()
        active = Mock(get_active=lambda: True)
        self.assertEqual(100, graph._ceiling())
        graph._select_mode(active, "cumulative", "cycle")
        self.assertEqual("cumulative", graph.cycle_mode)
        self.assertEqual("rate", graph.today_mode)
        graph.ranges.set_visible_child_name("today")
        self.assertEqual("1%/min", graph._axis_label(1))
        graph._select_mode(active, "cumulative", "today")
        self.assertEqual("4%", graph._axis_label([v for v in graph._values() if v is not None][-1]))
        graph._select_mode(active, "remaining", "today")
        self.assertEqual(76, [v for v in graph._values() if v is not None][-1])
        self.assertEqual(100, graph._ceiling())
        self.assertEqual("rate", graph.day_mode)

    def test_hover_on_missing_interval_never_claims_zero_usage(self):
        graph = self.graph()
        graph.ranges.set_visible_child_name("today")
        graph.selected = 1
        graph._update_detail()
        self.assertIn("No comparable readings", graph.detail.set_text.call_args.args[0])
        graph.selected = 721
        graph._update_detail()
        self.assertIn("1% of allowance/min", graph.detail.set_text.call_args.args[0])
        self.assertIn("1.00% used over 1.0 observed min", graph.detail.set_text.call_args.args[0])
        graph.today_mode = "remaining"
        graph._update_detail()
        self.assertIn("12:01:00", graph.detail.set_text.call_args.args[0])
        self.assertIn("79% remaining", graph.detail.set_text.call_args.args[0])

    def test_cycle_navigation_and_rollover_only_at_a_reset(self):
        graph = self.graph()
        current_start = graph._cycle().start
        graph.next_cycle.set_sensitive.assert_called_with(False)
        graph._move_cycle(None, -1)
        self.assertLess(graph._cycle().start, current_start)
        self.assertEqual(8, len({bucket.at.date() for bucket in graph.series}))
        graph._move_cycle(None, 1)
        self.assertIsNone(graph.selected_cycle)
        graph.set_usage(graph.samples, graph.now + timedelta(minutes=1))
        self.assertEqual(current_start, graph._cycle().start)
        reset = graph._cycle().end.timestamp()
        new = Sample(reset + 60, 1, reset + 7 * 86400, 10080, True)
        graph.set_usage(graph.samples + (new,), datetime.fromtimestamp(new.at).astimezone())
        self.assertEqual(reset, graph._cycle().start.timestamp())
        self.assertEqual(1, len(graph.series))

    def test_browsing_old_cycle_survives_new_reset_and_drill_down_excludes_other_cycles(self):
        graph = self.graph()
        graph._move_cycle(None, -1)
        old_start = graph._cycle().start
        old_day_index = next(i for i, bucket in enumerate(graph.series) if bucket.has_samples)
        self.assertTrue(graph._open_day(old_day_index))
        self.assertTrue(all(bucket.remaining is None or bucket.remaining >= 86 for bucket in graph.series))
        reset = graph.cycles[-1].end.timestamp()
        new = Sample(reset + 60, 1, reset + 7 * 86400, 10080, True)
        graph.set_usage(graph.samples + (new,), datetime.fromtimestamp(new.at).astimezone())
        self.assertEqual(old_start, graph._cycle().start)
        graph._back_to_cycle(None)
        self.assertEqual(old_start, graph._cycle().start)

    def test_history_summary_excludes_other_allowance_sizes(self):
        graph = self.graph()
        short_window = Sample(graph.samples[0].at - 86400, 95, None, 300, True)
        graph.set_usage((short_window,) + graph.samples, graph.now)
        self.assertEqual(15, len(graph.samples))
        self.assertIn("76.00%", graph.remaining_label.set_markup.call_args.args[0])
        self.assertIn("4.00%", graph.today_label.set_markup.call_args.args[0])

    def test_empty_scale_and_small_or_large_rates_remain_distinct(self):
        graph = self.graph()
        for values in ([], [None], [None, 0], [0.00001], [0.01], [1.5], [132], [12980.22]):
            with self.subTest(values=values):
                scale = chart_scale(values)
                self.assertGreater(scale, 0)
                self.assertGreaterEqual(scale, max((v or 0 for v in values), default=0))
                self.assertEqual(5, len({graph._axis_label(scale * tick / 4) for tick in range(5)}))


if __name__ == "__main__":
    unittest.main()
