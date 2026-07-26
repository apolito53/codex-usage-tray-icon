using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;

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
            int? availableResetCredits,
            IList<ResetCreditSnapshot> resetCredits,
            string limitId,
            string limitName,
            DateTime checkedAtLocal)
        {
            UsedPercent = usedPercent;
            RemainingPercent = remainingPercent;
            ResetAtLocal = resetAtLocal;
            WindowDurationMinutes = windowDurationMinutes;
            IsWeekly = isWeekly;
            AvailableResetCredits = availableResetCredits;
            ResetCredits = new ReadOnlyCollection<ResetCreditSnapshot>(
                new List<ResetCreditSnapshot>(
                    resetCredits ?? new List<ResetCreditSnapshot>()));
            LimitId = limitId;
            LimitName = limitName;
            CheckedAtLocal = checkedAtLocal;
        }

        internal int UsedPercent { get; private set; }

        internal int RemainingPercent { get; private set; }

        internal DateTime? ResetAtLocal { get; private set; }

        internal long WindowDurationMinutes { get; private set; }

        internal bool IsWeekly { get; private set; }

        /// <summary>
        /// Free full-reset credits reported by Codex. A null value means the
        /// server did not provide a summary; zero is a real reported count.
        /// </summary>
        internal int? AvailableResetCredits { get; private set; }

        /// <summary>
        /// Individual expiry details can be shorter than AvailableResetCredits
        /// when the backend caps or omits the detail list.
        /// </summary>
        internal IList<ResetCreditSnapshot> ResetCredits { get; private set; }

        internal string LimitId { get; private set; }

        internal string LimitName { get; private set; }

        internal DateTime CheckedAtLocal { get; private set; }
    }
}
