using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;

namespace CodexUsageTray
{
    internal static class TrayIconRenderer
    {
        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        private static extern bool DestroyIcon(IntPtr handle);

        internal static Icon CreateStatusIcon(string text, Color background)
        {
            return CreateStatusIcon(text, background, false);
        }

        internal static Icon CreateStatusIcon(
            string text,
            Color background,
            bool showOfflineBadge)
        {
            const int canvasSize = 64;

            using (var bitmap = new Bitmap(canvasSize, canvasSize))
            using (Graphics graphics = Graphics.FromImage(bitmap))
            {
                graphics.SmoothingMode = SmoothingMode.AntiAlias;
                graphics.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
                graphics.Clear(Color.Transparent);

                using (var shadowBrush = new SolidBrush(Color.FromArgb(90, 0, 0, 0)))
                using (var backgroundBrush = new SolidBrush(background))
                using (var borderPen = new Pen(Color.FromArgb(220, 255, 255, 255), 2f))
                {
                    // Use nearly the whole icon canvas. At the 16px size most
                    // taskbars choose, every unused source pixel costs real
                    // legibility after Windows scales the icon down.
                    graphics.FillEllipse(shadowBrush, 2, 4, 60, 60);
                    graphics.FillEllipse(backgroundBrush, 1, 1, 60, 60);
                    graphics.DrawEllipse(borderPen, 2f, 2f, 58f, 58f);
                }

                float fontSize = text.Length >= 3
                    ? 25f
                    : text.Length == 2
                        ? 38f
                        : 42f;

                using (var font = new Font(
                    "Segoe UI",
                    fontSize,
                    FontStyle.Bold,
                    GraphicsUnit.Pixel))
                using (var textBrush = new SolidBrush(Color.White))
                using (var format = new StringFormat()
                {
                    Alignment = StringAlignment.Center,
                    LineAlignment = StringAlignment.Center,
                    FormatFlags = StringFormatFlags.NoWrap
                })
                {
                    graphics.DrawString(
                        text,
                        font,
                        textBrush,
                        new RectangleF(1, -1, 60, 62),
                        format);
                }

                if (showOfflineBadge)
                {
                    // A small red status light preserves the useful number
                    // while making a stale/offline reading obvious at a glance.
                    using (var badgeBrush = new SolidBrush(
                        Color.FromArgb(245, 207, 34, 46)))
                    using (var badgeBorder = new Pen(Color.White, 2.5f))
                    {
                        graphics.FillEllipse(badgeBrush, 48, 0, 15, 15);
                        graphics.DrawEllipse(badgeBorder, 49.25f, 1.25f, 12.5f, 12.5f);
                    }
                }

                IntPtr iconHandle = bitmap.GetHicon();
                try
                {
                    using (Icon temporaryIcon = Icon.FromHandle(iconHandle))
                    {
                        return (Icon)temporaryIcon.Clone();
                    }
                }
                finally
                {
                    DestroyIcon(iconHandle);
                }
            }
        }
    }
}
