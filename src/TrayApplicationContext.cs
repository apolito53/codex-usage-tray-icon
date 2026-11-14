using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace CodexUsageTray
{
    internal sealed class TrayApplicationContext : ApplicationContext
    {
        private const int RefreshIntervalMilliseconds = 5 * 60 * 1000;
        private const int OfflineRetryIntervalMilliseconds = 60 * 1000;
        private const int SecondOfflineRetryIntervalMilliseconds = 2 * 60 * 1000;

        private readonly CodexUsageClient _usageClient;
        private readonly NotifyIcon _notifyIcon;
        private readonly Timer _refreshTimer;
        private readonly ToolStripMenuItem _summaryItem;
        private readonly ToolStripMenuItem _fiveHourItem;
        private readonly ToolStripMenuItem _fiveHourResetItem;
        private readonly ToolStripMenuItem _trayWindowMenuItem;
        private readonly ToolStripMenuItem _trayFiveHourChoiceItem;
        private readonly ToolStripMenuItem _trayWeeklyChoiceItem;
        private readonly ToolStripMenuItem _resetItem;
        private readonly ToolStripMenuItem _resetCreditsItem;
        private readonly ToolStripMenuItem _prepaidCreditsItem;
        private readonly ToolStripMenuItem _statusItem;
        private readonly ToolStripMenuItem _refreshItem;
        private readonly ToolStripMenuItem _startupItem;

        private string _trayWindowChoice;
        private UsageSnapshot _latestSnapshot;
        private Icon _currentIcon;
        private bool _refreshInProgress;
        private bool _navigationInProgress;
        private bool _disposed;
        private string _lastError;
        private int _consecutiveRefreshFailures;

        internal TrayApplicationContext()
        {
            _usageClient = new CodexUsageClient();
            _trayWindowChoice = TrayWindowPreference.Load();

            _summaryItem = new ToolStripMenuItem("Codex usage: loading…")
            {
                Enabled = false
            };
            _fiveHourItem = new ToolStripMenuItem("5-hour: loading…")
            {
                Enabled = false,
                Visible = false
            };
            _fiveHourResetItem = new ToolStripMenuItem("5-hour reset: loading…")
            {
                Enabled = false,
                Visible = false
            };
            _resetItem = new ToolStripMenuItem("Reset time: loading…")
            {
                Enabled = false
            };
            _resetCreditsItem = new ToolStripMenuItem("Free resets: loading…")
            {
                Enabled = false
            };
            _statusItem = new ToolStripMenuItem("Connection: loading…")
            {
                Enabled = false
            };
            _prepaidCreditsItem = new ToolStripMenuItem("Prepaid credits: loading…") { Enabled = false };
            _refreshItem = new ToolStripMenuItem("Refresh now");
            _startupItem = new ToolStripMenuItem("Start with Windows");

            _trayWindowMenuItem = new ToolStripMenuItem("Tray icon shows") { Visible = false };
            _trayFiveHourChoiceItem = new ToolStripMenuItem("5-hour allowance");
            _trayWeeklyChoiceItem = new ToolStripMenuItem("Weekly allowance");
            _trayWindowMenuItem.DropDownItems.Add(_trayFiveHourChoiceItem);
            _trayWindowMenuItem.DropDownItems.Add(_trayWeeklyChoiceItem);
            _trayFiveHourChoiceItem.Click += delegate { SelectTrayWindow(TrayWindowPreference.FiveHour); };
            _trayWeeklyChoiceItem.Click += delegate { SelectTrayWindow(TrayWindowPreference.Weekly); };
            UpdateTrayWindowChoiceMenu();

            _refreshItem.Click += delegate { RefreshUsageAsync(); };
            _startupItem.Click += ToggleStartup;

            var openLogsItem = new ToolStripMenuItem("Open diagnostic logs");
            openLogsItem.Click += OpenDiagnosticLogs;

            var exitItem = new ToolStripMenuItem("Exit");
            exitItem.Click += delegate { ExitThread(); };

            var menu = new ContextMenuStrip();
            menu.Items.Add(_fiveHourItem);
            menu.Items.Add(_fiveHourResetItem);
            menu.Items.Add(_summaryItem);
            menu.Items.Add(_resetItem);
            menu.Items.Add(_resetCreditsItem);
            menu.Items.Add(_prepaidCreditsItem);
            menu.Items.Add(_statusItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(_trayWindowMenuItem);
            menu.Items.Add(_refreshItem);
            menu.Items.Add(_startupItem);
            menu.Items.Add(openLogsItem);
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(exitItem);

            _currentIcon = TrayIconRenderer.CreateStatusIcon("?", Color.DimGray);
            _notifyIcon = new NotifyIcon
            {
                ContextMenuStrip = menu,
                Icon = _currentIcon,
                Text = "Codex usage: loading",
                Visible = true
            };
            _notifyIcon.MouseClick += OnTrayMouseClick;

            _startupItem.Checked = StartupRegistration.IsEnabled();

            _refreshTimer = new Timer
            {
                // The first short tick happens after Application.Run installs
                // the WinForms synchronization context. Later ticks use the
                // normal five-minute cadence.
                Interval = 250,
                Enabled = true
            };
            _refreshTimer.Tick += OnRefreshTimerTick;
        }

        private void OnRefreshTimerTick(object sender, EventArgs args)
        {
            if (_refreshTimer.Interval != RefreshIntervalMilliseconds)
            {
                _refreshTimer.Stop();
                _refreshTimer.Interval = RefreshIntervalMilliseconds;
                _refreshTimer.Start();
            }

            RefreshUsageAsync();
        }

        private async void RefreshUsageAsync()
        {
            if (_refreshInProgress || _disposed)
            {
                return;
            }

            _refreshInProgress = true;
            _refreshItem.Enabled = false;
            if (_latestSnapshot == null)
            {
                _summaryItem.Text = "Codex usage: refreshing…";
                _statusItem.Text = "Connecting…";
            }
            else
            {
                _summaryItem.Text = FormatUsageSummary(
                    _latestSnapshot,
                    " - checking…");
                _statusItem.Text = "Checking connection…";
            }

            try
            {
                UsageSnapshot snapshot = await _usageClient.GetWeeklyUsageAsync();
                if (_disposed)
                {
                    return;
                }

                _latestSnapshot = snapshot;
                _lastError = null;
                _consecutiveRefreshFailures = 0;
                ApplySnapshot(snapshot);
                AppLog.Info(
                    string.Format(
                        "Usage refreshed: {0}% remaining; reset {1}; free resets {2}; expiry details {3}.",
                        snapshot.RemainingPercent,
                        snapshot.ResetAtLocal.HasValue
                            ? snapshot.ResetAtLocal.Value.ToString("O")
                            : "unknown",
                        snapshot.AvailableResetCredits.HasValue
                            ? snapshot.AvailableResetCredits.Value.ToString()
                            : "unknown",
                        snapshot.ResetCredits.Count));
            }
            catch (Exception exception)
            {
                if (_disposed)
                {
                    return;
                }

                _lastError = exception.Message;
                _consecutiveRefreshFailures++;
                ApplyError(exception);
                AppLog.Error("Usage refresh failed.", exception);
            }
            finally
            {
                _refreshInProgress = false;
                if (!_disposed)
                {
                    _refreshItem.Enabled = true;
                }
            }
        }

        private void ApplySnapshot(UsageSnapshot snapshot)
        {
            _fiveHourItem.Visible = snapshot.IsPlus;
            _fiveHourResetItem.Visible = snapshot.IsPlus;
            _trayWindowMenuItem.Visible = snapshot.IsPlus;
            UpdateTrayWindowChoiceMenu();
            if (snapshot.IsPlus)
            {
                _fiveHourItem.Text = FormatFiveHourSummary(snapshot, string.Empty);
                _fiveHourResetItem.Text = FormatWindowReset(
                    "5-hour", snapshot.FiveHourWindow);
            }

            _summaryItem.Text = FormatUsageSummary(snapshot, string.Empty);
            _resetItem.Text = snapshot.IsPlus
                ? FormatWindowReset("Weekly", snapshot.WeeklyWindow)
                : snapshot.ResetAtLocal.HasValue
                    ? "Resets " + snapshot.ResetAtLocal.Value.ToString("ddd, MMM d 'at' h:mm tt")
                    : "Reset time unavailable";

            ApplyResetCreditMenu(snapshot);
            _prepaidCreditsItem.Text = PrepaidCredits.Format(snapshot.PrepaidCredits);

            _statusItem.Text =
                "Online - updated " +
                snapshot.CheckedAtLocal.ToString("h:mm:ss tt");

            string tooltip = snapshot.IsPlus
                ? string.Format(
                    "Codex Plus: 5h {0} | week {1} - {2}",
                    FormatWindowPercent(snapshot.FiveHourWindow),
                    FormatWindowPercent(snapshot.WeeklyWindow),
                    FormatResetCredits(snapshot.AvailableResetCredits))
                : string.Format(
                    "Codex: {0}% left - {1} - reset {2}",
                    snapshot.RemainingPercent,
                    FormatResetCredits(snapshot.AvailableResetCredits),
                    snapshot.ResetAtLocal.HasValue
                        ? snapshot.ResetAtLocal.Value.ToString("ddd h:mm tt")
                        : "unknown");

            _notifyIcon.Text = TruncateTooltip(tooltip);
            ReplaceSnapshotIcon(snapshot, false);
            SetRefreshTimerInterval(RefreshIntervalMilliseconds);
        }

        private void ApplyError(Exception exception)
        {
            int retryInterval = GetOfflineRetryIntervalMilliseconds();
            SetRefreshTimerInterval(retryInterval);

            if (_latestSnapshot != null)
            {
                _prepaidCreditsItem.Text = PrepaidCredits.Format(_latestSnapshot.PrepaidCredits) + " - STALE";
                _summaryItem.Text = FormatUsageSummary(
                    _latestSnapshot,
                    " - STALE");
                if (_latestSnapshot.IsPlus)
                {
                    _fiveHourItem.Text = FormatFiveHourSummary(
                        _latestSnapshot, " - STALE");
                    _fiveHourResetItem.Text = FormatWindowReset(
                        "5-hour", _latestSnapshot.FiveHourWindow) + " - STALE";
                }
                _statusItem.Text = string.Format(
                    "OFFLINE - showing {0} reading",
                    _latestSnapshot.CheckedAtLocal.ToString("h:mm:ss tt"));
                _notifyIcon.Text = TruncateTooltip(
                    _latestSnapshot.IsPlus
                        ? string.Format(
                            "Codex Plus: 5h {0} | week {1} - OFFLINE",
                            FormatWindowPercent(_latestSnapshot.FiveHourWindow),
                            FormatWindowPercent(_latestSnapshot.WeeklyWindow))
                        : string.Format(
                            "Codex: {0}% left - OFFLINE - last {1}",
                            _latestSnapshot.RemainingPercent,
                            _latestSnapshot.CheckedAtLocal.ToString("h:mm tt")));
                ReplaceSnapshotIcon(_latestSnapshot, true);
                AppLog.Info(
                    string.Format(
                        "Showing last successful reading from {0:O}; retrying in {1} minute(s).",
                        _latestSnapshot.CheckedAtLocal,
                        retryInterval / (60 * 1000)));
                return;
            }

            _fiveHourItem.Visible = false;
            _fiveHourResetItem.Visible = false;
            _trayWindowMenuItem.Visible = false;
            _summaryItem.Text = "Codex usage unavailable";
            _prepaidCreditsItem.Text = PrepaidCredits.Format(null);
            _resetItem.Text = FriendlyError(exception);
            ClearResetCreditMenu();
            _resetCreditsItem.Text = "Free reset count unavailable";
            _resetCreditsItem.Enabled = false;
            _statusItem.Text =
                "OFFLINE - last attempt " +
                DateTime.Now.ToString("h:mm:ss tt");
            _notifyIcon.Text = TruncateTooltip("Codex usage unavailable - click for details");
            ReplaceIcon("!", Color.FromArgb(207, 34, 46));
        }

        private void OnTrayMouseClick(object sender, MouseEventArgs args)
        {
            if (args.Button != MouseButtons.Left)
            {
                return;
            }

            if (_latestSnapshot != null)
            {
                bool isOffline = !string.IsNullOrWhiteSpace(_lastError);
                if (_latestSnapshot.IsPlus)
                {
                    ShowBalloon(
                        isOffline ? "Codex Plus usage - offline" : "Codex Plus usage",
                        string.Format(
                            "{0}\\n{1}\\n{2}\\n{3}\\n{4}\\n{5}{6}",
                            FormatFiveHourSummary(_latestSnapshot, string.Empty),
                            FormatWindowReset("5-hour", _latestSnapshot.FiveHourWindow),
                            FormatUsageSummary(_latestSnapshot, string.Empty),
                            FormatWindowReset("Weekly", _latestSnapshot.WeeklyWindow),
                            FormatResetCredits(_latestSnapshot.AvailableResetCredits),
                            PrepaidCredits.Format(_latestSnapshot.PrepaidCredits),
                            isOffline ? "\\nOFFLINE - last successful reading" : string.Empty),
                        isOffline ? ToolTipIcon.Warning : ToolTipIcon.Info);
                    return;
                }

                string resetText = _latestSnapshot.ResetAtLocal.HasValue
                    ? "Resets " + _latestSnapshot.ResetAtLocal.Value.ToString(
                        "dddd, MMMM d 'at' h:mm tt")
                    : "Reset time unavailable";
                string resetCreditsText = _latestSnapshot.AvailableResetCredits.HasValue
                    ? string.Format(
                        "{0} free reset{1} available",
                        _latestSnapshot.AvailableResetCredits.Value,
                        _latestSnapshot.AvailableResetCredits.Value == 1 ? string.Empty : "s")
                    : "Free reset count unavailable";
                string offlineText = isOffline
                    ? "\nConnection is down; showing the reading from " +
                      _latestSnapshot.CheckedAtLocal.ToString("h:mm:ss tt") + "."
                    : string.Empty;

                ShowBalloon(
                    isOffline
                        ? "Codex usage - offline"
                        : "Codex weekly usage",
                    string.Format(
                        "{0}% left ({1}% used)\n{2}\n{3}\n{5}{4}",
                        _latestSnapshot.RemainingPercent,
                        _latestSnapshot.UsedPercent,
                        resetText,
                        resetCreditsText,
                        offlineText,
                        PrepaidCredits.Format(_latestSnapshot.PrepaidCredits)),
                    isOffline ? ToolTipIcon.Warning : ToolTipIcon.Info);
            }
            else
            {
                ShowBalloon(
                    "Codex usage unavailable",
                    string.IsNullOrWhiteSpace(_lastError)
                        ? "Still loading. Right-click to refresh."
                        : _lastError,
                    ToolTipIcon.Warning);
            }
        }

        private void ToggleStartup(object sender, EventArgs args)
        {
            bool shouldEnable = !_startupItem.Checked;
            try
            {
                StartupRegistration.SetEnabled(shouldEnable);
                _startupItem.Checked = StartupRegistration.IsEnabled();
                AppLog.Info(
                    "Start with Windows set to " +
                    (_startupItem.Checked ? "on." : "off."));
            }
            catch (Exception exception)
            {
                AppLog.Error("Could not change startup registration.", exception);
                ShowBalloon(
                    "Startup setting failed",
                    exception.Message,
                    ToolTipIcon.Error);
            }
        }

        private static void OpenDiagnosticLogs(object sender, EventArgs args)
        {
            try
            {
                Directory.CreateDirectory(AppLog.LogDirectory);
                Process.Start("explorer.exe", "\"" + AppLog.LogDirectory + "\"");
            }
            catch (Exception exception)
            {
                AppLog.Error("Could not open the diagnostic log folder.", exception);
                MessageBox.Show(
                    exception.Message,
                    "Codex Usage Tray",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
        }

        private void ShowBalloon(string title, string text, ToolTipIcon icon)
        {
            _notifyIcon.BalloonTipTitle = title;
            _notifyIcon.BalloonTipText = text;
            _notifyIcon.BalloonTipIcon = icon;
            _notifyIcon.ShowBalloonTip(5000);
        }

        private void ReplaceIcon(string text, Color color)
        {
            ReplaceIcon(text, color, false);
        }

        private void ReplaceIcon(
            string text,
            Color color,
            bool showOfflineBadge)
        {
            Icon nextIcon = TrayIconRenderer.CreateStatusIcon(
                text,
                color,
                showOfflineBadge);
            Icon previousIcon = _currentIcon;

            _currentIcon = nextIcon;
            _notifyIcon.Icon = nextIcon;

            if (previousIcon != null)
            {
                previousIcon.Dispose();
            }
        }

        private void ReplaceSnapshotIcon(UsageSnapshot snapshot, bool showOfflineBadge)
        {
            // Pro always keeps the original long-window icon.
            if (!snapshot.IsPlus)
            {
                ReplaceIcon(
                    snapshot.RemainingPercent.ToString(),
                    GetUsageColor(snapshot.RemainingPercent),
                    showOfflineBadge);
                return;
            }

            UsageWindowSnapshot selected = GetSelectedTrayWindow(snapshot);
            ReplaceIcon(
                selected == null ? "??" : selected.RemainingPercent.ToString(),
                selected == null ? Color.DimGray : GetUsageColor(selected.RemainingPercent),
                showOfflineBadge);
        }

        private UsageWindowSnapshot GetSelectedTrayWindow(UsageSnapshot snapshot)
        {
            return string.Equals(_trayWindowChoice, TrayWindowPreference.Weekly,
                StringComparison.OrdinalIgnoreCase)
                ? snapshot.WeeklyWindow
                : snapshot.FiveHourWindow;
        }

        private void UpdateTrayWindowChoiceMenu()
        {
            _trayFiveHourChoiceItem.Checked =
                !string.Equals(_trayWindowChoice, TrayWindowPreference.Weekly,
                    StringComparison.OrdinalIgnoreCase);
            _trayWeeklyChoiceItem.Checked = !_trayFiveHourChoiceItem.Checked;
        }

        private void SelectTrayWindow(string choice)
        {
            _trayWindowChoice = TrayWindowPreference.Normalize(choice);
            UpdateTrayWindowChoiceMenu();
            try
            {
                TrayWindowPreference.Save(_trayWindowChoice);
            }
            catch (Exception exception)
            {
                AppLog.Error("Could not save tray window preference.", exception);
            }

            if (_latestSnapshot != null && _latestSnapshot.IsPlus)
            {
                // Switch immediately, without waiting for the next API poll.
                ReplaceSnapshotIcon(_latestSnapshot, !string.IsNullOrWhiteSpace(_lastError));
            }
        }

        private void SetRefreshTimerInterval(int intervalMilliseconds)
        {
            if (_disposed)
            {
                return;
            }

            // Treat the interval as "time after this completed attempt." This
            // also prevents a manual refresh from inheriting an almost-expired
            // offline retry timer and immediately launching another request.
            _refreshTimer.Stop();
            _refreshTimer.Interval = intervalMilliseconds;
            _refreshTimer.Start();
        }

        private int GetOfflineRetryIntervalMilliseconds()
        {
            if (_consecutiveRefreshFailures <= 1)
            {
                return OfflineRetryIntervalMilliseconds;
            }

            if (_consecutiveRefreshFailures == 2)
            {
                return SecondOfflineRetryIntervalMilliseconds;
            }

            return RefreshIntervalMilliseconds;
        }

        private static string FormatUsageSummary(
            UsageSnapshot snapshot,
            string suffix)
        {
            if (snapshot.IsPlus)
            {
                return "Weekly: " + FormatWindowPercent(snapshot.WeeklyWindow) +
                    " left" + suffix;
            }

            return string.Format(
                "Codex: {0}% {1} left{2}",
                snapshot.RemainingPercent,
                snapshot.IsWeekly ? "weekly" : "longest-window",
                suffix);
        }

        private static string FormatFiveHourSummary(UsageSnapshot snapshot, string suffix)
        {
            return "5-hour: " + FormatWindowPercent(snapshot.FiveHourWindow) +
                " left" + suffix;
        }

        private static string FormatWindowPercent(UsageWindowSnapshot window)
        {
            return window == null ? "unavailable" : window.RemainingPercent + "%";
        }

        private static string FormatWindowReset(string label, UsageWindowSnapshot window)
        {
            return label + " resets " +
                (window != null && window.ResetAtLocal.HasValue
                    ? window.ResetAtLocal.Value.ToString("ddd, MMM d 'at' h:mm tt")
                    : "unavailable");
        }

        private static Color GetUsageColor(int remainingPercent)
        {
            if (remainingPercent > 50)
            {
                return Color.FromArgb(45, 164, 78);
            }

            if (remainingPercent > 20)
            {
                return Color.FromArgb(210, 153, 34);
            }

            return Color.FromArgb(207, 34, 46);
        }

        private static string FormatResetCredits(int? availableResetCredits)
        {
            if (!availableResetCredits.HasValue)
            {
                return "resets unknown";
            }

            return string.Format(
                "{0} free reset{1}",
                availableResetCredits.Value,
                availableResetCredits.Value == 1 ? string.Empty : "s");
        }

        private void ApplyResetCreditMenu(UsageSnapshot snapshot)
        {
            ClearResetCreditMenu();

            _resetCreditsItem.Text = snapshot.AvailableResetCredits.HasValue
                ? string.Format(
                    "Free resets available: {0}",
                    snapshot.AvailableResetCredits.Value)
                : "Free reset count unavailable";

            for (int index = 0; index < snapshot.ResetCredits.Count; index++)
            {
                ResetCreditSnapshot credit = snapshot.ResetCredits[index];
                string expiry = credit.ExpiresAtLocal.HasValue
                    ? credit.ExpiresAtLocal.Value.ToString(
                        "ddd, MMM d 'at' h:mm tt")
                    : "unavailable";

                _resetCreditsItem.DropDownItems.Add(
                    CreateResetCreditMenuItem(
                        string.Format(
                            "Reset {0}: expires {1}",
                            index + 1,
                            expiry)));
            }

            int knownCount = snapshot.AvailableResetCredits ?? snapshot.ResetCredits.Count;
            int missingDetailCount = Math.Max(
                0,
                knownCount - snapshot.ResetCredits.Count);

            if (missingDetailCount > 0)
            {
                _resetCreditsItem.DropDownItems.Add(
                    CreateResetCreditMenuItem(
                        string.Format(
                            "{0} more reset{1}: expiry unavailable",
                            missingDetailCount,
                            missingDetailCount == 1 ? string.Empty : "s")));
            }

            _resetCreditsItem.Enabled = _resetCreditsItem.DropDownItems.Count > 0;
        }

        private ToolStripMenuItem CreateResetCreditMenuItem(string text)
        {
            var item = new ToolStripMenuItem(text)
            {
                ToolTipText = "Open Codex usage settings"
            };
            item.Click += OpenCodexUsageSettings;
            return item;
        }

        private async void OpenCodexUsageSettings(object sender, EventArgs args)
        {
            if (_navigationInProgress || _disposed)
            {
                return;
            }

            _navigationInProgress = true;

            try
            {
                UsageNavigationResult result =
                    await CodexNavigation.OpenUsageSettingsAsync();

                if (_disposed)
                {
                    return;
                }

                if (result == UsageNavigationResult.UsageAndResetSection)
                {
                    AppLog.Info(
                        "Opened Codex Usage & billing at the reset section.");
                    return;
                }

                if (result == UsageNavigationResult.UsagePageOnly)
                {
                    AppLog.Info(
                        "Opened Codex Usage & billing, but could not focus the reset section.");
                    ShowBalloon(
                        "Opened Usage & billing",
                        "The reset list could not be focused automatically.",
                        ToolTipIcon.Warning);
                    return;
                }

                AppLog.Info(
                    "Opened Codex Settings, but could not select Usage & billing.");
                ShowBalloon(
                    "Opened Codex Settings",
                    "Choose Usage & billing from the sidebar.",
                    ToolTipIcon.Warning);
            }
            catch (Exception exception)
            {
                AppLog.Error("Could not open Codex usage settings.", exception);
                if (!_disposed)
                {
                    ShowBalloon(
                        "Could not open Codex",
                        exception.Message,
                        ToolTipIcon.Error);
                }
            }
            finally
            {
                _navigationInProgress = false;
            }
        }

        private void ClearResetCreditMenu()
        {
            while (_resetCreditsItem.DropDownItems.Count > 0)
            {
                ToolStripItem detailItem = _resetCreditsItem.DropDownItems[0];
                _resetCreditsItem.DropDownItems.RemoveAt(0);
                detailItem.Dispose();
            }
        }

        private static string FriendlyError(Exception exception)
        {
            string message = exception.Message;
            return message.Length <= 80
                ? message
                : message.Substring(0, 77) + "…";
        }

        private static string TruncateTooltip(string text)
        {
            // NotifyIcon.Text throws when the value exceeds 63 characters on
            // .NET Framework, so keep the failure path boring and explicit.
            return text.Length <= 63 ? text : text.Substring(0, 63);
        }

        protected override void ExitThreadCore()
        {
            _disposed = true;
            _refreshTimer.Stop();
            _refreshTimer.Dispose();

            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();

            if (_currentIcon != null)
            {
                _currentIcon.Dispose();
                _currentIcon = null;
            }

            AppLog.Info("Codex Usage Tray exiting.");
            base.ExitThreadCore();
        }
    }
}
