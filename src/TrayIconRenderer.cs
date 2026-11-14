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
        // NotifyIcon occupies a square system-tray slot. The Plus capsule is
        // drawn inside that fixed slot; Pro continues using CreateStatusIcon.
        // Numbers have no '%' glyph to keep both readings as legible as the
        // taskbar's icon scaling permits.
        internal static Icon CreateDualStatusIcon(
            string fiveHourText,
            Color fiveHourColor,
            string weeklyText,
            Color weeklyColor,
            bool showOfflineBadge)
        {
            const int canvasSize = 64;
            using (var bitmap = new Bitmap(canvasSize, canvasSize))
            using (Graphics graphics = Graphics.FromImage(bitmap))
            using (var pill = new GraphicsPath())
            using (var shadowBrush = new SolidBrush(Color.FromArgb(90, 0, 0, 0)))
            using (var leftBrush = new SolidBrush(fiveHourColor))
            using (var rightBrush = new SolidBrush(weeklyColor))
            using (var outline = new Pen(Color.FromArgb(220, 255, 255, 255), 1.8f))
            using (var divider = new Pen(Color.FromArgb(140, 255, 255, 255), 1.1f))
            using (var textBrush = new SolidBrush(Color.White))
            {
                graphics.SmoothingMode = SmoothingMode.AntiAlias;
                graphics.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
                graphics.Clear(Color.Transparent);

                pill.AddArc(1, 12, 40, 40, 90, 180);
                pill.AddArc(23, 12, 40, 40, 270, 180);
                pill.CloseFigure();

                graphics.TranslateTransform(0, 2);
                graphics.FillPath(shadowBrush, pill);
                graphics.ResetTransform();
                graphics.FillPath(leftBrush, pill);
                graphics.SetClip(pill);
                graphics.FillRectangle(rightBrush, 32, 12, 32, 40);
                graphics.ResetClip();
                graphics.DrawPath(outline, pill);
                graphics.DrawLine(divider, 32, 16, 32, 48);

                using (var format = new StringFormat()
                {
                    Alignment = StringAlignment.Center,
                    LineAlignment = StringAlignment.Center,
                    FormatFlags = StringFormatFlags.NoWrap
                })
                using (var leftFont = new Font(
                    "Segoe UI",
                    fiveHourText.Length >= 3 ? 17f : 21f,
                    FontStyle.Bold,
                    GraphicsUnit.Pixel))
                using (var rightFont = new Font(
                    "Segoe UI",
                    weeklyText.Length >= 3 ? 17f : 21f,
                    FontStyle.Bold,
                    GraphicsUnit.Pixel))
                {
                    graphics.DrawString(fiveHourText, leftFont, textBrush,
                        new RectangleF(2, 12, 29, 40), format);
                    graphics.DrawString(weeklyText, rightFont, textBrush,
                        new RectangleF(33, 12, 29, 40), format);
                }

                if (showOfflineBadge)
                {
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
