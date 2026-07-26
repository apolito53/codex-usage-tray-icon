using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Automation;

namespace CodexUsageTray
{
    internal enum UsageNavigationResult
    {
        SettingsOnly,
        UsagePageOnly,
        UsageAndResetSection
    }

    /// <summary>
    /// Opens the supported Codex Settings deep link, then crosses the final
    /// gap with Windows accessibility. Codex currently exposes /settings/usage
    /// only as an internal renderer route; unsupported external settings paths
    /// deliberately fall back to General.
    ///
    /// This class invokes only the "Usage & billing" sidebar item and scrolls
    /// a text label into view. It never searches for or invokes a reset button.
    /// </summary>
    internal static class CodexNavigation
    {
        private const string SettingsUri = "codex://settings";
        private const string UsageButtonName = "Usage & billing";
        private const string ResetSectionName = "Usage limit resets";
        private const string ExpiryPrefix = "Expires ";
        private const int NavigationTimeoutMilliseconds = 10000;
        private const int PollIntervalMilliseconds = 100;

        private static readonly string[] CandidateProcessNames =
        {
            "ChatGPT",
            "Codex"
        };

        internal static Task<UsageNavigationResult> OpenUsageSettingsAsync()
        {
            return Task.Run(
                new Func<UsageNavigationResult>(OpenUsageSettings));
        }

        private static UsageNavigationResult OpenUsageSettings()
        {
            Process launchedProcess = Process.Start(
                new ProcessStartInfo
                {
                    FileName = SettingsUri,
                    UseShellExecute = true
                });

            if (launchedProcess != null)
            {
                launchedProcess.Dispose();
            }

            DateTime settingsDeadline = DateTime.UtcNow.AddMilliseconds(
                NavigationTimeoutMilliseconds);

            SettingsTarget target = null;
            while (DateTime.UtcNow < settingsDeadline)
            {
                target = TryFindSettingsTarget();
                if (target != null && TryInvoke(target.UsageButton))
                {
                    break;
                }

                target = null;
                Thread.Sleep(PollIntervalMilliseconds);
            }

            if (target == null)
            {
                return UsageNavigationResult.SettingsOnly;
            }

            bool usagePageOpened = false;
            DateTime usageDeadline = DateTime.UtcNow.AddMilliseconds(
                NavigationTimeoutMilliseconds);

            while (DateTime.UtcNow < usageDeadline)
            {
                AutomationElement root =
                    TryGetWindowRoot(target.WindowHandle) ?? target.Root;

                if (FindElement(
                    root,
                    UsageButtonName,
                    ControlType.Text) != null)
                {
                    usagePageOpened = true;
                }

                AutomationElement resetHeading = FindElement(
                    root,
                    ResetSectionName,
                    ControlType.Text);

                if (resetHeading != null)
                {
                    AutomationElement scrollTarget =
                        FindLastResetExpiry(root) ?? resetHeading;

                    return TryScrollIntoView(scrollTarget)
                        ? UsageNavigationResult.UsageAndResetSection
                        : UsageNavigationResult.UsagePageOnly;
                }

                Thread.Sleep(PollIntervalMilliseconds);
            }

            return usagePageOpened
                ? UsageNavigationResult.UsagePageOnly
                : UsageNavigationResult.SettingsOnly;
        }

        private static SettingsTarget TryFindSettingsTarget()
        {
            foreach (string processName in CandidateProcessNames)
            {
                Process[] processes = Process.GetProcessesByName(processName);
                foreach (Process process in processes)
                {
                    try
                    {
                        IntPtr windowHandle = process.MainWindowHandle;
                        AutomationElement root = TryGetWindowRoot(windowHandle);
                        if (root == null)
                        {
                            continue;
                        }

                        AutomationElement usageButton = FindElement(
                            root,
                            UsageButtonName,
                            ControlType.Button);

                        if (usageButton != null)
                        {
                            return new SettingsTarget(
                                windowHandle,
                                root,
                                usageButton);
                        }
                    }
                    finally
                    {
                        process.Dispose();
                    }
                }
            }

            return null;
        }

        private static AutomationElement TryGetWindowRoot(IntPtr windowHandle)
        {
            if (windowHandle == IntPtr.Zero)
            {
                return null;
            }

            try
            {
                return AutomationElement.FromHandle(windowHandle);
            }
            catch (Exception exception)
            {
                if (exception is InvalidOperationException ||
                    exception is ElementNotAvailableException)
                {
                    return null;
                }

                throw;
            }
        }

        private static AutomationElement FindElement(
            AutomationElement root,
            string name,
            ControlType controlType)
        {
            if (root == null)
            {
                return null;
            }

            try
            {
                var condition = new AndCondition(
                    new PropertyCondition(
                        AutomationElement.NameProperty,
                        name),
                    new PropertyCondition(
                        AutomationElement.ControlTypeProperty,
                        controlType));

                return root.FindFirst(TreeScope.Descendants, condition);
            }
            catch (Exception exception)
            {
                if (exception is InvalidOperationException ||
                    exception is ElementNotAvailableException)
                {
                    return null;
                }

                throw;
            }
        }

        private static AutomationElement FindLastResetExpiry(
            AutomationElement root)
        {
            if (root == null)
            {
                return null;
            }

            try
            {
                var textCondition = new PropertyCondition(
                    AutomationElement.ControlTypeProperty,
                    ControlType.Text);

                AutomationElementCollection textElements = root.FindAll(
                    TreeScope.Descendants,
                    textCondition);

                AutomationElement lastExpiry = null;
                for (int index = 0; index < textElements.Count; index++)
                {
                    AutomationElement candidate = textElements[index];
                    if (candidate.Current.Name.StartsWith(
                        ExpiryPrefix,
                        StringComparison.OrdinalIgnoreCase))
                    {
                        lastExpiry = candidate;
                    }
                }

                return lastExpiry;
            }
            catch (Exception exception)
            {
                if (exception is InvalidOperationException ||
                    exception is ElementNotAvailableException)
                {
                    return null;
                }

                throw;
            }
        }

        private static bool TryInvoke(AutomationElement element)
        {
            try
            {
                object pattern;
                if (!element.TryGetCurrentPattern(
                    InvokePattern.Pattern,
                    out pattern))
                {
                    return false;
                }

                ((InvokePattern)pattern).Invoke();
                return true;
            }
            catch (Exception exception)
            {
                if (exception is InvalidOperationException ||
                    exception is ElementNotAvailableException)
                {
                    return false;
                }

                throw;
            }
        }

        private static bool TryScrollIntoView(AutomationElement element)
        {
            try
            {
                object pattern;
                if (!element.TryGetCurrentPattern(
                    ScrollItemPattern.Pattern,
                    out pattern))
                {
                    return false;
                }

                ((ScrollItemPattern)pattern).ScrollIntoView();
                return true;
            }
            catch (Exception exception)
            {
                if (exception is InvalidOperationException ||
                    exception is ElementNotAvailableException)
                {
                    return false;
                }

                throw;
            }
        }

        private sealed class SettingsTarget
        {
            internal SettingsTarget(
                IntPtr windowHandle,
                AutomationElement root,
                AutomationElement usageButton)
            {
                WindowHandle = windowHandle;
                Root = root;
                UsageButton = usageButton;
            }

            internal IntPtr WindowHandle { get; private set; }

            internal AutomationElement Root { get; private set; }

            internal AutomationElement UsageButton { get; private set; }
        }
    }
}
