"""Parse AppThemes.kt and audit every WCAG pair in the four static ColorSchemes.

Ground truth for ThemeContrastTest.kt: whatever this reports as passing is what the
Kotlin test may assert. Run it after any color edit.

    python scripts/wcag_audit.py
"""
import io
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

SRC = 'app/src/main/java/com/yumark/app/presentation/theme/AppThemes.kt'

# --- WCAG 2.1 math (same as wcag_solve.py) ---------------------------------


def srgb_to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def lum(rgb):
    r, g, b = (srgb_to_lin(x) for x in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


# --- parse -----------------------------------------------------------------

SLOT = re.compile(r'(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)')


def slots(region):
    out = {}
    for name, hex8 in SLOT.findall(region):
        h = hex8[2:]
        out[name] = (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))
    return out


def schemes(path):
    text = open(path, encoding='utf-8').read()
    bounds = [
        ('Default', text.index('private val DefaultTheme'), text.index('private val ClaudeTheme')),
        ('Claude', text.index('private val ClaudeTheme'), text.index('private val DynamicTheme')),
    ]
    out = {}
    for name, lo, hi in bounds:
        block = text[lo:hi]
        li = block.index('light = lightColorScheme(')
        di = block.index('dark = darkColorScheme(')
        out['%s-light' % name] = slots(block[li:di])
        out['%s-dark' % name] = slots(block[di:])
    return out


# --- the pair sets the Kotlin test will encode ------------------------------

# M3 的 on-X / X 配对：这些是货真价实的「文字压在填充上」，一律 4.5:1。
ON_PAIRS = [
    ('onPrimary', 'primary'),
    ('onSecondary', 'secondary'),
    ('onTertiary', 'tertiary'),
    ('onError', 'error'),
    ('onBackground', 'background'),
    ('onSurface', 'surface'),
    ('onSurfaceVariant', 'surfaceVariant'),
    ('onPrimaryContainer', 'primaryContainer'),
    ('onSecondaryContainer', 'secondaryContainer'),
    ('onTertiaryContainer', 'tertiaryContainer'),
    ('onErrorContainer', 'errorContainer'),
    ('inverseOnSurface', 'inverseSurface'),
]

# 正文前景可能落在的全部底色。M3 的 AlertDialog 容器是 surfaceContainerHigh、
# ModalBottomSheet 是 surfaceContainerLow，所以容器家族全部纳入。
SURFACES = [
    'surface', 'background', 'surfaceVariant',
    'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest',
]

# 落在上面那批底色上的前景角色。
FG_ON_SURFACES = [('onSurface', 4.5), ('onSurfaceVariant', 4.5), ('outline', 3.0)]

# 明知不达标、已确认保留的豁免项。键是 "scheme|fg|bg"。
EXEMPT = {
    'Claude-light|onPrimary|primary': '品牌签名色；达标只能压深赤陶或把按钮文字改深，两者都改掉主题身份',
    'Claude-dark|onPrimary|primary': '同上（深浅两套 primary 同值）',
}

fails, exempted, checked = [], [], 0
for name, s in schemes(SRC).items():
    missing = [k for k in set(SURFACES) | {p for pair in ON_PAIRS for p in pair} if k not in s]
    if missing:
        print('!! %s 缺少槽位: %s' % (name, ', '.join(sorted(missing))))
    for fg, bg in ON_PAIRS:
        if fg not in s or bg not in s:
            continue
        checked += 1
        r = ratio(s[fg], s[bg])
        key = '%s|%s|%s' % (name, fg, bg)
        if r < 4.5:
            (exempted if key in EXEMPT else fails).append((key, r, 4.5))
    for fg, target in FG_ON_SURFACES:
        if fg not in s:
            continue
        for bg in SURFACES:
            if bg not in s:
                continue
            checked += 1
            r = ratio(s[fg], s[bg])
            key = '%s|%s|%s' % (name, fg, bg)
            if r < target:
                (exempted if key in EXEMPT else fails).append((key, r, target))

print('已检查配对: %d' % checked)
print('豁免 %d 项:' % len(exempted))
for key, r, t in exempted:
    print('  %-44s %.2f (需 %.1f)  ← %s' % (key, r, t, EXEMPT[key]))
print('不达标 %d 项:' % len(fails))
for key, r, t in sorted(fails, key=lambda x: x[1]):
    print('  %-44s %.2f (需 %.1f)' % (key, r, t))
sys.exit(1 if fails else 0)
