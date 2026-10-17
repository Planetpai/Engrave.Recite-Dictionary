# 项目状态交接 · 刻词 Engrave

> 更新时间：2026-10-17 ｜ 工作目录：`e:\WearOS-VocabProject`
> 仓库：https://github.com/Planetpai/Engrave.Recite-Dictionary ｜ 分支：master
> **注意：`git remote` 已改为 `Planetpai/Engrave.Recite-Dictionary`（原 SouthFishowo/Engrave.Recite-Dictionary_Beta 自动重定向，push 不受影响）。**
> 本地与远端对齐（最后已推提交 `e82ec5f`）。交接编号：H-20261017-01

## 交接区（本次唯一编号 H-20261017-01）

- 源任务：本对话（用户明确要求开新窗口做第二步，非自动压缩）
- 交接状态：**材料已核对、等待接手核验**（第一步已发版并推送；GitHub Release 由用户自行上传，是否已上传未确认）
- 用户确认的目录／动作范围：同一项目原目录 `e:\WearOS-VocabProject` 接续；本次为「仅保存」——用户自行开新窗口。接续后在用户确认方案的前提下推进**第二步**（⑧a 词典扩展 + ⑧b 离线整句翻译）
- 接手任务编号：未创建（仅保存交接材料，用户自行开新对话）

## 0. 用户计划（已收到，2026-10-14）

计划即 `docs/product-roadmap.md`（**已用用户上传的新版覆盖**：编号体系重排，②进度条/④会话切片/⑥b小组件/⑦自动备份/⑨语音识别均标「留档暂不做」，新增 ⑩卡片显示、11 UI重构、12 错题本导出词典、13 历史记录、14 详情页显示排序）。要点：

- **执行顺序**：
  1. **第一步：③ 通知点开直达学习页（2026-10-14 用户改主意缩小范围：去掉按钮+条件发送，只做直达）+ ⑤a Tile/复杂功能 + ⑩ 卡片显示优化 + 检查滚轮问题 → v0.3beta.2** —— **✅ 已完成、手测通过、已发版并推送（见 §2 第 7 条）**
  2. 第二步：⑧ a 词典扩展（反义词/同义词/句式，**内容由用户整理后发来**）+ b 离线整句翻译 → v0.4beta.1 ← **当前所在**
  3. 第三步：12/13/14 → v0.4beta.2（等体验与 bug 反馈）
  4. 第四步：11 UI 重构 → v0.5beta.1
  5. 最后：等反馈发 v1.0
- **工作规则（用户定的）**：每完成一步用 handoff 写交接开新窗口；每步开始前先列具体计划与理解；有疑问及时提出不要猜。
- **当前状态（2026-10-17）**：**第一步全部完成、手测通过、已发版 v0.3beta.2 并 push（commit `e82ec5f`）**；GitHub Release 资产由用户自行上传（未确认已上传）。代码已全部提交，工作区仅剩按约定不入库的 untracked 文档。**进入第二步准备：等用户整理并发来词典扩展内容后，先出方案再开工。**

## 1. 项目与当前版本

Wear OS 手表背单词应用「刻词」，Kotlin + Jetpack Compose，MIT 开源。三模块结构：

| 模块 | applicationId | 当前版本 | 说明 |
| --- | --- | --- | --- |
| `:core` | `top.southfish.engrave.core` | — | 共享库：domain/data/reminder/update/di、ViewModel、assets 词库、单测 |
| `:app`（Wear 版） | `top.southfish.engrave` | **0.3beta.2 / code 6** | minSdk 28 / **targetSdk 29（硬约束）** |
| `:app-phone`（手机版） | `top.southfish.engrave.phone` | **0.3beta.2 / code 3** | minSdk 26，M3 + WearCompat shim；给无 Wear 库的手表用，仅侧载 |

- 本版构建产物：`Engrave-0.3beta.2-wearos.apk`（10.1MB）+ `Engrave-0.3beta.2-android.apk`（7.88MB），**已由用户自行上传 GitHub Release**（AI 未建 Release，本机无 `gh` CLI 亦无 token；说明文字已交付用户，见 §2 第 7 条）
- 体积构成参考：两端 assets 相同（6.04MB，其中 `assets/dict/ecdict_core.db` 5.55MB）；差在 dex——Wear 版 3.16MB vs 手机版 1.17MB，多出的是 Wear Compose 三件套 + tiles 1.1.0 + watchface complications + kotlinx-coroutines-guava(Guava) 等手表专用依赖
- **发布流程 skill**：`.trae/skills/engrave-release-flow/SKILL.md`（构建/装机/发版/提交全流程，含内存坑、设备识别、禁入库清单），发版任务自动套用

## 2. 最近完成

> ⚠️ 第 1~4 项历史已推（`f46f553` 及更早）；第 5、6 项（v0.3beta.2 内容改动）已随第 7 项一并提交推送；第 7 项为发版收尾。

1. **v0.2beta.4 阶段 → v0.3beta.1 发布**（2026-10-11/12）：
   - `a2c568d` 交互式使用教程（真实卡片分步练习，TutorialContent 双端共享）+ 拼写面板点字母误翻面修复（CardGestures awaitFirstDown requireUnconsumed）
   - `95214af` 设置拆分：「我的」页新增「其他设置」页收纳「详情页释义」「单词发音」；单词发音改为**仅控制卡片英文词头自动发音**（默认关闭，DataStore 默认值 true→false）；TTS 可用性检测（`isLanguageAvailable>=LANG_AVAILABLE`，勿用>=2 严格阈值会误报），不可用禁开 + 灰字「暂无tts语音可用」**独立 item** 排按钮下方；详情页点词头发音始终可用
   - `21007f7` / `f46f553` 双端版本号 → 0.3beta.1；README 更新日志同步
2. **PageDots 翻页小圆点（双端）**：首页与搜索结果底部圆点指示（圆屏弧形点阵，用官方 `HorizontalPageIndicator`）；仅翻页/滑动时淡入，停手约 2.5s 淡出（自动隐藏需自加一层，官方只管绘制）
3. **产品路线图**：`docs/product-roadmap.md`（2026-10-12/13 与用户多轮确认后成文）
   - **场景前提（用户修正后）**：用户=学校学生，**校内无网、无手机**，手表是唯一设备；使用时段=课间/午休/晚自习/宿舍——**能看屏、不能出声**
   - 立即可做：② 通知挂「开始复习 N 个」按钮；③ 首页任务量改造
   - 需设计：④a Tile（仅:app）/④b 桌面小组件（仅:app-phone）、⑤ 本地自动备份、⑥ 导入体验优化
   - 划掉：⑦a 词典扩容、云同步、CSV 导出、词汇量测试、统计图表、语音识别（无网环境基本不可用）
4. **双模块融合调研（结论：暂不做）**：32 个 UI 文件镜像同构，仅 4 类机械差异；推荐方案 B（单模块 + product flavors，包名/签名不变）；留档于会话记忆，用户未否决，暂不推进
5. **v0.3beta.2 内容补做（2026-10-15/16）**：
   - **备份断点续背**：`core/.../data/backup/BackupManager.kt` 加 `pendingTerms`（选了没背完的词）随备份走，还原时重建待背队列；**不游标不动 SQL**（保跨天回退）
   - **引导页第一页加「或者」+「导入数据」**：从主页挪到 `OnboardingScreen` step 0（双端）
   - **release 包命名**：双端 `build.gradle` 加 `outputFileName = "Engrave-${variant.versionName}-wearos/android.apk"`
   - **选词页删「点一下看释义」文字**（双端 BrowsingPhase）
   - **中文搜索隐藏变形标注条**：`core/.../ui/search/SearchViewModel.kt`（双端共享）中文输入时不显示变形标注
   - **TimeText 时间条显隐**：新增 `app/.../ui/components/TimeTextVisibility.kt`，非学习页列表顶部／往回滑显示，离开顶部隐藏；学习页常驻。**用户确认保持手写 `rememberTimeTextVisible`，不迁移官方 scrollAway 库**（官方需 Wear Compose 1.4+，当前锁 1.2.1，Horologist 改动面太大）
   - **检查更新**：新增 `core/.../update/UpdateChecker.kt`；双端 `AndroidManifest.xml` 补 `INTERNET` 权限（漏声明会表现为「网络连接失败」假性断网）；`Result.NetworkError` 改为 `data class(detail: String)` 带 HTTP 码／异常短标签上屏。仅点击时联网查 GitHub Releases（含预发行），按资产名 `Engrave-版本号-flavor.apk` 区分双端
   - **Tile + 复杂功能（仅:app）**：`tile/StudyTileService.kt`（贴屏缘 3/4 圆环进度、缺口朝下、中央「待复习/今日已完成」、点按带直达 extra）+ `tile/StudyComplicationService.kt`（SHORT_TEXT 待复习数）
6. **手测反馈两轮（2026-10-16/17）**：第一轮装表手测后，用户反馈整改并复测通过——
   - **第一轮（英→中）卡片正面可滑**：双端 `TestingPhase` 的 `gradeEnabled` 加 `|| showExample`，正面不翻面也能滑会/不会；其余卡型、拼写轮均不动
   - **表冠滚动重做（仅 :app）**：`RotarySupport`——分发改**栈顶优先（LIFO）**（先注册先吃会让后台残留页面永远截走事件，曾致首页初始页滚轮「必死」）；滚动改「累积 + 每帧追赶」（积压 ≥ 两屏一帧到位＝猛转，否则按视口高度 20% 滑翔）；**真实滚动才 `CLOCK_TICK` 震动**，滚到尽头自然安静
   - **滚动条**：曾自绘弧形+变蓝版，用户看后要求回退，**已全部恢复系统原生 `PositionIndicator`**（自绘代码已删）
   - **TimeText 淡入淡出（仅 :app）**：新增 `AnimatedTimeText`（fade 300ms、纯透明度、方圆屏通用）
   - **小圆点换官方**：手表端删手写 PageDots，改用 `HorizontalPageIndicator`；自动隐藏自加（此前换组件时把隐藏层丢了，已补回）
   - **首页按钮去数字**：core `HomeViewModel.buttonLabel` 「开始学习 N」→「开始学习」
   - **Wear Compose 不升级（用户拍板 2026-10-16）**：1.3 官方 rotaryScrollable 靠焦点（本机焦点链路不可靠，恐招回老 bug）、1.4 需连升 AGP/Kotlin/compileSdk，改动面不值
   - 改前备份：`_backup/backup-20261016-pre-feedback-fixes.zip`（robocopy 源码范围 + ZipFile，9.88MB；**项目含 `_旧文件待删除`/`_video`/`手表安装包` 等 GB 级杂物目录，备份一律排除**）
7. **发版 v0.3beta.2（2026-10-17）**：
   - 第一步全部手测通过 → 版本号 `:app` code 5→6 / `:app-phone` code 2→3，versionName → `0.3beta.2`（aapt2 核验通过：app 6/0.3beta.2、phone 3/0.3beta.2）
   - `README.md`（版本行 + 新增 v0.3beta.2 更新日志小节）、`readme-aicode.md`（新增 §6.13 Tile/复杂功能、§6.14 表冠滚动与页面指示）同步
   - 提交 `e82ec5f`（47 files changed, +1767 / -249），已 `git push origin master`（`f46f553..e82ec5f`，exit 0）
   - **GitHub Release 由用户自行上传**：已交付可直接粘贴的纯文本说明与两个资产路径；AI 未建 Release（本机无 `gh`、无 token）
   - **发布说明定稿（用户口径）**：只「新增 / 优化了已知问题 / 修复」三栏，与已发布的 beta1 对比，不罗列开发中自引入又修掉的问题——
     ```
     新增：表盘卡片新增词书进度圆环与待复习数量，点按直达学习页 / 提醒通知直达学习页 /
          卡片正面显示例句；第一轮正面滑动即可选「会 / 不会」/ 新增「检查更新」/
          引导页新增「导入数据」/ 翻页小圆点 / 时间条自动显隐
     优化：优化了已知问题
     修复：数据导出导入时，词书从 0 开始选词
     ```

## 3. 词典/句式调研留档（⚠️ 与第二步 ⑧a 直接相关，用户计划由本人整理内容后发来）

**背景**：用户希望补「句式/句型」（`make sb do sth`、`too...to...` 这类带空槽模板），要求先找现成数据源、不手写。走完两条路径后暂停，等用户新计划——**本次第二步 ⑧a 即该内容落地**。

**已核实的事实**：
- **ECDICT 任何版本都没有句式字段**：全字段为单词级（word/phonetic/definition/translation/pos/collins/oxford/tag/bnc/frq/exchange/detail/audio）。当前 `core/src/main/assets/dict/ecdict_core.db` 实测表结构仅 `dict(word,phonetic,zh,en,exchange,frq)` + `phrase(phrase,zh,src_word)`，5.9 万词 + 3.1 万词组
- 现有词组/例句来自 `tools/build_phrases.py` + `wordbook-source/*-例句版.json`（kajweb/dict 系），其 sentences 是每词 3 条例句，非句型
- **路径 1（LLM 生成）**：提示词已写好 `docs/prompt-sentence-patterns.md`（分批 50 条、JSON schema 带 key 字段用于单词详情页反查、语法红线清单、uncertain 标记）
- **路径 2（现成数据源检索）**：已派 agent 全网检索并逐仓库解包核实，报告要点：
  - **无「中文释义+成规模+可商用」的现成句式库**
  - 最优候选：[pressedb/Verb_Pattern_Matcher](https://github.com/pressedb/Verb_Pattern_Matcher) `patterns.db`（SQLite，293 动词/604 模板串，MIT）——**已经 GitHub API 复核属实**；缺陷：仅动词句型一类、无中文无例句、数据系爬自博客
  - 双语小样：Kaochi11/english-learning-site（10 条，无许可）、EchoLingo（62 条，PolyForm 禁商用）；构式类（Constructionary 109 条 MIT、constructarium 16 条 CC BY）用 NP/VP 语言学记法不可直接用
  - 教辅清单（新东方/学科网等 60~300 条/篇）格式规整但为网页文章且疑似抄纸质教辅，**抄其释义例句有版权风险**
- **法律判断（已向用户陈述）**：句式模板本身是语法事实不受版权保护（可用 #1 模板串）；受保护的是释义与例句措辞（须自生成，不可抄教辅）
- **我方建议（待用户拍板）**：混合路线——动词句型用 #1 的 604 条做骨架 + LLM 补中文例句；其余 7 类（基本句型/从句/固定结构/比较/强调倒装/虚拟/写作）LLM 生成；注意 #1 的逗号切分坑（`advise sb how to do ... / sb how + clause`）

## 4. 关键文件

| 文件 | 内容 |
| --- | --- |
| `docs/product-roadmap.md` | 产品路线图（场景前提、分档清单、执行顺序）——**计划类事务以此为准**（untracked，是否入库待用户定） |
| `docs/prompt-sentence-patterns.md` | 句式生成提示词（路径 1 用，未启用；与第二步 ⑧a 相关） |
| `docs/handoff-onboarding-settings.md` | 设置拆分交接留档（已落地） |
| `core/src/main/java/.../data/tts/TtsSpeaker.kt` | TTS 单例：懒连 + setLanguage 探测可用性 |
| `app/src/main/java/.../ui/settings/OtherSettingsScreen.kt`（双端同名） | 「其他设置」页（含检查更新入口） |
| `core/src/main/java/.../update/UpdateChecker.kt` | 检查更新：查 GitHub Releases，双端按资产名区分；`NetworkError(detail)` 带错误详情上屏 |
| `app/src/main/java/.../ui/components/TimeTextVisibility.kt` | `rememberTimeTextVisible` + `AnimatedTimeText`（仅 :app） |
| `app/src/main/java/.../tile/StudyTileService.kt` + `StudyComplicationService.kt` | Tile + 表盘复杂功能（仅 :app） |
| `app(-phone)/.../ui/components/PageDots.kt` + 双端 `HomeScreen.kt`/`SearchScreen.kt` | 翻页小圆点（手表端配官方 `HorizontalPageIndicator`，手机端手写） |
| `app/src/main/java/.../ui/components/RotarySupport.kt`（双端各一份） | 表冠滚动：LIFO 分发 + 追赶式滚动 + 真实滚动才震动 |
| `app/build/outputs/apk/release/Engrave-0.3beta.2-wearos.apk` | 本版手表 release 包（用户已上传 Release） |
| `app-phone/build/outputs/apk/release/Engrave-0.3beta.2-android.apk` | 本版手机端 release 包（用户已上传 Release） |

## 5. 决定与限制

- **死规矩（用户 2026-10-13）：未经用户明确确认不得改动任何代码；方案/讨论阶段只动方案与文档文件。** 且每一步开工前必须先出方案、等用户确认。
- **优先使用官方组件（用户 2026-10-17 明确）**：能用官方组件就不自己画；自绘只在官方确实满足不了时，且须先查官方规格参数（小圆点、滚动条均已按此处理）
- `docs/review-mechanism.md` 永不入库；`PROJECT_STATE.md`、`.trae/`、`_20svideo/`、`tools/_*.py`、`_backup/`、`docs/product-roadmap.md`（待定）、`docs/prompt-sentence-patterns.md` 均 untracked 不提交；提交只显式 `git add <文件>`，禁止 `git add -A/.`
- targetSdk 29（双端）不变；改前备份、改后测试
- **TimeText 保持手写实现**（用户 2026-10-15 确认）：官方 scrollAway 需 Wear Compose 1.4+，本仓库锁 1.2.1，不值得迁移
- **Wear Compose 不升级**（用户 2026-10-16）：维持 1.2.1（理由见 §2 第 6 条）
- **release 包命名规则**：`Engrave-<versionName>-wearos.apk` / `Engrave-<versionName>-android.apk`（双端 gradle `outputFileName`），检查更新按此资产名区分端别
- **GitHub Release 由用户自行上传**（用户 2026-10-17）：本机无 `gh` CLI、无 token，AI 只负责改码/构建/提交/push
- ~~发版/push 时绝不顺手装到用户手表（用户 2026-10-16）~~ → 仍有效：装机只在手测轮次，发版后让用户用「检查更新」自测
- ~~用户 2026-10-15/16 明确「先不发版」~~（已被 2026-10-17 发版替代）
- 模拟器未经用户许可绝不启动；真机 adb 自动化允许；AI 装机、用户手测
- 真机：Galaxy Watch4（SM-R870）WiFi 调试**端口每次变化**（本次为 `192.168.1.103:34559`，装机前必须问用户要新地址）；vivo_X21A（USB `735ac045`）不是手表，不装手表应用

## 6. 接续动作

**⚠️ 当前卡点：进入第二步，但 ⑧a 的词典扩展内容需用户自己整理后发来；内容到齐前只做调研/出方案，不得改码。**

1. **【接续第一步】只读核对本文件与关键成果**，与用户确认状态（第一步已完成、已发版 `0.3beta.2`、已 push `e82ec5f`；Release 由用户上传），确认第二步范围后再动。
2. **第二步范围（按 roadmap）**：⑧a 词典扩展（反义词/同义词/句式，**内容由用户整理后发来**）+ ⑧b 离线整句翻译 → **v0.4beta.1**。
   - ⑧a 相关调研与提示词见 §3、`docs/prompt-sentence-patterns.md`；混合路线建议待用户拍板
   - ⑧b 离线整句翻译：无现成结论，需先调研可行方案（离线模型/词表拼装等），**先出方案再实现**
3. **工作方式不变**：每步开工前先列具体方案与理解 → 等用户确认 → 才动码；每完成一步用 handoff 写交接。
4. **发布动作（到 v0.4beta.1 时）**：走 `.trae/skills/engrave-release-flow/SKILL.md`；`:app` code 6→7、`:app-phone` code 3→4，versionName `0.4beta.1`；push 后 Release 仍由用户自行上传。
5. **无遗留未提交改动**：工作区已提交干净（`git status` 仅剩按约定不入库的 untracked 文档/备份/临时脚本）。

### 构建命令（PowerShell，无 `&&` 用 `;`）

`.\gradlew.bat :core:testDebugUnitTest :app:clean :app:assembleRelease :app-phone:assembleRelease --console=plain -q`；需 `dangerouslyDisableSandbox` + `GRADLE_USER_HOME=E:\AndroidDev\gradle-home` + `JAVA_HOME=E:\AndroidDev\jdk`

已知坑（重蹈前先看）：
- dl.google.com 超时 → 查 `E:\AndroidDev\gradle-home` 缓存钉版
- release 增量编译 `Unresolved reference` → `:app:clean :app:assembleRelease`
- Wear Compose 1.2.1 连续详情页路由需交替 route pattern 防滑动返回跳层
- DataStore 设置页加载门禁：loaded 前不组合可写内容（转轮回写坑）
- PowerShell 5 无 `&&`、git commit 用 here-string；Gradle 构建需 dangerouslyDisableSandbox
- 手表息屏快：adb 截图/dump 前 `input keyevent KEYCODE_WAKEUP`
- tiles 1.1.0 API 坑（实测过）：`TimelineBuilders.TimelineEntry` 是顶层类；布尔用 `AndroidBooleanExtra.Builder().setValue(true)`；复杂功能 1.1.1 无 plainText 工厂，用 `PlainComplicationText.Builder(s).build()`；`ShortComplicationTextData` 同为顶层类；Tile 圆环需用两条 `ArcLine` **独立叠放**（同容器多弧段是首尾相接）

## 7. 恢复入口（新会话开场白，可复制）

> `$project-handoff` 读取 `e:\WearOS-VocabProject\PROJECT_STATE.md`（交接编号 **H-20261017-01**）恢复「刻词」项目。当前状态：**第一步已全部完成、手测通过、已发版 v0.3beta.2 并推送（commit `e82ec5f`）**，GitHub Release 资产由用户自行上传。现进入**第二步：⑧a 词典扩展（反义词/同义词/句式，内容由用户整理后发来）+ ⑧b 离线整句翻译 → v0.4beta.1**。收到本条后：**先只读核对本文件与关键成果，跟我确认状态与第二步范围**；我未确认方案前不改任何代码、不提交；有疑问先问不要猜。
