# 句式/句型数据集生成提示词

> 用途：交给任意 LLM/agent 批量生成「常考英语句式」结构化数据，产出供「刻词」App 的句式库使用。
> 使用方法：把下面 `====` 之间的全部内容原样粘贴给 agent。分批产出，每批 50 条。

====

# 任务

你是英语教学数据标注员。请生成一份**结构化 JSON 数据**，收录中国学生**初中 / 高中 / 四六级 / 考研**阶段的常考英语**句式（句型模板）**。

注意区分三个概念，你只输出第三类：

| 类型 | 例子 | 是否输出 |
|---|---|---|
| 单词 | `abandon` | 否 |
| 词组（固定搭配，无空槽） | `make sure`、`give up` | 否 |
| **句式（带空槽的模板）** | `make sb do sth`、`too...to...`、`It is adj for sb to do sth` | **是** |

# 输出格式（严格遵守）

每批输出**一个完整合法的 JSON 对象**，可直接被 `json.loads()` 解析：

```json
{
  "name": "常考句式",
  "description": "覆盖初高中至考研常考英语句式",
  "batch": 1,
  "total_planned": 300,
  "patterns": [
    {
      "pattern": "make sb do sth",
      "zh": "使某人做某事",
      "level": "junior",
      "category": "动词句型",
      "key": "make",
      "examples": [
        { "en": "The teacher made him clean the classroom.", "zh": "老师让他打扫教室。" }
      ],
      "uncertain": false
    }
  ]
}
```

## 字段说明

| 字段 | 类型 | 规则 |
|---|---|---|
| `pattern` | string | 句式模板。**占位符必须统一用这几个词**：`sb`（人）、`sth`（物）、`do`、`doing`、`to do`、`done`、`adj`、`adv`、`n`。全部小写。例：`make sb do sth` |
| `zh` | string | 中文释义，简洁，一句话，不加句号 |
| `level` | string | 只能取：`junior`（初中）/ `senior`（高中）/ `cet4` / `cet6` / `postgrad`（考研）。按**最早出现**的学段标注 |
| `category` | string | 只能取：`基本句型` / `从句` / `动词句型` / `固定结构` / `比较结构` / `强调倒装` / `虚拟语气` / `写作句型` |
| `key` | string | **用于在单词详情页反查该句式**。取句式中最核心的那个单词（通常是核心动词），全小写。例：`make sb do sth` → `make`；`too...to...` → `too`；`It is adj for sb to do sth` → `it` |
| `examples` | array | 1~2 条例句。**每条必须同时有英文 `en` 和中文 `zh` 翻译** |
| `uncertain` | boolean | 你对该条准确性没把握时标 `true`，我会人工复核 |

# 覆盖范围清单（按此逐类覆盖，不要遗漏）

1. **基本句型**：五大基本句型（S+V、S+V+O、S+V+IO+DO、S+V+O+C、S+V+P）
2. **从句**：宾语从句、定语从句（含关系代词/关系副词）、主语从句、表语从句、同位语从句；状语从句八类（时间、地点、原因、结果、目的、条件、让步、方式/比较）
3. **动词句型**：`make/let/have sb do`、`see/hear/watch sb do / doing`、`spend ... (in) doing`、`It takes sb 时间 to do`、`prefer A to B`、`would rather ... than ...`、`be used to doing` vs `used to do`、`can't help doing`、`look forward to doing` 等
4. **固定结构**：`too ... to ...`、`so ... that ...`、`such ... that ...`、`It is adj for sb to do`、`not ... until ...`、`There be ...`、`hardly ... when ...`、`no sooner ... than ...`、`as soon as ...` 等
5. **比较结构**：`the + 比较级, the + 比较级`、`as ... as ...`、`not as/so ... as`、`比较级 + than any other`、`倍数 + as ... as`
6. **强调倒装**：`It is ... that ...`（强调句）、`Only + 状语 + 倒装`、`Never/Not only ... 倒装`、`as/though 引导的倒装让步`、全部倒装/部分倒装
7. **虚拟语气**：与现在/过去/将来事实相反、`wish` 从句、`would rather` 从句、`It's time sb did`、`suggest/demand + (should) do`
8. **写作句型**：书面表达常用框架句（开篇、原因论证、建议、对比转折、总结），如 `It is widely believed that ...`、`There is no denying that ...`

# 语法红线（最容易出错，务必逐条核对）

- `make / let / have + sb + do`（**不加 to**）；但 `help sb (to) do` 两种都可接受
- `see / hear / watch + sb + do`（全过程）与 `+ doing`（正在进行）**都正确**，但必须在 `pattern` 里区分清楚
- `would rather do A than do B`（两个都不加 to）
- `prefer A to B`（to，不是 than）；`prefer to do rather than do`
- `used to do`（过去常常）与 `be used to doing`（习惯于）是两个不同句式，**不要混淆**
- `suggest / demand / insist / require + that + sb + (should) do`
- `It is ... that ...` 强调句与 `It is adj that ...` 主语从句要区分（前者去掉 `It is ... that` 句子仍完整）

**只要你拿不准某个语法点，就把 `uncertain` 设为 `true`，不要凭印象硬写。**

# 质量要求

1. **准确性优先于数量**。总量目标 200~400 条；**不要为凑数编造冷门或错误的句式**
2. 例句必须**真实、地道、语法正确**，且**确实体现该句式**
3. **同一句式不要重复出现**（例如 `make sb do sth` 只能出现一次）
4. `pattern` 中占位符必须用规定的词，不要写 `somebody`、`per`、`某人` 等
5. 输出**只能是 JSON**，不要输出任何解释、前言、Markdown 代码围栏以外的文字

# 交付方式

- **每批输出 50 条**，输出完**停下等我回复「继续」**
- 停下时，用**一行纯文本**（JSON 之后另起一行）告诉我：本批条数、累计条数、还有哪些 category 未覆盖
- 下一批直接继续输出新的 JSON 对象（`batch` 递增），**不要重复前面已输出的句式**

# 输出前自检

- [ ] 是合法 JSON，能被 `json.loads()` 解析（无注释、无尾随逗号）
- [ ] 每条都有 `pattern / zh / level / category / key / examples`，且 `examples` 每条都有 `en` 和 `zh`
- [ ] `level` 和 `category` 都在规定的枚举值内
- [ ] `pattern` 的占位符全部用规定词
- [ ] 本批内没有重复句式
- [ ] 已核对「语法红线」清单

现在开始输出第 1 批（50 条）。

====
