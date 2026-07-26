using System;
using System.Collections.Generic;
using System.IO;

namespace CodexUsageTray
{
    internal static class CodexPathResolver
    {
        private const string OverrideVariable = "CODEX_USAGE_TRAY_CODEX_PATH";

        internal static string Resolve()
        {
            string overridePath = Environment.GetEnvironmentVariable(OverrideVariable);
            if (!string.IsNullOrWhiteSpace(overridePath))
            {
                if (File.Exists(overridePath))
                {
                    return Path.GetFullPath(overridePath);
                }

                throw new FileNotFoundException(
                    OverrideVariable + " points to a missing file.",
                    overridePath);
            }

            // Codex Desktop keeps runnable CLI copies in mutable LocalAppData.
            // Prefer those over the protected WindowsApps execution alias,
            // which can exist on PATH while refusing child-process launches.
            string localAppData = Environment.GetFolderPath(
                Environment.SpecialFolder.LocalApplicationData);
            string desktopBinRoot = Path.Combine(localAppData, "OpenAI", "Codex", "bin");
            string newestDesktopCli = FindNewestCodexExecutable(desktopBinRoot);

            if (newestDesktopCli != null)
            {
                return newestDesktopCli;
            }

            string pathValue = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
            string[] pathEntries = pathValue.Split(Path.PathSeparator);

            foreach (string rawEntry in pathEntries)
            {
                string entry = rawEntry.Trim().Trim('"');
                if (string.IsNullOrWhiteSpace(entry))
                {
                    continue;
                }

                string candidate;
                try
                {
                    candidate = Path.Combine(entry, "codex.exe");
                }
                catch (ArgumentException)
                {
                    continue;
                }

                if (File.Exists(candidate) &&
                    candidate.IndexOf(
                        "\\WindowsApps\\",
                        StringComparison.OrdinalIgnoreCase) < 0)
                {
                    return Path.GetFullPath(candidate);
                }
            }

            throw new FileNotFoundException(
                "Could not find a runnable Codex CLI. Install or update Codex Desktop, " +
                "then sign in with ChatGPT.");
        }

        private static string FindNewestCodexExecutable(string root)
        {
            if (!Directory.Exists(root))
            {
                return null;
            }

            var candidates = new List<string>();
            try
            {
                candidates.AddRange(
                    Directory.GetFiles(root, "codex.exe", SearchOption.AllDirectories));
            }
            catch (UnauthorizedAccessException)
            {
                return null;
            }
            catch (IOException)
            {
                return null;
            }

            string newestPath = null;
            DateTime newestWrite = DateTime.MinValue;

            foreach (string candidate in candidates)
            {
                DateTime writeTime;
                try
                {
                    writeTime = File.GetLastWriteTimeUtc(candidate);
                }
                catch (IOException)
                {
                    continue;
                }

                if (newestPath == null || writeTime > newestWrite)
                {
                    newestPath = candidate;
                    newestWrite = writeTime;
                }
            }

            return newestPath;
        }
    }
}

