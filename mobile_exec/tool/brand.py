#!/usr/bin/env python3
"""OrbixHQ brands: add a customer brand, or regenerate a brand's build files.

A brand is a folder ``brands/<id>/`` holding ONE hand-written file,
``brand.json``:

    {
      "id": "kilimanjaro",                 lowercase letters/digits; also the
                                           Android flavor and app-id suffix
      "appName": "Kilimanjaro HQ",         sign-in title, task switcher, and
                                           the label under the launcher icon
      "company": "Kilimanjaro Supermarket",  line under the title (the
                                           product tagline when it equals
                                           appName)
      "mark": "K",                         the letter on the icon and sign-in
      "color": "#1D4ED8",                  THE brand colour
      "server": "http://51.21.23.170",     the DEFAULT server baked in - only a
                                           default: the phone can change it
                                           (sign-in footer, 7 taps), and
                                           dist/build-hq.ps1 offers it and asks
                                           to confirm. Empty for orbix: the
                                           generic app asks on first launch.
      "palette": { ... }                   optional - exact overrides for any
                                           derived shade (the Orbix brand pins
                                           its hand-tuned teal this way)
    }

Everything else is DERIVED from ``color`` here, so a new customer is six
fields, not a dozen hex codes: the darker and softer shades, the dark hero
gradient, and the text tints used on it. Lightness is set per role, not
copied from the input, so every brand gets the same contrast - and a colour
too light to carry white text is refused rather than shipped.

The tool writes ``brands/<id>/generated/`` (committed, never hand-edited):

    dart_defines.json        passed to Flutter with --dart-define-from-file;
                             lib/app/brand.dart reads it
    android/res/...          launcher icons, app_name, splash colour; Gradle
                             adds them to the brand's flavor (not for orbix:
                             android/app/src/main/res already IS Orbix)

Usage:
    python tool/brand.py new kilimanjaro --name "Kilimanjaro HQ" \\
        --company "Kilimanjaro Supermarket" --mark K --color "#1D4ED8" \\
        --server http://51.21.23.170
    python tool/brand.py gen kilimanjaro     # after editing brand.json
    python tool/brand.py gen --all            # every brand
    python tool/brand.py list

Pillow is needed for the icons. The output is committed, so building an APK
needs only Flutter - see dist/build-hq.ps1.
"""

from __future__ import annotations

import argparse
import colorsys
import json
import os
import re
import shutil
import sys
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRANDS = os.path.join(ROOT, "brands")
GENERIC = "orbix"
DEFAULT_TAGLINE = "Your business, in your pocket."

ID_RE = re.compile(r"^[a-z][a-z0-9]{1,30}$")
HEX_RE = re.compile(r"^#?([0-9A-Fa-f]{6})$")

# Derived palette roles -> the dart-define each one feeds (lib/app/brand.dart).
ROLES = {
    "primary": "BRAND_PRIMARY",
    "primaryDark": "BRAND_PRIMARY_DARK",
    "primarySoft": "BRAND_PRIMARY_SOFT",
    "heroMid": "BRAND_HERO_MID",
    "heroEnd": "BRAND_HERO_END",
    "gradientStart": "BRAND_GRADIENT_START",
    "gradientEnd": "BRAND_GRADIENT_END",
    "onDarkSecondary": "BRAND_ON_DARK_2",
    "onDarkTertiary": "BRAND_ON_DARK_3",
}

# ---------------------------------------------------------------------------
# colour
# ---------------------------------------------------------------------------


def parse_hex(value: str) -> tuple[int, int, int]:
    m = HEX_RE.match(value.strip())
    if not m:
        raise ValueError(f"not a #RRGGBB colour: {value!r}")
    h = m.group(1)
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def to_hex(rgb: tuple[int, int, int]) -> str:
    return "#%02X%02X%02X" % rgb


def _hls(hue: float, light: float, sat: float) -> tuple[int, int, int]:
    r, g, b = colorsys.hls_to_rgb(hue, max(0.0, min(1.0, light)), max(0.0, min(1.0, sat)))
    return (round(r * 255), round(g * 255), round(b * 255))


def _luminance(rgb: tuple[int, int, int]) -> float:
    def ch(c: int) -> float:
        c = c / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (ch(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: tuple[int, int, int], b: tuple[int, int, int]) -> float:
    la, lb = sorted((_luminance(a), _luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


WHITE = (255, 255, 255)


def derive_palette(primary: tuple[int, int, int]) -> dict[str, tuple[int, int, int]]:
    """Every shade the app paints with, from the one brand colour.

    Calibrated against the hand-tuned Orbix teal (#0F766E): run on that colour
    it lands within a few units of each original. Roles that sit behind text
    get a FIXED lightness, so contrast does not depend on the input.
    """
    h, l, s = colorsys.rgb_to_hls(*(c / 255 for c in primary))
    gradient_end = _hls(h, l * 0.805, s)
    return {
        "primary": primary,
        "primaryDark": _hls(h, l * 0.76, s),
        "primarySoft": _hls(h, 0.93, s * 0.50),
        "heroMid": _hls(h, 0.137, s * 0.89),
        "heroEnd": _hls(h, 0.078, s * 0.78),
        "gradientStart": _gradient_start(h, l, s, gradient_end),
        "gradientEnd": gradient_end,
        "onDarkSecondary": _hls(h, 0.78, s * 0.34),
        "onDarkTertiary": _hls(h, 0.60, s * 0.25),
    }


def _mix(a: tuple[int, int, int], b: tuple[int, int, int]) -> tuple[int, int, int]:
    return tuple(round((x + y) / 2) for x, y in zip(a, b))


def _gradient_start(h: float, base: float, s: float, end: tuple[int, int, int]):
    """The light end of the brand gradient: [base] lightness x1.165, backed off
    until white text still reads at the gradient's MIDDLE - where the only
    text on it (the dashboard avatar's initials) sits. Never darker than base."""
    light = base * 1.165
    while light > base and contrast(WHITE, _mix(_hls(h, light, s), end)) < 4.5:
        light -= 0.005
    return _hls(h, max(light, base), s)


def check_contrast(brand_id: str, p: dict[str, tuple[int, int, int]]) -> None:
    """WCAG AA, which the app ships to. Refuse rather than ship unreadable."""
    checks = [
        ("white text on the brand colour (buttons, app bar)", WHITE, p["primary"]),
        ("white text on the brand gradient", WHITE, _mix(p["gradientStart"], p["gradientEnd"])),
        ("secondary text on the hero field", p["onDarkSecondary"], p["heroMid"]),
        ("tertiary text on the hero field", p["onDarkTertiary"], p["heroMid"]),
    ]
    bad = [(what, contrast(fg, bg)) for what, fg, bg in checks if contrast(fg, bg) < 4.5]
    if bad:
        lines = "\n".join(f"  - {w}: {r:.2f}:1 (needs 4.5:1)" for w, r in bad)
        sys.exit(f"{brand_id}: colour fails contrast -\n{lines}\n"
                 "Choose a darker brand colour (or set an override under \"palette\").")


# ---------------------------------------------------------------------------
# icon
# ---------------------------------------------------------------------------

CORNER_RADIUS = 0.22   # matches tool/gen_icon.py (the Orbix "H")
GLYPH_HEIGHT = 0.55
SS = 4
DENSITIES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]
FONTS = [
    r"C:\Windows\Fonts\segoeuib.ttf",
    r"C:\Windows\Fonts\arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/Library/Fonts/Arial Bold.ttf",
]


def render_icon(size: int, mark: str, rgb: tuple[int, int, int]):
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        sys.exit("Pillow is required for icons:  python -m pip install Pillow")
    font_path = next((f for f in FONTS if os.path.isfile(f)), None)
    if not font_path:
        sys.exit("No bold system font found; add one to FONTS in tool/brand.py.")

    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((0, 0, big - 1, big - 1),
                           radius=int(CORNER_RADIUS * big), fill=rgb + (255,))
    # Size by the glyph's INK, then centre the ink: letters differ in their
    # bearings, so the line box centre is not the visual centre.
    target = GLYPH_HEIGHT * big
    font_size = int(target)
    for _ in range(4):
        font = ImageFont.truetype(font_path, font_size)
        l, t, r, b = draw.textbbox((0, 0), mark, font=font)
        font_size = max(1, int(font_size * target / max(1, b - t)))
    font = ImageFont.truetype(font_path, font_size)
    l, t, r, b = draw.textbbox((0, 0), mark, font=font)
    draw.text(((big - (r - l)) / 2 - l, (big - (b - t)) / 2 - t), mark,
              font=font, fill=WHITE + (255,))
    return img.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------------------
# brand files
# ---------------------------------------------------------------------------


def load(brand_id: str) -> dict:
    path = os.path.join(BRANDS, brand_id, "brand.json")
    if not os.path.isfile(path):
        sys.exit(f"No brand '{brand_id}'. Known: {', '.join(all_ids()) or 'none'}")
    with open(path, encoding="utf-8") as fh:
        b = json.load(fh)
    for key in ("id", "appName", "company", "mark", "color"):
        if not str(b.get(key, "")).strip():
            sys.exit(f"{brand_id}: brand.json is missing '{key}'")
    if b["id"] != brand_id:
        sys.exit(f"{brand_id}: \"id\" in brand.json must equal the folder name")
    if not ID_RE.match(brand_id):
        sys.exit(f"{brand_id}: id must be lowercase letters/digits, starting with a letter")
    if len(b["mark"]) != 1:
        sys.exit(f"{brand_id}: \"mark\" must be a single character")
    return b


def all_ids() -> list[str]:
    if not os.path.isdir(BRANDS):
        return []
    return sorted(d for d in os.listdir(BRANDS)
                  if os.path.isfile(os.path.join(BRANDS, d, "brand.json")))


def generate(brand_id: str) -> None:
    b = load(brand_id)
    palette = derive_palette(parse_hex(b["color"]))
    for role, value in (b.get("palette") or {}).items():
        if role not in ROLES:
            sys.exit(f"{brand_id}: unknown palette role '{role}' (known: {', '.join(ROLES)})")
        palette[role] = parse_hex(value)
    check_contrast(brand_id, palette)

    out = os.path.join(BRANDS, brand_id, "generated")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)

    defines = {
        "BRAND_ID": brand_id,
        "BRAND_APP_NAME": b["appName"],
        # The line under the title. When the company IS the app name (a brand
        # called just "Kilimanjaro"), repeating it says nothing - use the
        # product line instead.
        "BRAND_TAGLINE": (b["company"] if b["company"].strip() != b["appName"].strip()
                          else DEFAULT_TAGLINE),
        "BRAND_MARK": b["mark"],
    }
    for role, key in ROLES.items():
        defines[key] = "0xFF" + to_hex(palette[role])[1:]
    with open(os.path.join(out, "dart_defines.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(defines, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    if brand_id != GENERIC:
        res = os.path.join(out, "android", "res")
        for density, size in DENSITIES:
            os.makedirs(os.path.join(res, density))
            render_icon(size, b["mark"], palette["primary"]).save(
                os.path.join(res, density, "ic_launcher.png"), optimize=True)
        values = os.path.join(res, "values")
        os.makedirs(values)
        head = ('<?xml version="1.0" encoding="utf-8"?>\n'
                f"<!-- GENERATED from brands/{brand_id}/brand.json by tool/brand.py. -->\n")
        with open(os.path.join(values, "brand.xml"), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(head + "<resources>\n"
                     f'    <string name="app_name">{escape(b["appName"])}</string>\n'
                     f'    <color name="hq_brand_teal">{to_hex(palette["primary"])}</color>\n'
                     "</resources>\n")

    shades = "  ".join(f"{r}={to_hex(c)}" for r, c in palette.items())
    print(f"{brand_id}: '{b['appName']}' ({b['company']}), mark '{b['mark']}'\n  {shades}")


def cmd_new(a) -> None:
    if not ID_RE.match(a.id):
        sys.exit("id must be lowercase letters/digits, starting with a letter (e.g. shayo)")
    folder = os.path.join(BRANDS, a.id)
    if os.path.exists(folder):
        sys.exit(f"brands/{a.id} already exists - edit its brand.json and run: gen {a.id}")
    color = to_hex(parse_hex(a.color))
    os.makedirs(folder)
    with open(os.path.join(folder, "brand.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump({"id": a.id, "appName": a.name, "company": a.company,
                   "mark": (a.mark or a.name.strip()[0]).upper(), "color": color,
                   "server": (a.server or "").strip().rstrip("/")},
                  fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    try:
        generate(a.id)
    except SystemExit:
        shutil.rmtree(folder, ignore_errors=True)   # leave nothing half-made
        raise
    print(f"\nCreated brands/{a.id}. Commit it, then build with:\n"
          f"  .\\dist\\build-hq.ps1 -Brand {a.id}")


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="OrbixHQ brands")
    sub = p.add_subparsers(dest="cmd", required=True)

    n = sub.add_parser("new", help="add a customer brand")
    n.add_argument("id")
    n.add_argument("--name", required=True, help='app name, e.g. "Shayo HQ"')
    n.add_argument("--company", required=True, help='e.g. "Shayo Express Trading"')
    n.add_argument("--color", required=True, help="brand colour, #RRGGBB")
    n.add_argument("--mark", help="icon letter (default: first letter of --name)")
    n.add_argument("--server", help="default server, e.g. http://51.21.23.170 "
                                    "(empty: the build asks)")

    g = sub.add_parser("gen", help="regenerate build files from brand.json")
    g.add_argument("ids", nargs="*")
    g.add_argument("--all", action="store_true")

    sub.add_parser("list", help="list brands")

    a = p.parse_args(argv)
    if a.cmd == "new":
        cmd_new(a)
    elif a.cmd == "gen":
        ids = all_ids() if a.all else a.ids
        if not ids:
            p.error("name a brand, or pass --all")
        for i in ids:
            generate(i)
    else:
        for i in all_ids():
            b = load(i)
            print(f"{i:<14} {b['appName']:<18} {b['color']}  "
                  f"{b.get('server') or '(asks)':<26} {b['company']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
