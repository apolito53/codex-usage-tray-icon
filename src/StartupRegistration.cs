using System;
using System.IO;
using System.Windows.Forms;
using Microsoft.Win32;

namespace CodexUsageTray
{
    internal static class StartupRegistration
    {
        private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string RunValueName = "CodexUsageTray";

        internal static bool IsEnabled()
        {
            using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKeyPath))
            {
                string value = key == null
                    ? null
                    : key.GetValue(RunValueName) as string;

                if (string.IsNullOrWhiteSpace(value))
                {
                    return false;
                }

                string registeredPath = value.Trim().Trim('"');
                try
                {
                    return Path.GetFullPath(registeredPath).Equals(
                        Path.GetFullPath(Application.ExecutablePath),
                        StringComparison.OrdinalIgnoreCase);
                }
                catch (Exception exception)
                {
                    if (exception is ArgumentException ||
                        exception is NotSupportedException ||
                        exception is PathTooLongException)
                    {
                        return false;
                    }

                    throw;
                }
            }
        }

        internal static void SetEnabled(bool enabled)
        {
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(RunKeyPath))
            {
                if (enabled)
                {
                    key.SetValue(
                        RunValueName,
                        "\"" + Application.ExecutablePath + "\"",
                        RegistryValueKind.String);
                }
                else
                {
                    key.DeleteValue(RunValueName, false);
                }
            }
        }
    }
}

