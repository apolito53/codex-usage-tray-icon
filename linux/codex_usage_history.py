"""Private local allowance samples and chart math; no GTK or account data."""

import bisect
from dataclasses import dataclass
from datetime import datetime, time, timedelta
import math
import os
from pathlib import Path
import sqlite3
from typing import Optional


RETENTION_DAYS = 90
MAX_SAMPLE_GAP_SECONDS = 150
INTERVAL_MINUTES = (60, 30, 15, 5, 1)


@dataclass(frozen=True)
class Sample:
    at: float
    used: float
    reset_at: Optional[float]
    window_minutes: int
    break_before: bool


@dataclass(frozen=True)
class AllowanceCycle:
    start: datetime
    end: datetime
    samples: tuple


def allowance_cycles(samples):
    """Group reported reset windows; outages and restarts are not resets."""
    groups = []
    previous = None
    for sample in samples:
        reset_changed = (previous is not None and sample.reset_at is not None
                         and previous.reset_at is not None
                         and sample.reset_at != previous.reset_at)
        reset = (previous is None or reset_changed or sample.used < previous.used
                 or sample.window_minutes != previous.window_minutes)
        if reset:
            duration = (sample.window_minutes or 10080) * 60
            reported_start = sample.reset_at - duration if sample.reset_at is not None else None
            # The reported deadline identifies an existing cycle even if we
            # started recording partway through it. A counter reset without a
            # new deadline starts a fresh window at the first reset reading.
            if (reported_start is not None and reported_start <= sample.at < sample.reset_at
                    and (previous is None or reported_start > previous.at)):
                start, end = reported_start, sample.reset_at
            else:
                start, end = sample.at, sample.at + duration
            if groups:
                groups[-1][1] = min(groups[-1][1], start)
            groups.append([start, end, []])
        groups[-1][2].append(sample)
        previous = sample
    return tuple(AllowanceCycle(
        datetime.fromtimestamp(start).astimezone(), datetime.fromtimestamp(end).astimezone(),
        tuple(rows),
    ) for start, end, rows in groups)


def connected(previous, sample):
    """A counter difference is meaningful only within one observed window."""
    return (
        previous is not None
        and not sample.break_before
        and 0 < sample.at - previous.at <= MAX_SAMPLE_GAP_SECONDS
        and sample.reset_at == previous.reset_at
        and sample.window_minutes == previous.window_minutes
        and sample.used >= previous.used
    )


class UsageHistory:
    def __init__(self, path):
        self.path = Path(path).absolute()
        # Even a quick restart leaves an unobserved interval.
        self._break_before = True

    def mark_gap(self):
        self._break_before = True

    def record(self, snapshot):
        used = snapshot.measured_used_percent
        if used is None:
            used = snapshot.used_percent
        at = snapshot.checked_at_local.timestamp()
        if not math.isfinite(used) or not 0 <= used <= 100 or not math.isfinite(at):
            raise ValueError("Invalid usage sample")
        reset_at = snapshot.reset_at_local.timestamp() if snapshot.reset_at_local else None
        sample = Sample(at, used, reset_at, snapshot.window_duration_minutes, self._break_before)
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path.parent.chmod(0o700)
        # Create the database privately before SQLite creates any journal files.
        fd = os.open(str(self.path), os.O_CREAT | os.O_WRONLY, 0o600)
        os.close(fd)
        self.path.chmod(0o600)
        connection = sqlite3.connect(str(self.path), timeout=2)
        try:
            with connection:
                connection.execute("""
                    CREATE TABLE IF NOT EXISTS samples (
                        at REAL PRIMARY KEY, used REAL NOT NULL,
                        reset_at REAL, window_minutes INTEGER NOT NULL,
                        break_before INTEGER NOT NULL
                    )
                """)
                row = connection.execute(
                    "SELECT * FROM samples ORDER BY at DESC LIMIT 1"
                ).fetchone()
                previous = Sample(*row) if row else None
                if previous is not None and at <= previous.at:
                    self.mark_gap()
                    return False
                connection.execute(
                    "INSERT INTO samples VALUES (?, ?, ?, ?, ?)",
                    (at, used, reset_at, sample.window_minutes, int(not connected(previous, sample))),
                )
                connection.execute(
                    "DELETE FROM samples WHERE at < ?", (at - RETENTION_DAYS * 86400,)
                )
            self._break_before = False
            return True
        finally:
            connection.close()

    def read(self, now=None):
        if not self.path.exists():
            return ()
        now = datetime.now().timestamp() if now is None else now
        connection = sqlite3.connect(self.path.as_uri() + "?mode=ro", uri=True, timeout=2)
        try:
            return tuple(Sample(*row) for row in connection.execute(
                "SELECT * FROM samples WHERE at >= ? AND at <= ? ORDER BY at",
                (now - RETENTION_DAYS * 86400, now),
            ))
        finally:
            connection.close()


@dataclass(frozen=True)
class Bucket:
    at: datetime
    end: datetime
    points: Optional[float]
    seconds: float
    break_before: bool
    has_samples: bool
    remaining: Optional[float] = None
    checked_at: Optional[datetime] = None

    @property
    def rate(self):
        return self.points * 60 / self.seconds if self.seconds else None


def local_midnight(day):
    # Resolve each midnight separately so 23/25-hour DST days stay correct.
    return datetime.combine(day, time()).astimezone()


def bucket_samples(samples, start, end, minutes=None, include_end=False):
    """Assign increases to the poll that observed them, without interpolation.

    Empty buckets are unknown, not zero. Rate denominators use actual elapsed
    polling time, including jitter and manual refreshes. A reset, recovery, or
    restart contributes no delta. Coarser buckets retain a visible gap marker.
    """
    if minutes is not None and minutes not in INTERVAL_MINUTES:
        raise ValueError("Unsupported chart interval")
    start_at, end_at = start.timestamp(), end.timestamp()
    if end_at < start_at or (end_at == start_at and not include_end):
        return ()
    boundaries = [start_at]
    while boundaries[-1] < end_at:
        if minutes is None:
            day = datetime.fromtimestamp(boundaries[-1]).date() + timedelta(days=1)
            next_at = local_midnight(day).timestamp()
        else:
            next_at = boundaries[-1] + minutes * 60
        boundaries.append(min(next_at, end_at))
    # At an exact boundary, a live reading belongs to the new, incomplete
    # bucket, just as it will after the next refresh. Never move it backwards.
    end_is_boundary = (end_at == local_midnight(end.astimezone().date()).timestamp()
                       if minutes is None else (end_at - start_at) % (minutes * 60) == 0)
    if include_end and end_is_boundary:
        boundaries.append(end_at)
    count = len(boundaries) - 1
    points, seconds = [0.0] * count, [0.0] * count
    covered = [0.0] * count
    gaps, seen = [False] * count, [False] * count
    remaining, checked_at = [None] * count, [None] * count
    previous = None
    for sample in samples:
        if sample.at > end_at or (sample.at == end_at and not include_end):
            break
        index = bisect.bisect_right(boundaries, sample.at) - 1
        # A sample exactly at the displayed cutoff belongs to the final bucket.
        if sample.at == end_at and include_end:
            index = count - 1
        if 0 <= index < count:
            seen[index] = True
            remaining[index] = 100 - sample.used
            checked_at[index] = datetime.fromtimestamp(sample.at).astimezone()
            if connected(previous, sample):
                points[index] += sample.used - previous.used
                seconds[index] += sample.at - previous.at
                first = max(0, bisect.bisect_right(boundaries, previous.at) - 1)
                for slot in range(first, index + 1):
                    covered[slot] += max(0, min(sample.at, boundaries[slot + 1])
                                         - max(previous.at, boundaries[slot]))
            else:
                gaps[index] = True
        previous = sample
    return tuple(Bucket(
        datetime.fromtimestamp(boundaries[i]).astimezone(),
        datetime.fromtimestamp(boundaries[i + 1]).astimezone(),
        points[i] if seconds[i] else None, seconds[i],
        gaps[i] or boundaries[i + 1] - boundaries[i] - covered[i] > MAX_SAMPLE_GAP_SECONDS,
        seen[i], remaining[i], checked_at[i],
    ) for i in range(count))


def chart_values(buckets, cumulative=False, rate=False, remaining=False):
    total = 0.0
    values = []
    for bucket in buckets:
        if remaining:
            values.append(bucket.remaining)
        elif bucket.points is None:
            values.append(None)
        elif cumulative:
            total += bucket.points
            values.append(total)
        else:
            values.append(bucket.rate if rate else bucket.points)
    return values


def chart_scale(values):
    peak = max((value for value in values if value is not None), default=0.0)
    target = max(peak, 0.000004) / 4 if peak else 0.25
    magnitude = 10 ** math.floor(math.log10(target))
    step = next(size * magnitude for size in (1, 2, 5, 10) if size * magnitude >= target)
    return step * 4
