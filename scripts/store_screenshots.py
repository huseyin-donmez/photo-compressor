#!/usr/bin/env python3
"""Scripted Play-store screenshot pass for Photo Compressor (Android).

Ads can never render: airplane mode for the whole pass.
  1. Home @ 100 KB      -> 01-home-100kb.png
  2. 8 photos selected  -> 02-selection.png
  3. Mid-batch          -> 03-running-{a,b,c}.png (best one kept)
  4. Done screen        -> 04-done.png
  5. Dark-mode home     -> 05-dark-home.png
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
PKG = "com.appkitstudios.photocompressor"
# Relative ".MainActivity" would resolve against the applicationId, but the
# source namespace stays com.imageresizer.app — launch with the full class.
MAIN = f"{PKG}/com.imageresizer.app.MainActivity"
OUT = "/Users/huseyin/Desktop/projects/image-resizer/store/screenshots"
TMP_SHOT = "/data/local/tmp/pcshots"  # NOT on shared storage → never indexed by MediaStore
WANT = 6  # shot-list wants 6–9 thumbs; rows 1–2 of the grid are tap-safe


def sh(*args, timeout=60):
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).strip()


def shell(cmd, timeout=60):
    return sh("shell", cmd, timeout=timeout)


def dump():
    out = ""
    for i in range(8):
        if i % 2 == 0:
            sh("shell", "uiautomator", "dump", "/dev/null")  # warm-up kick
        shell("rm -f /sdcard/d.xml")
        out = sh("shell", "uiautomator", "dump", "/sdcard/d.xml")
        if "ERROR" not in out and "error" not in out.lower():
            xml = shell("cat /sdcard/d.xml")
            if xml.startswith("<?xml"):
                return xml
        time.sleep(1.5)
    raise RuntimeError(f"uiautomator dump failed: {out}")


def nodes(xml):
    root = ET.fromstring(xml)
    result = []
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        l, t, r, b = map(int, m.groups())
        result.append({
            "text": n.get("text", ""),
            "desc": n.get("content-desc", ""),
            "clickable": n.get("clickable") == "true",
            "box": (l, t, r, b),
            "cx": (l + r) // 2,
            "cy": (t + b) // 2,
        })
    return result


def find(ns, text, exact=True):
    for n in ns:
        if exact and n["text"] == text:
            return n
        if not exact and text in n["text"]:
            return n
    return None


def find_button(ns, text):
    """Prefer the clickable node with this label (skips titles)."""
    matches = [n for n in ns if n["text"] == text]
    if not matches:
        return None
    for n in matches:
        if n["clickable"]:
            return n
    return max(matches, key=lambda n: n["cx"])


def tap_node(n, note=""):
    shell(f"input tap {n['cx']} {n['cy']}")
    print(f"  tap '{n['text'] or n['desc']}' @({n['cx']},{n['cy']}) {note}", flush=True)


def tap(x, y, note=""):
    shell(f"input tap {x} {y}")
    print(f"  tap @({x},{y}) {note}", flush=True)


def shot(name):
    shell(f"mkdir -p {TMP_SHOT}")
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    sh("shell", f"screencap -p {TMP_SHOT}/{name}")
    sh("pull", f"{TMP_SHOT}/{name}", path)
    print(f"  SHOT {path}", flush=True)


def current_target(xml):
    m = re.search(r"Output will be at most ([\d.]+) (KB|MB)", xml)
    return (float(m.group(1)), m.group(2)) if m else None


def texts_of(ns):
    return " | ".join(t for t in (n["text"] for n in ns) if t)


def settle(max_tries=8):
    """Dump until no modal is up; return the fresh node list."""
    ns = []
    for _ in range(max_tries):
        xml = dump()
        ns = nodes(xml)
        texts = texts_of(ns)
        if "Billing service unavailable" in texts:
            print("  dismissing billing alert…", flush=True)
            ok = find(ns, "OK") or find(ns, "Ok")
            tap_node(ok, "(dismiss)") if ok else shell("input keyevent 4")
            time.sleep(1.5)
            continue
        return ns
    return ns


def set_kb100():
    """Drive the wheel to 100 KB (first KB entry). Returns True on success."""
    xml = dump()
    if current_target(xml) == (100.0, "KB"):
        return True
    kb = find(nodes(xml), "KB")
    if kb:
        tap_node(kb, "(unit=KB)")
        time.sleep(1.5)
    for _ in range(10):
        xml = dump()
        cur = current_target(xml)
        if cur == (100.0, "KB"):
            return True
        if cur is None:
            return False
        print(f"  wheel at {cur[0]} {cur[1]} → swiping down", flush=True)
        val_node = find(nodes(xml), str(int(cur[0])))
        x = val_node["cx"] if val_node else 430
        y = val_node["cy"] if val_node else 1200
        shell(f"input swipe {x} {y - 260} {x} {y + 300} 140")
        time.sleep(1.4)
    return False


def picker_counter(ns, want):
    """Selection count from the picker bar — only valid when the bar exists."""
    if not any(n["text"] == "Preview" for n in ns):
        return 0
    best = 0
    for n in ns:
        t = n["text"].strip()
        if t.isdigit():
            best = max(best, int(t))
    return min(best, want)


def main():
    print("== prep: reset + airplane + credits + launch ==", flush=True)
    shell("input keyevent 4; input keyevent 4")  # close leftovers
    shell("cmd connectivity airplane-mode enable")
    shell("svc wifi disable; svc data disable")
    shell(f"am force-stop {PKG}")
    shell("run-as %s sh -c 'rm -f shared_prefs/batch.xml'" % PKG)  # no restored batch
    shell("settings put global window_animation_scale 0; "
          "settings put global transition_animation_scale 0; "
          "settings put global animator_duration_scale 0")
    # Previous runs left screencaps + *_resized outputs in MediaStore; they'd
    # crowd out the sample photos in the picker. Remove files AND their rows
    # (scanning a missing path drops the row).
    shots = [f"/sdcard/shots/{n}"
             for n in shell("ls /sdcard/shots 2>/dev/null").split()]
    outs = [f"/sdcard/Pictures/ImageResizer/{n}"
            for n in shell("ls /sdcard/Pictures/ImageResizer 2>/dev/null").split()]
    extra = ["/sdcard/s.png"] if shell("ls /sdcard/s.png 2>/dev/null").strip() else []
    for path in shots + outs + extra:
        shell(f"rm -f {path}")
    for path in shots + outs + extra:
        shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE "
              f"-d file://{path} >/dev/null")
    shell("rm -rf /sdcard/shots")
    print(f"  cleaned {len(shots) + len(outs) + len(extra)} indexed files",
          flush=True)
    time.sleep(1)
    rows = shell("content query --uri content://media/external/images/media "
                 "--projection _display_name")
    bad = [l for l in rows.splitlines()
           if ".png" in l or "_resized" in l or "/shots/" in l]
    if bad:
        raise RuntimeError(f"MediaStore still polluted ({len(bad)} rows): "
                           f"{bad[:3]}")
    # 20 credits so an 8-photo batch can finish (free tier only has 5).
    prefs = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n'
             '<map>\n<int name="credits" value="20" />\n'
             '<boolean name="premium" value="false" />\n</map>\n')
    subprocess.run(
        [ADB, "shell", f"run-as {PKG} sh -c "
                       f"'mkdir -p shared_prefs && cat > shared_prefs/usage.xml'"],
        input=prefs, capture_output=True, text=True, timeout=30,
    )
    shell(f"am start -n {MAIN}")
    time.sleep(4)

    # Home must be visible (billing modal dismissed if it shows).
    for _ in range(25):
        ns = settle()
        texts = texts_of(ns)
        if "Photo Compressor" in texts:
            break
        if "photos saved" in texts:  # leftover done screen (belt & braces)
            done_btn = find_button(ns, "Done")
            if done_btn:
                tap_node(done_btn, "(stale done screen)")
                time.sleep(1.5)
                continue
        time.sleep(1)
    else:
        raise RuntimeError(f"home never became visible: {texts[:200]}")

    print("== 1. Home @ 100 KB ==", flush=True)
    if not set_kb100():
        raise RuntimeError("could not reach 100 KB")
    shot("01-home-100kb.png")

    print(f"== 2. pick {WANT} photos ==", flush=True)
    xml = dump()
    # Picking appends to any restored selection — clear first for a clean count.
    clear = find(nodes(xml), "Clear")
    if clear:
        tap_node(clear, "(reset old selection)")
        time.sleep(1)
        xml = dump()
    choose = find(nodes(xml), "Choose photos")
    if not choose:
        raise RuntimeError("Choose photos button missing")
    tap_node(choose)
    time.sleep(3.5)

    xml = dump()
    ns = nodes(xml)
    photos = [n for n in ns if "Photo taken" in n["desc"]]
    if len(photos) < WANT:
        photos = [n for n in ns
                  if 300 <= n["box"][2] - n["box"][0] <= 420
                  and abs((n["box"][2] - n["box"][0]) - (n["box"][3] - n["box"][1])) <= 4]
    # Rows whose center sits under the bottom bar are unusable — skip them.
    cells = sorted({n["box"]: n for n in photos
                    if (n["box"][1] + n["box"][3]) // 2 <= 2090}.values(),
                   key=lambda n: (n["box"][1], n["box"][0]))
    print(f"  picker cells found: {len(cells)}", flush=True)
    if len(cells) < WANT:
        raise RuntimeError("not enough tap-safe picker cells")

    selected, attempts = 0, 0
    while selected < WANT and attempts < WANT * 2:
        cell = cells[selected] if selected < len(cells) else cells[attempts % len(cells)]
        tap(cell["cx"], (cell["box"][1] + cell["box"][3]) // 2, f"(select {selected + 1})")
        time.sleep(0.7)
        ns = nodes(dump())
        c = picker_counter(ns, WANT)
        if c > selected:
            selected = c
            print(f"  counter = {selected}", flush=True)
        attempts += 1
    if selected < WANT:
        raise RuntimeError(f"only {selected} of {WANT} selected")

    xml = dump()
    ns = nodes(xml)
    confirm = next((n for n in ns
                    if n["text"] == "Done" and n["cx"] > 700
                    and n["box"][2] - n["box"][0] < 400), None)
    if not confirm:
        confirm = find_button(ns, "Done") or find(ns, "Add")
    if not confirm:
        raise RuntimeError(f"no confirm button; texts: {texts_of(ns)[:200]}")
    tap_node(confirm, "(commit selection)")
    time.sleep(3)

    print("== 3. selection shot ==", flush=True)
    ns = settle()
    resize = next((n for n in ns
                   if n["text"].startswith("Resize ") and n["text"].endswith(" photos")),
                  None)
    if not resize:
        raise RuntimeError(f"no Resize button; texts: {texts_of(ns)[:250]}")
    if f"Resize {WANT} photos" not in resize["text"]:
        raise RuntimeError(f"expected {WANT}, got: {resize['text']}")
    print(f"  button: {resize['text']}", flush=True)
    shot("02-selection.png")

    print("== 4. batch ==", flush=True)
    tap_node(resize, "(start batch)")
    time.sleep(0.4)
    shot("03-running-a.png")
    time.sleep(0.7)
    shot("03-running-b.png")
    time.sleep(0.9)
    shot("03-running-c.png")

    done_ns = None
    for _ in range(70):
        ns = settle()
        texts = texts_of(ns)
        if ("photos saved" in texts) or (find(ns, "Share") and find(ns, "Done")):
            done_ns = ns
            break
        time.sleep(1.5)
    if not done_ns:
        raise RuntimeError(f"batch never finished; texts: {texts_of(ns)[:250]}")
    print(f"  done: {texts_of(done_ns)[:220]}", flush=True)
    shot("04-done.png")

    print("== 5. back home + dark ==", flush=True)
    done_btn = find_button(done_ns, "Done")
    if not done_btn:
        raise RuntimeError("no clickable Done button on done screen")
    tap_node(done_btn, "(to home)")
    time.sleep(2.5)
    ns = settle()
    if "Photo Compressor" not in texts_of(ns):
        raise RuntimeError(f"not on home after Done: {texts_of(ns)[:150]}")
    clear = find(ns, "Clear")  # clean home for the dark shot
    if clear:
        tap_node(clear, "(clear selection)")
        time.sleep(1)
    shell("cmd uimode night yes")
    time.sleep(3)
    settle()  # activity recreation can re-trigger the billing query
    if not set_kb100():
        raise RuntimeError("could not re-set 100 KB in dark")
    shot("05-dark-home.png")
    shell("cmd uimode night no")
    print("ALL SHOTS DONE", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"FAILED: {e}", flush=True)
        try:
            shot("99-failure-state.png")
        except Exception:
            pass
        sys.exit(1)
