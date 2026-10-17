# -*- coding: utf-8 -*-
"""
给 ecdict_core.db 追加 relation 表（同义词 / 反义词）。

数据源：Princeton WordNet 3.0（含 NLTK 打包版，与本仓库 tools 用法一致）。
- 同义：同一 synset 内的其它 lemma（WordNet 的「同义词集」定义）
- 反义：synset 数据行里 `!` 指针（antonym）指向的目标 synset 词

收敛策略（控体积 + 保证可点跳）：
1. 只保留「源词」与「目标词」都存在于本词典 dict 表的条目；
2. 每个源词每类最多若干条，按目标词的 COCA 词频（dict.frq）降序取前面；
3. 目标词的中文释义只取 dict.zh 首行（截断到 30 字符），避免重复整段释义撑体积。

relation 表结构：
  word     TEXT  源词（小写，查询用 COLLATE NOCASE）
  rel_type TEXT  syn = 同义 / ant = 反义
  target   TEXT  目标词（小写，保证在 dict 表中可点跳）
  zh       TEXT  目标词中文释义（dict.zh 首行，已截断）

用法： python tools/build_relations.py [wordnet_data_dir]
      默认从 _tmp/wn/wordnet 读取；目录不存在则报错退出。
幂等：先 DROP TABLE IF EXISTS 再重建，可反复重跑。
"""
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "core" / "src" / "main" / "assets" / "dict" / "ecdict_core.db"
DEFAULT_WN = ROOT / "_tmp" / "wn" / "wordnet"

MAX_PER_TYPE = 6          # 每词每类上限
ZH_MAX_LEN = 30           # 目标词中文释义截断长度

DATA_FILES = ("data.noun", "data.verb", "data.adj", "data.adv")

# index.sense 里的词性数字 → data.* 文件里的词性码（5 = 形容词卫星集，归到 a）
POS_DIGIT = {"1": "n", "2": "v", "3": "a", "4": "r", "5": "a"}


def load_senses(wn_dir):
    """解析 index.sense，返回 {(小写词, pos): [(tag_cnt, sense_num, offset), ...]}。

    用于「取前 K 个义项」：WordNet 一个词常横跨多个义项，若全用会让
    「big → boastful / crowing」这类跨义项噪声混进来；只用第一义项又会漏掉
    「happy → felicitous」（happy 第一义项 synset 内没有别的常用词）。
    折中：按语料标注频次 tag_cnt 降序、义项序升序排，取前 K 个义项。
    """
    out = {}
    f = wn_dir / "index.sense"
    if not f.exists():
        return out
    with f.open(encoding="utf-8", errors="replace") as fh:
        for line in fh:
            parts = line.split()
            if len(parts) < 4:
                continue
            key = parts[0]
            if "%" not in key:
                continue
            lemma, rest = key.split("%", 1)
            pos = POS_DIGIT.get(rest[0]) if rest else None
            if pos is None:
                continue
            try:
                sense_num = int(parts[2])
                tag_cnt = int(parts[3])
            except ValueError:
                continue
            out.setdefault((lemma.lower(), pos), []).append((tag_cnt, sense_num, parts[1]))
    for v in out.values():
        v.sort(key=lambda x: (-x[0], x[1]))
    return out


TOP_SENSES = 2    # 每个 (词, 词性) 只取前 K 个义项


def load_dict(conn):
    """返回 {小写词: (原样词, 首行中文释义, frq)}"""
    out = {}
    for word, zh, frq in conn.execute("SELECT word, zh, frq FROM dict"):
        if not word:
            continue
        key = word.strip().lower()
        if not key or key in out:
            continue
        first = (zh or "").split("\n")[0].strip()
        out[key] = (word.strip(), first, frq or 0)
    return out


def parse_synsets(wn_dir):
    """解析 WordNet data.* 文件。

    返回 (synsets, ant_edges)：
      synsets: {(pos, offset): [小写词, ...]}
      ant_edges: [(源词, 目标词), ...]  已按 `!` 指针解出的词对
    """
    synsets = {}
    raw_ant = []   # [(src_key, ppos, poff, dst_idx)]

    for name in DATA_FILES:
        f = wn_dir / name
        if not f.exists():
            continue
        with f.open(encoding="utf-8", errors="replace") as fh:
            for line in fh:
                head = line[:8]
                if len(head) < 8 or not head.isdigit():
                    continue  # 版权头 / 空行
                data = line.split(" | ", 1)[0]
                t = data.split()
                if len(t) < 6:
                    continue
                offset = t[0]
                ss_type = t[2]
                pos = "a" if ss_type in ("a", "s") else ss_type
                w_cnt = int(t[3], 16)
                i = 4
                words = []
                for _ in range(w_cnt):
                    if i + 1 >= len(t):
                        break
                    words.append(t[i].strip().lower().replace("_", " "))
                    i += 2
                if not words:
                    continue
                key = (pos, offset)
                synsets.setdefault(key, words)
                if i >= len(t):
                    continue
                p_cnt = int(t[i])
                i += 1
                # 指针格式 4 个 token：symbol offset pos src_dst
                # src_dst 为两字节十六进制：高字节=源 synset 词号，低字节=目标 synset 词号
                for _ in range(p_cnt):
                    if i + 3 >= len(t):
                        break
                    symbol = t[i]
                    poff = t[i + 1]
                    ppos = t[i + 2]
                    try:
                        src_dst = int(t[i + 3], 16)
                    except ValueError:
                        break
                    src = (src_dst >> 8) & 0xFF
                    dst = src_dst & 0xFF
                    i += 4
                    if symbol != "!":
                        continue
                    # 形容词卫星集也归到 a
                    ppos = "a" if ppos in ("a", "s") else ppos
                    idx = src - 1 if src >= 1 else 0
                    if idx >= len(words):
                        idx = 0
                    # 记录：源词、源 synset（pos+offset，判常用义项用）、目标 pos/offset、目标词号
                    raw_ant.append((words[idx], pos, offset, ppos, poff, dst))
    return synsets, raw_ant


def collect(wn_dir, dict_map):
    synsets, raw_ant = parse_synsets(wn_dir)
    senses = load_senses(wn_dir)

    def top_offsets(word, pos):
        """该词在指定词性下「最常用的前 K 个义项」的 synset offset 集合。"""
        entries = senses.get((word, pos))
        if not entries:
            return None
        return {off for _, _, off in entries[:TOP_SENSES]}

    def is_top(word, pos, offset):
        allowed = top_offsets(word, pos)
        return allowed is None or offset in allowed

    _dompos_cache = {}

    def dominant_pos(word):
        """该词标注频次最高的词性。跨词性的同义（形容词 big → 副词 boastfully）
        语义上站不住，只保留主词性下的义项。"""
        if word in _dompos_cache:
            return _dompos_cache[word]
        best, best_cnt = None, -1
        for pos in POS_DIGIT.values():
            entries = senses.get((word, pos))
            if entries and entries[0][0] > best_cnt:
                best, best_cnt = pos, entries[0][0]
        _dompos_cache[word] = best
        return best

    syn_pairs = {}   # src -> set(target)
    for (pos, offset), words in synsets.items():
        present = [w for w in set(words) if w in dict_map]
        if len(present) < 2:
            continue
        for a in present:
            # 只认该词主词性下最常用的几个义项，避免跨义项/跨词性噪声
            if pos != dominant_pos(a):
                continue
            if not is_top(a, pos, offset):
                continue
            for b in present:
                if a != b:
                    syn_pairs.setdefault(a, set()).add(b)

    ant_pairs = {}
    for src, spos, spoff, tpos, toff, dst in raw_ant:
        if src not in dict_map:
            continue
        # 反义同样只认源词常用义项所在的 synset，且不跨词性
        if spos != tpos:
            continue
        if spos != dominant_pos(src) or not is_top(src, spos, spoff):
            continue
        targets = synsets.get((tpos, toff))
        if not targets:
            continue
        idx = dst - 1 if dst >= 1 else 0
        if idx >= len(targets):
            idx = 0
        tgt = targets[idx]
        if tgt in dict_map and tgt != src:
            ant_pairs.setdefault(src, set()).add(tgt)

    def rank(pairs):
        rows = []
        for src, tgts in pairs.items():
            ordered = sorted(
                tgts,
                key=lambda w: (-dict_map[w][2], len(w), w)
            )[:MAX_PER_TYPE]
            for tgt in ordered:
                rows.append((src, tgt, dict_map[tgt][1][:ZH_MAX_LEN]))
        rows.sort()
        return rows

    return rank(syn_pairs), rank(ant_pairs)


def main():
    wn_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_WN
    if not wn_dir.exists():
        print(f"[error] WordNet 数据目录不存在：{wn_dir}")
        print("        请先下载解压 NLTK wordnet.zip 到该目录（见文件头说明）")
        sys.exit(1)

    size_before = DB.stat().st_size
    conn = sqlite3.connect(str(DB))
    dict_map = load_dict(conn)
    print(f"dict words = {len(dict_map)}, db size = {round(size_before/1024/1024, 2)} MB")

    syn_rows, ant_rows = collect(wn_dir, dict_map)
    syn_all = sorted({r[0] for r in syn_rows})
    ant_all = sorted({r[0] for r in ant_rows})
    print(f"relation rows: syn={len(syn_rows)} ({len(syn_all)} 词) "
          f"ant={len(ant_rows)} ({len(ant_all)} 词)")

    cur = conn.cursor()
    cur.execute("DROP TABLE IF EXISTS relation")
    cur.execute(
        "CREATE TABLE relation ("
        " word TEXT NOT NULL,"
        " rel_type TEXT NOT NULL,"
        " target TEXT NOT NULL,"
        " zh TEXT NOT NULL)"
    )
    cur.execute("CREATE INDEX idx_relation_word ON relation(word)")
    cur.executemany(
        "INSERT INTO relation VALUES (?,?,?,?)",
        [(w, "syn", t, zh) for w, t, zh in syn_rows]
        + [(w, "ant", t, zh) for w, t, zh in ant_rows]
    )
    conn.commit()
    cur.execute("VACUUM")
    conn.commit()
    n = cur.execute("SELECT COUNT(*) FROM relation").fetchone()[0]
    conn.close()
    size_after = DB.stat().st_size
    print(f"relation rows = {n}, db size = {round(size_after/1024/1024, 2)} MB "
          f"(+{round((size_after-size_before)/1024/1024, 2)} MB)")


if __name__ == "__main__":
    main()
