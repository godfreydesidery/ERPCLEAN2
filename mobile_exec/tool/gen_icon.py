#!/usr/bin/env python3
"""Generate the OrbixHQ launcher icon as PNGs, stdlib only.

No Pillow, no third-party packages. The image is rasterised into a plain
bytearray and the PNG chunks are written by hand with ``zlib`` + ``struct``.

The mark matches the Orbix family (cf. web/public/favicon.svg): a rounded
square (22% corner radius) filled with the brand teal #0F766E, carrying a
single bold white "H" centred at roughly 55% of the tile height.

Anti-aliasing comes from supersampling: everything is drawn at SS x the
target resolution and box-downsampled in premultiplied alpha, so the teal
never bleeds a dark halo into the transparent corners.

Usage:  python tool/gen_icon.py [--out <android res dir>]
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import zlib

# ---------------------------------------------------------------------------
# Brand constants
# ---------------------------------------------------------------------------

TEAL = (0x0F, 0x76, 0x6E)             # HqColors.brand  #0F766E
WHITE = (0xFF, 0xFF, 0xFF)

CORNER_RADIUS = 0.22                  # fraction of the tile edge
H_HEIGHT = 0.55                       # cap height as a fraction of the tile
H_WIDTH = 0.50                        # overall glyph width
STEM_WIDTH = 0.135                    # each vertical stem, heavy sans weight
BAR_HEIGHT = 0.125                    # the crossbar

SS = 4                                # supersampling factor

# Android launcher densities.
DENSITIES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192),
]


# ---------------------------------------------------------------------------
# Rasteriser
# ---------------------------------------------------------------------------

def _rounded_rect_hit(x, y, size, radius):
    """True when the point lies inside a rounded square anchored at (0, 0)."""
    if x < 0.0 or y < 0.0 or x > size or y > size:
        return False
    # Clamp the point into the inner rect; whatever is left over is the
    # vector out into one of the four corner quadrants.
    cx = min(max(x, radius), size - radius)
    cy = min(max(y, radius), size - radius)
    dx = x - cx
    dy = y - cy
    if dx == 0.0 and dy == 0.0:
        return True
    return (dx * dx + dy * dy) <= (radius * radius)


def _letter_h_hit(x, y, size):
    """True when the point lies inside the bold letter H."""
    half = size / 2.0
    gw = H_WIDTH * size
    gh = H_HEIGHT * size
    stem = STEM_WIDTH * size
    bar = BAR_HEIGHT * size

    left = half - gw / 2.0
    right = half + gw / 2.0
    top = half - gh / 2.0
    bottom = half + gh / 2.0

    if y < top or y > bottom:
        return False
    if left <= x <= left + stem:          # left stem
        return True
    if right - stem <= x <= right:        # right stem
        return True
    if (half - bar / 2.0) <= y <= (half + bar / 2.0) and left <= x <= right:
        return True                       # crossbar
    return False


def render_rgba(size):
    """Rasterise the icon at ``size`` x ``size`` and return raw RGBA bytes."""
    big = size * SS
    radius_big = CORNER_RADIUS * big
    samples = SS * SS
    fbig = float(big)

    # Per-supersample classification, one byte per sample:
    #   0 = outside the tile, 1 = teal tile, 2 = white glyph.
    mask = bytearray(big * big)
    for py in range(big):
        y = py + 0.5                      # sample at pixel centres
        row = py * big
        for px in range(big):
            x = px + 0.5
            if not _rounded_rect_hit(x, y, fbig, radius_big):
                continue
            mask[row + px] = 2 if _letter_h_hit(x, y, fbig) else 1

    out = bytearray(size * size * 4)
    for oy in range(size):
        base_y = oy * SS
        for ox in range(size):
            base_x = ox * SS
            # Accumulate coverage and colour separately, then unpremultiply
            # against the covered-sample count so edge pixels keep full
            # chroma and only lose alpha.
            r = g = b = covered = 0
            for sy in range(SS):
                row = (base_y + sy) * big + base_x
                for sx in range(SS):
                    kind = mask[row + sx]
                    if kind == 1:
                        r += TEAL[0]
                        g += TEAL[1]
                        b += TEAL[2]
                        covered += 1
                    elif kind == 2:
                        r += WHITE[0]
                        g += WHITE[1]
                        b += WHITE[2]
                        covered += 1
            i = (oy * size + ox) * 4
            if covered == 0:
                out[i] = out[i + 1] = out[i + 2] = out[i + 3] = 0
                continue
            out[i] = min(255, r // covered)
            out[i + 1] = min(255, g // covered)
            out[i + 2] = min(255, b // covered)
            out[i + 3] = (covered * 255) // samples
    return bytes(out)


# ---------------------------------------------------------------------------
# PNG writer
# ---------------------------------------------------------------------------

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _chunk(tag, payload):
    return (
        struct.pack(">I", len(payload))
        + tag
        + payload
        + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
    )


def encode_png(rgba, size):
    """Wrap raw RGBA bytes into a colour-type-6, 8-bit PNG."""
    stride = size * 4
    raw = bytearray()
    for y in range(size):
        raw.append(0)                     # filter type 0 (None) per scanline
        raw += rgba[y * stride:(y + 1) * stride]

    ihdr = struct.pack(
        ">IIBBBBB",
        size,   # width
        size,   # height
        8,      # bit depth
        6,      # colour type: truecolour with alpha
        0,      # compression: deflate
        0,      # filter method: adaptive
        0,      # interlace: none
    )
    return (
        PNG_SIGNATURE
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + _chunk(b"IEND", b"")
    )


# ---------------------------------------------------------------------------
# Verification
# ---------------------------------------------------------------------------

def verify(path, size):
    """Re-read a written file and confirm it is a plausible PNG of ``size``."""
    try:
        with open(path, "rb") as fh:
            data = fh.read()
    except OSError as exc:
        return False, "unreadable: %s" % exc

    if len(data) < 57:
        return False, "too small (%d bytes)" % len(data)
    if data[:8] != PNG_SIGNATURE:
        return False, "bad PNG signature"
    if data[12:16] != b"IHDR":
        return False, "missing IHDR"
    width, height = struct.unpack(">II", data[16:24])
    if (width, height) != (size, size):
        return False, "dimensions %dx%d, expected %dx%d" % (width, height, size, size)
    if data[24] != 8 or data[25] != 6:
        return False, "not 8-bit RGBA"

    # Walk every chunk, check its CRC, and inflate the pixel data.
    pos = 8
    idat = bytearray()
    saw_iend = False
    while pos + 12 <= len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        crc = struct.unpack(">I", data[pos + 8 + length:pos + 12 + length])[0]
        if crc != (zlib.crc32(tag + payload) & 0xFFFFFFFF):
            return False, "CRC mismatch on %s" % tag.decode("ascii", "replace")
        if tag == b"IDAT":
            idat += payload
        if tag == b"IEND":
            saw_iend = True
        pos += 12 + length

    if not saw_iend:
        return False, "missing IEND"
    try:
        pixels = zlib.decompress(bytes(idat))
    except zlib.error as exc:
        return False, "IDAT will not inflate: %s" % exc
    expected = height * (1 + width * 4)
    if len(pixels) != expected:
        return False, "pixel data is %d bytes, expected %d" % (len(pixels), expected)
    return True, "ok"


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    default_out = os.path.join(here, os.pardir, "android", "app", "src", "main", "res")

    parser = argparse.ArgumentParser(description="Generate OrbixHQ launcher icons.")
    parser.add_argument("--out", default=default_out, help="Android res/ directory")
    args = parser.parse_args(argv)

    res_dir = os.path.abspath(args.out)
    failures = []

    for folder, size in DENSITIES:
        target_dir = os.path.join(res_dir, folder)
        os.makedirs(target_dir, exist_ok=True)
        path = os.path.join(target_dir, "ic_launcher.png")

        png = encode_png(render_rgba(size), size)
        with open(path, "wb") as fh:
            fh.write(png)

        ok, note = verify(path, size)
        if ok:
            print("%-18s %3dx%-3d  %6d bytes  OK"
                  % (folder + "/ic_launcher.png", size, size, os.path.getsize(path)))
        else:
            failures.append("%s/ic_launcher.png: %s" % (folder, note))
            try:
                os.remove(path)
            except OSError:
                pass
            print("%s/ic_launcher.png FAILED (%s) - removed" % (folder, note),
                  file=sys.stderr)

    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("all %d icons written and verified" % len(DENSITIES))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
