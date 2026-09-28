#!/usr/bin/env python3
"""Render store listing assets from the chosen launcher design.

Outputs (store/):
  icon-512.png         Play Store icon (512x512, opaque, square)
  icon-1024.png        App Store icon (1024x1024, opaque, no rounded corners)
  feature-graphic.png  Play feature graphic (1024x500)

Reuses the glyph/background code from make_icon_candidates.py so the store
art is pixel-identical to the shipped launcher icon (concept C3 brackets on
the indigo gradient — see docs/ALGORITHM.md history / scripts).
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_icon_candidates import (  # noqa: E402
    CANVAS,
    BACKGROUNDS,
    concept_brackets,
    hex_rgb,
    load_font,
    render_icon,
)

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "store")
os.makedirs(OUT, exist_ok=True)

BRAND_TOP = hex_rgb("#1E88E5")
BRAND_BOTTOM = hex_rgb("#0D47A1")


def vertical_gradient(width, height, top, bottom):
    strip = Image.new("RGB", (1, 512))
    strip.putdata([
        tuple(int(top[i] + (bottom[i] - top[i]) * t / 511) for i in range(3))
        for t in range(512)
    ])
    return strip.resize((width, height), Image.BILINEAR).convert("RGBA")


def glyph_only():
    accent = next(b[2] for b in BACKGROUNDS if b[0] == "indigo")
    layer = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    concept_brackets(ImageDraw.Draw(layer), accent)
    return layer


def fit(draw, text, font, max_width):
    """Shrink font until the line fits (returns fitted font)."""
    size = font.size
    while size > 10 and draw.textlength(text, font=font) > max_width:
        size -= 2
        font = load_font(size)
    return font


def main():
    # --- icons (square, opaque: stores apply their own masking) ---
    icon = render_icon(*((concept_brackets,) + (
        next(b[2] for b in BACKGROUNDS if b[0] == "indigo"),
        next(b[1] for b in BACKGROUNDS if b[0] == "indigo")(),
    )))
    icon.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "icon-512.png"))
    icon.resize((1024, 1024), Image.LANCZOS).save(os.path.join(OUT, "icon-1024.png"))

    # --- feature graphic 1024x500 (Play) ---
    w, h = 1024, 500
    bg = vertical_gradient(w, h, BRAND_TOP, BRAND_BOTTOM)
    glyph = glyph_only().resize((240, 240), Image.LANCZOS)
    bg.alpha_composite(glyph, (72, (h - 240) // 2))

    d = ImageDraw.Draw(bg)
    text_x = 360
    max_w = w - text_x - 56
    title1 = fit(d, "Photo Compressor", load_font(64), max_w)
    title2 = fit(d, "KB & MB", load_font(64), max_w)
    tag1 = fit(d, "Compress photos to any size limit.", load_font(30), max_w)
    tag2 = fit(d, "On-device — photos never leave your phone.", load_font(30), max_w)

    d.text((text_x, 132), "Photo Compressor", font=title1, fill=(255, 255, 255, 255))
    d.text((text_x, 206), "KB & MB", font=title2, fill=(210, 230, 255, 255))
    d.text((text_x, 310), "Compress photos to any size limit.", font=tag1,
           fill=(225, 238, 255, 255))
    d.text((text_x, 354), "On-device — photos never leave your phone.", font=tag2,
           fill=(190, 214, 245, 255))

    bg.convert("RGB").save(os.path.join(OUT, "feature-graphic.png"))

    for name in ("icon-512.png", "icon-1024.png", "feature-graphic.png"):
        path = os.path.join(OUT, name)
        print(f"{name}: {os.path.getsize(path)} bytes")


if __name__ == "__main__":
    main()
