"""Render the project's own Play artwork with Pillow; no third-party logo assets.

Run from any working directory. On Windows, the installed Segoe UI family is
used without copying or redistributing the font. For other environments pass
--font and --bold-font paths. Icon geometry matches app/src/main/res/drawable/ic_launcher.xml.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent
SCALE = 4
PURPLE = "#7951C9"
INK = "#251B34"
ACCENT = "#6940AA"
MUTED = "#62556F"
SEVEN = [(11, 15), (24, 15), (24, 18), (17, 33), (12, 33), (19, 19), (11, 19)]
TWO = [(27, 15), (37, 15), (37, 25), (31, 25), (31, 29), (37, 29), (37, 33), (27, 33), (27, 21), (33, 21), (33, 19), (27, 19)]


def number(draw: ImageDraw.ImageDraw, x: float, y: float, size: float, color: str) -> None:
    """Place the exact 26:18 launcher-number geometry without a font dependency."""
    for shape in (SEVEN, TWO):
        draw.polygon([((x + (px - 11) * size) * SCALE, (y + (py - 15) * size) * SCALE) for px, py in shape], fill=color)


def font(path: Path, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(path), size * SCALE)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path, default=Path("C:/Windows/Fonts/segoeui.ttf"))
    parser.add_argument("--bold-font", type=Path, default=Path("C:/Windows/Fonts/segoeuib.ttf"))
    args = parser.parse_args()
    for path in (args.font, args.bold_font):
        if not path.is_file():
            parser.error(f"Font not found: {path}. Pass installed font paths; do not add font binaries to the repository.")

    # Preserve the circular launcher mark on an opaque, full-square backdrop.
    # Google Play applies its own corner mask and shadow to this upload asset.
    icon = Image.new("RGBA", (512 * SCALE, 512 * SCALE), "#EEE7F6")
    draw = ImageDraw.Draw(icon)
    unit = 512 / 48
    draw.ellipse((unit * SCALE, unit * SCALE, 47 * unit * SCALE, 47 * unit * SCALE), fill=PURPLE)
    number(draw, 11 * unit, 15 * unit, unit, "#FFFFFF")
    icon = icon.resize((512, 512), Image.Resampling.LANCZOS)
    icon.save(ROOT / "icon-512.png", optimize=True)

    feature = Image.new("RGB", (1024 * SCALE, 500 * SCALE), "#EEE7F6")
    draw = ImageDraw.Draw(feature)

    def rectangle(box: tuple[float, float, float, float], **kwargs) -> None:
        draw.rounded_rectangle(tuple(v * SCALE for v in box), **kwargs)

    def text(x: float, y: float, value: str, size: int, color: str, bold: bool = False) -> None:
        draw.text((x * SCALE, y * SCALE), value, font=font(args.bold_font if bold else args.font, size), fill=color, anchor="lt")

    # Edge-only color fields give the poster depth without impersonating app UI.
    draw.ellipse((700 * SCALE, -260 * SCALE, 1350 * SCALE, 390 * SCALE), fill="#E1D3F6")
    draw.ellipse((-245 * SCALE, 380 * SCALE, 385 * SCALE, 1010 * SCALE), fill="#E6DDF2")
    rectangle((612, 101, 908, 399), radius=44 * SCALE, fill="#DECEF5")
    rectangle((640, 129, 686, 135), radius=3 * SCALE, fill="#B69AD9")
    rectangle((696, 129, 750, 135), radius=3 * SCALE, fill="#C4ABE3")
    rectangle((760, 129, 814, 135), radius=3 * SCALE, fill="#C4ABE3")
    rectangle((824, 129, 880, 135), radius=3 * SCALE, fill="#C4ABE3")

    text(100, 91, "Allowance Meter", 28, ACCENT, bold=True)
    rectangle((100, 139, 144, 145), radius=3 * SCALE, fill=PURPLE)
    text(96, 180, "A little number.", 51, INK, bold=True)
    text(96, 244, "A useful place.", 51, INK, bold=True)
    text(100, 337, "Independent utility for Codex users", 20, MUTED)

    number(draw, 656, 180, 8, ACCENT)
    text(666, 339, "weekly remaining", 22, ACCENT)
    text(704, 365, "EXAMPLE READING", 12, MUTED, bold=True)
    feature = feature.resize((1024, 500), Image.Resampling.LANCZOS)
    feature.save(ROOT / "feature-graphic-1024x500.png", optimize=True)

    # Visual QA proof is separate from the uploadable assets.
    proof = Image.new("RGB", (1120, 820), "#F8F5FC")
    proof.paste(feature, (48, 32))
    proof.paste(Image.new("RGB", (300, 230), "#15121C"), (400, 558))
    proof.paste(icon.resize((192, 192), Image.Resampling.LANCZOS), (100, 576), icon.resize((192, 192), Image.Resampling.LANCZOS))
    proof.paste(icon.resize((192, 192), Image.Resampling.LANCZOS), (454, 576), icon.resize((192, 192), Image.Resampling.LANCZOS))
    small = icon.resize((48, 48), Image.Resampling.LANCZOS)
    proof.paste(small, (780, 644), small)
    ImageDraw.Draw(proof).text((850, 653), "48 px", fill=INK, font=ImageFont.truetype(str(args.font), 20))
    proof.save(ROOT / "artwork-proof.png", optimize=True)

    for name, dimensions, mode in (("icon-512.png", (512, 512), "RGBA"), ("feature-graphic-1024x500.png", (1024, 500), "RGB")):
        path = ROOT / name
        with Image.open(path) as saved:
            assert saved.size == dimensions and saved.mode == mode
        assert path.stat().st_size < 1024 * 1024
        print(f"{name}: {dimensions[0]}x{dimensions[1]} {mode}, {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
