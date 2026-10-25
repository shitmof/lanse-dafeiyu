#!/usr/bin/env python3
"""Black-box edge-drag smoke test for the Android pet overlay.

This validator deliberately checks *actual* WindowManager frames, not the
requested `mAttrs` values. It also filters by package and tracks one stable
window token so an unrelated overlay or a stale full-screen window cannot
produce a false PASS.

Prerequisites:
  * the pet is running (auto animation may be on);
  * ADB can see the device and `adb` is on PATH.

Examples:
  python scripts/edge_smoke_test.py --device 127.0.0.1:16416 --iterations 30
  python scripts/edge_smoke_test.py --device ... --all --iterations 5
"""
import argparse
import re
import subprocess
import sys
import time

APP = 'com.lanse.fish'


def adb(device, *args, timeout=25):
    p = subprocess.run(
        ['adb', '-s', device, *args],
        capture_output=True, text=True, encoding='utf-8', errors='replace',
        timeout=timeout,
    )
    if p.returncode != 0:
        raise RuntimeError(f'adb failed ({p.returncode}): {" ".join(args)}\n{p.stderr.strip()}')
    return p.stdout


def screen_size(device):
    out = adb(device, 'shell', 'dumpsys', 'window', 'displays')
    m = re.search(r'cur=(\d+)x(\d+)', out)
    if not m:
        raise RuntimeError('cannot read current display size')
    return int(m.group(1)), int(m.group(2))


def overlay_windows(device):
    """Return app overlay windows with hash, requested attrs and actual frame."""
    out = adb(device, 'shell', 'dumpsys', 'window', 'windows')
    starts = list(re.finditer(r'^\s*Window #\d+ Window\{([0-9a-fA-F]+) u0 ([^}]+)\}:', out, re.M))
    windows = []
    for i, start in enumerate(starts):
        end = starts[i + 1].start() if i + 1 < len(starts) else len(out)
        block = out[start.start():end]
        if APP not in block or 'ty=APPLICATION_OVERLAY' not in block:
            continue
        if 'mViewVisibility=0x0' not in block:
            continue
        frame = None
        fm = re.search(r'\bframe=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', block)
        if fm:
            x, y, x2, y2 = map(int, fm.groups())
            frame = (x, y, x2, y2)
        else:
            fm = re.search(r'mFrame=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)', block)
            if fm:
                x, y, x2, y2 = map(int, fm.groups())
                frame = (x, y, x2, y2)
        if frame is None:
            continue
        attrs = re.search(r'mAttrs=\{\((-?\d+),(-?\d+)\)\((\d+)x(\d+)\)', block)
        windows.append({
            'token': start.group(1),
            'name': start.group(2).strip(),
            'frame': frame,
            'attrs': tuple(map(int, attrs.groups())) if attrs else None,
        })
    return windows


def choose_window(device, token=None, index=0):
    windows = overlay_windows(device)
    if not windows:
        raise RuntimeError('no visible com.lanse.fish application overlay found; start the pet first')
    if token:
        for w in windows:
            if w['token'] == token:
                return w
        raise RuntimeError(f'window token {token} disappeared')
    # A single pet is the normal case. The explicit token option handles 3 pets;
    # index selection is a convenience for a deliberately chosen window.
    return windows[index]


def contains(frame, x, y):
    return frame[0] <= x < frame[2] and frame[1] <= y < frame[3]


def target_covered(device, token, cx, cy):
    """True when an earlier (higher-z) app overlay covers the touch point."""
    windows = overlay_windows(device)
    target_index = next((i for i, w in enumerate(windows) if w['token'] == token), None)
    if target_index is None:
        return True
    for later in windows[:target_index]:
        if contains(later['frame'], cx, cy):
            return True
    return False


def drag(device, token, side, fast, settle_ms):
    display_w, display_h = screen_size(device)
    before = choose_window(device, token)
    x, y, x2, y2 = before['frame']
    w, h = x2 - x, y2 - y
    if w <= 0 or h <= 0:
        raise RuntimeError(f'invalid actual frame {before["frame"]}')
    cx, cy = x + w // 2, y + h // 2
    if target_covered(device, token, cx, cy):
        print(f'FAIL side={side:5s} token={token} target_covered at {cx},{cy}')
        return False, token
    target = 2 if side == 'left' else display_w - 3
    adb(device, 'shell', 'input', 'swipe', str(cx), str(cy), str(target), str(cy),
        '100' if fast else '800')
    time.sleep(settle_ms / 1000.0)
    after = choose_window(device, token)
    ax, ay, ax2, ay2 = after['frame']
    aw, ah = ax2 - ax, ay2 - ay
    # A valid result must be inside the display and look like a cropped pet, not
    # a full-screen carrier. A wide but short effect is still a normal pet clip.
    inside = ax >= -2 and ay >= -2 and ax2 <= display_w + 2 and ay2 <= display_h + 2
    area_ratio = (aw * ah) / float(max(1, display_w * display_h))
    small = aw <= display_w * 0.98 and ah <= display_h * 0.88 and area_ratio <= 0.55
    restored = inside and small
    if side == 'left':
        edge_ok = ax <= 3
    else:
        edge_ok = ax2 >= display_w - 3
    residual = any(
        win['frame'][2] - win['frame'][0] >= display_w - 2
        and win['frame'][3] - win['frame'][1] >= display_h - 2
        for win in overlay_windows(device)
    )
    ok = restored and edge_ok and not residual
    print(
        f"{'OK  ' if ok else 'FAIL'} side={side:5s} {'fast' if fast else 'slow'} "
        f"screen={display_w}x{display_h} token={token} "
        f"before_actual=({x},{y},{x2},{y2}) after_actual=({ax},{ay},{ax2},{ay2}) "
        f"inside={inside} small={small} area={area_ratio:.3f} "
        f"restored={restored} edge={edge_ok} residual_fullscreen={residual}"
    )
    return ok, token


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--device', default='127.0.0.1:16416')
    ap.add_argument('--iterations', type=int, default=30)
    ap.add_argument('--settle-ms', type=int, default=650)
    ap.add_argument('--window-hash', default=None,
                    help='stable Window{hash}; recommended when >1 pet is running')
    ap.add_argument('--all', action='store_true',
                    help='test every app overlay window (for 3-pet runs)')
    args = ap.parse_args()
    if args.iterations < 1:
        ap.error('iterations must be positive')

    if args.all:
        tokens = [w['token'] for w in overlay_windows(args.device)]
        if not tokens:
            raise SystemExit('no overlay windows')
    elif args.window_hash:
        tokens = [args.window_hash]
    else:
        tokens = [choose_window(args.device)['token']]

    good = True
    for token in tokens:
        for i in range(args.iterations):
            # Alternate speed order so both fast/slow cover both sides.
            ok, _ = drag(args.device, token, 'left', i % 2 == 0, args.settle_ms)
            good &= ok
            ok, _ = drag(args.device, token, 'right', i % 2 == 1, args.settle_ms)
            good &= ok
    print('RESULT', 'PASS' if good else 'FAIL')
    return 0 if good else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        sys.exit(1)
