# -*- coding: utf-8 -*-
"""从 ECDICT 完整库生成手表用的词典子集 + 高考词书 JSON。

词典：考纲标签(zk/gk/cet4/cet6/ky/toefl/ielts/gre)全部保留，
再按 COCA 词频(frq)补齐高频词到目标行数，输出只读子集 sqlite。
词书：gk 标签的词按 assets 词书 JSON 同构格式导出。
"""
import json
import re
import os
import sqlite3
import sys

SRC = "stardict.db"
DICT_OUT = "../../app/src/main/assets/dict/ecdict_core.db"
BOOK_OUT = "../../app/src/main/assets/wordbooks/gk-core.json"
EXAM_TAGS = {"zk", "gk", "cet4", "cet6", "ky", "toefl", "ielts", "gre"}
TARGET_ROWS = 130_000
MEANING_CAP = 90


def main():
    os.makedirs(os.path.dirname(DICT_OUT), exist_ok=True)
    for path in (DICT_OUT, BOOK_OUT):
        if os.path.exists(path):
            os.remove(path)

    src = sqlite3.connect(SRC)
    out = sqlite3.connect(DICT_OUT)
    out.execute("ATTACH DATABASE ? AS src", (os.path.abspath(SRC),))
    out.execute(
        """
        CREATE TABLE dict(
            word TEXT COLLATE NOCASE PRIMARY KEY,
            phonetic TEXT,
            zh TEXT,
            en TEXT,
            exchange TEXT,
            frq INTEGER
        ) WITHOUT ROWID
        """
    )

    tag_clause = " OR ".join(
        "(' ' || COALESCE(tag, '') || ' ') LIKE ?" for _ in EXAM_TAGS
    )
    params = [f'% {t} %' for t in EXAM_TAGS]

    # 先收考纲词，再按词频补齐
    out.execute(
        f"""
        INSERT INTO dict(word, phonetic, zh, en, exchange, frq)
        SELECT word, phonetic, translation, definition, exchange, COALESCE(frq, 0)
        FROM src.stardict
        WHERE {tag_clause}
        """,
        params,
    )
    exam_rows = out.execute("SELECT COUNT(*) FROM dict").fetchone()[0]
    out.execute(
        """
        INSERT OR IGNORE INTO dict(word, phonetic, zh, en, exchange, frq)
        SELECT word, phonetic, translation, definition, exchange, COALESCE(frq, 0)
        FROM src.stardict
        WHERE COALESCE(frq, 0) > 0 OR COALESCE(bnc, 0) > 0
           OR COALESCE(collins, 0) > 0 OR COALESCE(oxford, 0) = 1
        ORDER BY COALESCE(frq, 999999) DESC, COALESCE(bnc, 999999) ASC
        LIMIT ?
        """,
        (TARGET_ROWS - exam_rows,),
    )
    total = out.execute("SELECT COUNT(*) FROM dict").fetchone()[0]
    out.commit()
    out.execute("VACUUM")
    out.close()

    size_mb = os.path.getsize(DICT_OUT) / 1048576
    print(f"dict rows={total} (exam={exam_rows}) size={size_mb:.1f}MB")
    if size_mb > 40:
        print("WARN: dict too big, consider trimming TARGET_ROWS", file=sys.stderr)

    # 高考词书
    rows = src.execute(
        """
        SELECT word, phonetic, translation FROM stardict
        WHERE tag LIKE '%gk%'
        ORDER BY word COLLATE NOCASE ASC
        """,
    ).fetchall()
    random.shuffle(words)
    words = []
    for term, phonetic, translation in rows:
        if not re.fullmatch(r"[a-zA-Z][a-zA-Z'-]*", term):
            continue
        # 逐行清洗：去掉 [网络] 罗列和各类换行噪音，只留正常释义
        lines = []
        for line in (translation or "").replace("\r", "\n").split("\n"):
            line = line.strip()
            if line and not line.startswith("[网络]") and not line.startswith("[医]"):
                lines.append(line)
        meaning = "；".join(lines)
        if len(meaning) > MEANING_CAP:
            meaning = meaning[: MEANING_CAP - 1] + "…"
        if not meaning:
            continue
        entry = {"term": term, "meaning": meaning}
        if phonetic:
            entry["phonetic"] = phonetic
        words.append(entry)

    book = {
        "name": "高考核心词",
        "description": "ECDICT 高考纲词汇（gk），含音标与释义",
        "words": words,
    }
    with open(BOOK_OUT, "w", encoding="utf-8") as f:
        json.dump(book, f, ensure_ascii=False, indent=1)
    print(f"book words={len(words)} -> {BOOK_OUT}")
    src.close()


if __name__ == "__main__":
    main()
