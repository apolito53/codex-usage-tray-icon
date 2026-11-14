using System;

namespace CodexUsageTray
{
    // Optional independently reported quota window. A missing window is not
    // equivalent to 100% remaining or to the longest-window reading.
    internal sealed class UsageWindowSnapshot
    {
        internal UsageWindowSnapshot(int usedPercent, DateTime? resetAtLocal, long durationMinutes)
        {
            UsedPercent = Math.Max(0, Math.Min(100, usedPercent));
            RemainingPercent = 100 - UsedPercent;
            ResetAtLocal = resetAtLocal;
            DurationMinutes = durationMinutes;
        }

        internal int UsedPercent { get; private set; }
        internal int RemainingPercent { get; private set; }
        internal DateTime? ResetAtLocal { get; private set; }
        internal long DurationMinutes { get; private set; }
    }
}
