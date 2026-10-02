using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace CodexUsageTray
{
    internal static class Program
    {
        private const string MutexName = @"Local\CodexUsageTray-b35ccb90-ab7b-4c98-b2ba-b36fa99331cc";
        private static Mutex _singleInstance;

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool IsProcessInJob(
            IntPtr processHandle,
            IntPtr jobHandle,
            [MarshalAs(UnmanagedType.Bool)] out bool inJob);

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool QueryInformationJobObject(
            IntPtr job, int informationClass, IntPtr information,
            uint length, IntPtr returnLength);

        [STAThread]
        private static void Main()
        {
            bool ownsMutex = false;
            try
            {
                bool createdNew;
                _singleInstance = new Mutex(true, MutexName, out createdNew);
                if (!createdNew)
                {
                    return;
                }

                ownsMutex = true;
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.SetUnhandledExceptionMode(
                    UnhandledExceptionMode.CatchException);

                Application.ThreadException += delegate(object sender, System.Threading.ThreadExceptionEventArgs args)
                {
                    AppLog.Error("Unhandled UI exception.", args.Exception);
                };

                AppDomain.CurrentDomain.UnhandledException += delegate(object sender, UnhandledExceptionEventArgs args)
                {
                    AppLog.Error(
                        "Unhandled application exception.",
                        args.ExceptionObject as Exception);
                };

                AppLog.Info("Codex Usage Tray starting.");
                using (Process current = Process.GetCurrentProcess())
                {
                    bool inJob;
                    string jobState = IsProcessInJob(current.Handle, IntPtr.Zero, out inJob)
                        ? inJob.ToString()
                        : "unknown";
                    if (inJob)
                    {
                        IntPtr jobInformation = Marshal.AllocHGlobal(144);
                        try
                        {
                            if (QueryInformationJobObject(IntPtr.Zero, 9,
                                jobInformation, 144, IntPtr.Zero))
                            {
                                jobState += " (limits 0x" +
                                    Marshal.ReadInt32(jobInformation, 16).ToString("X") + ")";
                            }
                        }
                        finally
                        {
                            Marshal.FreeHGlobal(jobInformation);
                        }
                    }
                    AppLog.Info(string.Format(
                        "Process {0}; session {1}; Windows job {2}; version {3}.",
                        current.Id,
                        current.SessionId,
                        jobState,
                        typeof(Program).Assembly.GetName().Version));
                }
                Application.Run(new TrayApplicationContext());
                AppLog.Info("Tray message loop ended.");
            }
            catch (Exception exception)
            {
                AppLog.Error("Tray startup or message loop failed.", exception);
                Environment.ExitCode = 1;
            }
            finally
            {
                if (_singleInstance != null)
                {
                    if (ownsMutex)
                    {
                        try
                        {
                            _singleInstance.ReleaseMutex();
                        }
                        catch (ApplicationException)
                        {
                            // Ownership may have been lost as the process ends.
                        }
                    }

                    _singleInstance.Dispose();
                }
            }
        }
    }
}

