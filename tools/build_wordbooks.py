# -*- coding: utf-8 -*-
"""
把 wordbook-source/ 里的 ECDICT 体系源数据（kajweb/dict 例句版）转换成
应用内置词书格式，并写入 app/src/main/assets/wordbooks/。

输出格式（WordBookJsonParser 解析）：
  { "name": ..., "description": ...,
    "words": [ {"term","meaning","phonetic"?, "example"?, "exampleTranslation"?} ] }

要点：
- 数据源：开源 ECDICT（https://github.com/skywind3000/ECDICT ，MIT License），
  例句来自公开词典整理（kajweb/dict，同样基于 ECDICT 体系）。
- 词性 type 归一成短缩写（n./v./adj./adv./…），多个词性用 / 连。
- 取第一对「英文句 + 中文译文」都非空的例句；音标优先美式 us。
- **单词顺序随机打乱**（固定 seed，可复现），不按字母/教材顺序排列。
- 去重（同 term 保留第一条），缺释义的条目丢弃。

用法： python tools/build_wordbooks.py
"""
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT / "wordbook-source"
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "wordbooks"

POS_MAP = {
    "n": "n.",
    "v": "v.",
    "vt": "vt.",
    "vi": "vi.",
    "adj": "adj.",
    "adv": "adv.",
    "prep": "prep.",
    "pron": "pron.",
    "num": "num.",
    "conj": "conj.",
    "int": "interj.",
    "interjection": "interj.",
    "abbr": "abbr.",
    "aux": "aux.",
    "art": "art.",
    "modal verb": "modal v.",
    "determiner": "det.",
    "conjunction": "conj.",
}


def pos_label(t: str) -> str:
    """indefinite article / n&v 这类归一化成短缩写。"""
    parts = [p.strip().lower() for p in t.split("&")]
    labels = [POS_MAP.get(p, p + ".") for p in parts]
    return "/".join(labels)


def convert(src: Path, dst: Path, name: str, description: str, seed: int) -> int:
    data = json.loads(src.read_text(encoding="utf-8"))
    entries = []
    seen = set()
    for w in data.get("words", []):
        term = (w.get("word") or "").strip()
        if not term or term in seen:
            continue

        parts = []
        for tr in w.get("translations") or []:
            text = (tr.get("translation") or "").strip()
            if not text:
                continue
            ptype = (tr.get("type") or "").strip()
            parts.append(f"{pos_label(ptype)} {text}" if ptype else text)
        if not parts:
            continue

        phonetic = (w.get("us") or w.get("uk") or "").strip()
        if phonetic and not phonetic.startswith("/"):
            phonetic = f"/{phonetic}/"
        example = example_tr = ""
        for s in w.get("sentences") or []:
            en = (s.get("sentence") or "").strip()
            zh = (s.get("translation") or "").strip()
            if en and zh:
                example, example_tr = en, zh
                break

        entry = {"term": term, "meaning": "；".join(parts)}
        if phonetic:
            entry["phonetic"] = phonetic
        if example:
            entry["example"] = example
            entry["exampleTranslation"] = example_tr
        entries.append(entry)
        seen.add(term)

    # 随机打乱顺序（固定 seed，保证可复现）
    random.Random(seed).shuffle(entries)

    dst.write_text(
        json.dumps(
            {"name": name, "description": description, "words": entries},
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )
    return len(entries)


def main() -> None:
    jobs = [
        (
            SRC_DIR / "初中词汇-例句版.json",
            OUT_DIR / "chuzhong-ecdict.json",
            "初中词汇",
            "初中英语词汇：音标、词性释义、例句与译文。词库基于开源 ECDICT（skywind3000/ECDICT，MIT License）与公开例句数据整理；单词顺序已随机打乱。",
            20260908,
        ),
        (
            SRC_DIR / "高中词汇-例句版.json",
            OUT_DIR / "gaozhong-ecdict.json",
            "高中词汇",
            "高中英语词汇：音标、词性释义、例句与译文。词库基于开源 ECDICT（skywind3000/ECDICT，MIT License）与公开例句数据整理；单词顺序已随机打乱。",
            20260909,
        ),
    ]
    for src, dst, name, desc, seed in jobs:
        count = convert(src, dst, name, desc, seed)
        print(f"{dst.name}: {count} words")


if __name__ == "__main__":
    main()
