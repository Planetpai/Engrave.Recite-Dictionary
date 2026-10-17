# 刻词复习机制说明（SM-2 Ladder）

> 本文面向开发者和 AI 助手，完整描述刻词的间隔重复（SRS）调度机制。
> 代码事实以 0.21Beta（2026-09 版）为准，改动调度逻辑前请先读本文和源码注释。

## 目录

- [1. 核心文件索引](#1-核心文件索引)
- [2. 一句话总览](#2-一句话总览)
- [3. 数据模型](#3-数据模型)
- [4. 会话流程与「只落一次评分」](#4-会话流程与只落一次评分)
- [5. 各阶段调度规则](#5-各阶段调度规则)
- [6. 到期时间怎么算](#6-到期时间怎么算)
- [7. 参数表](#7-参数表)
- [8. 保护机制](#8-保护机制)
- [9. 每日额度与到期收集](#9-每日额度与到期收集)
- [10. 测试覆盖](#10-测试覆盖)

---

## 1. 核心文件索引

| 文件 | 职责 |
|---|---|
| [Sm2Scheduler.kt](../app/src/main/java/top/southfish/engrave/domain/srs/Sm2Scheduler.kt) | 调度算法本体：四个阶段各自的评分→新状态计算，纯函数 |
| [Sm2Params.kt](../app/src/main/java/top/southfish/engrave/domain/srs/Sm2Params.kt) | 全部可调参数（阶梯、ease 惩罚/奖励、fuzz 等） |
| [Scheduler.kt](../app/src/main/java/top/southfish/engrave/domain/srs/Scheduler.kt) | 调度器接口与 `ReviewOutcome` 定义 |
| [ReviewState.kt](../app/src/main/java/top/southfish/engrave/domain/model/ReviewState.kt) | 每个词的调度状态（stage / stepIndex / intervalDays / easeFactor…） |
| [StudyModels.kt](../app/src/main/java/top/southfish/engrave/domain/model/StudyModels.kt) | `Grade`（AGAIN/HARD/GOOD/EASY）与每日额度配置 |
| [StudyUseCases.kt](../app/src/main/java/top/southfish/engrave/domain/usecase/StudyUseCases.kt) | 会话计划（GetStudySession）、评分落库（SubmitReview）、撤销（UndoReview） |
| [StudyViewModel.kt](../app/src/main/java/top/southfish/engrave/ui/study/StudyViewModel.kt) | 会话编排：三遍考法、离队判定、撤销、快照恢复 |

## 2. 一句话总览

新词学完后 **1 小时**到期复习，答会才毕业进天级复习；天级先走固定阶梯
**1 → 2 → 4 → 7 → 15 天**，15 天后转 SM-2 乘法（间隔 × ease）；中途忘词打回
**1 小时重记**，重记毕业按**旧间隔折半**回阶梯。

```
            ┌──AGAIN──▶ LEARNING(1小时)   （自环：仍停在 LEARNING）
            │                │
NEW ──GOOD──┘                └──GOOD──▶ REVIEW(1天档)
 │                                ▲
 │ EASY                          │
 └─────────▶ REVIEW(2天档)        │ AGAIN（打回重记）
                                  │
              REVIEW ──AGAIN──▶ RELEARNING(1小时) ──GOOD──▶ REVIEW(旧间隔×0.5 钳≥1天)
                            ▲       │
                            └─AGAIN─┘   （自环：仍停在 RELEARNING）
```

> 注意：**只有 REVIEW 答 AGAIN 才进 RELEARNING**。LEARNING / RELEARNING 自身
> 的 AGAIN 是停在原地的 1 小时自环，不会越级跳到 REVIEW。

## 3. 数据模型

`ReviewState`（每个词一条，落 Room 数据库）：

| 字段 | 含义 |
|---|---|
| `stage` | `NEW → LEARNING → REVIEW`，忘词进 `RELEARNING` |
| `stepIndex` | 学习/重记阶段=分钟步下标；**REVIEW 阶段复用为阶梯档位**（0→1天, 1→2天, 2→4天, 3→7天, 4→15天, ≥5→SM-2 区） |
| `intervalDays` | 当前间隔（天），学习期是分钟换算的小数 |
| `easeFactor` | SM-2 难易因子，初始 2.5，范围 [1.3, 5.0]，越大间隔涨越快 |
| `repetitions` / `lapses` | 连对次数（忘词归零）/ 累计忘词次数（只增不减） |
| `dueAt` | 下次到期时间戳（毫秒） |
| `lastReviewedAt` | 最近一次评分的时间戳，`ReviewLog.reviewedAt` 直接取它（无则用 `now`） |

`Grade` 四档：`AGAIN(1) / HARD(3) / GOOD(4) / EASY(5)`，其中 GOOD、EASY 算「答会」。

## 4. 会话流程与「只落一次评分」

这是全机制**最容易误解的一条**，见 [StudyViewModel.kt](../app/src/main/java/top/southfish/engrave/ui/study/StudyViewModel.kt) 头部注释：

- 正式阶段（新词）每个词固定刷三遍：**第一遍英文面 → 第二遍中文面 → 第三遍拼写**。
  答会进下一遍、答不会归零回第一遍；无论哪种都沉到队底，拼写通过才出队。
- **复习阶段分两条路**（用 `failureCounts` 判断，不改 TestCard 结构）：
  - **快速路**（本会话还没答错过）：只考一遍英文面，答会**直接出队**落 `GOOD`；
  - **强化路**（已经答错过 ≥1 次）：走完整三轮（英→中→拼写），任一遍不会归零重刷，
    三轮全对才出队，因 `failureCounts>0` 落 `AGAIN`（打回 1 小时重记）。
- **每个词每次会话只落一次 SRS 评分**（只有离队那一下写库）：
  - 正式阶段（新词）：无论中途卡壳几次，统一落 `GOOD` → 排到 1 小时后；
  - 复习阶段：快速路答会落 `GOOD` 进阶；强化路三轮通过落 `AGAIN` 打回 1 小时重记。
- 中途的「不会」只记在内存 `failureCounts`（随快照持久化），**不碰数据库和 ease**。

> 历史教训：旧版每遍滑动都提交评分，新词的三次 GOOD 在几分钟内连走完
> 「1 小时学习步 → 毕业」，导致「学完 1 小时后复习」永远不出现。

**会话断点续传**：队列（`browseQueue` / `selected` / `testQueue`）只活在内存里，
每次状态发射都把快照写进 `StudySessionStore`。只恢复**本学习日**（4AM 界）
的快照，跨日作废；撤销栈不跨会话保留。中途退出再进来优先恢复快照，恢复不了
才按当日额度重开一轮——避免「明明没背完却提示学完了」。快照里存的是
`wordId` + `encounter`（第几遍考法）+ 翻面状态，不含考法类型，考法靠
`encounter` 模 `KIND_CYCLE` 现算。

## 5. 各阶段调度规则

实现在 [Sm2Scheduler.kt](../app/src/main/java/top/southfish/engrave/domain/srs/Sm2Scheduler.kt)。

### 学习阶段（NEW / LEARNING，[L46-L114](../app/src/main/java/top/southfish/engrave/domain/srs/Sm2Scheduler.kt#L46-L114)）

学习步只有一步：1 小时。NEW 词首次 GOOD 进这一步（**不毕业**），1 小时后
复习再答 GOOD（此时已是最后一步）才毕业。

| 评分 | NEW（首遍） | LEARNING 在最后一步（即 1 小时复习时） |
|---|---|---|
| AGAIN | 停 1 小时，ease −0.20 | 停 1 小时，ease −0.20 |
| HARD | 停 1 小时 ×1.5（90 分钟），ease −0.15 | **直接毕业**到第 1 档，间隔钳到下限 **1 天**（与 GOOD 同档，`1.0 × 0.8` 被 `max(1, …)` 钳回），ease −0.15 |
| GOOD | 进 1 小时学习步 | **毕业**到第 1 档（1 天） |
| EASY | **直接毕业**到阶梯末档（**15 天**），ease +0.25，加 ±3 天 fuzz 分散到期日 | 同左 |

### 复习阶段（REVIEW，[L206-L279](../app/src/main/java/top/southfish/engrave/domain/srs/Sm2Scheduler.kt#L206-L279)）

| 评分 | 行为 |
|---|---|
| AGAIN | 打回重记：进 RELEARNING 停 1 小时，ease −0.20，lapses+1 |
| HARD | 不换档：间隔 = 当前档 × 0.8，ease −0.15 |
| GOOD | 档位 +1：阶梯内取阶梯值，出阶梯后 = 旧间隔 × ease，ease +0.05（低 ease 时 +0.10） |
| EASY | 档位 +2（**跳过一个档位**，如 1 天 → 4 天），ease +0.25，间隔算法同 GOOD |

### 重记阶段（RELEARNING，[L117-L194](../app/src/main/java/top/southfish/engrave/domain/srs/Sm2Scheduler.kt#L117-L194)）

| 评分 | 行为 |
|---|---|
| AGAIN | 再停 1 小时，ease −0.20 |
| HARD | 最后一步则毕业（间隔同下），否则停 1 小时 ×1.5 |
| GOOD | **毕业**：新间隔 = max(1 天, 旧间隔 × 0.5)，**ease 不变**，按新间隔反查对齐阶梯档 |
| EASY | **毕业**：新间隔同上，**ease +0.25** |

## 6. 到期时间怎么算

- **分钟级（学习/重记步）**：精确 `now + 60 分钟`，**不锚定 4AM、不加 fuzz**。
- **天级（毕业/复习间隔）**：锚定到「**下一个凌晨 4 点**」+ ceil(间隔−1) 天 + fuzz 整数天
  （[dayAnchoredDueAt](../app/src/main/java/top/southfish/engrave/domain/srs/Sm2Scheduler.kt#L344-L350)）。
  同一天学的卡统一在次日 4 点到期；ceil 保证 SM-2 乘法的小数间隔（如 38.25 天）
  到期时刻仍精确落在 4:00:00。
- **学习日界**：凌晨 4 点前算前一天（[studyDay](../app/src/main/java/top/southfish/engrave/domain/srs/Sm2Scheduler.kt#L375-L379)），逾期天数按此对齐。
- **fuzz**：天级间隔 ±5% 的整数天抖动；间隔 < 20 天时不足 1 天、fuzz 为 0。

## 7. 参数表

见 [Sm2Params.kt](../app/src/main/java/top/southfish/engrave/domain/srs/Sm2Params.kt)，默认值：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `learningStepsMinutes` / `relearningStepsMinutes` | `[60.0]` | 学习/重记各只有 1 小时一步（旧版 10 分钟步已移除） |
| `reviewIntervalLadderDays` | `[1, 2, 4, 7, 15]` | 固定阶梯，走完转 SM-2 乘法 |
| `initialEase` / `minimumEase` | 2.5 / 1.3 | ease 初值与下限 |
| `hardIntervalMultiplier` / `hardEasePenalty` | 0.8 / 0.15 | HARD：间隔打八折 |
| `againEasePenalty` | 0.20 | AGAIN 的 ease 惩罚 |
| `goodEaseBonus` / `goodEaseBonusLowEase` | 0.05 / 0.10 | GOOD 奖励；ease<2.0 时用大额加速爬坑 |
| `easyEaseBonus` / `easyRungJump` | 0.25 / 2 | EASY 奖励与跳档数（REVIEW 阶段用） |
| `easyGraduationRungIndex` | 4 | NEW EASY 毕业档位 = 阶梯末档（15 天），对应浏览阶段「已学过」 |
| `easyFuzzDays` | 3 | NEW EASY 毕业时额外 ±3 天 fuzz，分散「已学过」词的到期日 |
| `lapseIntervalMultiplier` / `minimumLapseIntervalDays` | 0.5 / 1.0 | 忘词重记毕业：旧间隔折半，下限 1 天 |
| `maximumIntervalDays` | 1825（5 年） | 间隔上限 |
| `fuzzRatio` | 0.05 | 天级抖动比例 |
| `overdueCompensationFactor` / `...MaxMultiplier` | 0.5 / 2.0 | 逾期补偿：新间隔 += 逾期天数 × 0.5，上限 = 原间隔 × 2 |

## 8. 保护机制

1. **ease hell 保护**：ease < 2.0 时 GOOD 奖励翻倍（+0.10），加速爬出低 ease 区。
2. **逾期补偿**：晚复习的天数 × 0.5 加到新间隔（上限原间隔 × 2），越拖不忘则间隔涨得越快。
3. **忘词不清零间隔**：重记毕业按旧间隔折半（下限 1 天），而不是原版 SM-2 的直接回 1 天；
   且按恢复后的间隔反查阶梯档，避免下次 GOOD 错误跳回 2 天。
4. **跨夜学习卡自动毕业**：`GetStudySessionUseCase` 开工先调 `graduateStaleCards(now)`，
   隔了一夜的分钟级学习卡直接以复习卡身份出现——否则补走分钟步会把到期时间推到
   明天，表现为「学完第二天没有复习」。
5. **每日评分唯一性**：见第 4 节，防止同会话连刷把分钟步瞬间走完。

## 9. 每日额度与到期收集

见 [GetStudySessionUseCase](../app/src/main/java/top/southfish/engrave/domain/usecase/StudyUseCases.kt#L31-L93)：

- `dailyNewLimit`（默认 10，用户可调）减去「今日已学新词数」= 今日新词额度；
  额度为 0 时新词列表为空、只剩到期复习。
- `dueItems` 一次最多拉 500 个（实际上到不了），新词批次多预取 30 个缓冲
  覆盖浏览阶段「已学过」左滑跳过的部分。
- 产品流程固定「先新词、后复习」：新词三遍刷完 → 若有 `dueItems` 则插入
  过场页「**新词完成** · 还有 N 个到期复习」，点「开始复习」后进入测试页，
  复习卡同样从英文面开始。测试页顶部状态行显示「复习 · 剩 N」。
- 「继续再学」按钮用 `newLimitOverride` 绕过当日额度额外学新词。
- **当天新词会回流**：新词学完落 1 小时学习步，1 小时内再次到期时以「复习卡」
  身份出现在 `dueItems` 里。所以当天 `dueItems` = 「今天刚学的新词」+
  「之前学过现在到期的词」，复习量往往比表面看到的多。

## 10. 测试覆盖

- [Sm2SchedulerTest.kt](../app/src/test/java/top/southfish/engrave/domain/srs/Sm2SchedulerTest.kt)：调度算法 32 个用例（各阶段 × 各评分 × 边界）。
- [GetStudySessionUseCaseTest.kt](../app/src/test/java/top/southfish/engrave/domain/usecase/GetStudySessionUseCaseTest.kt)：额度、到期收集、「没有新词直接进复习」等 8 个用例。
- [StudyFlowTest.kt](../app/src/test/java/top/southfish/engrave/ui/study/StudyFlowTest.kt)：会话编排 21 个用例（三遍考法、离队落库、撤销）。
- [DayIndexTest.kt](../app/src/test/java/top/southfish/engrave/data/local/DayIndexTest.kt)：学习日界（4AM）与连续天数 9 个用例，对应第 6 节的到期时间计算。
- [SpellingChunksTest.kt](../app/src/test/java/top/southfish/engrave/ui/study/SpellingChunksTest.kt)：拼写分块与乱序 3 个用例，对应第 4 节的第三遍考法。

以上合计 **73 个单测**。

> 注意：`StudySessionStore`（会话快照）依赖 `org.json`，在 JVM 单测里是 Android
> stub，**快照恢复逻辑无法用现有单测覆盖**，只能真机/模拟器端到端验证。
