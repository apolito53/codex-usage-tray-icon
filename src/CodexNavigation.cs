using System.Diagnostics;

namespace CodexUsageTray
{
    /// <summary>
    /// Keeps desktop-app navigation in one deliberately tiny boundary.
    /// Codex officially registers the codex:// scheme; the current desktop
    /// bundle routes /settings/usage to its account usage page. If that
    /// internal page changes, only this URI should need repair.
    /// </summary>
    internal static class CodexNavigation
    {
        private const string UsageSettingsUri = "codex://settings/usage";

        internal static void OpenUsageSettings()
        {
            Process.Start(
                new ProcessStartInfo
                {
                    FileName = UsageSettingsUri,
                    UseShellExecute = true
                });
        }
    }
}

