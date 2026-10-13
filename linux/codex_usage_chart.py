"""Native allowance charts following AI Usage Explorer's GTK chart layout."""

from __future__ import annotations

import math
from datetime import datetime, timedelta

from codex_usage_history import (
    INTERVAL_MINUTES, allowance_cycles, bucket_samples, chart_scale, chart_values, local_midnight,
)


ACCENT = (0.12, 0.65, 0.57)
DETAIL_BAR_COLOR = (0.06, 0.44, 0.38)
TOTAL_BAR_COLOR = (0.54, 0.80, 0.75)


class UsageGraphWindow:
    def __init__(self, gtk, refresh):
        import gi

        gi.require_foreign("cairo")
        from gi.repository import Gdk
        import cairo

        self.Gtk, self.Gdk, self.cairo = gtk, Gdk, cairo
        self.samples = ()
        self.now = datetime.now().astimezone()
        self.cycles = ()
        self.selected_cycle = None
        self.allowance = "Weekly allowance"
        self.cycle_mode = "remaining"
        self.today_mode = "rate"
        self.day_mode = "rate"
        self.view = "cycle"
        self.history_day = None
        self.interval_minutes = 60
        self.interval_buttons = {}
        self.history_interval_buttons = {}
        self.series = ()
        self.plot_bounds = None
        self.chart_message = "Waiting for usage…"
        self.selected = None
        self.window = gtk.Window(title="Codex usage graphs")
        self.window.set_default_size(680, 500)
        self.window.set_position(gtk.WindowPosition.MOUSE)
        self.window.connect("delete-event", lambda window, _event: window.hide_on_delete())
        self.window.connect("key-press-event", self._window_key)

        body = gtk.Box(orientation=gtk.Orientation.VERTICAL, spacing=12)
        body.set_border_width(20)
        self.window.add(body)
        title = gtk.Label(xalign=0)
        title.set_markup('<span size="x-large" weight="bold">Codex usage</span>')
        body.pack_start(title, False, False, 0)
        self.subtitle = gtk.Label(label="Waiting for usage…", xalign=0)
        body.pack_start(self.subtitle, False, False, 0)

        totals = gtk.Box(spacing=32)
        self.remaining_label = gtk.Label(xalign=0)
        self.today_label = gtk.Label(xalign=0)
        for label, caption in ((self.remaining_label, "Remaining"), (self.today_label, "Used today (observed)")):
            label.set_markup(f'{caption}\n<span size="x-large" weight="bold">…</span>')
            totals.pack_start(label, False, False, 0)
        body.pack_start(totals, False, False, 0)

        self.ranges = gtk.Stack()
        self.ranges.set_homogeneous(False)
        switcher = gtk.StackSwitcher()
        switcher.set_stack(self.ranges)
        switcher.set_halign(gtk.Align.START)
        body.pack_start(switcher, False, False, 0)
        body.pack_start(self.ranges, False, False, 0)

        controls = gtk.Box(spacing=0)
        controls.get_style_context().add_class("linked")
        self._mode_controls(controls, "cycle", (
            ("% Remaining", "remaining"), ("Daily usage", "usage"), ("Cumulative", "cumulative"),
        ))
        cycle_controls = gtk.Box(orientation=gtk.Orientation.VERTICAL, spacing=8)
        navigation = gtk.Box(spacing=8)
        self.previous_cycle = gtk.Button(label="←")
        self.previous_cycle.set_tooltip_text("Previous allowance cycle")
        self.previous_cycle.connect("clicked", self._move_cycle, -1)
        self.next_cycle = gtk.Button(label="→")
        self.next_cycle.set_tooltip_text("Next allowance cycle")
        self.next_cycle.connect("clicked", self._move_cycle, 1)
        self.cycle_caption = gtk.Label()
        self.cycle_caption.set_line_wrap(True)
        for widget in (self.previous_cycle, self.cycle_caption, self.next_cycle):
            navigation.pack_start(widget, False, False, 0)
        cycle_controls.pack_start(navigation, False, False, 0)
        cycle_controls.pack_start(controls, False, False, 0)
        self.ranges.add_titled(cycle_controls, "cycle", "Allowance cycle")

        today_controls = self._day_controls("today", self.interval_buttons)
        self.ranges.add_titled(today_controls, "today", "Today")
        self.history_controls = self._day_controls("day", self.history_interval_buttons)
        self.history_controls.show_all()
        self.history_controls.set_no_show_all(True)
        self.history_controls.hide()

        self.detail = gtk.Label(label="Waiting for usage…", xalign=0)
        self.detail.set_line_wrap(True)
        self.detail.set_max_width_chars(75)
        body.pack_start(self.detail, False, False, 0)
        self.area = gtk.DrawingArea()
        self.area.set_size_request(440, 190)
        self.area.set_can_focus(True)
        self.area.add_events(Gdk.EventMask.POINTER_MOTION_MASK | Gdk.EventMask.LEAVE_NOTIFY_MASK
                             | Gdk.EventMask.BUTTON_PRESS_MASK)
        self.area.connect("draw", self._draw)
        self.area.connect("motion-notify-event", self._motion)
        self.area.connect("leave-notify-event", self._leave)
        self.area.connect("button-press-event", self._click)
        self.area.connect("key-press-event", self._chart_key)
        self.area.get_accessible().set_name("Codex allowance usage chart")
        self.area.set_tooltip_text(
            "Hover to inspect usage, or focus the chart and use Left / Right.\n"
            "Click a daily bar or point, or press Enter, to open day details."
        )
        body.pack_start(self.area, True, True, 0)

        self.chart_note = gtk.Label(xalign=0)
        self.chart_note.set_line_wrap(True)
        self.chart_note.set_max_width_chars(75)
        self.chart_note.set_no_show_all(True)
        body.pack_start(self.chart_note, False, False, 0)
        self.legend = gtk.Box(spacing=20)
        for color, caption in ((DETAIL_BAR_COLOR, "Recorded usage · click for day details"),
                               (TOTAL_BAR_COLOR, "Gaps / partial observations")):
            item = gtk.Box(spacing=6)
            swatch = gtk.DrawingArea()
            swatch.set_size_request(12, 12)
            swatch.connect("draw", self._draw_swatch, color)
            item.pack_start(swatch, False, False, 0)
            item.pack_start(gtk.Label(label=caption), False, False, 0)
            self.legend.pack_start(item, False, False, 0)
        self.legend.show_all()
        self.legend.set_no_show_all(True)
        body.pack_start(self.legend, False, False, 0)
        self.ranges.connect("notify::visible-child-name", self._select_view)

        footer = gtk.Box(spacing=12)
        self.updated = gtk.Label(label="Not refreshed yet", xalign=0)
        self.refresh_button = gtk.Button(label="Refresh")
        self.refresh_button.connect("clicked", refresh)
        footer.pack_start(self.updated, True, True, 0)
        footer.pack_end(self.refresh_button, False, False, 0)
        body.pack_start(footer, False, False, 0)
        self.status = gtk.Label(xalign=0)
        self.status.set_line_wrap(True)
        self.status.set_max_width_chars(75)
        self.status.set_no_show_all(True)
        body.pack_start(self.status, False, False, 0)
        self.interval_buttons[1].set_active(True)
        self.history_interval_buttons[1].set_active(True)
        self.ranges.show_all()
        self.ranges.set_visible_child_name("today")

    def _day_controls(self, view, buttons):
        gtk = self.Gtk
        controls = gtk.Box(orientation=gtk.Orientation.VERTICAL, spacing=8)
        if view == "day":
            back = gtk.Button(label="← Allowance cycle")
            back.set_halign(gtk.Align.START)
            back.connect("clicked", self._back_to_cycle)
            controls.pack_start(back, False, False, 0)
        modes = gtk.Box(spacing=0)
        modes.get_style_context().add_class("linked")
        self._mode_controls(modes, view, (
            ("Burn rate", "rate"), ("Cumulative", "cumulative"), ("% Remaining", "remaining"),
        ))
        controls.pack_start(modes, False, False, 0)
        intervals = gtk.Box(spacing=0)
        intervals.get_style_context().add_class("linked")
        group = None
        for minutes in INTERVAL_MINUTES:
            button = gtk.RadioButton.new_with_label_from_widget(
                group, "1h" if minutes == 60 else f"{minutes}m",
            )
            group = group or button
            button.set_mode(False)
            button.connect("toggled", self._select_interval, minutes)
            buttons[minutes] = button
            intervals.pack_start(button, False, False, 0)
        controls.pack_start(intervals, False, False, 0)
        return controls

    def _mode_controls(self, container, view, choices):
        group = None
        for label, mode in choices:
            button = self.Gtk.RadioButton.new_with_label_from_widget(group, label)
            group = group or button
            button.set_mode(False)
            button.connect("toggled", self._select_mode, mode, view)
            container.pack_start(button, False, False, 0)

    @staticmethod
    def _draw_swatch(area, cr, color):
        cr.set_source_rgb(*color)
        cr.rectangle(0, (area.get_allocated_height() - 12) / 2, 12, 12)
        cr.fill()
        return False

    def present(self):
        self.window.show_all()
        self.window.present()

    def set_usage(self, samples, now):
        if self.now.date() != now.date():
            self.selected = None
        self.now = now.astimezone()
        # Percentages from different allowance sizes cannot be added together.
        duration = samples[-1].window_minutes if samples else 10080
        self.samples = tuple(sample for sample in samples if sample.window_minutes == duration)
        self.allowance = "Weekly allowance" if duration >= 10020 else "Allowance"
        self.cycles = allowance_cycles(self.samples)
        if not any(cycle.start.timestamp() == self.selected_cycle for cycle in self.cycles):
            self.selected_cycle = None
        today = bucket_samples(
            self.samples, local_midnight(self.now.date()), self.now, include_end=True,
        )
        today_points = today[-1].points if today else None
        for label, caption, value in (
            (self.remaining_label, "Remaining", 100 - self.samples[-1].used if self.samples else None),
            (self.today_label, "Used today (observed)", today_points),
        ):
            amount = f"{value:,.2f}%" if value is not None else "—"
            label.set_markup(f'{caption}\n<span size="x-large" weight="bold">{amount}</span>')
        if self.samples:
            checked = datetime.fromtimestamp(self.samples[-1].at).astimezone()
            self.updated.set_text(checked.strftime("Last reading %b %-d, %-I:%M:%S %p"))
        else:
            self.updated.set_text("History starts with successful polls · every minute")
        self._update_chart()

    def _move_cycle(self, _button, direction):
        if not self.cycles:
            return
        index = max(0, min(len(self.cycles) - 1, self._cycle_index() + direction))
        self.selected_cycle = (self.cycles[index].start.timestamp()
                               if index < len(self.cycles) - 1 else None)
        self.selected = None
        self._update_chart()

    def _cycle_index(self):
        return next((i for i, cycle in enumerate(self.cycles)
                     if cycle.start.timestamp() == self.selected_cycle), len(self.cycles) - 1)

    def _cycle(self):
        return self.cycles[self._cycle_index()] if self.cycles else None

    def set_status(self, message, refreshing):
        self.status.set_text(message)
        self.status.set_visible(bool(message))
        self.refresh_button.set_sensitive(not refreshing)

    def _window_key(self, _window, event):
        if event.keyval == self.Gdk.KEY_Escape:
            self.window.hide()
            return True
        return False

    def _select_mode(self, button, mode, view="cycle"):
        if button.get_active():
            setattr(self, view + "_mode", mode)
            self._update_chart()

    def _select_view(self, stack, _property):
        self.view = stack.get_visible_child_name()
        if self.view != "day":
            self.history_controls.hide()
            if self.history_controls.get_parent() is stack:
                stack.remove(self.history_controls)
        self.selected = None
        self._update_chart()

    def _select_interval(self, button, minutes):
        if button.get_active() and minutes != self.interval_minutes:
            self.interval_minutes = minutes
            for buttons in (self.interval_buttons, self.history_interval_buttons):
                buttons[minutes].set_active(True)
            self.selected = None
            self._update_chart()

    def _mode(self):
        return getattr(self, self.view + "_mode")

    def _is_line(self):
        return self._mode() in ("cumulative", "remaining")

    def _values(self):
        return chart_values(self.series, cumulative=self._mode() == "cumulative",
                            rate=self._mode() == "rate", remaining=self._mode() == "remaining")

    def _ceiling(self):
        return 100 if self._mode() == "remaining" else chart_scale(self._values())

    def _update_chart(self):
        cycle = self._cycle()
        caption = (f"{cycle.start.strftime('%b %-d, %H:%M')} → {cycle.end.strftime('%b %-d, %H:%M %Z')}"
                   if cycle else "Waiting for the first reading")
        self.cycle_caption.set_text(caption)
        self.previous_cycle.set_sensitive(self._cycle_index() > 0)
        self.next_cycle.set_sensitive(self._cycle_index() < len(self.cycles) - 1)
        if self.view in ("today", "day"):
            day = self.now.date() if self.view == "today" else self.history_day
            start = local_midnight(day)
            end = min(local_midnight(day + timedelta(days=1)), self.now)
            samples = self.samples
            if self.view == "day" and cycle:
                start, end = max(start, cycle.start), min(end, cycle.end)
                samples = cycle.samples
            self.series = bucket_samples(samples, start, end, self.interval_minutes,
                                         include_end=end == self.now)
            heading = "Today" if day == self.now.date() else day.strftime("%A")
            self.subtitle.set_text(f"{heading} · {day.strftime('%b %-d, %Y')} · {self.allowance}")
        else:
            end = min(cycle.end, self.now) if cycle else self.now
            # Keep the cycle balance useful from the first day; daily closing
            # balances hide all intraday movement behind a single point.
            interval = 5 if self._mode() == "remaining" else None
            self.series = (bucket_samples(cycle.samples, cycle.start, end, interval,
                                          include_end=end == self.now)
                           if cycle else ())
            self.subtitle.set_text("Allowance cycle · " + self.allowance)
        if self._mode() == "remaining":
            self.chart_message = "No recorded balance in this view"
        elif any(bucket.points is not None for bucket in self.series):
            self.chart_message = "No measured increase in this view"
        else:
            self.chart_message = "Waiting for two readings" if not self.samples else "No comparable readings in this view"
        if self._mode() == "remaining":
            interval = "5-minute interval" if self.view == "cycle" else "interval"
            mode = "Allowance remaining · latest reading in each " + interval
        elif self._mode() == "cumulative":
            mode = "Observed running total · % of allowance"
        elif self.view == "cycle":
            mode = "Observed daily usage · % of allowance"
        else:
            mode = "Average observed burn rate · % of allowance/min"
        note = mode + "\nLocal time · shading marks gaps and resets."
        if self._mode() == "rate":
            note += "\nRounded or delayed readings can make minute rates spiky."
        if self.selected is not None and self.selected >= len(self.series):
            self.selected = None
        self.chart_note.set_text(note)
        self.chart_note.set_visible(bool(note))
        self.legend.set_visible(self.view == "cycle" and self._mode() == "usage")
        self.plot_bounds = None
        self._update_detail()
        self.area.queue_draw()

    def _update_detail(self):
        if not self.series:
            self.detail.set_text(self.chart_message)
            return
        index = self._selected_index()
        bucket = self.series[index]
        day = bucket.at
        value = self._values()[index]
        amount = "No recorded reading" if self._mode() == "remaining" else "No comparable readings"
        if value is not None:
            amount = self._axis_label(value)
            if self._mode() == "remaining":
                amount += " remaining"
            elif self._mode() == "rate":
                amount = amount.replace("%/min", "% of allowance/min")
            else:
                amount += " of allowance"
        if self._mode() == "remaining" and bucket.checked_at is not None:
            description = bucket.checked_at.strftime("%b %-d, %H:%M:%S %Z") + " · " + amount
            if self.view == "cycle":
                description += " · click or press Enter for details"
        elif self.view in ("today", "day"):
            if self._mode() == "cumulative":
                period = "Observed through " + bucket.end.strftime("%H:%M %Z")
            elif day == bucket.end:
                period = day.strftime("%H:%M %Z") + " · current interval"
            else:
                period = day.strftime("%H:%M") + "–" + bucket.end.strftime("%H:%M %Z")
            description = period + " · " + amount
            if bucket.points is not None and self._mode() == "rate":
                description += f" · {bucket.points:.2f}% used over {bucket.seconds / 60:.1f} observed min"
        else:
            period = "Cycle through " if self._mode() == "cumulative" else ""
            description = f"{period}{day.strftime('%b %-d')} · {amount}"
            description += (" · click or press Enter for details" if bucket.has_samples else " · no history")
        if bucket.break_before and value is not None:
            description += " · contains a gap / reset"
        self.detail.set_text(description)
        self.area.get_accessible().set_description(description)

    def _plot_bounds(self, width, height, context=None):
        ceiling = self._ceiling()
        # Include units in the axis without clipping the label.
        left = max(58, max(context.text_extents(self._axis_label(ceiling * tick / 4))[2]
                           for tick in range(5)) + 14) if context else 58
        return left, 12, max(1, width - left - 28), max(1, height - 42)

    def _index_at(self, event):
        if not self.series or self.plot_bounds is None:
            return None
        left, top, width, height = self.plot_bounds
        if left <= event.x <= left + width and top <= event.y <= top + height:
            count = len(self.series)
            fraction = (event.x - left) / width
            index = round(fraction * (count - 1)) if self._is_line() else int(fraction * count)
            return max(0, min(count - 1, index))
        return None

    @staticmethod
    def _bar_width(slot_width):
        return min(40, slot_width * (0.8 if slot_width >= 3 else 1))

    def _clickable_index(self, event):
        if self.view != "cycle":
            return None
        index = self._index_at(event)
        if index is None or not self.series[index].has_samples:
            return None
        left, top, width, height = self.plot_bounds
        if self._is_line():
            value = self._values()[index]
            if value is None:
                return None
            center = left + width * (index / (len(self.series) - 1) if len(self.series) > 1 else 0.5)
            y = top + height * (1 - value / self._ceiling())
            return index if math.hypot(event.x - center, event.y - y) <= 8 else None
        slot = width / len(self.series)
        center = left + slot * (index + 0.5)
        bar_height = height * (self.series[index].points or 0) / chart_scale(self._values())
        if (abs(event.x - center) <= self._bar_width(slot) / 2
                and event.y >= top + height - max(bar_height, 3)):
            return index
        return None

    def _set_cursor(self, area, clickable):
        window = area.get_window()
        if window is not None:
            cursor = self.Gdk.Cursor.new_from_name(window.get_display(), "pointer") if clickable else None
            window.set_cursor(cursor)

    def _motion(self, area, event):
        if not self.series or self.plot_bounds is None:
            return False
        self.selected = self._index_at(event)
        self._set_cursor(area, self._clickable_index(event) is not None)
        self._update_detail()
        area.queue_draw()
        return False

    def _leave(self, area, _event):
        self.selected = None
        self._set_cursor(area, False)
        self._update_detail()
        area.queue_draw()
        return False

    def _open_day(self, index):
        if self.view != "cycle" or index is None:
            return False
        day = self.series[index].at.date()
        if not self.series[index].has_samples:
            return False
        self.history_day = day
        if day == self.now.date() and self._cycle_index() == len(self.cycles) - 1:
            self.ranges.set_visible_child_name("today")
        else:
            self.ranges.add_titled(self.history_controls, "day", day.strftime("%b %-d"))
            self.history_controls.show()
            self.ranges.set_visible_child_name("day")
        self._set_cursor(self.area, False)
        self.area.grab_focus()
        return True

    def _click(self, _area, event):
        return event.button == 1 and self._open_day(self._clickable_index(event))

    def _back_to_cycle(self, _button):
        self.ranges.set_visible_child_name("cycle")
        self.selected = next((index for index, bucket in enumerate(self.series)
                              if bucket.at.date() == self.history_day), None)
        self._update_detail()
        self.area.grab_focus()
        self.area.queue_draw()

    def _chart_key(self, area, event):
        if not self.series:
            return False
        if event.keyval in (self.Gdk.KEY_Return, self.Gdk.KEY_KP_Enter, self.Gdk.KEY_space):
            return self._open_day(self._selected_index())
        if event.keyval not in (self.Gdk.KEY_Left, self.Gdk.KEY_Right):
            return False
        count = len(self.series)
        index = self._selected_index()
        self.selected = max(0, min(count - 1, index + (1 if event.keyval == self.Gdk.KEY_Right else -1)))
        self._update_detail()
        area.queue_draw()
        return True

    def _selected_index(self):
        if self.selected is not None:
            return self.selected
        values = self._values()
        return next((i for i in range(len(values) - 1, -1, -1) if values[i] is not None),
                    len(values) - 1)

    def _axis_label(self, value):
        amount = f"{value:,.0f}" if value == int(value) else f"{value:,.6f}".rstrip("0")
        return amount + ("%/min" if self._mode() == "rate" else "%")

    def _draw(self, area, cr):
        gtk = self.Gtk
        width, height = area.get_allocated_width(), area.get_allocated_height()
        style = area.get_style_context()
        gtk.render_background(style, cr, 0, 0, width, height)
        color = style.get_color(gtk.StateFlags.NORMAL)
        foreground = (color.red, color.green, color.blue)
        accent = ACCENT
        cr.set_font_size(11)
        left, top, plot_width, plot_height = self._plot_bounds(width, height, cr)
        self.plot_bounds = (left, top, plot_width, plot_height)
        bottom = top + plot_height
        values = self._values()
        ceiling = self._ceiling()

        for tick in range(5):
            value = ceiling * tick / 4
            y = bottom - plot_height * tick / 4
            cr.set_source_rgba(*foreground, 0.12)
            cr.set_line_width(1)
            cr.move_to(left, y)
            cr.line_to(left + plot_width, y)
            cr.stroke()
            label = self._axis_label(value)
            cr.set_source_rgba(*foreground, 0.7)
            cr.move_to(left - cr.text_extents(label)[2] - 10, y + 4)
            cr.show_text(label)

        if values:
            count = len(values)
            line_chart = self._is_line()
            selected = self._selected_index()
            slot_width = plot_width / count
            points = [
                (left + (plot_width * (index / (count - 1) if count > 1 else 0.5)
                         if line_chart else slot_width * (index + 0.5)),
                 bottom - (value or 0) / ceiling * plot_height)
                for index, value in enumerate(values)
            ]
            for index, bucket in enumerate(self.series):
                if values[index] is None or bucket.break_before:
                    cr.set_source_rgba(*foreground, 0.055)
                    cr.rectangle(left + slot_width * index, top, slot_width, plot_height)
                    cr.fill()
            if line_chart:
                segments, segment = [], []
                for index, point in enumerate(points):
                    if values[index] is None or self.series[index].break_before:
                        if segment:
                            segments.append(segment)
                        segment = []
                    if values[index] is not None:
                        segment.append(point)
                if segment:
                    segments.append(segment)
                for segment in segments:
                    cr.move_to(segment[0][0], bottom)
                    for x, y in segment:
                        cr.line_to(x, y)
                    cr.line_to(segment[-1][0], bottom)
                    cr.close_path()
                    gradient = self.cairo.LinearGradient(0, top, 0, bottom)
                    gradient.add_color_stop_rgba(0, *accent, 0.3)
                    gradient.add_color_stop_rgba(1, *accent, 0.03)
                    cr.set_source(gradient)
                    cr.fill()
                    cr.move_to(*segment[0])
                    for x, y in segment[1:]:
                        cr.line_to(x, y)
                    cr.set_source_rgb(*accent)
                    cr.set_line_width(2.5)
                    cr.set_line_join(self.cairo.LINE_JOIN_ROUND)
                    cr.stroke()
                    if len(segment) == 1:
                        cr.arc(*segment[0], 2.5, 0, math.tau)
                        cr.fill()
            else:
                # Keep sparse bars compact and dense minute bars within their slots.
                bar_width = self._bar_width(slot_width)
                for index, (x, y) in enumerate(points):
                    if values[index] is None:
                        continue
                    if self.view == "cycle":
                        color = TOTAL_BAR_COLOR if self.series[index].break_before else DETAIL_BAR_COLOR
                        cr.set_source_rgb(*color)
                    else:
                        cr.set_source_rgba(*accent, 1 if index == selected else 0.75)
                    cr.rectangle(x - bar_width / 2, y, bar_width, bottom - y)
                    cr.fill()

            x, y = points[selected]
            if self.selected is not None:
                cr.set_source_rgba(*accent, 0.5)
                cr.set_line_width(1)
                cr.set_dash([3, 3])
                cr.move_to(x, top)
                cr.line_to(x, bottom)
                cr.stroke()
                cr.set_dash([])
            if line_chart and values[selected] is not None:
                cr.set_source_rgb(*accent)
                cr.arc(x, y, 4, 0, math.tau)
                cr.fill()
            # Space date labels evenly, including partial reset days.
            ticks = sorted({round(index * (count - 1) / min(4, count - 1))
                            for index in range(min(4, count - 1) + 1)}) if count > 1 else [0]
            cr.set_source_rgba(*foreground, 0.7)
            for index in ticks:
                label = self.series[index].at.strftime("%b %-d" if self.view == "cycle" else "%H:%M")
                cr.move_to(points[index][0] - cr.text_extents(label)[2] / 2, bottom + 22)
                cr.show_text(label)

        if not any(value is not None for value in values) or (not any(values) and self._mode() != "remaining"):
            message = self.chart_message
            cr.set_source_rgba(*foreground, 0.7)
            cr.move_to(left + (plot_width - cr.text_extents(message)[2]) / 2, top + plot_height / 2)
            cr.show_text(message)
        if area.has_focus():
            gtk.render_focus(style, cr, 0, 0, width, height)
        return False
