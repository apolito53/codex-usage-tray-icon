using System;
using System.Threading;
using System.Windows.Forms;

namespace CodexUsageTray
{
    internal static class Program
    {
        private const string MutexName = @"Local\CodexUsageTray-b35ccb90-ab7b-4c98-b2ba-b36fa99331cc";
        private static Mutex _singleInstance;

        [STAThread]
        private static void Main()
        {
            bool createdNew;
            _singleInstance = new Mutex(true, MutexName, out createdNew);

            if (!createdNew)
            {
                _singleInstance.Dispose();
                return;
            }

            try
            {
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
                Application.Run(new TrayApplicationContext());
            }
            finally
            {
                try
                {
                    _singleInstance.ReleaseMutex();
                }
                catch (ApplicationException)
                {
                    // The process is already ending; there is nothing useful
                    // to recover if ownership was lost.
                }

                _singleInstance.Dispose();
            }
        }
    }
}

