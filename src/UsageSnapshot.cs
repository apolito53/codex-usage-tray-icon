using System;

namespace CodexUsageTray
{
    internal sealed class UsageSnapshot
    {
        internal UsageSnapshot(
            int usedPercent,
            int remainingPercent,
            DateTime? resetAtLocal,
            long windowDurationMinutes,
            bool isWeekly,
            string limitId,
            string limitName,
            DateTime checkedAtLocal)
        {
            UsedPercent = usedPercent;
            RemainingPercent = remainingPercent;
            ResetAtLocal = resetAtLocal;
            WindowDurationMinutes = windowDurationMinutes;
            IsWeekly = isWeekly;
            LimitId = limitId;
            LimitName = limitName;
            CheckedAtLocal = checkedAtLocal;
        }

        internal int UsedPercent { get; private set; }

        internal int RemainingPercent { get; private set; }

        internal DateTime? ResetAtLocal { get; private set; }

        internal long WindowDurationMinutes { get; private set; }

        internal bool IsWeekly { get; private set; }

        internal string LimitId { get; private set; }

        internal string LimitName { get; private set; }

        internal DateTime CheckedAtLocal { get; private set; }
    }
}
