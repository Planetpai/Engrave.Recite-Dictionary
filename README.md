# Wear OS 背单词

三星 Galaxy Watch 4 上的背单词应用，目标平台 Wear OS 4（API 33）。

---

## 一、还差一步才能跑模拟器

**模拟器硬件加速驱动（AEHD）尚未安装**，装之前模拟器会慢到无法使用。

安装需要管理员权限，请手动执行：

1. 双击 `install-hypervisor.bat`
2. 在弹出的 UAC 窗口点 **「是」**
3. 脚本会打印安装结果，并把日志写到 `E:\AndroidDev\aehd-install.log`
4. **重启电脑**
5. 重启后双击 `check-env.bat`，第 5 项应显示 `OK AEHD driver service installed`

安装前的当前状态（已确认）：

```
emulator -accel-check
  → Android Emulator hypervisor driver is not installed on this machine
```

本机 CPU 虚拟化在 BIOS 中已开启（VT-x + SLAT 均支持），且无其它 hypervisor 冲突
（Hyper-V 未启用，MuMu 未安装内核驱动），所以 AEHD 是正确的加速方案。

装完并重启后，双击 `start-emulator.bat` 启动 Wear OS 4 模拟器。
如果模拟器窗口黑屏（本机是 Intel Iris Xe 核显，且挂了 Todesk / MuMu 两个虚拟
显示适配器，GPU 检测可能被带偏），改用软件渲染：

```
start-emulator.bat swiftshader_indirect
```

> 450×450 的小圆屏用软件渲染也足够流畅。

---

## 二、目录结构

工具链和项目已分离，公共环境放在 `E:\AndroidDev`，以后加新项目可以直接复用：

```
E:\AndroidDev\                  公共开发环境（约 10.6 GB）
├── android-studio\             Android Studio 2026.1.3（原 E:\aaaa）
├── sdk\                        Android SDK（5.7 GB）
│   ├── platforms\              android-33、android-34
│   ├── build-tools\            34.0.0
│   ├── system-images\          android-33 / android-wear / x86_64（Wear OS 4）
│   ├── emulator\               37.1.11
│   ├── platform-tools\         adb 37.0.1
│   ├── cmdline-tools\          sdkmanager、avdmanager
│   └── extras\google\          AEHD 驱动安装包
├── jdk\                        JDK 17.0.12（Microsoft build）
├── gradle-home\                Gradle 缓存 + wrapper 分发（910 MB）
├── android-home\               adbkey、debug.keystore、AVD
│   └── avd\Watch4.avd\         Wear OS 4 模拟器镜像
├── studio-config\              Studio 配置（Roaming）
├── studio-system\              Studio 缓存索引（Local，386 MB）
└── setup-env.ps1               重新写入环境变量（改路径后可重跑）

E:\WearOS-VocabProject\         本项目（只放代码和脚本）
├── app\                        应用模块
│   └── src\main\
│       ├── java\com\example\vocabwatch\
│       │   ├── MainActivity.kt     入口 + SwipeDismissableNavHost 导航
│       │   ├── HomeScreen.kt       首页
│       │   └── StudyScreen.kt      学习页 + Word 数据类
│       ├── res\
│       └── AndroidManifest.xml
├── build.gradle                AGP 8.2.0 / Kotlin 1.9.20
├── gradle.properties           含 org.gradle.java.home 锁定 JDK 17
├── local.properties            sdk.dir 指向 E:\AndroidDev\sdk
├── gradlew.bat / gradlew
├── check-env.bat               环境自检（7 项）
├── create-avd.bat              重建 AVD
├── patch-avd-config.bat/.ps1   按 Watch4 实机调校 AVD 配置
├── start-emulator.bat          启动模拟器
└── install-hypervisor.bat/.ps1 安装 AEHD 加速驱动
```

---

## 三、环境变量

已写入**用户级**环境变量，新开终端和 Studio 后生效：

| 变量 | 值 |
|---|---|
| `JAVA_HOME` | `E:\AndroidDev\jdk` |
| `ANDROID_HOME` | `E:\AndroidDev\sdk` |
| `ANDROID_SDK_ROOT` | `E:\AndroidDev\sdk` |
| `ANDROID_AVD_HOME` | `E:\AndroidDev\android-home\avd` |
| `GRADLE_USER_HOME` | `E:\AndroidDev\gradle-home` |
| `PATH` 追加 | `sdk\platform-tools`、`sdk\emulator`、`sdk\cmdline-tools\latest\bin` |

`jdk\bin` **没有**加进 PATH：系统 PATH 里有 Oracle 的 `javapath` 垫片指向 Java 8，
用户级 PATH 排在系统级之后会被遮蔽，加了也没用。Gradle 和 sdkmanager 都走
`JAVA_HOME`，不受影响。

改路径后重跑：`powershell -ExecutionPolicy Bypass -File E:\AndroidDev\setup-env.ps1`

---

## 四、C 盘空间

配置前 C 盘只剩 **3.7 GB**，现在剩 **6.6 GB**（腾出约 2.9 GB）。

四处目录联接（junction）让任何硬编码 C 路径的工具仍然可用，但数据实际落在 E 盘：

```
C:\Users\32474\.gradle                                     → E:\AndroidDev\gradle-home
C:\Users\32474\.android                                    → E:\AndroidDev\android-home
C:\Users\32474\AppData\Local\Google\AndroidStudio2026.1.3  → E:\AndroidDev\studio-system
C:\Users\32474\AppData\Roaming\Google\AndroidStudio2026.1.3→ E:\AndroidDev\studio-config
```

另外**删除**了 `C:\Users\32474\AppData\Local\Android\Sdk`（1.6 GB）。那是 Studio
首次启动时自动新建的重复 SDK，只含 API 37 平台和 build-tools 36，本项目用不到
（compileSdk 34 / targetSdk 33）。删除前已逐项校验 E 盘 SDK 组件齐全。
Studio 的全局 SDK 路径也已改写指向 E 盘，不会再重新下载。

如果以后需要 API 37：

```
sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "sources;android-34"
```

> `sources;android-34` 是 API 34 的框架源码，装了之后在 Studio 里点进
> Android 系统类才能看到实现，约 200 MB，按需安装。

---

## 五、常用命令

在项目根目录 `E:\WearOS-VocabProject` 下执行：

```bat
check-env.bat                 REM 环境自检
gradlew.bat assembleDebug     REM 构建 debug APK
gradlew.bat clean assembleDebug
start-emulator.bat            REM 启动 Wear OS 4 模拟器
gradlew.bat installDebug      REM 装到已连接的设备/模拟器
gradlew.bat --stop            REM 停掉 Gradle 守护进程释放内存
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（约 22 MB）

最近一次全新构建：**BUILD SUCCESSFUL in 27s**，增量构建 1s。

---

## 六、部署到真机 Watch 4

模拟器之外，直接装到你的手表上体验更好（真旋转表圈、真圆屏、真实性能）。

1. 手表：设置 → 关于手表 → 连续点「版本号」7 次，开启开发者模式
2. 手表：设置 → 开发者选项 → 打开「ADB 调试」和「无线调试」
3. 手表和电脑连同一个 WiFi，在手表的无线调试页面看到 IP 和端口
4. 电脑连接并安装：

```bat
adb connect <手表IP>:<端口>
adb devices
gradlew.bat installDebug
```

`adbkey` 已保存在 `E:\AndroidDev\android-home\adbkey`（迁移时一并搬过来了），
手表上如果弹出「允许 USB 调试」的授权框，勾选「一律允许」再确定。

---

## 七、AVD 配置

`Watch4` 这个 AVD 已按 Galaxy Watch 4 实机调校，和默认值的差异：

| 配置项 | 默认 | 已改为 | 原因 |
|---|---|---|---|
| `hw.rotaryInput` | `no` | **`yes`** | Watch4 有物理旋转表圈，是主要交互方式 |
| `hw.gpu.enabled` | `no` | **`yes`** | 默认是软件渲染，非常卡 |
| `hw.keyboard` | `no` | **`yes`** | 允许用电脑键盘在模拟器里输入 |
| 设备档位 | `wearos_small_round` 384×384 | **`wearos_large_round` 454×454** | 对应 Watch4 44mm |
| `hw.ramSize` | 1536 | 1536（不变） | Watch4 实机就是 1.5 GB |
| `hw.lcd.density` | 320 | 320（不变） | 与实机一致 |

454×454 是 Google 官方 large round 档位的原生值，和 Watch4 44mm 的 450×450
相差 2dp（320dpi 下 1px = 0.5dp），对 Compose 布局没有实际影响，所以没有强行
改成 450，避免和档位其它参数不匹配。

如果你的 Watch4 是 **40mm**（396×396），把 `create-avd.bat` 里的
`DEVICE` 改成 `wearos_small_round`，然后依次跑 `create-avd.bat`、
`patch-avd-config.bat`。

`patch-avd-config.bat` 会先备份 `config.ini.bak`，可重复执行。

> 注意：`config.ini` 里有 `avd.id=<build>` 这类含尖括号的行，批处理的 `echo`
> 会把它们当重定向符号从而写坏文件，所以实际改写用的是
> `patch-avd-config.ps1`。

---

## 八、Android Studio

用 `E:\AndroidDev\android-studio\bin\studio64.exe` 启动（原名 `E:\aaaa`）。
打开 `E:\WearOS-VocabProject` 即可。

**Gradle JDK 已在 `gradle.properties` 里锁定为 JDK 17：**

```properties
org.gradle.java.home=E:/AndroidDev/jdk
```

原因：Studio 2026.1.3 自带的是 **JBR 25**，而 **Gradle 8.2 最高只支持 Java 19**。
不锁定的话，命令行构建正常但 IDE 里点 Sync 会直接失败。

Studio 的全局 SDK 路径已改写为 `E:\AndroidDev\sdk`（`studio-config\options\
android.sdk.path.xml` 和 `jdk.table.xml`），不会再往 C 盘下载 SDK。

---

## 九、应用架构

四层，依赖方向单向向下。`domain` 是纯 Kotlin，**不 import 任何 Android 或
Compose 类**，所以能在 JVM 上直接跑单元测试，将来换 UI 框架或换调度算法都不动它。

```
ui          Compose 界面 + ViewModel，只依赖 domain
domain      模型、SRS 算法、仓库接口、用例    ← 纯 Kotlin，可单测
data        Room / DataStore / assets 实现 domain 的接口
di          AppContainer 手动装配
```

### 目录结构

```
app/src/main/java/com/example/vocabwatch/
├── MainActivity.kt              入口，只负责 setContent
├── VocabApplication.kt          持有 AppContainer，启动时后台导入词书
├── di/
│   └── AppContainer.kt          手写依赖图（全部 lazy，冷启动不碰数据库）
├── domain/
│   ├── model/                   Word、WordBook、ReviewState、Grade、
│   │                            LearningStage、StudyItem、StudyStats、
│   │                            StudyQueueConfig、ReviewLog
│   ├── srs/
│   │   ├── Scheduler.kt         调度器接口 + TimeProvider + ReviewOutcome
│   │   └── Sm2Scheduler.kt      SM-2 实现 + Sm2Params
│   ├── repository/              WordRepository、StudyRepository、SettingsRepository（接口）
│   └── usecase/                 GetStudySessionUseCase、SubmitReviewUseCase
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt       Room 数据库
│   │   ├── DayIndex.kt          学习日折算（凌晨 4 点翻天）+ 连续天数计算
│   │   ├── Projections.kt       JOIN 出来的扁平行：StudyItemRow 等
│   │   ├── entity/              4 张表的实体
│   │   └── dao/                 WordDao、WordBookDao、ReviewStateDao、ReviewLogDao
│   ├── mapper/                  entity ↔ domain 双向映射
│   ├── repository/              三个仓库的实现
│   └── seed/                    WordBookJsonParser、SeedDataProvider
└── ui/
    ├── VocabViewModels.kt       vocabViewModel() 工厂助手
    ├── navigation/              Destinations、VocabNavHost
    ├── components/              RotarySupport（旋转表圈）、Formatters
    ├── home/                    HomeScreen + HomeViewModel
    ├── study/                   StudyScreen + StudyViewModel
    └── stats/                   StatsScreen + StatsViewModel

app/src/main/assets/wordbooks/cet4-core.json    内置词书（30 词）
app/src/test/java/...                           34 个单元测试
app/schemas/                                    Room 导出的建表 SQL，进版本库
```

### 关键设计决策

**间隔重复用 SM-2，但抽成接口。** `Scheduler` 只有一个 `schedule(state, grade, now)`
方法，`Sm2Scheduler` 是当前实现。原始 SM-2 只有「答对就 interval × EF」一条规则，
这里按 Anki 的做法扩成三段式：

```
NEW ──答对──> LEARNING（1 分钟 → 10 分钟）──走完──> REVIEW（1 天 → 2.5 天 → 6.25 天 …）
                                                        │
                                          答错 lapses+1 ↓
                                                   RELEARNING（10 分钟）──答对──> REVIEW
```

ease factor 仍用 SM-2 原式 `EF' = EF + (0.1 - (5-q)(0.08 + (5-q)0.02))`，
下限夹在 1.3。以后想换 FSRS，只要再写一个 `Scheduler` 实现，改
`AppContainer` 里一行即可。

**调度器是纯函数。** 不碰数据库、不取系统时间，`now` 由调用方传入。
这带来两个好处：可以直接单测（16 个用例锁住每档评分的具体天数），
以及 UI 能**提前把四档评分的结果都算出来**——所以每个评分按钮上
都能显示「选了它下次是几天后」，用户在点之前就知道后果。

**会话队列是内存快照，不是 Flow。** 若直接观察数据库，刚答对的卡片会因为
`dueAt` 变化而立刻从队列消失，进度显示和翻页都会错乱。所以
`GetStudySessionUseCase` 一次性取出快照交给 ViewModel 持有。

**队列组装策略放在用例层，仓库只取原料。** `GetStudySessionUseCase` 返回
`SessionPlan(newItems, dueItems)`：每日新词额度、每日复习额度在这里扣掉，
调用方拿到的就是今天真正允许学的量。有意不混排——产品流程是「先浏览选新词、
再正式背、最后复习到期词」，混在一个列表里表达不出阶段。策略是纯逻辑，
用假的仓库就能测（6 个用例覆盖额度用完、额度部分消耗、短路不查库等分支）。

**导入词书时给每个词预建一条 `ReviewState`（stage = NEW）。** 代价是每本书
多几千行小记录，换来的是所有复习查询都能用 INNER JOIN，不必到处处理
「这个词还没学过」的 null 情况。

**手动 DI 而非 Hilt。** 当前是 AGP 8.2 / Kotlin 1.9.20 的老工具链，
再叠一层注解处理器只增加版本匹配风险。`AppContainer` 三十行就够清楚，
每个 `by lazy` 都能一对一换成 `@Provides`。

**旋转表圈自己封了一层。** Wear Compose **1.2.1 完全没有 rotary API**
（`foundation.rotary` 是 1.3 才引入的，1.2.1 的 jar 里搜不到任何 Rotary 类），
所以 `RotarySupport.kt` 从根 View 上截获 `SOURCE_ROTARY_ENCODER` 原生事件。
升级到 Wear Compose 1.3+ 后这个文件可以整体删掉，调用处换成
`Modifier.rotaryScrollable(state)`。

### 数据库

4 张表，全部外键 `ON DELETE CASCADE`，删词书会连带清掉词条、调度状态和日志：

| 表 | 作用 | 关键约束 |
|---|---|---|
| `word_books` | 词书 | `sourceKey` **唯一索引** → 导入幂等 |
| `words` | 词条 | `(bookId, term)` 唯一索引 → 同书内不重复 |
| `review_states` | 每词一行调度状态 | `wordId` 主键兼唯一索引 |
| `review_logs` | 每次评分一条流水 | `reviewDay`、`reviewedAt` 建索引 |

`review_logs.reviewDay` 是写入时就折算好的**本地学习日序号**（epochDay），
翻天时刻定为凌晨 4 点——熬夜到 1 点背单词不该打断连续天数。
查询侧因此不用再关心时区。

建表 SQL 已导出到 `app/schemas/`，应进版本库；以后改表结构要写 Migration。

### 技术栈

| | |
|---|---|
| 语言 | Kotlin 1.9.20 |
| UI | Jetpack Compose for Wear OS 1.2.1（Material 2） |
| 数据库 | Room 2.6.1 + KSP 1.9.20-1.0.14 |
| 设置存储 | DataStore Preferences 1.0.0 |
| 异步 | Coroutines 1.7.3（Flow / StateFlow） |
| 构建 | Gradle 8.2 / AGP 8.2.0 / JDK 17 |
| SDK | compileSdk 34、minSdk 30、targetSdk 34（真机 Watch 4 是 Wear OS 5 / API 34） |
| 测试 | JUnit 4.13.2 + coroutines-test |

`minSdk 30` 覆盖 Wear OS 3/4/5，所以这个 APK 在 Watch4 上无论升到哪个版本都能装。
Manifest 已声明 `com.google.android.wearable.standalone=true`，作为独立手表应用
分发，不依赖手机伴侣端。

移除了 `appcompat` 和 `play-services-wearable`（都用不上），APK 从 21.5 MB
降到 19.5 MB。

---

## 十、验证状态

命令行与真机/模拟器都实际跑过：

| 项 | 结果 |
|---|---|
| `gradlew test assembleDebug` | **BUILD SUCCESSFUL**，0 编译警告 |
| 单元测试 | **42 个全部通过** |
| Room 建表 SQL | 已核对 `app/schemas/.../1.json`，4 张表、外键 CASCADE、唯一索引均正确 |
| 真机 Watch 4（Wear OS 5 / API 34 / 450×450@340dpi） | 无线 ADB 安装运行正常，冷启动约 1.3s |
| 模拟器 | AVD 已改成和真机一致的 450×450、340dpi、圆屏（`patch-avd-config.ps1`） |

真机/模拟器上逐项点过的学习流程：

| 步骤 | 结果 |
|---|---|
| 首页进度 / 开始学习按钮 | 圆屏内完整可见，不再被弧形遮罩裁掉 |
| 浏览选词：左滑「已学过」 | 按 EASY 落库，数据库 `review_logs` 有记录 |
| 浏览选词：上滑「不认识」 | 不写库，只进待背列表；选够额度出「选够了 / 开始背单词」提示页 |
| 浏览选词：上拉露例句 | 整叠卡上移停住，灰线下面露例句；下拉复位继续背这个词 |
| 叠卡下层 | 渲染队列里下一个词，拖动时看得见接下来背什么；停驻后淡出给例句让位 |
| 正式背单词：点击翻面 | 3D 翻面动画正常 |
| 翻面后左滑「会」/ 上滑「不会」 | GOOD / AGAIN 落库；不会的卡换考法隔两张重排 |
| 拼写卡 | 乱序字母点选；拼错抖动后按「不会」落库并重排，拼对按「会」前进 |
| 双击撤销 | 原卡回队首、重排的那张被摘掉、`review_logs` 里那条记录被删除（查库确认） |
| 学习页侧滑返回 | 真机+模拟器都确认被禁（`SwipeToDismissBoxState.confirmStateChange` 拦截），返回键可退出且进度保留 |

测试分布：

```
Sm2SchedulerTest             16 个   SM-2 每档评分的具体天数、ease 上下限、间隔单调增长、纯函数性
StudyFlowTest                13 个   浏览选词/正式/复习三阶段、答错重排、双击撤销、拼写卡字母、停驻例句
DayIndexTest                  9 个   连续天数、凌晨 4 点翻天边界
GetStudySessionUseCaseTest    6 个   每日额度、额度部分消耗、额度用尽时的短路
```

跑测试：`gradlew.bat test`

> **脚本化手势验证请在模拟器上做。** 真机息屏很快，屏幕熄灭时注入的
> `input swipe` 会产生乱序位移（实测出现过一次上滑被识别成右滑），
> 每次注入前都得先 `input keyevent 224` 唤醒并等动画跑完。
> 另外 `input swipe` 实际只送达约八成的请求距离（150px 的请求到手约 120px），
> 写验证脚本时滑动距离要按阈值留出这个余量。

---

## 十一、已知问题与后续

**产品规格里还没做的部分**

1. **首页 / 我的页左右滑动切换。** 规格要求两页之间横滑切换，当前是
   导航栈推入。改成 `HorizontalPager` 时要注意和学习页的手势隔离。
2. **搜索页与离线词典。**  fuzzy 搜索、搜索历史、详情页、错题本入口都还没做。
   词典源已调研定案：ECDICT（MIT，约 76 万词条），缺例句和近义反义——
   例句按产品决定**只用词书自带**，近义反义**不做**。
3. **我的页。** 头像、昵称、连续天数展示、词书管理（导出/删除/体积）、
   每日新词设置、数据导入导出。`SettingsRepository` 的 setter 已就绪，缺 UI。
4. **首次引导流程。** 选词书 → 每日词量 → 可选提醒时间 → 欢迎页。
5. **夜间模式开关。** AMOLED 省电取向的深色主题目前就是默认主题，
   还没有可切换的日间模式。
6. **词书转换网站暂缓。** 纯前端、不接 AI 的转换工具，等软件本体稳定后再做。

**框架层面**

7. **每日上限没有 UI 能改。** `setDailyNewLimit` / `setDailyReviewLimit`
   已写好，缺设置页（并入第 3 项）。
8. **没有发音。** 手表上背单词大概率需要 TTS 或音频，当前完全没做。
9. **复习阶段的卡型只有两种**（英选中 / 中选英），规格如此；若以后想加
   拼写复习，`kindsFor()` 一行就能放开。

**环境层面**

10. **工具链偏旧** —— AGP 8.2.0 / Gradle 8.2 / Kotlin 1.9.20 / Wear Compose 1.2.1
    是 2023 年的版本，配 Studio 2026.1.3 可能在 Sync 时报版本过低。
    命令行构建正常。若要升级，`domain` 和 `data` 两层基本不用动，
    只有 `ui/` 的 import 需要跟着 Wear Compose 1.3+ 调整
    （`RotarySupport.kt` 可整个删掉换成官方 `rotaryScrollable`）。
11. **内存偏紧** —— 15.8 GB 总内存常只剩 5–6 GB 可用。模拟器（1.5 GB）+
    Gradle 守护进程（2 GB）+ Studio（2–4 GB）同时开会吃紧。不用时跑
    `gradlew.bat --stop` 释放。
