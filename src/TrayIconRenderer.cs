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
            const int canvasSize = 64;

            using (var bitmap = new Bitmap(canvasSize, canvasSize))
            using (Graphics graphics = Graphics.FromImage(bitmap))
            {
                graphics.SmoothingMode = SmoothingMode.AntiAlias;
                graphics.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
                graphics.Clear(Color.Transparent);

                using (var shadowBrush = new SolidBrush(Color.FromArgb(90, 0, 0, 0)))
                using (var backgroundBrush = new SolidBrush(background))
                using (var borderPen = new Pen(Color.FromArgb(220, 255, 255, 255), 3f))
                {
                    graphics.FillEllipse(shadowBrush, 4, 6, 56, 56);
                    graphics.FillEllipse(backgroundBrush, 3, 3, 56, 56);
                    graphics.DrawEllipse(borderPen, 4.5f, 4.5f, 53f, 53f);
                }

                float fontSize = text.Length >= 3 ? 17f : 23f;
                using (var font = new Font(
                    "Segoe UI",
                    fontSize,
                    FontStyle.Bold,
                    GraphicsUnit.Pixel))
                using (var textBrush = new SolidBrush(Color.White))
                using (var format = new StringFormat()
                {
                    Alignment = StringAlignment.Center,
                    LineAlignment = StringAlignment.Center
                })
                {
                    graphics.DrawString(
                        text,
                        font,
                        textBrush,
                        new RectangleF(2, 1, 58, 58),
                        format);
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
