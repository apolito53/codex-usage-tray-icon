using System;
using System.IO;

namespace CodexUsageTray
{
    /// <summary>
    /// A local presentation preference only. No account information or usage
    /// data is stored, and older installations default to the 5-hour icon.
    /// </summary>
    internal static class TrayWindowPreference
    {
        internal const string FiveHour = "five-hour";
        internal const string Weekly = "weekly";

        private static string PreferencePath
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "CodexUsageTray",
                    "tray-window.txt");
            }
        }

        internal static string Normalize(string value)
        {
            return string.Equals(value == null ? null : value.Trim(), Weekly,
                StringComparison.OrdinalIgnoreCase) ? Weekly : FiveHour;
        }

        internal static string Load()
        {
            return LoadFromPath(PreferencePath);
        }

        internal static string LoadFromPath(string path)
        {
            try
            {
                return Normalize(File.ReadAllText(path));
            }
            catch (IOException)
            {
                return FiveHour;
            }
            catch (UnauthorizedAccessException)
            {
                return FiveHour;
            }
        }

        internal static void Save(string value)
        {
            SaveToPath(PreferencePath, value);
        }

        internal static void SaveToPath(string path, string value)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            File.WriteAllText(path, Normalize(value));
        }
    }
}
