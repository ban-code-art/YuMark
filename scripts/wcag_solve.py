import re, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

def lin_to_srgb(v):
    v = max(0.0, min(1.0, v))
    s = v * 12.92 if v <= 0.0031308 else 1.055 * (v ** (1 / 2.4)) - 0.055
    return max(0, min(255, int(round(s * 255))))

def lum(rgb):
    r, g, b = (srgb_to_lin(x) for x in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b

def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)

def parse(h):
    h = h.lstrip('#')
    if len(h) == 8:
        h = h[2:]
    return (int(h[0:2], 16), int(h[2:4], 16), h and int(h[4:6], 16))

def hexof(rgb):
    return '0xFF%02X%02X%02X' % rgb

def scale(rgb, k):
    """Scale in linear space -> chromaticity (hue+saturation) preserved exactly."""
    return tuple(lin_to_srgb(srgb_to_lin(c) * k) for c in rgb)

def toward_white(rgb, t):
    return tuple(lin_to_srgb(srgb_to_lin(c) + (1.0 - srgb_to_lin(c)) * t) for c in rgb)

def solve(fg, backgrounds, target, direction):
    """Smallest change to fg so every background meets `target`. Returns (rgb, worst)."""
    lo, hi = 0.0, 1.0
    f = (lambda t: scale(fg, 1.0 - t)) if direction == 'darken' else (lambda t: toward_white(fg, t))
    if min(ratio(fg, b) for b in backgrounds) >= target:
        return fg, min(ratio(fg, b) for b in backgrounds), 0.0
    for _ in range(60):
        mid = (lo + hi) / 2
        if min(ratio(f(mid), b) for b in backgrounds) >= target:
            hi = mid
        else:
            lo = mid
    out = f(hi)
    return out, min(ratio(out, b) for b in backgrounds), hi

# 一个前景色可能落在的全部底色：surface / background / surfaceVariant 加三档 surfaceContainer。
# 输入框不只出现在 surface 上——M3 的 AlertDialog 容器是 surfaceContainerHigh、ModalBottomSheet
# 是 surfaceContainerLow，而 28 个 OutlinedTextField 里就有落在对话框里的。只按 surface 求解，
# 同一个边框放进对话框就又不达标了，所以四个 outline 一律按全家族的最差一档解。
SURFACES_DEFAULT_LIGHT = ["FFFFFF", "FCFCFC", "F3F3F3", "F5F5F5", "EFEFEF", "E9E9E9"]
SURFACES_DEFAULT_DARK = ["252526", "1E1E1E", "2D2D2D", "2F2F30", "3A3A3B"]
SURFACES_CLAUDE_LIGHT = ["FAF9F5", "F5F4ED", "EAE8E0", "F2F0E8", "ECEAE1", "E5E2D8"]
SURFACES_CLAUDE_DARK = ["30302E", "262624", "3A3A37", "454541"]

CASES = [
    # name, fg, [backgrounds], target, direction
    ("Default-light outline", "E0E0E0", SURFACES_DEFAULT_LIGHT, 3.0, "darken"),
    ("Default-dark  outline", "3D3D3D", SURFACES_DEFAULT_DARK, 3.0, "lighten"),
    ("Claude-light  outline", "DDD9CC", SURFACES_CLAUDE_LIGHT, 3.0, "darken"),
    ("Claude-dark   outline", "4A4A46", SURFACES_CLAUDE_DARK, 3.0, "lighten"),
    ("Claude-light  onSurfaceVariant", "87867F", SURFACES_CLAUDE_LIGHT, 4.5, "darken"),
    ("Default-light onSurfaceVariant", "6B6B6B", SURFACES_DEFAULT_LIGHT, 4.5, "darken"),
    ("Default-dark  onSurfaceVariant", "9E9E9E", SURFACES_DEFAULT_DARK, 4.5, "lighten"),
    ("Claude-light  secondary (vs white onSecondary)", "8A8775", ["FFFFFF"], 4.5, "darken"),
    ("Claude-light  tertiary  (vs white onTertiary)", "7D7B5E", ["FFFFFF"], 4.5, "darken"),
    # primaryText: derive from primary, must read on every surface it can land on
    ("Default-light  primaryText from primary", "4B5A68", SURFACES_DEFAULT_LIGHT, 4.5, "darken"),
    ("Default-dark   primaryText from primary", "8FA1B3", SURFACES_DEFAULT_DARK, 4.5, "lighten"),
    ("Claude-light   primaryText from primary", "D97757", SURFACES_CLAUDE_LIGHT, 4.5, "darken"),
    ("Claude-dark    primaryText from primary", "D97757", SURFACES_CLAUDE_DARK, 4.5, "lighten"),
]

print("%-46s %-11s %-11s %-7s %s" % ("case", "current", "proposed", "ratio", "delta"))
print("-" * 92)
for name, fg, bgs, target, direction in CASES:
    f = parse(fg)
    bl = [parse(b) for b in bgs]
    before = min(ratio(f, b) for b in bl)
    out, after, t = solve(f, bl, target, direction)
    mark = "unchanged" if out == f else ("%s %.1f%%" % (direction, t * 100))
    print("%-46s %-11s %-11s %.2f→%.2f  %s" % (name, hexof(f), hexof(out), before, after, mark))
