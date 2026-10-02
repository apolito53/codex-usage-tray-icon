using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

public static class CodexUsageTrayDesktop
{
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetFinalPathNameByHandle(
        IntPtr file, StringBuilder path, uint length, uint flags);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsProcessInJob(
        IntPtr process, IntPtr job, [MarshalAs(UnmanagedType.Bool)] out bool inJob);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool QueryInformationJobObject(
        IntPtr job, int informationClass, IntPtr information,
        uint length, IntPtr returnLength);

    public static string ResolvePhysicalPath(string path)
    {
        using (var file = new FileStream(path, FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete))
        {
            var buffer = new StringBuilder(32768);
            uint count = GetFinalPathNameByHandle(file.SafeFileHandle.DangerousGetHandle(),
                buffer, (uint)buffer.Capacity, 0);
            if (count == 0 || count >= buffer.Capacity)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(),
                    "Could not resolve the tray's physical file path.");
            }

            string resolved = buffer.ToString();
            if (resolved.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase))
            {
                return @"\\" + resolved.Substring(8);
            }

            return resolved.StartsWith(@"\\?\", StringComparison.Ordinal)
                ? resolved.Substring(4)
                : resolved;
        }
    }

    public static bool InJob(int processId)
    {
        using (Process process = Process.GetProcessById(processId))
        {
            bool inJob;
            if (!IsProcessInJob(process.Handle, IntPtr.Zero, out inJob))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }

            return inJob;
        }
    }

    public static bool InCallerJob(int processId)
    {
        using (Process caller = Process.GetCurrentProcess())
        {
            if (!InJob(caller.Id))
            {
                return false;
            }
        }

        const int bufferSize = 65536;
        IntPtr buffer = Marshal.AllocHGlobal(bufferSize);
        try
        {
            // JobObjectBasicProcessIdList: two DWORD counts, then ULONG_PTR IDs.
            if (!QueryInformationJobObject(IntPtr.Zero, 3, buffer,
                bufferSize, IntPtr.Zero))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(),
                    "Could not verify independence from the terminal's job.");
            }

            int count = Marshal.ReadInt32(buffer, 4);
            for (int index = 0; index < count; index++)
            {
                long id = Marshal.ReadIntPtr(buffer, 8 + index * IntPtr.Size).ToInt64();
                if (id == processId)
                {
                    return true;
                }
            }

            return false;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }
}
