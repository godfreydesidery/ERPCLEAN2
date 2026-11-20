#!/usr/bin/env python3
"""Generate the Orbix App (PWA) icons into ``web/``.

WEB ONLY. This writes nothing the Android build reads: the APK launcher icons
come from ``tool/brand.py`` / ``tool/gen_icon.py`` and are untouched.

The mark is the Orbix family tile - Orbix teal, white bold "O" - the same
letter as the ERP web favicon (web/public/favicon.svg), so the installed app
and the ERP read as one product.

Writes:
    web/icons/Icon-192.png            "any" icon, rounded tile, transparent corners
    web/icons/Icon-512.png
    web/icons/Icon-maskable-192.png   full-bleed square; the mark stays inside
    web/icons/Icon-maskable-512.png   the 80 % safe zone, so Android can cut it
                                      into a circle, squircle or teardrop
    web/icons/apple-touch-icon.png    180 x 180, full-bleed (iOS rounds it
                                      itself and paints transparency black)
    web/favicon.png                   32 x 32 browser tab icon

Usage:  python tool/pwa_icons.py [--mark O] [--color "#0F766E"]
Needs Pillow (same as tool/brand.py).
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import brand  # noqa: E402  (reuses its font list, colour parsing and tile renderer)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, "web")

# The maskable safe zone is a circle of 40 % radius around the centre; a glyph
# 36 % of the edge tall sits comfortably inside it.
MASKABLE_GLYPH = 0.36
APPLE_GLYPH = 0.50


def render_full_bleed(size: int, mark: str, rgb, glyph: float):
    from PIL import Image, ImageDraw, ImageFont

    font_path = next((f for f in brand.FONTS if os.path.isfile(f)), None)
    if not font_path:
        sys.exit("No bold system font found; add one to FONTS in tool/brand.py.")
    big = size * brand.SS
    img = Image.new("RGBA", (big, big), rgb + (255,))
    draw = ImageDraw.Draw(img)
    target = glyph * big
    font_size = int(target)
    for _ in range(4):
        font = ImageFont.truetype(font_path, font_size)
        l, t, r, b = draw.textbbox((0, 0), mark, font=font)
        font_size = max(1, int(font_size * target / max(1, b - t)))
    font = ImageFont.truetype(font_path, font_size)
    l, t, r, b = draw.textbbox((0, 0), mark, font=font)
    draw.text(((big - (r - l)) / 2 - l, (big - (b - t)) / 2 - t), mark,
              font=font, fill=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS)


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Generate the Orbix App PWA icons.")
    p.add_argument("--mark", default="O")
    p.add_argument("--color", default="#0F766E", help="tile colour (Orbix teal)")
    a = p.parse_args(argv)
    if len(a.mark) != 1:
        sys.exit("--mark must be a single character")
    rgb = brand.parse_hex(a.color)

    icons = os.path.join(WEB, "icons")
    os.makedirs(icons, exist_ok=True)
    out = {
        os.path.join(icons, "Icon-192.png"): brand.render_icon(192, a.mark, rgb),
        os.path.join(icons, "Icon-512.png"): brand.render_icon(512, a.mark, rgb),
        os.path.join(icons, "Icon-maskable-192.png"):
            render_full_bleed(192, a.mark, rgb, MASKABLE_GLYPH),
        os.path.join(icons, "Icon-maskable-512.png"):
            render_full_bleed(512, a.mark, rgb, MASKABLE_GLYPH),
        os.path.join(icons, "apple-touch-icon.png"):
            render_full_bleed(180, a.mark, rgb, APPLE_GLYPH).convert("RGB"),
        os.path.join(WEB, "favicon.png"): brand.render_icon(32, a.mark, rgb),
    }
    for path, img in out.items():
        img.save(path, optimize=True)
        print(f"wrote {os.path.relpath(path, ROOT)}  {img.size[0]}x{img.size[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
