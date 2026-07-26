using System;
using System.IO;

namespace CodexUsageTray
{
    /// <summary>
    /// Keeps a deliberately small local breadcrumb trail. Raw app-server
    /// payloads are never logged because they can gain sensitive fields as
    /// the protocol evolves.
    /// </summary>
    internal static class AppLog
    {
        private const long MaximumLogBytes = 1024 * 1024;
        private static readonly object SyncRoot = new object();

        internal static string LogDirectory
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "CodexUsageTray",
                    "logs");
            }
        }

        internal static string LogPath
        {
            get { return Path.Combine(LogDirectory, "usage-tray.log"); }
        }

        internal static void Info(string message)
        {
            Write("INFO", message);
        }

        internal static void Error(string message, Exception exception)
        {
            string detail = exception == null
                ? message
                : message + " " + exception.GetType().Name + ": " + exception.Message;

            Write("ERROR", detail);
        }

        private static void Write(string level, string message)
        {
            try
            {
                lock (SyncRoot)
                {
                    Directory.CreateDirectory(LogDirectory);
                    RotateIfNeeded();

                    string line = string.Format(
                        "{0:O} [{1}] {2}{3}",
                        DateTimeOffset.Now,
                        level,
                        message,
                        Environment.NewLine);

                    File.AppendAllText(LogPath, line);
                }
            }
            catch
            {
                // A tray utility must not crash merely because diagnostics
                // cannot be written.
            }
        }

        private static void RotateIfNeeded()
        {
            if (!File.Exists(LogPath) || new FileInfo(LogPath).Length < MaximumLogBytes)
            {
                return;
            }

            string previousPath = Path.Combine(LogDirectory, "usage-tray.previous.log");
            if (File.Exists(previousPath))
            {
                File.Delete(previousPath);
            }

            File.Move(LogPath, previousPath);
        }
    }
}

