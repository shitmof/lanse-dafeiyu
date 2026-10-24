#!/usr/bin/env python3
"""Black-box edge-drag smoke test for the Android pet overlay.

Prerequisites:
  * one pet is running (设置 -> 自动播放 can be on; edge anchors are tested with
    auto animation running);
  * ADB can see the device and adb is on PATH.

The script alternates slow (800 ms) and fast (100 ms) drags to both edges. It
reads the real WindowManager frame after each gesture; a pass means the window
reached x=0 (left) or x+width=displayWidth (right), not merely the visible body.
"""
import argparse
import re
import subprocess
import sys
import time


def adb(device, *args, timeout=20):
    p = subprocess.run(
        ['adb', '-s', device, *args],
        capture_output=True, text=True, encoding='utf-8', errors='replace',
        timeout=timeout,
    )
    return p.stdout + p.stderr


def screen_size(device):
    out = adb(device, 'shell', 'dumpsys', 'window', 'displays')
    m = re.search(r'cur=(\d+)x(\d+)', out)
    if not m:
        raise RuntimeError('cannot read current display size')
    return int(m.group(1)), int(m.group(2))


def pet_frame(device):
    """Return the smallest application-overlay frame (the pet, not menu/bubble)."""
    out = adb(device, 'shell', 'dumpsys', 'window', 'windows')
    frames = []
    for m in re.finditer(
        r'mAttrs=\{\((\d+),(\d+)\)\((\d+)x(\d+)\)[^\n]*?ty=APPLICATION_OVERLAY',
        out,
    ):
        x, y, w, h = map(int, m.groups())
        if w > 0 and h > 0:
            frames.append((x, y, w, h))
    if not frames:
        raise RuntimeError('no application overlay found; start the pet first')
    return min(frames, key=lambda f: f[2] * f[3])


def drag_to_edge(device, side, fast, settle):
    display_w, display_h = screen_size(device)
    x, y, w, h = pet_frame(device)
    cx, cy = x + w // 2, y + h // 2
    target = 2 if side == 'left' else display_w - 3
    adb(device, 'shell', 'input', 'swipe', str(cx), str(cy), str(target), str(cy),
        '100' if fast else '800')
    time.sleep(settle)
    x2, y2, w2, h2 = pet_frame(device)
    if side == 'left':
        ok = x2 <= max(3, int(display_w * 0.002))
    else:
        ok = x2 + w2 >= display_w - 3
    print(
        f"{'FAIL' if not ok else 'OK  '} side={side:5s} "
        f"{'fast' if fast else 'slow'} screen={display_w}x{display_h} "
        f"before=({x},{y},{w},{h}) after=({x2},{y2},{w2},{h2})"
    )
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--device', default='127.0.0.1:16416')
    ap.add_argument('--iterations', type=int, default=30,
                    help='pairs of left+right drags (default 30)')
    ap.add_argument('--settle-ms', type=int, default=550)
    args = ap.parse_args()
    if args.iterations < 1:
        ap.error('iterations must be positive')
    good = True
    for i in range(args.iterations):
        # Alternate speed order so both fast/slow cover both sides.
        good &= drag_to_edge(args.device, 'left', i % 2 == 0, args.settle_ms / 1000)
        good &= drag_to_edge(args.device, 'right', i % 2 == 1, args.settle_ms / 1000)
    print('RESULT', 'PASS' if good else 'FAIL')
    return 0 if good else 1


if __name__ == '__main__':
    sys.exit(main())
