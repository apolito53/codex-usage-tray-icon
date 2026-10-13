"""Render the real GTK charts using synthetic readings, never account data.

Run with a desktop display: python3 tests/smoke_charts.py /tmp/codex-chart-smoke
"""

from datetime import datetime, timedelta
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "linux"))
import gi

gi.require_version("Gtk", "3.0")
gi.require_foreign("cairo")
from gi.repository import GLib, Gtk
import cairo

from codex_usage_chart import UsageGraphWindow
from codex_usage_history import Sample


def drain_events():
    loop = GLib.MainLoop()
    GLib.timeout_add(120, lambda: (loop.quit(), False)[1])
    loop.run()


def main():
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    now = datetime(2026, 10, 13, 12, 30).astimezone()
    samples = []
    used = 10.0
    reset = now.timestamp() + 86400
    for day in (12, 13):
        start = now.replace(day=day, hour=0, minute=0)
        for minute in range(751 if day == 13 else 1440):
            if 540 < minute < 565:
                continue
            used += (0.1 if 600 <= minute < 660 else 0.04) if 420 <= minute < 720 else 0
            samples.append(Sample((start + timedelta(minutes=minute)).timestamp(),
                                  used, reset, 10080, minute in (0, 565)))
    graph = UsageGraphWindow(Gtk, lambda _button: None)
    graph.set_usage(samples, now)
    graph.present()
    drain_events()

    def capture(name):
        drain_events()
        width, height = graph.window.get_size()
        surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, width, height)
        graph.window.draw(cairo.Context(surface))
        surface.write_to_png(str(output / (name + ".png")))

    assert graph.view == "today" and graph.interval_minutes == 1
    capture("today-minute-rate")
    for minutes in (60, 30, 15, 5, 1):
        graph.interval_buttons[minutes].set_active(True)
        assert graph.interval_minutes == minutes
        graph.area.queue_draw()
        drain_events()
    today_modes = graph.ranges.get_child_by_name("today").get_children()[0]
    today_modes.get_children()[1].set_active(True)
    assert graph.today_mode == "cumulative"
    capture("today-cumulative")
    today_modes.get_children()[2].set_active(True)
    assert graph.today_mode == "remaining" and graph._ceiling() == 100
    capture("today-remaining")
    graph.ranges.set_visible_child_name("cycle")
    assert graph.cycle_mode == "remaining"
    capture("cycle-remaining")
    cycle_modes = graph.ranges.get_child_by_name("cycle").get_children()[1]
    cycle_modes.get_children()[1].set_active(True)
    capture("cycle-daily-usage")
    cycle_modes.get_children()[2].set_active(True)
    capture("cycle-cumulative")
    day_index = next(i for i, bucket in enumerate(graph.series)
                     if bucket.at.date().day == 12)
    assert graph._open_day(day_index)
    capture("historical-day")
    graph._back_to_cycle(None)
    assert graph.selected == day_index
    graph.window.hide()
    graph.present()
    capture("reopened")
    graph.window.destroy()
    drain_events()
    print("GTK charts: intervals, remaining, cumulative, cycle, drill-down, back, and reopen passed.")
    print("Synthetic chart renders:", output)


if __name__ == "__main__":
    main()
