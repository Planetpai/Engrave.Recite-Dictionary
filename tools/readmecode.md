# 词典转换工具 · 开发者说明（readmecode.md）

> 面向要**改这个工具**或**对接这套词书格式**的开发者。
> 工具使用说明见 [readme.md](readme.md)；App 侧工程约定见仓库根目录 [readme-code.md](../readme-code.md)。

本文件夹包含两个互相独立的工具：

| 文件 | 形态 | 用途 |
| --- | --- | --- |
| `wordbook-converter.html` | 纯前端单文件（无构建、无依赖） | 任意格式词典数据 → 刻词词书 JSON |
| `build_wordbooks.py` | Python 3 脚本（仅标准库） | ECDICT 体系源数据 → App 内置词书 assets |

---

## 1. 刻词词书 JSON 格式规范（两个工具的共同契约）

```json
{
  "name": "词书名称",
  "description": "词书描述（可选）",
  "words": [
    {
      "term": "apple",
      "meaning": "n. 苹果",
      "phonetic": "/ˈæp.əl/",
      "example": "I ate an apple.",
      "exampleTranslation": "我吃了一个苹果。"
    }
  ]
}
```

约束与解析行为（App 侧 `WordBookJsonParser`）：

- 顶层必须是有 `name` / `words` 的对象；`words` 是数组
- 每条 `term`、`meaning` 必填（空串/缺失 → 该条被静默跳过）
- 其余字段可选；多余的未知字段被忽略
- 文件编码 UTF-8；`meaning` 内多词性用 `；` 连接是展示惯例，非强制
- App 导入幂等：同 `term` 重复导入不会产生重复词条

## 2. wordbook-converter.html 实现要点

单文件 IIFE，无框架。核心流水线：`读文件 → 按格式解析成行数组 → 列映射 → 行级清洗选项 → 预览/统计 → 导出`。

- **格式检测**（`formatSel=auto`）：优先 JSON（`JSON.parse` 成功且能取出词数组），否则按首行探测制表符/逗号/自定义分隔符；`custom` 模式支持 `=`、`:`、`|`、`;`、`——`
- **JSON 输入两种形态**：`words` 数组对象（已是词书格式）或 `{term: meaning}` 键值对
- **列映射**：解析出列头后按关键词猜（如列名含「单词/word」→ term），`detectTag` 显示「自动猜测」，用户改动后变为「手动」
- **清洗选项**在映射后逐行应用：去重（保留首条，`Map` 按 term）、音标补斜杠（已有 `/` 开头则跳过）、去 HTML 标签（`strip_tags` 正则）、跳过空释义
- **导出**：`JSON.stringify`（无缩进）→ Blob 下载 / `navigator.clipboard` 复制
- **隐私**：无任何网络请求，全部计算在页面内完成

改这块时注意：

- `els` 集中了所有 DOM 引用；新增选项要在 `opts` 对象和 HTML 两处同步
- 预览表最多渲染 80 条是刻意限制（大表 DOM 卡顿），统计数字仍是全量

## 3. build_wordbooks.py 实现要点

- 输入：`wordbook-source/*.json`（kajweb/dict 例句版结构：`words[]` 内含 `word/translations/us/uk/sentences`）
- 词性归一：`POS_MAP` 把 ECDICT type 缩写映射成 `n./v./adj.…`；`&` 复合词性拆分后用 `/` 连
- 例句挑选：取**第一对**英文与中文译都非空的句子；不再往后找
- 音标：优先美式 `us`，缺则英式 `uk`；非 `/` 开头时自动补斜杠
- 打乱：`random.Random(seed).shuffle`，seed 写死在 `main()` 的任务表里（20260908/20260909）——**同 seed 输出可复现**，diff 友好
- 输出：无缩进紧凑 JSON 直接写入 `app/src/main/assets/wordbooks/`

改这块时注意：

- 改了内置词书内容/条数后需要重新 `gradlew assembleDebug` 并安装；App 启动时 `SeedDataProvider` 会 `list("wordbooks")` 枚举 assets 并清理数据库里已消失的内置词书（`retireMissingBuiltInBooks`）
- 内置词书条数变化后，`readme.md` 与 `readme-user.md` 里写死的条数（初中 1987 / 高中 3743）记得同步

## 4. 验证清单（改完工具后）

- [ ] 网页版：分别喂 TSV / CSV / JSON 数组 / 键值对四种样例，预览与统计正确
- [ ] 网页版：列映射手动改后导出结果随之变化
- [ ] 脚本：`python tools/build_wordbooks.py` 后 `git diff` 只有预期条目变化
- [ ] 端到端：导出的 JSON 推到手表 `Download/`，在 App「词书管理 → 从设备导入词书」导入成功、词条数正确
