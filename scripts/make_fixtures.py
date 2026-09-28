#!/usr/bin/env python3
"""Generate golden test fixtures into testdata/.

Deterministic (seeded). Run: python3 scripts/make_fixtures.py
Used by ImageResizerCoreTests and android core tests.
"""
import io
import os
import random
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "testdata")

SMOOTH_TARGET_BYTES = 12 * 1024 * 1024  # photo_smooth_12mb.jpg


def noise_bytes(w, h, seed, channels=3):
    rng = random.Random(seed)
    return rng.randbytes(w * h * channels)


def make_smooth_photo(w, h, seed):
    """Photo-like image: blurred low-frequency blotches + adjustable fine grain."""
    small = Image.frombytes("RGB", (32, 24), noise_bytes(32, 24, seed))
    base = small.resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(6))
    return base


def grain(base, amplitude, seed):
    """Add fine photographic grain (deterministic) so the JPEG behaves like a real photo."""
    from PIL import ImageChops
    w, h = base.size
    rng = random.Random(seed + 999)
    noise = Image.frombytes("L", (w, h), bytes(rng.randbytes(w * h)))
    noise = noise.point(lambda v: (v * amplitude) // 255)  # 0..amplitude
    noise_rgb = Image.merge("RGB", (noise, noise, noise))
    return ImageChops.add(base, noise_rgb, scale=1.0, offset=-amplitude // 2)


def save_jpeg_to_size(img, path, target_bytes, lo=70, hi=98):
    """Save as JPEG, binary-searching quality to land close to target_bytes."""
    best = None
    while lo <= hi:
        q = (lo + hi) // 2
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=q, optimize=True)
        size = buf.tell()
        best = (q, buf.getvalue())
        if size < target_bytes:
            lo = q + 1
        else:
            hi = q - 1
    if best is None:
        raise RuntimeError("jpeg size search failed")
    q, data = best
    with open(path, "wb") as f:
        f.write(data)
    return q, len(data)


def exif_portrait_gps():
    exif = Image.Exif()
    exif[0x0112] = 6  # Orientation: rotate 90 CW to display
    exif[0x010F] = "TestMake"  # Make
    exif[0x0110] = "TestModel"  # Model
    gps = exif.get_ifd(0x8825)
    gps[1] = "N"
    gps[2] = (48, 51, 30)  # 48°51'30"N  (as rationals written by Pillow if ints)
    gps[3] = "E"
    gps[4] = (2, 17, 0)
    return exif


def fmt(size):
    for unit in ("B", "KB", "MB"):
        if size < 1024 or unit == "MB":
            return f"{size:,.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024.0


def report(path, size):
    print(f"  {os.path.basename(path):32s} {fmt(size):>12s}")


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f"Writing fixtures to {OUT}")

    # 1. Smooth photo-like 12 MB JPEG, 4000x3000 — typical path (quality search)
    w, h = 4000, 3000
    base = make_smooth_photo(w, h, seed=1)
    # photo-like: blurred base + strong grain; binary-search quality to ~12 MB
    img = grain(base, amplitude=160, seed=1)
    q, size = save_jpeg_to_size(img, os.path.join(OUT, "photo_smooth_12mb.jpg"),
                                SMOOTH_TARGET_BYTES)
    report(os.path.join(OUT, "photo_smooth_12mb.jpg"), size)
    print(f"    (quality={q})")

    # 2. Worst-case noise JPEG 4000x3000 — forces resolution reduction
    noise_img = Image.frombytes("RGB", (w, h), noise_bytes(w, h, seed=2))
    path = os.path.join(OUT, "photo_noise_large.jpg")
    noise_img.save(path, "JPEG", quality=95, optimize=True)
    report(path, os.path.getsize(path))

    # 3. Portrait orientation=6 + GPS + Make/Model EXIF
    path = os.path.join(OUT, "portrait_orient6.jpg")
    small = make_smooth_photo(2000, 1500, seed=3)
    small = grain(small, amplitude=110, seed=3)
    small.save(path, "JPEG", quality=88, exif=exif_portrait_gps())
    report(path, os.path.getsize(path))

    # 4. HEIC conversions (macOS sips)
    for src, dst in [
        ("photo_smooth_12mb.jpg", "photo.heic"),
        ("portrait_orient6.jpg", "portrait_orient6.heic"),
    ]:
        sp = os.path.join(OUT, src)
        dp = os.path.join(OUT, dst)
        subprocess.run(["sips", "-s", "format", "heic", sp, "--out", dp],
                       check=True, capture_output=True)
        report(dp, os.path.getsize(dp))

    # 5. Flat graphic PNG (small, no alpha) — pass-through candidate
    w, h = 2500, 2500
    img = Image.new("RGB", (w, h), (245, 245, 250))
    d = ImageDraw.Draw(img)
    colors = [(220, 60, 60), (60, 160, 220), (60, 200, 120), (240, 180, 40)]
    for i in range(24):
        x = (i * 137) % w
        y = (i * 311) % h
        r = 60 + (i * 53) % 300
        d.ellipse([x - r, y - r, x + r, y + r], fill=colors[i % 4])
    d.rectangle([200, 200, 1200, 700], outline=(30, 30, 30), width=12)
    path = os.path.join(OUT, "flat_graphic.png")
    img.save(path, "PNG", optimize=True)
    report(path, os.path.getsize(path))

    # 6. Photo-as-PNG, no alpha, > target — must convert to JPEG
    w, h = 1600, 1600
    img = Image.frombytes("RGB", (w, h), noise_bytes(w, h, seed=6))
    path = os.path.join(OUT, "photo_png_noalpha.png")
    img.save(path, "PNG")
    report(path, os.path.getsize(path))

    # 7. Transparent noise PNG — alpha, resolution-reduction only
    w, h = 1000, 1000
    img = Image.frombytes("RGBA", (w, h), noise_bytes(w, h, seed=7, channels=4))
    path = os.path.join(OUT, "transparent_noise.png")
    img.save(path, "PNG")
    report(path, os.path.getsize(path))

    # 8. Small photo, well under target — pass-through
    path = os.path.join(OUT, "small_photo.jpg")
    img = grain(make_smooth_photo(800, 600, seed=8), amplitude=110, seed=8)
    img.save(path, "JPEG", quality=85)
    report(path, os.path.getsize(path))

    # 9. Animated GIF
    frames = []
    for i in range(5):
        f = Image.new("RGB", (500, 500), (10 * i, 255 - 10 * i, 128))
        d = ImageDraw.Draw(f)
        d.ellipse([50 + i * 60, 50, 350 + i * 60, 350], fill=(255, 255, 0))
        frames.append(f)
    path = os.path.join(OUT, "animated.gif")
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=100)
    report(path, os.path.getsize(path))

    # 10. WebP (opaque) + transparent WebP (alpha)
    path = os.path.join(OUT, "test.webp")
    img = grain(make_smooth_photo(1600, 1200, seed=10), amplitude=110, seed=10)
    img.save(path, "WEBP", quality=85)
    report(path, os.path.getsize(path))

    path = os.path.join(OUT, "transparent.webp")
    img = Image.frombytes("RGBA", (1000, 1000),
                          noise_bytes(1000, 1000, seed=14, channels=4))
    img.save(path, "WEBP", quality=85)
    report(path, os.path.getsize(path))

    # 11. Corrupt JPEG (valid SOI, garbage after)
    path = os.path.join(OUT, "corrupt.jpg")
    rng = random.Random(11)
    with open(path, "wb") as f:
        f.write(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")
        f.write(rng.randbytes(64 * 1024))
    report(path, os.path.getsize(path))

    # 12. Empty file
    path = os.path.join(OUT, "empty.jpg")
    open(path, "wb").close()
    report(path, os.path.getsize(path))

    print("done")


if __name__ == "__main__":
    sys.exit(main())
