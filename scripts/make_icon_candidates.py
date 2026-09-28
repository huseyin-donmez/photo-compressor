#!/usr/bin/env python3
"""Render launcher-icon candidates as masked PNG previews.

Draws N glyph concepts x M background treatments on the 108dp adaptive-icon
canvas (heavily supersampled), then saves circle- and squircle-masked contact
sheets plus unmasked individuals for close inspection. Preview-only tool — the
chosen design gets hand-translated into drawable vector XML afterwards.

Usage: python3 scripts/make_icon_candidates.py [--out DIR]
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageFont

SCALE = 16                       # px per dp (108 dp canvas -> 1728 px)
CANVAS = 108 * SCALE
SHEET_CELL = 300                 # icon size on the contact sheets
LABEL_H = 44


def dp(v):
    # All design values are multiples of 0.5dp; at 16px/dp they are exact ints
    # (PIL's outlined shapes reject float widths/coords).
    return int(round(v * SCALE))


# ---------------------------------------------------------------- backgrounds

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def hex_rgb(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def bg_solid(color):
    return Image.new("RGBA", (CANVAS, CANVAS), color + (255,))


def bg_gradient(top, bottom):
    n = 512
    strip = Image.new("RGB", (1, n))
    strip.putdata([lerp(top, bottom, i / (n - 1)) for i in range(n)])
    return strip.resize((CANVAS, CANVAS), Image.BILINEAR).convert("RGBA")


# Each background also defines the accent color carved into white cards.
BACKGROUNDS = [
    ("blue",   lambda: bg_solid(hex_rgb("#1565C0")),   hex_rgb("#1565C0")),
    ("indigo", lambda: bg_gradient(hex_rgb("#1E88E5"), hex_rgb("#0D47A1")), hex_rgb("#0D47A1")),
    ("teal",   lambda: bg_gradient(hex_rgb("#26A69A"), hex_rgb("#00695C")), hex_rgb("#00695C")),
]

WHITE = (255, 255, 255, 255)
SOFT = (255, 255, 255, 204)      # 80% white for depth
GHOST = (255, 255, 255, 110)     # ~43% white for background layers


def _px(pts):
    return [(dp(x), dp(y)) for x, y in pts]


def rr(d, box, r, **kw):
    d.rounded_rectangle([dp(box[0]), dp(box[1]), dp(box[2]), dp(box[3])],
                        radius=dp(r), **kw)


def line(d, pts, w, color=WHITE):
    """Polyline with round caps and joints (PIL joints are only rounded)."""
    d.line(_px(pts), fill=color, width=int(dp(w)), joint="curve")
    rad = dp(w) / 2
    for x, y in (pts[0], pts[-1]):
        d.ellipse([dp(x) - rad, dp(y) - rad, dp(x) + rad, dp(y) + rad], fill=color)


# ------------------------------------------------------------------ concepts
# All coordinates are dp on the 108dp canvas; every glyph stays inside the
# 66dp-diameter safe zone centered on (54, 54).

def concept_squeeze(d, accent):
    """Rounded frame, center square, four chevrons squeezing inward."""
    rr(d, (31, 31, 77, 77), 13, outline=WHITE, width=dp(4.5))
    rr(d, (46, 46, 62, 62), 4, fill=WHITE)
    line(d, [(45, 38), (54, 44), (63, 38)], 5, SOFT)   # top -> down
    line(d, [(45, 70), (54, 64), (63, 70)], 5, SOFT)   # bottom -> up
    line(d, [(38, 45), (44, 54), (38, 63)], 5, SOFT)   # left -> right
    line(d, [(70, 45), (64, 54), (70, 63)], 5, SOFT)   # right -> left


def concept_stack(d, accent):
    """Two overlapping cards: big ghost card behind, small photo card in front."""
    rr(d, (31, 31, 65, 65), 9, outline=GHOST, width=dp(4))
    rr(d, (39, 39, 77, 77), 9, fill=WHITE)
    d.ellipse([dp(61), dp(44), dp(70), dp(53)], fill=accent)               # sun
    d.polygon(_px([(43, 70), (53, 56), (61, 66), (66, 61), (74, 70)]),
              fill=accent)                                                  # mountains


def concept_brackets(d, accent):
    """Viewfinder corner brackets around a bold down-arrow (capture -> smaller)."""
    w = 5
    line(d, [(33, 45), (33, 33), (45, 33)], w)
    line(d, [(63, 33), (75, 33), (75, 45)], w)
    line(d, [(75, 63), (75, 75), (63, 75)], w)
    line(d, [(45, 75), (33, 75), (33, 63)], w)
    line(d, [(54, 39), (54, 64)], 6)
    d.polygon(_px([(54, 74), (46, 61), (62, 61)]), fill=WHITE)


def concept_photo_down(d, accent):
    """Photo with a landscape glyph, compress arrow underneath."""
    rr(d, (34, 28, 74, 56), 8, outline=WHITE, width=dp(4.5))
    d.ellipse([dp(60), dp(33), dp(68), dp(41)], fill=accent)               # sun
    d.polygon(_px([(38, 51), (48, 38), (55, 47), (60, 42), (70, 51)]),
              fill=accent)                                                  # mountains
    line(d, [(54, 62), (54, 71)], 6)
    d.polygon(_px([(54, 79), (46.5, 68), (61.5, 68)]), fill=WHITE)


CONCEPTS = [
    ("c1_squeeze", "C1 squeeze", concept_squeeze),
    ("c2_stack",   "C2 stack",   concept_stack),
    ("c3_brackets","C3 brackets", concept_brackets),
    ("c4_photo",   "C4 photo",   concept_photo_down),
]


# --------------------------------------------------------------------- masks

def mask_circle():
    m = Image.new("L", (CANVAS, CANVAS), 0)
    r = dp(36)  # 72dp keyline circle, like Pixel launchers
    ImageDraw.Draw(m).ellipse([CANVAS / 2 - r, CANVAS / 2 - r,
                               CANVAS / 2 + r, CANVAS / 2 + r], fill=255)
    return m


def mask_squircle():
    m = Image.new("L", (CANVAS, CANVAS), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, CANVAS - 1, CANVAS - 1],
                                        radius=dp(25), fill=255)
    return m


def render_icon(concept_fn, accent, bg_image):
    layer = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    concept_fn(ImageDraw.Draw(layer), accent)
    icon = bg_image.copy()
    icon.alpha_composite(layer)
    return icon


def load_font(size):
    for path in (
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
    ):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


def save_masked(icon, mask, size):
    m = Image.new("RGBA", (CANVAS, CANVAS))
    m.paste(icon, (0, 0), mask)
    return m.resize((size, size), Image.LANCZOS)


def contact_sheet(masks_by_row, title_fonts, path):
    cols = len(BACKGROUNDS)
    rows = len(CONCEPTS)
    cell_w = SHEET_CELL + 24
    sheet = Image.new("RGB", (cols * cell_w + 16, rows * (SHEET_CELL + LABEL_H) + 60),
                      (245, 245, 247))
    d = ImageDraw.Draw(sheet)
    d.text((16, 14), os.path.basename(path).replace(".png", ""),
           font=title_fonts["title"], fill=(40, 40, 45))
    for ci, (bg_name, _, _) in enumerate(BACKGROUNDS):
        d.text((16 + 8 + ci * cell_w + SHEET_CELL // 2 - 30, 52),
               bg_name, font=title_fonts["small"], fill=(90, 90, 100))
    for ri, (cid, cname, _) in enumerate(CONCEPTS):
        y = 60 + LABEL_H + ri * (SHEET_CELL + LABEL_H)
        d.text((16, y + SHEET_CELL // 2 - 10), cname.split()[0],
               font=title_fonts["small"], fill=(90, 90, 100))
        for ci in range(cols):
            x = 8 + ci * cell_w + 8
            sheet.paste(masks_by_row[ri][ci], (x, y))
    sheet.save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="icon_candidates")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    fonts = {"title": load_font(26), "small": load_font(20)}
    masks = {"circle": mask_circle(), "squircle": mask_squircle()}
    masked_sheets = {name: [] for name in masks}

    for cid, cname, fn in CONCEPTS:
        for bg_name, bg_factory, accent in BACKGROUNDS:
            icon = render_icon(fn, accent, bg_factory())
            # Unmasked individual (512) for close inspection / vector tracing.
            icon.resize((512, 512), Image.LANCZOS).convert("RGB").save(
                os.path.join(args.out, f"{cid}_{bg_name}.png"))
            for mname, mask in masks.items():
                big = save_masked(icon, mask, 512)
                big.save(os.path.join(args.out, f"{cid}_{bg_name}_{mname}.png"))
                masked_sheets[mname].append(
                    save_masked(icon, mask, SHEET_CELL).convert("RGB"))

    # Rebuild sheets from flat append order (concept-major).
    for mname, cells in masked_sheets.items():
        rows = [cells[i * len(BACKGROUNDS):(i + 1) * len(BACKGROUNDS)]
                for i in range(len(CONCEPTS))]
        contact_sheet(rows, fonts, os.path.join(args.out, f"sheet_{mname}.png"))

    print(f"Wrote {len(CONCEPTS) * len(BACKGROUNDS) * 4 + 2} files to {args.out}/")


if __name__ == "__main__":
    main()
