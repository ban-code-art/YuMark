#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重算 Room 导出 schema 的 identityHash。

为什么需要它：`app/schemas/<db>/<version>.json` 里的 identityHash 是 Room 编译器算出来的，
手写或手改一份历史 schema 时没法凭猜写对。而 4.json 曾被人直接从 5.json 复制改版本号，
两份的 identityHash 一模一样 —— 这种「内容不同、哈希相同」的状态本身就是 schema 被伪造的
指纹，靠肉眼 diff 很难注意到。

算法照 room-compiler 2.8.4 的字节码逐字复刻（androidx.room.vo）：

    SchemaIdentityKey.SEPARATOR = "?:?"
    SchemaIdentityKey.append(s)        -> sb += s + SEPARATOR
    SchemaIdentityKey.appendSorted(xs) -> 按 idKey.lowercase(ENGLISH) 排序后逐个 append
    SchemaIdentityKey.hash()           -> md5Hex(sb)

    Database.identityHash = key{ appendSorted(entities); appendSorted(views) }.hash()
    Entity.idKey          = key{ append(tableName); append(primaryKey);
                                 appendSorted(properties); appendSorted(indices);
                                 appendSorted(foreignKeys) }.hash()
    Property.idKey        = "$columnName-${affinity ?: TEXT}-$nonNull"
                            + (defaultValue?.let { "-defaultValue=$it" } ?: "")
    PrimaryKey.idKey      = "$autoGenerateId-$columnNames"     // Java List.toString()
    Index.idKey           = "$unique-$name-${columns.join(\",\")}"
                            + (orders.takeIf { it.isNotEmpty() }?.let { "-${it.join(\",\")}" } ?: "")
    ForeignKey.idKey      = "$parentTable-$parentColumns-$childColumns-$onDelete-$onUpdate-$deferred"

`deferred` 不在导出的 bundle 里（ForeignKeyBundle 没这个字段），Room 注解的默认值是 false，
且 createSql 里没有 `DEFERRABLE INITIALLY DEFERRED` —— 所以按 false 处理。

用法：
    python scripts/room_identity_hash.py verify           # 用已知的 12 份 schema 自检实现
    python scripts/room_identity_hash.py hash <file.json> # 打印某份 bundle 的正确 identityHash
"""

import glob
import hashlib
import io
import json
import os
import sys

SEPARATOR = "?:?"
SCHEMA_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "schemas", "com.yumark.app.data.local.db.AppDatabase",
)


def md5hex(s: str) -> str:
    return hashlib.md5(s.encode("utf-8")).hexdigest()


def jbool(b) -> str:
    """Kotlin 字符串模板里的 Boolean。"""
    return "true" if b else "false"


def jlist(xs) -> str:
    """Java AbstractCollection.toString()：`[a, b]`，逗号后有一个空格。"""
    return "[" + ", ".join(xs) + "]"


def append_sorted(keys) -> str:
    # ENGLISH_SORT: 两边都 toLowerCase(Locale.ENGLISH) 再 compareTo。
    # 参与排序的都是 ASCII（md5 十六进制串、列名、affinity、布尔），Python 的
    # str.lower() + 码点比较与之等价。
    return "".join(k + SEPARATOR for k in sorted(keys, key=lambda s: s.lower()))


def property_idkey(f: dict) -> str:
    # notNull 为 false 时 Room 的序列化器会整个省掉这个键，不能按缺失即报错处理
    s = "{}-{}-{}".format(
        f["columnName"], f.get("affinity") or "TEXT", jbool(f.get("notNull", False))
    )
    if f.get("defaultValue") is not None:
        s += "-defaultValue={}".format(f["defaultValue"])
    return s


def primary_key_idkey(pk: dict) -> str:
    return "{}-{}".format(jbool(pk.get("autoGenerate", False)), jlist(pk["columnNames"]))


def index_idkey(ix: dict) -> str:
    s = "{}-{}-{}".format(jbool(ix["unique"]), ix["name"], ",".join(ix["columnNames"]))
    orders = ix.get("orders") or []
    if orders:
        s += "-{}".format(",".join(orders))
    return s


def foreign_key_idkey(fk: dict) -> str:
    return "-".join([
        fk["table"],
        ",".join(fk["referencedColumns"]),
        ",".join(fk["columns"]),
        fk["onDelete"],
        fk["onUpdate"],
        jbool(False),  # ForeignKeyBundle 不导出 deferred；注解默认 false
    ])


def entity_idkey(e: dict) -> str:
    if "ftsVersion" in e:
        raise NotImplementedError("FTS 实体走 FtsEntity.idKey，本脚本未复刻：" + e["tableName"])
    sb = e["tableName"] + SEPARATOR
    sb += primary_key_idkey(e["primaryKey"]) + SEPARATOR
    sb += append_sorted([property_idkey(f) for f in e["fields"]])
    sb += append_sorted([index_idkey(i) for i in e.get("indices") or []])
    sb += append_sorted([foreign_key_idkey(k) for k in e.get("foreignKeys") or []])
    return md5hex(sb)


def database_identity_hash(db: dict) -> str:
    if db.get("views"):
        raise NotImplementedError("含 database view 的 schema 需要 DatabaseView.idKey，本脚本未复刻")
    sb = append_sorted([entity_idkey(e) for e in db["entities"]])
    sb += append_sorted([])  # views
    return md5hex(sb)


def load(path: str) -> dict:
    with io.open(path, encoding="utf-8") as fh:
        return json.load(fh)["database"]


def cmd_verify() -> int:
    files = sorted(
        glob.glob(os.path.join(SCHEMA_DIR, "*.json")),
        key=lambda p: int(os.path.basename(p)[:-5]),
    )
    ok = skipped = bad = 0
    for path in files:
        name = os.path.basename(path)
        db = load(path)
        try:
            got = database_identity_hash(db)
        except NotImplementedError as e:
            print("SKIP {:<8} {}".format(name, e))
            skipped += 1
            continue
        want = db["identityHash"]
        if got == want:
            print("OK   {:<8} {}".format(name, got))
            ok += 1
        else:
            print("BAD  {:<8} stored={} computed={}".format(name, want, got))
            bad += 1
    print("\nOK {} 份，BAD {} 份，SKIP {} 份".format(ok, bad, skipped))
    return 1 if bad else 0


def cmd_hash(path: str) -> int:
    db = load(path)
    print(database_identity_hash(db))
    return 0


def main() -> int:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    args = sys.argv[1:]
    if args and args[0] == "verify":
        return cmd_verify()
    if len(args) == 2 and args[0] == "hash":
        return cmd_hash(args[1])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
