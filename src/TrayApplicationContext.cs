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

        private readonly CodexUsageClient _usageClient;
        private readonly NotifyIcon _notifyIcon;
        private readonly Timer _refreshTimer;
        private readonly ToolStripMenuItem _summaryItem;
        private readonly ToolStripMenuItem _resetItem;
        private readonly ToolStripMenuItem _checkedItem;
        private readonly ToolStripMenuItem _refreshItem;
        private readonly ToolStripMenuItem _startupItem;

        private UsageSnapshot _latestSnapshot;
        private Icon _currentIcon;
        private bool _refreshInProgress;
        private bool _disposed;
        private string _lastError;

        internal TrayApplicationContext()
        {
            _usageClient = new CodexUsageClient();

            _summaryItem = new ToolStripMenuItem("Codex usage: loading…")
            {
                Enabled = false
            };
            _resetItem = new ToolStripMenuItem("Reset time: loading…")
            {
                Enabled = false
            };
            _checkedItem = new ToolStripMenuItem("Last checked: —")
            {
                Enabled = false
            };
            _refreshItem = new ToolStripMenuItem("Refresh now");
            _startupItem = new ToolStripMenuItem("Start with Windows");

            _refreshItem.Click += delegate { RefreshUsageAsync(); };
            _startupItem.Click += ToggleStartup;

            var openLogsItem = new ToolStripMenuItem("Open diagnostic logs");
            openLogsItem.Click += OpenDiagnosticLogs;

            var exitItem = new ToolStripMenuItem("Exit");
            exitItem.Click += delegate { ExitThread(); };

            var menu = new ContextMenuStrip();
            menu.Items.Add(_summaryItem);
            menu.Items.Add(_resetItem);
            menu.Items.Add(_checkedItem);
            menu.Items.Add(new ToolStripSeparator());
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
            _summaryItem.Text = "Codex usage: refreshing…";

            try
            {
                UsageSnapshot snapshot = await _usageClient.GetWeeklyUsageAsync();
                if (_disposed)
                {
                    return;
                }

                _latestSnapshot = snapshot;
                _lastError = null;
                ApplySnapshot(snapshot);
                AppLog.Info(
                    string.Format(
                        "Usage refreshed: {0}% remaining; reset {1}.",
                        snapshot.RemainingPercent,
                        snapshot.ResetAtLocal.HasValue
                            ? snapshot.ResetAtLocal.Value.ToString("O")
                            : "unknown"));
            }
            catch (Exception exception)
            {
                if (_disposed)
                {
                    return;
                }

                _lastError = exception.Message;
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
            string windowLabel = snapshot.IsWeekly ? "weekly" : "longest-window";
            _summaryItem.Text = string.Format(
                "Codex: {0}% {1} left",
                snapshot.RemainingPercent,
                windowLabel);

            _resetItem.Text = snapshot.ResetAtLocal.HasValue
                ? "Resets " + snapshot.ResetAtLocal.Value.ToString("ddd, MMM d 'at' h:mm tt")
                : "Reset time unavailable";

            _checkedItem.Text = "Last checked " + snapshot.CheckedAtLocal.ToString("h:mm:ss tt");

            string tooltip = string.Format(
                "Codex: {0}% left - reset {1}",
                snapshot.RemainingPercent,
                snapshot.ResetAtLocal.HasValue
                    ? snapshot.ResetAtLocal.Value.ToString("ddd h:mm tt")
                    : "unknown");

            _notifyIcon.Text = TruncateTooltip(tooltip);
            ReplaceIcon(
                snapshot.RemainingPercent.ToString(),
                GetUsageColor(snapshot.RemainingPercent));
        }

        private void ApplyError(Exception exception)
        {
            _summaryItem.Text = "Codex usage unavailable";
            _resetItem.Text = FriendlyError(exception);
            _checkedItem.Text = "Last attempt " + DateTime.Now.ToString("h:mm:ss tt");
            _notifyIcon.Text = TruncateTooltip("Codex usage unavailable - click for details");
            ReplaceIcon("!", Color.FromArgb(207, 34, 46));
        }

        private void OnTrayMouseClick(object sender, MouseEventArgs args)
        {
            if (args.Button != MouseButtons.Left)
            {
                return;
            }

            if (_latestSnapshot != null && string.IsNullOrWhiteSpace(_lastError))
            {
                string resetText = _latestSnapshot.ResetAtLocal.HasValue
                    ? "Resets " + _latestSnapshot.ResetAtLocal.Value.ToString(
                        "dddd, MMMM d 'at' h:mm tt")
                    : "Reset time unavailable";

                ShowBalloon(
                    "Codex weekly usage",
                    string.Format(
                        "{0}% left ({1}% used)\n{2}",
                        _latestSnapshot.RemainingPercent,
                        _latestSnapshot.UsedPercent,
                        resetText),
                    ToolTipIcon.Info);
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
            Icon nextIcon = TrayIconRenderer.CreateStatusIcon(text, color);
            Icon previousIcon = _currentIcon;

            _currentIcon = nextIcon;
            _notifyIcon.Icon = nextIcon;

            if (previousIcon != null)
            {
                previousIcon.Dispose();
            }
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
