# -*- coding: utf-8 -*-
"""
给 ecdict_core.db 追加 phrase 表（短语数据源：本地 wordbook-source/*-例句版.json，
短语来自 kajweb/dict 整理，仅抽取 phrase + translation + 所属词，不污染 dict 主表）。

phrase 表结构：
  phrase   TEXT  英文短语（小写归一查询在 SQL 层做）
  zh       TEXT  中文释义
  src_word TEXT  来源词（用于「关联短语」反查：relatedPhrases(term)）

用法： python tools/build_phrases.py
幂等：先 DROP TABLE IF EXISTS 再重建，可反复重跑。
"""
import json
import sqlite3
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "core" / "src" / "main" / "assets" / "dict" / "ecdict_core.db"
SRC = sorted((ROOT / "wordbook-source").glob("*-例句版.json"))


def collect():
    # phrase 归一去重：同一短语取第一次出现的释义；src_word 记录首个关联词
    seen = {}  # phrase.lower() -> (phrase, zh, src_word)
    for f in SRC:
        data = json.loads(f.read_text(encoding="utf-8"))
        for w in data.get("words", []):
            head = (w.get("word") or "").strip()
            for p in w.get("phrases") or []:
                phrase = (p.get("phrase") or "").strip()
                zh = (p.get("translation") or "").strip()
                if not phrase or not zh or not head:
                    continue
                key = phrase.lower()
                if key not in seen:
                    seen[key] = (phrase, zh, head.lower())
    return list(seen.values())


def main():
    rows = collect()
    print(f"collected {len(rows)} unique phrases from {len(SRC)} books")
    conn = sqlite3.connect(str(DB))
    cur = conn.cursor()
    cur.execute("DROP TABLE IF EXISTS phrase")
    cur.execute(
        "CREATE TABLE phrase ("
        " phrase TEXT NOT NULL,"
        " zh TEXT NOT NULL,"
        " src_word TEXT NOT NULL)"
    )
    # 搜索 LIKE 用不上索引（前导通配），但 src_word 等值反查用得上
    cur.execute("CREATE INDEX idx_phrase_src ON phrase(src_word)")
    cur.executemany("INSERT INTO phrase VALUES (?,?,?)", rows)
    conn.commit()
    n = cur.execute("SELECT COUNT(*) FROM phrase").fetchone()[0]
    conn.close()
    size_mb = round(DB.stat().st_size / 1024 / 1024, 2)
    print(f"phrase table rows={n}, db size={size_mb} MB")


if __name__ == "__main__":
    main()
