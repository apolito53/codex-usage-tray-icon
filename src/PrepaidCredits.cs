using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace CodexUsageTray
{
    internal sealed class PrepaidCredits
    {
        internal string Balance { get; private set; }
        internal bool Unlimited { get; private set; }

        internal static PrepaidCredits Parse(object value)
        {
            var credits = value as Dictionary<string, object>;
            if (credits == null) return null;
            object unlimited;
            if (credits.TryGetValue("unlimited", out unlimited) &&
                unlimited is bool && (bool)unlimited)
                return new PrepaidCredits { Unlimited = true };

            object raw;
            string balance = credits.TryGetValue("balance", out raw) ? raw as string : null;
            if (balance == null) return null;
            balance = balance.Trim();
            // Keep the server's decimal precision; credits are not currency.
            if (!Regex.IsMatch(balance, @"\A-?[0-9]+(?:\.[0-9]+)?\z")) return null;
            return new PrepaidCredits { Balance = balance };
        }

        internal static string Format(PrepaidCredits credits)
        {
            if (credits == null) return "Prepaid credits: unavailable";
            return "Prepaid credits: " + (credits.Unlimited ? "unlimited" : credits.Balance);
        }
    }
}
