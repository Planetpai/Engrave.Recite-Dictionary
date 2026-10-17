# 刻词 Engrave · 第二步执行清单（→ v0.4beta.1）

> 状态：**待用户确认，未开工、未改代码**
> 目标版本：v0.4beta.1 ｜ :app code 6→7 ｜ :app-phone code 3→4
> 更新时间：2026-10-17 ｜ 工作目录：`e:\WearOS-VocabProject`
> 前置规则（不变）：未经用户确认不得改任何代码；开工前先出方案等确认；改前备份、改后测试

---

## 0. 范围（2026-10-17 用户拍板）

- **纳入**：
  - ⑧a **反义词 + 同义词**（数据源：WordNet 自动生成）
  - ⑧b **离线整句翻译**（ONNX；模型不随包，改「App 自动下载」为主、手动导入兜底）
  - **带库 / 不带库 双版本**（应对商城 <20MB 上架限制）
  - **反馈：仅保留已有 QQ 群 832695614 兜底**（本步不做表单；用户服务器不稳）
  - **翻译历史页只留路由常量**（不实现）
- **剔除**：**句型/句式不做**（用户自行扫图书 OCR + 便宜模型处理，本步不涉及）。
- **剔除（0.1 修正）**：**⑨ 离线语音识别推迟到下一步**（见 §0.1）。
- **不做重流程**（用户明确）：不搞真机基准脚本、量化裁剪流水线、自动评测集；验收 = 用户手测 + 少量样例对照。
- **翻译耗时不作强制要求**（用户明确）。

### 0.1 开工后的决策修正（2026-10-17，用户拍板）

- **⑨ 离线语音（ASR）本步不做，整体推迟**。原因：实测 sherpa-onnx**并非**复用 ⑧b 的 onnxruntime——
  它把 ORT 静态链进自己的 `.so`，官方 AAR **47.81MB** 且**不在 Maven Central**（需 JitPack 或自带进仓库）；
  Vosk AAR 单 ABI 也有 +7.9~8.45MB。两者都做会让 withTrans 单 ABI 原生库累计 +26~35MB。
  ⇒ 翻译页**不做语音输入**，输入只保留「手动输入（原生文本框）」；§8 状态机的 5/6（录音/识别）作废。
- **翻译推理库：`onnxruntime-android` 1.18.0**（不做 mobile）。
  原因：`onnxruntime-mobile` 只吃 `.ort` 格式模型且算子集受限，Marian 大概率跑不通；
  `onnxruntime-android` 全算子、直接加载 HuggingFace 的 `.onnx`。
  实测 `.so`（**未压缩**，AGP 默认不压 .so）：arm64-v8a 16.25MB / armeabi-v7a 11.03MB。
- **翻译引擎做成「用户可选」两套并列**：ONNX Marian（自托管 GitHub Release）+ **Google ML Kit**
  （`com.google.mlkit:translate`，~30MB/对模型由 Google 服务器下载、**需 GMS**、**不可自托管**）。
- **⑧a 实测**：relation 表同义 51,124 条 / 反义 3,499 条（覆盖 22k+ 词）；
  词典 11.79MB → **15.81MB（原始 +4.02MB）**，比本节 §3 原估 +1~3MB 略高，noTrans 仍 ≈12MB。

### 0.2 分词器与模型档位定案（2026-10-17，用户拍板）

- **ONNX 侧分词器：DJL 官方 Android 包**（用户自行拆包核对，见其调研记录）：
  `ai.djl.huggingface:tokenizers:0.33.0` + `ai.djl.android:tokenizer-native:0.33.0`（**版本必须锁死 0.33.0**，
  因为 `tokenizer-native` 在 Maven Central 只有这一个版本）。
  - AAR 内容：`jni/arm64-v8a/libdjl_tokenizer.so` **8.06MB**、`jni/armeabi-v7a/libdjl_tokenizer.so` **4.95MB**
    （另有 x86/x86_64 供模拟器）；`classes.jar` 22 字节（纯原生包）。
  - Android 分支 `LibUtils.loadLibrary()` 直接 `System.loadLibrary("djl_tokenizer")`，**不依赖 JNA**。
  - AAR minSdk 21 ≤ 本项目 minSdk 28，满足。
  - **单 ABI 增量**：arm64 **+8.06MB**（withTrans v8a 43.46 → ≈51.5MB）、armv7 **+4.95MB**（34.1 → ≈39MB）。
  - 备选 `onnxruntime-extensions-android:0.13.0`（arm64 +6.13MB / armv7 +4.16MB）**只差约 2MB，
    但要先用 Python 把分词器烘进 ONNX 图** ⇒ 弃用，选 DJL（运行时装读 `tokenizer.json`，模型资产保持标准形态）。
- **本步（v0.4beta.1）只上传量化档**（用户 2026-10-17 二次拍板，因 fp32 实测 431MiB/方向过大）：
  Release 只挂 `Engrave-model-onnx-en-zh-quick.zip` 与 `Engrave-model-onnx-zh-en-quick.zip`；
  fp32 两档**待实测质量差异后再补**（补传不需改 App 代码，资产名已固定）。
- **模型档位设计：量化 + fp32 两档都提供，App 内由用户自选**（原设计，2026-10-17 拍板）。
  两方向 × 两档 = 最多 4 组资产。**实测体积（2026-10-17 hf-mirror 逐文件核对，此前"fp32≈113MB"是错的）**：
  - 量化档 ≈ **114MiB/方向**（encoder 50.4 + decoder 57.1 + tokenizer 6.1）
  - fp32 档 ≈ **431MiB/方向**（encoder 200.2 + decoder 224.7 + tokenizer 6.1）
  ⇒ 四组资产总量 ≈1.1GB（GitHub Release 不限总大小，可行，但用户需知情）。
- 布置：`:app` 主源集放共享层（引擎接口/模型清单/下载器/翻译页），
  `:app` 的 `src/withTrans` 与 `src/noTrans` 各放一份 `TranslationEngineFactory`，
  `noTrans` 版工厂抛异常且**搜索框不挂长按入口**。

### 0.3 实施中定案与实测（2026-10-17，编码阶段）

- **ONNX 解码用非缓存 `decoder_model.onnx`**（不用 `decoder_model_merged` + KV cache）：
  每步喂完整 decoder 序列、取最后一位置 logits 做贪心。等价于缓存解码，换来实现简单、
  对导出形态不挑；代价是长句 O(n²) 重复前向（用户已明确不设推理耗时硬指标）。
- **输入名自适应**：按 `OrtSession.inputNames` 动态装配输入（只喂图里真正存在的），
  兼容含/不含 `encoder_attention_mask` 的不同导出；未知输入名直接报错而非静默出错。
- **最大新生成 token 数收紧到 256**（`generation_config.json` 里通常写 512，手表上太慢）。
- **桌面死重剔除（关键体积修正）**：`ai.djl.huggingface:tokenizers` 的 AAR 内部带了
  `native/` 下 win/mac/linux 桌面原生库（单个 11MB 级），JNA 还带了各平台 `com/sun/jna` 原生库。
  Android 分支只用 `tokenizer-native` 的 `jni/*.so`，故在 `packagingOptions.resources.excludes`
  里剔除 `native/**` 与 `com/sun/jna/**`。**剔除前 v8a 61.61MB → 剔除后 52.43MB**。
- **实测 release 产物（2026-10-17 本机）**：
  - `noTrans` v7a / v8a：各 **12.01MB**（<20MB ✅；**两个 flavor 都带 abi 段**——noTrans 不带 abi 段
    会与 v7a/v8a 产物撞同一文件名导致打包失败，已修 `build.gradle` 命名条件并删掉失效的
    `androidComponents` 剔除块；两包内容相同，发版只上传其一）
  - `withTrans` v7a：**40.12MB** / v8a：**52.43MB**
  - 一条命令即可同时出全部 4 个包：`:app:assembleNoTransRelease :app:assembleWithTransRelease`
- **R8 缺类**：commons-compress（DJL api 传递依赖）引用 `org.apache.commons.lang3.SystemProperties`
  ⇒ 已加 `-dontwarn org.apache.commons.lang3.**`。
- **往返接口**：`src/main` 与 flavor 源集属同一次 Kotlin 编译，`createTranslationEngine` 由
  两个 flavor 各供一份同名顶层函数（单平台工程不支持 `expect/actual`）。

---

## 1. 已实测事实（决策依据）

| 事实 | 数值/结论 |
| --- | --- |
| 词典形态 | 只读 assets SQLite（不走 Room）：`dict` + `phrase` 两表，靠 `DictRepository.DICT_VERSION` 触发重拷 |
| `ecdict_core.db` | 原始 **11.79MB**，APK 内压缩后约 5.55MB（≈2.1×） |
| 现状 APK | Wear **10.1MB** / phone **7.88MB** |
| 详情页结构 | 已是「释义→词形变化→派生→例句→关联短语」分段，新增段落为同构扩展 |
| 构建模板 | `tools/build_phrases.py`（DROP+CREATE+INSERT，幂等）可照抄 |
| onnxruntime 依赖 | 在 **Maven Central**，实测可达 HTTP 200；**dl.google.com 实测同样可达**（Google Maven 200，既往「超时」结论当前不成立） |
| AAR 体积 | `onnxruntime-android` 1.20.0 = 26.58MB / 1.18.0 = 23.28MB（多 ABI）；`onnxruntime-mobile` 1.18.0 = **6.91MB** |
| Google ML Kit 翻译 | `com.google.mlkit:translate:17.0.2` 实测可取（200）；模型 ~30MB/语言对；**模型不可自托管**（运行时由 ML Kit 从 Google 服务器下载）；**需设备带 GMS** |
| GitHub Release 限制 | 单文件 <2GiB、单 Release ≤1000 资产、**不限总大小与带宽** ⇒ 可免费长期托管模型 |
| ⑨ 候选 | **sherpa-onnx**（官方支持 Android+WearOS、基于 onnxruntime、离线）/ **Vosk**（轻量、有手表用例） |
| 小漏缺 | `DictRepository` 注释引用的 `tools/build_dict.py` 已不在 `tools/`（历史脚本丢失，不影响运行） |

---

## 2. 任务清单

### A. ⑧a 反义词 / 同义词（WordNet 自动生成）

- [ ] **A1 数据源落地**：取 Open English WordNet / Princeton WordNet，导出 `synset` 内 synonym 关系与 antonym 关系（纯英文），记录许可证
- [ ] **A2 中文映射 + 收敛**：用 ECDICT 把英文同/反义目标词映射中文；**只保留目标词在本词典 59k 子集内**的条目（天然控体积 + 保证可点跳）
- [ ] **A3 建表脚本**：新增 `tools/build_relations.py`（照抄 `build_phrases.py` 幂等模板）
  - 表结构：`relation(word TEXT, rel_type TEXT /*syn|ant*/, target TEXT, zh TEXT)`
  - 索引：`CREATE INDEX idx_relation_word ON relation(word)`
- [ ] **A4 词典读取层**：`DictRepository` 新增 `synonyms(term)` / `antonyms(term)`（等值点查）；`DICT_VERSION` **+1**（触发 assets 重拷）
- [ ] **A5 领域模型**：新增 `RelationEntry(term, zh, relType)`（:core domain/model）
- [ ] **A6 ViewModel**：`WordDetailUiState` 增 `synonyms`/`antonyms`；并行 async 各加一路
- [ ] **A7 UI（双端）**：`WordDetailScreen` 在「派生」后加「近义词」「反义词」两段，条目可点跳详情（复用 `onOpenWord`）

**增加**：`relation` 表、`build_relations.py`、`DictRepository` 2 方法、1 个 domain model、详情页 2 段
**减少**：不动原 `dict`/`phrase` 表；句式/提示词路径整体搁置
**漏缺**：① ECDICT 一词一释义 → 同/反义只能给「最常用义」，做不到词性/义项分级；② WordNet 英文同义词常属生僻词，靠 A2 的 ECDICT 交集过滤，覆盖率会偏低；③ 「同义」语义为英英同义（映射中文），非中文词林
**优化**：零新增依赖、零 Room 改动、复用只读连接；与现有短语机制完全同构，风险最低

### B. ⑧b 离线整句翻译（ONNX，模型不随包 → App 自动下载）

- [x] **B1 依赖接入**：:app 的 `withTrans` 挂 `onnxruntime-android:1.18.0`（全算子，直接吃 HF `.onnx`）+ DJL 分词器
  - 不用 `onnxruntime-mobile`（只吃 `.ort` 且算子集受限，Marian 大概率跑不通，见 §0.1）
  - ABI：目标表**系统为 32 位、指令集支持 64 位** ⇒ **分别出两个包**（`armeabi-v7a` / `arm64-v8a`），不做合并包
- [x] **B2 模型分发**：**App 自动下载为主**——模型文件放在本项目 GitHub Release 资产；App 复用「检查更新」的 HTTP 写法下载到应用私有目录（带进度）；**同时保留「手动导入」兜底**（复用文件选择器，`PickerMode.MODEL_ZIP`）
- [x] **B3 推理封装**：`TranslationEngine` 接口（`src/main`）+ `OnnxMarianEngine` / `MlKitEngine`（`src/withTrans`）——懒加载 session、后台线程推理、**关闭页面即释放**
- [x] **B4 入口与页面**：**长按搜索框 ≈600ms** 进专用翻译页（手动输入 → 自动判方向出译文，详见 §8）
- [x] **B5 内存策略**：进入页面才加载模型；`onDispose`/`ViewModel.onCleared` 释放 session；加载中显示进度态
- [x] **B6 不带库版处理**：**不提供「长按进翻译页」入口**（`BuildConfig.WITH_TRANS` 门禁，故无需降级文案）；有库版保留入口
- [x] **B7 输入与方向**：**仅手动输入**（⑨ 推迟，见 §0.1）；**用原生 `EditText`**；**自动判别中/英文** ⇒ 方向 **中→英 / 英→中（双向）**
- [x] **B8 模型/引擎可选下载**：翻译页「翻译引擎」列表**可选下载**（用户自主选择）；4 条 ONNX（两方向 × 量化/fp32）+ 1 条 ML Kit：
  - **ONNX Marian**（自托管本项目 GitHub Release）：`en→zh`、`zh→en`
  - **Google ML Kit**（可选引擎，~30MB/语言对，质量好；**需 GMS**、**模型不可自托管**、由 Google 服务器下载）

> **待设备实测**：Marian 算子覆盖（B1 的最大不确定项）、Watch4 内存下加载峰值/耗时、长按与现有搜索交互是否冲突。
> 均需装真机手测（AI 装包、用户手测）。

**增加**：ONNX 依赖、`TranslationEngine`、翻译页/长按入口、模型自动下载 + 手动导入、加载/释放管理
**减少**：不删现有功能；翻译方向由「仅 en→zh」改为**自动判别中/英 → 双向**
**漏缺**：① `onnxruntime-mobile` 算子覆盖未知（B1 验证点，最大不确定）；② Watch4 1.5GB 内存下 int8 模型加载峰值/耗时未知，须实测；③ 国内访问 GitHub 下载模型可能慢/受限；④ 长按搜索框与现有搜索交互需防冲突（参考既往 rotary/焦点冲突教训）
**优化**：坚持「模型不随包」，把体积与内存风险从 APK 剥离；进页才加载、用完即释放

### C. 双版本拆分（应对商城 <20MB）

- [ ] **C1 构建拆分**：Gradle 引入 flavor（`withTrans` / `noTrans`），ONNX 依赖**仅加在 `withTrans`**；`noTrans` 版翻译模块（含长按入口）不入包
- [ ] **C2 ABI 拆分**：**仅 `withTrans` 分 ABI**——产出 `armeabi-v7a` 与 `arm64-v8a` 两个包（目标表系统为 32 位）；`noTrans` 无原生库，单包
- [ ] **C3 产物清单（:app）**：`noTrans`×1 + `withTrans`×2（v7a/v8a）= **3 个 APK**；`outputFileName` 纳入 flavor + abi（如 `Engrave-0.4beta.1-wearos-withtrans-v8a.apk`），检查更新按资产名区分逻辑同步
- [ ] **C4 端别**：**本次只做 :app（手表版）**；:app-phone 手机版后期可能上架，届时再产出对应双版本
- [ ] **C5 流程同步**：更新 `.trae/skills/engrave-release-flow/SKILL.md`（构建/发版命令、产物清单、Release 上传清单）

**增加**：flavor 维度、产物数量翻倍、发版流程改造
**减少**：不带库版不含 ONNX 原生库（体积最小化）
**漏缺**：生产两套产物时检查更新按资产名区分，避免用户装错端
**优化**：不带库版严格 <20MB 上架；**带库版仅侧载**

### D. 翻译历史页（只留端口）

- [ ] **D1 预留**：**仅保留导航路由常量**；不建表、不接线、不做 UI
- 说明：粒度 = 只留路由常量（用户 2026-10-17 确认）

### E. ⑨ 离线语音识别 —— **本步推迟（2026-10-17 用户拍板，见 §0.1）**

> 以下条目整体保留为下一步的备查方案，本步**不执行**。翻译页随之取消语音输入。

- [ ] **E1 引擎候选**：**sherpa-onnx**（k2-fsa；与 ⑧b 的 onnxruntime 同源可复用，官方支持 WearOS）+ **Vosk**（Kaldi 系、轻量、有手表用例）**均纳入**，做成用户可选引擎（2026-10-17 修正）
- [ ] **E2 模型可选下载**：语音模型**不打包**，提供**多档模型供用户选择下载**；分发同 ⑧b（GitHub Release 托管 + App 自动下载，手动导入兜底）
- [ ] **E3 设备能力探测 + 推荐**：读取设备**可用内存**（`ActivityManager.MemoryInfo` / `Runtime.maxMemory`）与 **CPU**（核数/主频/ABI）→ 按档**推荐**合适模型（内存/性能不足时推荐低档）
- [ ] **E4 内存机制**：**与翻译模型一致**——进翻译页才加载、`onDispose`/`onCleared` 释放（用户 2026-10-17 修正）
- [ ] **E5 集成**：麦克风采集 → 本地识别 → 文本；离线、不进网
- [ ] **E6 用途入口**：**翻译页语音输入**（读英文句 → 识别文本 → 走 ⑧b 翻译；用户 2026-10-17 确认）

**增加**：语音引擎（sherpa-onnx + Vosk 两套）+ 多档模型 + 设备能力探测/推荐 + 模型下载 + 麦克风采集链路 + 翻译页语音入口
**减少**：无
**漏缺**：① 两套引擎原生库体积需分别核（sherpa-onnx 可复用 onnxruntime，Vosk 自带 Kaldi 库）；② 多档模型 + 与 ⑧b 模型并存的内存/存储压力；③ 手表麦克风拾音质量与准确率未知；④ 手表可用内存探测波动大，推荐逻辑需容错
**优化**：模型全部不打包、按需下载；sherpa-onnx 侧与 ⑧b 共用 onnxruntime；翻译与语音统一内存机制

### F. 反馈收集

- [ ] **F1**：**本步不做表单**；仅保留已有「我的」页 QQ 群 832695614 入口作为反馈兜底（用户 2026-10-17 拍板）

---

## 3. 包体积结论（估，未实测）

| 情形 | 增量 | 结果 |
| --- | --- | --- |
| ⑧a 关系表 | 原始约 +1~3MB，压缩后约 +0.5~1.5MB | ≈ 无感 |
| **不带库版**（上架） | 仅 ⑧a 数据 | **≈ 11MB** ✅ <20MB |
| **带库版** + `onnxruntime-mobile` | +3~7MB | ≈ 13~17MB |
| **带库版** + `onnxruntime-android`（单 ABI） | +8~13MB | ≈ 19~24MB（**可能超 20MB**） |
| ⑨ ASR 原生库 | sherpa-onnx 复用 ⑧b 的 onnxruntime（≈0 额外库）；Vosk 自带 Kaldi 库（另计） | 待核实 |
| ASR 模型（多档可选） | 不打包；GitHub Release 托管，用户按设备推荐按需下载 | 不进 APK |
| 模型本体（翻译） | 不打包；GitHub Release 托管，用户下载；**双向可能需 2 个模型（英→中 / 中→英），下载量翻倍** | 不进 APK |
| 翻译引擎（可选）：Google ML Kit | 依赖（约 +几 MB，实为 AAR）+ 用户按需下载模型 ~30MB/对 | ~30MB/对（存于设备） |

---

## 4. 风险与前置

1. `onnxruntime-mobile` 算子覆盖（B1）——最大不确定，先小样验证。
2. Watch4 内存（⑧b 翻译模型 + ⑨ ASR 多档模型并存）——统一「进页加载/离页释放」，实测确认不 OOM；手表可用内存探测波动大，推荐逻辑需容错。
3. 新依赖首次构建需联网拉 Maven Central（已实测可达）；本机缓存无 ONNX。
4. 模型下载依赖 GitHub 可达性（国内网络）。
5. **文本输入必须用原生组件**（勿自绘/自实现输入框）——既往踩坑，禁止重犯。
6. **Google ML Kit 引擎**：需设备带 GMS；模型下载走 Google 服务器（国内可能不通）；**模型无法自托管**（不能放进我们的 Release）。
7. 改前备份：`_backup/backup-<日期>-pre-step2.zip`（排除 `_旧文件待删除`/`_video`/`手表安装包` 等杂物）。

---

## 5. 验收（轻量，无重流程）

- 用户手测：详情页同/反义段可读可点；有库版长按进翻译页、模型下载/导入后能出中文、关页释放；不带库版**无该入口**；⑨ 语音模型可选下载、按设备内存/CPU 推荐生效、语音输入可出文本并接 ⑧b 翻译。
- 少量样例对照（用户提供若干长句/词条），不做自动评测集。
- 双端 release 构建通过、aapt2 核验版本号。

---

## 6. 发版动作（到 v0.4beta.1）

1. `:app` code 6→7、`:app-phone` code 3→4，versionName → `0.4beta.1`
2. 按 `engrave-release-flow` 构建全部产物（含双版本）
3. `README.md` / `readme-aicode.md` 同步；提交显式 `git add <文件>`，禁用 `git add -A/.`
4. push 后 **GitHub Release（含 APK 与模型资产）由用户自行上传**（本机无 `gh`/token）

---

## 7. 待用户确认项

- [x] 上架端：**只上手表版 :app**（手机版后期可能上）
- [x] 带库版：**仅侧载**
- [x] 历史页「留端口」粒度：**仅路由常量**
- [x] ABI：**目标表系统 32 位/指令集 64 位** ⇒ `withTrans` 出 v7a + v8a 两个包
- [x] 模型分发：**GitHub Release 托管 + App 自动下载**（手动导入兜底）
- [x] ⑨ ASR（**2026-10-17 修正：本步推迟，不做**；原「sherpa-onnx + Vosk 均纳入」作废，见 §0.1）
- [x] 翻译推理库：**`onnxruntime-android` 1.18.0**（2026-10-17 修正，不做 mobile）
- [x] 翻译引擎：**ONNX Marian + Google ML Kit 两套并列可选**（2026-10-17 修正）
- [x] 反馈收集：**仅 QQ 群兜底**（不做表单）
- [x] 是否同意本清单后清空上下文开工 —— **用户 2026-10-17 确认：「确认，清空上下文开工」**

---

## 8. 附：整句翻译页布局（长按搜索框进入；待用户确认）

**进入方式**：搜索页**长按搜索框 ≈600ms** → 跳转本页（点击仍为普通搜索，不冲突）。
**退出方式**：左滑 / 返回键 → `onDispose`/`onCleared` **释放模型**。

### 骨架（1.2″ 圆屏，GN Watch4 396×396；内容列居中、避四角）

```
        ┌───────────────────────┐
        │  ←  整句翻译           │  ← 顶部：返回手势 + 标题
        │                       │
        │      ╭─────────╮      │
        │      │   🎤    │      │  ← 中央大按钮（就绪态：按住说话）
        │      ╰─────────╯      │
        │   按住说话（中/英）    │
        │      ✎ 手动输入        │  ← 备选：原生文本框手写/编辑
        │                       │
        │  ── 原文（可编辑）──   │
        │  I have never seen…   │  ← 语音识别或手写所得，可直接改
        │                       │
        │  ── 译文 ──             │
        │  我从未见过……          │  ← 自动判方向（中→英 / 英→中）
        │                       │
        │        ⏱ 历史           │  ← 底部历史入口（仅预留路由，本步置灰不可点）
        └───────────────────────┘
```

### 页面状态机

| 状态 | 页面显示 | 触发 |
| --- | --- | --- |
| 1 未下载模型 | 「需下载翻译模型(约112MB)」+ [下载] + [手动导入] | 首次进入且本地无模型 |
| 2 下载中 | 进度环 + xx% + [取消] | 点「下载」 |
| 3 加载中 | 转圈 +「正在加载模型…」 | 进入页面（模型已就绪） |
| 4 就绪 | 中央原生输入框 + [翻译] 按钮 | 模型加载完成 |
| 5 ~~录音中~~ | （**作废**：⑨ 推迟，本步无语音） | — |
| 6 ~~识别中~~ | （**作废**：⑨ 推迟，本步无语音） | — |
| 7 翻译中 | 上区显示原文 + 译文区转圈 | 点「翻译」 |
| 8 结果 | 英文（上）+ 中文（下，大字） | 翻译完成 |
| 9 失败 | 错误文案 + [重试] | 下载/识别/翻译出错 |

### 交互与注意

- **输入（0.1 修正）**：**仅手动输入**——**必须用原生文本组件**（勿自绘，既往踩坑）；
  可用键盘/手写输入；**自动判别中/英文**决定翻译方向（中→英 / 英→中）。语音按钮与 `RECORD_AUDIO` 权限本步不做。
- 就绪态中央改为「输入框 + 翻译按钮」替代原 🎤「按住说话」。
- **滚动**：长文本用旋转表冠滑动。
- **权限（0.1 修正）**：本步**不需要** `RECORD_AUDIO`（无语音）；ONNX 模型下载需要 `INTERNET`（已有）。
- **不带库版**：**无长按入口**，故此页不可达（无需降级文案）。
- **内存**：进页加载、离页释放（与 ⑧b/⑨ 统一机制）。
- **历史**：仅底部放一个置灰入口（路由常量），本步不实现存储与列表。
