#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 5.json 精确回推出真正的 4.json（一次性修复脚本，改完即可留档）。

背景：仓库里的 4.json 是从 5.json 复制、只把 "version" 改成 4 得来的 —— 两份的
identityHash 完全相同（244437077e7c0552291a814958537854），而内容里带着 messages 表的
stepsJson / attachmentsJson 两列。这两列是 MIGRATION_4_5 才加的，v4 的库不该有：

  * MigrationTestHelper.createDatabase(4) 会按 4.json 的 createSql 建表，messages 已带这两列，
    紧接着 MIGRATION_4_5 执行 `ALTER TABLE messages ADD COLUMN stepsJson` → duplicate column name。
  * 反向的 3→4 用例里，从 3.json 建库再跑 MIGRATION_3_4 得到的 messages 没有这两列，
    拿 4.json 做校验则会报 "Migration didn't properly handle messages"。

真正的 v4 = v3 + MIGRATION_3_4 加的 conversations.status，messages 保持 v3 的形状。
所以这里以 5.json 为底做文本级切除（保住 Room 自己的排版和 CRLF），再用
room_identity_hash.py 复刻的算法重算 identityHash。
"""

import io
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SCHEMA_DIR = os.path.join(ROOT, "app", "schemas", "com.yumark.app.data.local.db.AppDatabase")

sys.path.insert(0, HERE)
from room_identity_hash import database_identity_hash  # noqa: E402

OLD_HASH = "244437077e7c0552291a814958537854"

# createSql 里要去掉的两列（连同分隔的 ", "）
SQL_DROP = "`stepsJson` TEXT, `attachmentsJson` TEXT, "

# fields 数组里要去掉的两个对象。用 \r\n 拼：这些 schema 文件是 CRLF。
FIELDS_DROP = (
    "\r\n"
    '          {\r\n'
    '            "fieldPath": "stepsJson",\r\n'
    '            "columnName": "stepsJson",\r\n'
    '            "affinity": "TEXT",\r\n'
    '            "notNull": false\r\n'
    "          },\r\n"
    '          {\r\n'
    '            "fieldPath": "attachmentsJson",\r\n'
    '            "columnName": "attachmentsJson",\r\n'
    '            "affinity": "TEXT",\r\n'
    '            "notNull": false\r\n'
    "          }"
)


def die(msg):
    print("FAIL: " + msg)
    sys.exit(1)


def main():
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    src = os.path.join(SCHEMA_DIR, "5.json")
    dst = os.path.join(SCHEMA_DIR, "4.json")

    with io.open(src, encoding="utf-8", newline="") as fh:
        text = fh.read()

    if text.count(SQL_DROP) != 1:
        die("createSql 里的两列没找到或不止一处，5.json 结构变了")
    text = text.replace(SQL_DROP, "")

    # 切除 fields 里的两个对象：前一个对象（timestamp）末尾的 "}," 要收成 "}"
    marker = '            "notNull": true\r\n          },' + FIELDS_DROP
    if text.count(marker) != 1:
        die("messages.fields 里 timestamp 后紧跟的两个字段对象没匹配上")
    text = text.replace(marker, '            "notNull": true\r\n          }')

    text = text.replace('"version": 5,', '"version": 4,', 1)

    import json
    db = json.loads(text)["database"]
    new_hash = database_identity_hash(db)
    if new_hash == OLD_HASH:
        die("重算出的 identityHash 与 v5 相同，说明切除没生效")
    print("new identityHash = " + new_hash)

    if text.count(OLD_HASH) != 2:
        die("identityHash 出现次数不是 2（database.identityHash + room_master_table 的 INSERT）")
    text = text.replace(OLD_HASH, new_hash)

    with io.open(dst, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)
    print("written: " + dst)

    rc = subprocess.call([sys.executable, os.path.join(HERE, "room_identity_hash.py"), "verify"])
    sys.exit(rc)


if __name__ == "__main__":
    main()
