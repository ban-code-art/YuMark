# -*- coding: utf-8 -*-
"""
资源与代码引用的静态一致性检查（不需要 gradle）。

存在的理由：本项目没有 lint.xml、没有 baseline，MissingTranslation 是 **error** 级，
而字符串文案按模块拆成了 strings.xml / strings_editor.xml / strings_filelist.xml /
strings_ai.xml / strings_settings.xml 多个文件，且由多个并行任务同时写入。
最容易出的三类错都不需要编译就能查出来：
  1. 只加了中文没加英文（或反过来）→ MissingTranslation / ExtraTranslation
  2. 两个文件里出现同名键 → 资源合并阶段直接失败
  3. 代码里 R.string.X 的 X 根本不存在 → 编译失败

用法：python scripts/check_strings.py
退出码 0 = 无致命问题；1 = 有致命问题（FATAL）。
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
BASE_DIR = os.path.join(RES, "values")
EN_DIR = os.path.join(RES, "values-en")
KOTLIN_ROOT = os.path.join(ROOT, "app", "src", "main", "java")

# %1$s / %1$d 这类带位置的，以及 %s / %d 这类裸的
PLACEHOLDER_RE = re.compile(r"%(?:(\d+)\$)?([a-zA-Z])")


def placeholders(text):
    """返回 (位置, 类型) 的有序列表；裸占位符按出现顺序补位置。"""
    out = []
    auto = 0
    for pos, kind in PLACEHOLDER_RE.findall(text or ""):
        if pos:
            out.append((int(pos), kind))
        else:
            auto += 1
            out.append((auto, kind))
    return sorted(out)


def collect(dir_path):
    """{key: {"file":…, "kind":"string|plurals|array", "text":…, "quantities":set}}"""
    found = {}
    dups = []
    if not os.path.isdir(dir_path):
        return found, dups
    for name in sorted(os.listdir(dir_path)):
        if not name.endswith(".xml"):
            continue
        path = os.path.join(dir_path, name)
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as e:
            print("FATAL  XML 解析失败: %s: %s" % (os.path.relpath(path, ROOT), e))
            dups.append(("<parse-error>", path, path))
            continue
        if root.tag != "resources":
            continue
        for el in root:
            if el.tag not in ("string", "plurals", "string-array"):
                continue
            key = el.get("name")
            if not key:
                continue
            if key in found:
                dups.append((key, found[key]["file"], name))
                continue
            if el.tag == "plurals":
                qty = {}
                for item in el.findall("item"):
                    qty[item.get("quantity")] = "".join(item.itertext())
                found[key] = {"file": name, "kind": "plurals", "text": None, "quantities": qty}
            elif el.tag == "string-array":
                found[key] = {"file": name, "kind": "array", "text": None, "quantities": {}}
            else:
                found[key] = {
                    "file": name,
                    "kind": "string",
                    # itertext 是为了带 <xliff:g> 之类子元素的条目
                    "text": "".join(el.itertext()),
                    "quantities": {},
                }
    return found, dups


def collect_refs():
    """代码与资源里对字符串的引用。返回 {key: 第一个引用位置}"""
    refs = {}
    pat_kt = re.compile(r"R\.(?:string|plurals|array)\.([A-Za-z_]\w*)")
    pat_xml = re.compile(r"@(?:string|plurals|array)/([A-Za-z_]\w*)")
    targets = []
    for base, _dirs, files in os.walk(KOTLIN_ROOT):
        for f in files:
            if f.endswith(".kt"):
                targets.append((os.path.join(base, f), pat_kt))
    for base, _dirs, files in os.walk(os.path.join(ROOT, "app", "src", "main")):
        for f in files:
            if f.endswith(".xml"):
                targets.append((os.path.join(base, f), pat_xml))
    for path, pat in targets:
        try:
            with open(path, "r", encoding="utf-8") as fh:
                for lineno, line in enumerate(fh, 1):
                    for key in pat.findall(line):
                        refs.setdefault(key, "%s:%d" % (os.path.relpath(path, ROOT), lineno))
        except (OSError, UnicodeDecodeError):
            continue
    return refs


def main():
    base, base_dups = collect(BASE_DIR)
    en, en_dups = collect(EN_DIR)
    refs = collect_refs()

    fatal = 0
    warn = 0

    def report(level, msg):
        nonlocal fatal, warn
        if level == "FATAL":
            fatal += 1
        else:
            warn += 1
        print("%-6s %s" % (level, msg))

    print("=== 键数量 ===")
    print("values/      %d 个键，%d 个文件" % (len(base), len(set(v["file"] for v in base.values()))))
    print("values-en/   %d 个键，%d 个文件" % (len(en), len(set(v["file"] for v in en.values()))))
    print()

    print("=== 1. 同一语区内的重复键（资源合并会直接失败） ===")
    for key, f1, f2 in base_dups:
        report("FATAL", "values/ 重复键 %s（%s 与 %s）" % (key, f1, f2))
    for key, f1, f2 in en_dups:
        report("FATAL", "values-en/ 重复键 %s（%s 与 %s）" % (key, f1, f2))
    if not base_dups and not en_dups:
        print("       无")
    print()

    print("=== 2. MissingTranslation：中文有、英文没有（error 级，阻断构建） ===")
    missing_en = sorted(k for k in base if k not in en)
    for key in missing_en:
        report("FATAL", "%s 缺英文（中文在 %s）" % (key, base[key]["file"]))
    if not missing_en:
        print("       无")
    print()

    print("=== 3. ExtraTranslation：英文有、中文没有 ===")
    extra_en = sorted(k for k in en if k not in base)
    for key in extra_en:
        report("FATAL", "%s 只有英文（在 %s），基准语区缺失" % (key, en[key]["file"]))
    if not extra_en:
        print("       无")
    print()

    print("=== 4. 类型不一致（string vs plurals） ===")
    type_bad = 0
    for key in sorted(base):
        if key in en and base[key]["kind"] != en[key]["kind"]:
            report("FATAL", "%s 中文是 %s，英文是 %s" % (key, base[key]["kind"], en[key]["kind"]))
            type_bad += 1
    if not type_bad:
        print("       无")
    print()

    print("=== 5. plurals 的 quantity 覆盖（缺 other = MissingQuantity） ===")
    qty_bad = 0
    for locale, table, need in (("values", base, ("other",)), ("values-en", en, ("one", "other"))):
        for key in sorted(table):
            if table[key]["kind"] != "plurals":
                continue
            have = set(table[key]["quantities"])
            for q in need:
                if q not in have:
                    report("FATAL", "%s/%s 的 plurals %s 缺 quantity=\"%s\"（现有 %s）"
                           % (locale, table[key]["file"], key, q, sorted(have) or "无"))
                    qty_bad += 1
    if not qty_bad:
        print("       无")
    print()

    print("=== 6. 占位符不一致（个数或类型两边不同 → 运行期 IllegalFormatException） ===")
    ph_bad = 0
    for key in sorted(base):
        if key not in en:
            continue
        if base[key]["kind"] == "string" and en[key]["kind"] == "string":
            a, b = placeholders(base[key]["text"]), placeholders(en[key]["text"])
            if a != b:
                report("FATAL", "%s 占位符不一致：中文 %s，英文 %s" % (key, a, b))
                ph_bad += 1
        elif base[key]["kind"] == "plurals" and en[key]["kind"] == "plurals":
            # 以中文的 other 为基准，英文每个 quantity 都要一致
            ref = placeholders(base[key]["quantities"].get("other", ""))
            for q, text in sorted(en[key]["quantities"].items()):
                if placeholders(text) != ref:
                    report("FATAL", "%s 的英文 quantity=%s 占位符 %s 与中文 other 的 %s 不一致"
                           % (key, q, placeholders(text), ref))
                    ph_bad += 1
    if not ph_bad:
        print("       无")
    print()

    print("=== 7. 代码引用了不存在的键（编译失败） ===")
    dangling = sorted(k for k in refs if k not in base)
    for key in dangling:
        report("FATAL", "R.string/R.plurals.%s 不存在（引用于 %s）" % (key, refs[key]))
    if not dangling:
        print("       无")
    print()

    print("=== 8. 定义了但没被引用的键（UnusedResources，warning 级，仅供参考） ===")
    unused = sorted(k for k in base if k not in refs)
    print("       共 %d 个：%s" % (len(unused), ", ".join(unused) if unused else "无"))
    print()

    print("=== 结论 ===")
    print("FATAL %d 条，WARN %d 条" % (fatal, warn))
    return 1 if fatal else 0


if __name__ == "__main__":
    sys.exit(main())
