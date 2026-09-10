# 刻词（Engrave）AI 开发规范文档（readme-aicode.md）

> **本文档写给参与本项目的 AI 编码助手**（包括未来的 AI 会话），记录必须遵守的工程约定、历史踩坑与验证流程。
> 人类开发者请读 [readme-code.md](readme-code.md)（环境搭建/构建/发布），最终用户请读 [readme-user.md](readme-user.md)。

---

## 0. 项目身份速览（AI 必读）

| 项目 | 内容 |
|---|---|
| 应用名 | **刻词**（英文 Engrave） |
| 包名 applicationId / namespace | `top.southfish.engrave`（2026-09-09 由 `com.example.vocabwatch` 迁移，**不可再改**） |
| 版本 | versionName `0.1BETA` / versionCode 1（改版本号在 `app/build.gradle` 的 `defaultConfig`） |
| 定位 | 独立运行于 Wear OS 手表的背单词应用（无手机伴侣端） |
| 技术栈 | Kotlin + Jetpack Compose for Wear OS 1.2.1 + Room 2.6.1 + DataStore |
| minSdk / targetSdk / compileSdk | 30 / **29** / 34（targetSdk 29 是刻意的：One UI Watch 只给 targetSdk≤29 传统存储应用暴露可授予的存储权限；30+ 在手表上无任何用户可授予的文件权限。**不可升**） |
| 开发者 | SouthFish；AI 协作平台 qoder / zcode / trae |
| 开源依赖 | Jetpack Compose/AndroidX/Room（Apache-2.0）、ECDICT 词典数据（MIT） |
| 调试设备 | 三星 Galaxy Watch4 真机（WiFi adb，IP 和端口都会变：曾用 192.168.31.118:44767 → 192.168.1.104:36417，连不上就问用户要新的）；另有 450×450 圆屏模拟器 |

**文档分工**：`readme-code.md` = 人类向开发/构建文档；`readme-aicode.md`（本文）= AI 规范 + 模块级硬约束 + 排坑库；`readme-user.md` = 用户说明书。

---

## 1. 硬性产品约束（用户定稿，不得擅自更改）

以下行为均为用户明确拍板（标日期者为定稿日），改动前必须确认用户同意：

1. **头像/昵称只响应长按**：点头像、点昵称一律无反应。头像长按进 `AvatarPickerScreen`；昵称长按进原生 `EditText` 内联编辑（2026-09-08 移除了点按轮换 emoji）。
2. **卡片手势单检测器原则**：点击翻面 / 三击撤销 / 长按加错题 / 滑动评分必须在同一个 `pointerInput` 检测器内仲裁（`CardGestures.detectFlipAndUndo()`），禁止与 `detectTapGestures`/`detectDragGestures` 并存。
3. **搜索无结果**必须显示「没有相关单词」。
4. **冷启动/热启动**（2026-09-09 定稿）：进程被杀后冷启动 → 一律回主页；热启动（进程未死）→ 恢复上次退出页面。判断用 `VocabApplication.isColdStart` 进程级标志。
5. **NumberWheel**：触发区宽度 = 数字显示文本实测宽度 + 4dp 边距（`rememberTextMeasurer`），不得恢复固定宽度参数；选中值用视口中心检测，禁止 `firstVisibleItemIndex`。
6. **通知权限流程**（2026-09-08）：开提醒先 `hasNotificationPermission()` 检测，已授权直接调度 + 电池优化引导，**绝不**重复弹权限请求、**绝不**跳系统设置页；未授权才弹 POST_NOTIFICATIONS。`OnboardingViewModel.setReminder` 必须同时 `setReminderMinutes()` 落库（漏了会在重启后丢闹钟）。
7. **FilePicker**：`BOOK`/`BACKUP` 双模式；「从设备导入词书」「从设备导入备份」按钮**永久常驻**（导入成功后不得隐藏）；BACKUP 模式校验备份键、词书 JSON 要提示「这不是数据备份文件」。
8. **内置词书**：固定 2 本 ECDICT 词书（初中 1987 / 高中 3743），**单词顺序随机打乱**（固定 seed）；换书内容必须换文件名（importBook 按 sourceKey 幂等去重）。
9. **词书多选**：`activeBookIds: Set<Long>`，空集合 = 全部词书；DAO 用 `:allBooks = 1 OR w.bookId IN (:bookIds)`，空集合传 `listOf(-1L)`。
10. **复习算法（SM-2 变体）**：复习阶段 AGAIN → 10 分钟当日重学（不跳过、不隔天），重复忘记词越记越频、永不丢失；ease 下限 1.3。
11. **学习日切分凌晨 4 点**（`DayIndex`）；跨天未完成会话快照自动作废。
12. **选词页**：队列空且未达目标继续续拉新词（`browseSeen` 排除已见）；头部只显示「已选 x/target」，不得出现「待看」「需背 0 个」。
13. **选图链**（2026-09-09 定稿）：**厂商图库 ACTION_PICK 优先 → 应用内三星图库风格两列网格兜底**。**不使用 Google 照片选择器（PickVisualMedia）**——用户明确要求移除。应用内网格参照三星 One UI Watch 全屏两列方格样式。

---

## 2. AI 工作流程规范

1. **先读后改**：任何文件修改前必须 Read 原文；引用行号前必须确认当前内容。
2. **Edit 后必须 Read 回验证**：Edit 工具偶发**报成功但未落盘**（2026-09-09 多文件并行 Edit 时重现）。同一文件的多个 Edit 逐一确认；编译错误第一时间怀疑是 Edit 未生效而不是代码逻辑。
3. **每批改动必须编译**：`gradlew.bat :app:assembleDebug`（环境变量见 §2.1）。改动 domain/测试还要跑 `:app:testDebugUnitTest`。
4. **真机验证闭环**：改 UI 必须装到手表截图验证；验证方法见 §4。不要只靠静态检查宣布完成。
5. **不主动 git commit**；不创建计划外文件；删除/移动旧文件时**移到 `_旧文件待删除/` 文件夹**让用户检查，不直接删（用户要求）。
6. **注释用中文**，解释"为什么"（坑/约束），不复述代码。
7. **慎用全局批量替换**：PowerShell 批量替换成功率高，但排除目录要写全（`build/`、`.git/`、二进制资产）；替换后必须 grep 残留。
8. 长任务（编译、安装）用 `run_in_background`，期间并行做独立工作。

### 2.1 构建命令（PowerShell）

```powershell
$env:JAVA_HOME='E:\AndroidDev\jdk'; $env:ANDROID_HOME='E:\AndroidDev\sdk'
$env:ANDROID_SDK_ROOT='E:\AndroidDev\sdk'; $env:GRADLE_USER_HOME='E:\AndroidDev\gradle-home'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
& E:\AndroidDev\sdk\platform-tools\adb.exe -s 192.168.31.118:44767 install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 3. Windows / PowerShell / adb 工具坑（历史踩坑实录）

| 坑 | 处理 |
|---|---|
| Edit 工具报成功但文件没变 | Edit 后 Read 回验证；编译错误先查是否 un-landed edit |
| 含中文的 .ps1 ParserError（乱码 `閫変慨鍏?`） | .ps1 必须 UTF-8 **with BOM**；Edit/Write 工具不带 BOM，需 `[System.IO.File]::WriteAllText($p,$text,(New-Object System.Text.UTF8Encoding($true)))` 重写 |
| PowerShell `adb exec-out screencap > x.png` 截图损坏 | 文本重定向破坏 PNG 二进制；改 `adb shell screencap -p /sdcard/x.png` + `adb pull` |
| ConvertFrom-Json 管道进 `@()` 得 `.Count=1` | 先赋值变量再包数组：`$j = Get-Content $f -Raw | ConvertFrom-Json; $arr = @($j)`；字符串插值 `"$f:"` 会被解析成驱动器引用，用 `"${f}:"` |
| adb 长按不识别 | `input swipe x y x+1 y 1000`（同坐标 swipe 不算长按） |
| 手表键盘联想条偷点按 | 联想条约 y135；用键盘蓝色对勾提交搜索；深层页退出用 KEYCODE_BACK |
| 模拟器 MediaStore 无图 | push 图到 `/sdcard/Pictures/` 后发 `MEDIA_SCANNER_SCAN_FILE` 广播 |
| 验证闹钟 | `adb shell dumpsys alarm \| grep engrave`（旧包名 vocabwatch 时代的命令已失效，注意新包名） |
| Wear frameworkpackagestubs 空壳 | `ACTION_PICK/GET_CONTENT/OPEN_DOCUMENT` 会 resolve 到 `com.google.android.wearable.frameworkpackagestubs` 的空壳，启动无任何反应；按包名过滤 |
| Gradle 找不到 JDK/SDK | 检查 §2.1 四个环境变量 |

---

## 4. 真机/模拟器验证流程（AI 照此执行）

1. `adb connect 192.168.31.118:44767`（失败让用户确认手表同 WiFi、检查手表上的调试端口）。
2. `adb install -r app-debug.apk`；失败时 `pm install` + logcat 取详细错误。
3. 启动：`adb shell monkey -p top.southfish.engrave -c android.intent.category.LAUNCHER 1`。
4. 截图：`adb shell screencap -p /sdcard/t.png && adb pull /sdcard/t.png`（screenshot 后 Read 图片确认 UI）。
5. UI 层级：`adb shell uiautomator dump /sdcard/ui.xml && adb pull` 后解析 bounds 拿精确坐标。
6. 冷/热启动验证：`am force-stop` 后重开 = 冷启动（应回主页）；`input keyevent KEYCODE_HOME` 后重开 = 热启动（应回原页）。
7. 测相册：先 push 测试图 + MEDIA_SCANNER_SCAN_FILE 广播。

---

## 5. 代码结构

按**分层架构**组织：`data`（持久化）→ `domain`（纯业务）→ `ui`（Compose）。依赖方向单向向内，`domain` 不 import 任何 Android/UI 类型。

```
app/src/main/java/top/southfish/engrave/
├── MainActivity.kt            # 唯一 Activity；消费 isColdStart 决定冷/热启动行为
├── VocabApplication.kt        # Application：DI 容器、播种、闹钟兜底重排、isColdStart 标志
├── di/AppContainer.kt         # 手写 DI 容器（rememberAppContainer() 获取）
│
├── domain/                    # 纯 Kotlin 业务层（可单测，无 Android 依赖）
│   ├── model/                 # Word / StudyItem / StudyQueueConfig / Grade / ReviewState ...
│   ├── repository/            # 接口：StudyRepository、SettingsRepository ...
│   ├── srs/                   # Sm2Scheduler（间隔重复算法）、TimeProvider
│   └── usecase/               # GetStudySessionUseCase / SubmitReviewUseCase
│                              # / UndoReviewUseCase / RestoreSessionUseCase（StudyUseCases.kt）
│
├── data/
│   ├── local/                 # Room：AppDatabase、dao/、entity/Entities.kt、DayIndex.kt
│   ├── repository/            # StudyRepositoryImpl、SettingsRepositoryImpl
│   ├── session/               # StudySessionStore：未完成学习的快照，跨天自动作废
│   ├── dictionary/            # 词典解析（搜索/详情用 ECDICT sqlite）
│   ├── backup/                # BackupManager：备份/恢复（JSON 导入导出，含设置项）
│   ├── importer/              # ImportBookActivity：系统「打开/分享」JSON 入口
│   ├── mapper/  seed/         # Entity↔Model 映射、内置词书种子
│
├── reminder/                  # 通知提醒（Scheduler/Receiver/BootReceiver）
│
└── ui/
    ├── navigation/            # Destinations、VocabNavHost（导航+冷热启动+栈记忆）
    ├── home/                  # 首页（HorizontalPager：学习页 + 我的页）
    ├── study/                 # 学习核心：状态机三阶段、卡片堆叠、手势仲裁、拼写
    ├── settings/              # StudySettingsScreen（每日新词/释义/提醒转轮）
    ├── components/            # NumberWheel、ReminderWheels、rememberRotaryScrollable
    ├── search/                # 搜索（原生 EditText）+ WordDetailScreen
    ├── mistakes/  books/  stats/  data/  onboarding/
    ├── profile/               # ProfileScreen（含关于区）、AvatarPickerScreen（选图链）
    └── VocabViewModels.kt     # vocabViewModel() —— 带 AppContainer 工厂的 VM 获取器
```

配套工具：`tools/build_wordbooks.py`（ECDICT 词书生成）、`tools/wordbook-converter.html`（通用词典转换器，单文件离线网页，部署到用户网站；输出格式与 §6.5 词书 JSON 一致）。

---

## 6. 核心模块说明与硬约束

### 6.1 SRS 间隔复习（`domain/srs/Sm2Scheduler.kt`）

SM-2 变体：`Grade`（AGAIN/HARD/GOOD/EASY）→ 下次间隔与熟练度。调度器纯 Kotlin，`TimeProvider` 抽象时间可注入固定时钟。**复习阶段 AGAIN 语义**：当日 10 分钟重学（relearningStepsMinutes=[10.0]），ease -0.20（下限 1.3），毕业后毕业间隔恒 1 天——重复忘记的词出现更频繁而非被跳过。

### 6.2 学习流程状态机（`ui/study`）

三阶段：**浏览选词**（上滑选入/左滑已学过）→ **正式背新词**（每词三遍：英→中→拼写）→ **复习**。

- 续批不重复：`getNewItems` 用 `browseSeen` 排除本会话已见词。
- 三击撤销（`CardGestures.detectFlipAndUndo()`）：第一/二击立即翻面，第三击在 ~300ms（`doubleTapTimeoutMillis`）窗口内触发撤销；拖拽/长按/超时断链。
- 纵轴滑动只动顶卡（>52dp 飞出，否则弹簧回零）；下层叠卡 `DeckBehind` **固定不动**。
- 测试阶段必须先 tap 翻面才能滑动评分（防误滑，有意设计）。
- 自动化手势：快滑 ~300ms；慢拖 >~400ms 会被长按加错题劫持。
- 会话快照：`filesDir/study_session.json`；`DayIndex` 凌晨 4 点切日，跨天快照在 `load()` 删除。

### 6.3 通知提醒（`reminder/`）

- `setExactAndAllowWhileIdle`（Doze 准时），无权限兜底 `setWindow(…,10min)`。
- **四层重排**：Application 启动 / ReminderReceiver 次日 / BootReceiver（BOOT_COMPLETED、MY_PACKAGE_REPLACED、TIME_SET、TIMEZONE_CHANGED）/ 设置变更。
- 权限见 §1.6 流程约束；`USE_EXACT_ALARM`(33+)/`SCHEDULE_EXACT_ALARM`(≤32)、电池白名单。
- 验证：`adb shell dumpsys alarm | grep engrave`，看 `window=0 exactAllowReason=policy_permission` 与 `origWhen`。

### 6.4 数字转轮（`ui/components/NumberWheel.kt`）

见 §1.5。视口中心检测 + 文本实测宽度触发区 + 停稳才回调。

### 6.5 设置页（`ui/settings/StudySettingsScreen.kt`）

- **加载门禁**：`loaded=false` 时设置主体 `StudySettingsContent` 整个不组合——转轮 `remember { mutableStateOf(默认值) }` 若在 DataStore 读到前组合，auto-save 会把默认值静默回写、重置提醒时间（实测踩过）。注意：**只在 Scaffold content lambda 里 gate 是不够的**，必须保证轮子状态声明也在 loaded 之后。
- 开提醒的权限流程见 §1.6；`ON_RESUME` 复检授权。

### 6.6 导航与冷热启动（`ui/navigation/VocabNavHost.kt`）

- `SwipeDismissableNavHost`（Wear 边缘返回手势）；学习页/错题重练页临时关闭侧滑。
- **冷热启动**（2026-09-09）：`VocabApplication.isColdStart`（进程级，Application 随进程创建置 true，MainActivity 首次 onCreate 消费置 false）→ `VocabNavHost(coldStart)`。冷启动 `restoreStack=false`：不恢复 DataStore 栈、从 HOME 开始，录制器立刻用 [HOME] 覆盖旧栈；热启动（进程活着，Activity 可能被系统回收重建）恢复上次页面。
- **栈持久化 + 对账**：栈顶变化写 DataStore；Wear 导航同页带同路由包装条目，按「重复=忽略 / 回旧路由=出栈 / 新路由=压栈」对账；录制器栈底**必须预置 startDestination**（否则栈序记反）。
- 引导页不记栈；`detail/{term}` 用实际词头拼回路由。

### 6.7 头像与选图链（`ui/profile/`）

- **两步选图链**（2026-09-09 改版，Google 照片选择器已移除）：
  1. **厂商图库**：`Intent.ACTION_PICK` + `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`。三星 One UI Watch 图库响应它给出全屏两列大图网格；返回 URI 带临时读授权，免存储权限。Manifest `<queries>` 声明保证 11+ 包可见性。
  2. **应用内相册**：厂商图库被取消 / 无厂商图库（frameworkpackagestubs 空壳）时，展示**三星图库风格全屏两列方格网格**（`LazyVerticalGrid`，2dp 间距，点格子即选），读 `MediaStore.Images`（33+ `READ_MEDIA_IMAGES`，≤32 `READ_EXTERNAL_STORAGE`），缩略图 `loadThumbnail`。标题条右侧「系统图库」胶囊可重拉厂商图库。
  - **关键坑**：frameworkpackagestubs 空壳 ResolveInfo 必须按包名过滤（§3）。
- 头像压缩存 `filesDir`；**`BitmapFactory.decodeStream` 在 `inJustDecodeBounds=true` 时按约定返回 null**，不是失败。
- 交互：长按头像进选图；长按昵称进编辑（原生 `EditText`，HoneyBoard 兼容，见 §6.8）。

### 6.8 搜索与详情页

- 搜索框用**原生 `EditText`**——HoneyBoard 全屏提取模式下 BasicTextField 打不出字；无结果显示「没有相关单词」。
- `WordDetailScreen` 展示离线词典完整词条（释义/英义/词形变换/例句），比词书 meaning 丰富。
- **圆屏底部防裁切**：每行内容独立列表 item + 水平 16dp padding；禁止固定高度容器。

### 6.9 词书/数据导入与文件权限

- `FilePickerScreen`：浏览 `Android/data/top.southfish.engrave/files/books/`（免权限）；有 MANAGE_EXTERNAL_STORAGE 时根目录 Download。`PickerMode.BOOK/BACKUP` 见 §1.7。
- `ImportBookActivity` 注册 JSON 的 VIEW/SEND intent-filter——三星「我的文件」等文件管理器「打开/分享」JSON 可直接导入，**免权限**。
- **文件权限调研结论（2026-09-09）**：Wear OS 没有「所有文件访问」设置页，`ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 直接抛 "No Activity found"（Google IssueTracker 299174252）；唯一授予途径是 `adb shell appops set --uid <pkg> MANAGE_EXTERNAL_STORAGE allow`（用户不采用）。**结论：免 adb 读任意公共目录无解**，产品方案 = 应用专属目录 + ImportBookActivity 分享入口 + 备份文件放 Download/vocab（有 appops 时可读）。用户级操作建议：文件放到 `Android/data/top.southfish.engrave/files/books/`。
- 词书 JSON 格式：`{name, description, words:[{term, meaning, phonetic?, example?, exampleTranslation?}]}`（term/meaning 必填；转换器 `tools/wordbook-converter.html` 输出此格式）。

### 6.10 内置词书与种子机制（ECDICT）

- 数据源 ECDICT（MIT，github.com/skywind3000/ECDICT）；搜索/详情词典 `assets/dict/ecdict_core.db`（已生成入库；其生成脚本在 ECDICT 全量库内，该库 1GB 已移入 `_旧文件待删除/ecdict弃用全量库/` 待用户处置，删除前注意保留生成能力或接受不可再生）；内置词书由 `tools/build_wordbooks.py` 从 `wordbook-source/`（kajweb/dict 例句版）生成（初中/高中各一本，随机洗牌固定 seed）。
- `SeedDataProvider.seedAssets()` 运行时枚举 `assets.list("wordbooks")`，按 sourceKey 幂等播种；`retireMissingBuiltInBooks()` 自动退役 assets 已删的内置书（FK CASCADE，从 activeBookIds 剔除）；用户导入书无 `wordbooks/` 前缀永不触碰。
- 词书列表 `naturalStringOrder` 自然排序；多选启用见 §1.9。

### 6.11 学习卡片视觉

- 正面 = CardTitle + CardPhonetic + BrowseHint；背面 = CardBody(meaning, maxLines=2) + ExampleBlock（例句 2 行 + 译文 1 行）。
- 卡片 `cardWidth=0.88w`、`cardHeight=0.78h`；CardFace padding start/end=12、top=18、bottom=26。
- `CardHintOverlay`：leftHint BottomStart、upHint TopCenter，常显 alpha 0.4，内容排版避让两角。
- 词性标签统一带句点（`adj.`/`n.`/`v.`…）。

### 6.12 「我的」页关于区（`ui/profile/ProfileScreen.kt`，2026-09-09 新增）

列表底部：应用图标（`PackageManager.getApplicationIcon`，AdaptiveIcon 不能 painterResource 直读）→ 「刻词 v<versionName>」（`getPackageInfo` 运行时读）→ 「开发者 SouthFish」→ 三平台徽章 Q/zcode…（qoder/zcode/trae，首字母圆形徽章）→ 开源声明（Compose/AndroidX/Room Apache-2.0、ECDICT MIT）。版本发布后新增开源依赖时**必须同步更新这段声明**。

---

## 7. 数据层

### 7.1 Room（`data/local`）

| DAO | 职责 |
|---|---|
| `WordDao` | 词书单词 CRUD |
| `ReviewStateDao` | SRS 状态（熟练度、间隔、到期时间） |
| `ReviewLogDao` | 评分历史（统计/撤销依据） |
| `SearchDao` | 搜索查询 |
| `MistakeDao` | 错题集 |

`DayIndex` 凌晨 4 点切日；Room schema 导出 `app/schemas/`。**改 applicationId/包名后 Room 文件随应用数据目录迁移，旧包名数据不自动迁移**（2026-09-09 迁包名时用户数据即全新开始）。

### 7.2 DataStore（`SettingsRepositoryImpl`）

键见 `Keys`：`daily_new_limit`、`reminder_minutes`、`definition_display`、`active_book_ids`（CSV，空=全部；旧 `active_book_id` 读取时迁移）、`user_name`、`avatar_index`、`avatar_image_version`、`onboarding_done`、`nav_stack`。新增键：加 `Keys` 常量 + `resetAll()` 同步清理。

---

## 8. 测试

### 8.1 单元测试（`app/src/test/`）

| 测试类 | 覆盖 |
|---|---|
| `DayIndexTest` | 学习日切分边界 |
| `Sm2SchedulerTest` | SRS 各评分档间隔/熟练度演进 |
| `GetStudySessionUseCaseTest` | 队列组装、续批排除已见、activeBookIds 透传 |
| `StudyFlowTest` | 状态机、答错重排、三击撤销、词书见底分支 |
| `SpellingChunksTest` | 拼写字母切分 |

结果 XML：`app/build/test-results/testDebugUnitTest/`。

### 8.2 手工回归清单（真机）

1. 冷启动：进深层页 → `am force-stop` → 重开 → **应回主页**（2026-09-09 新行为）。
2. 热启动：进深层页 → HOME 键/表盘 → 重开 → 应回到该页。
3. 提醒 08:55 → 强杀重启 → 时间不回 08:00。
4. 学习页三连击撤销；顶卡滑动飞出、叠卡不动。
5. 长按头像 → 厂商图库（三星表应出全屏两列图库）→ 取消 → 应用内两列网格 → 选图头像刷新。
6. 我的页滚到底：关于区（图标/版本 0.1BETA/SouthFish/三平台/开源声明）完整可见。
7. 搜索「apple」→ 详情页滑到底末行不裁切。
8. 词书管理多选 → 首页计数变化；数据管理导入词书 JSON 提示「这不是数据备份文件」。
9. 通知权限：已授权开提醒不弹请求不跳设置。

---

## 9. 代码规范

- **分层不越界**：`domain` 禁止 import `android.*`/`androidx.*`；UI 不直接碰 DAO。
- 仓库接口在 `domain/repository`，实现 `data/repository`，UI 经 `AppContainer` 获取。
- Compose 状态单向向下、事件向上；屏幕级状态 `StateFlow<UiState>` + `stateIn(WhileSubscribed(5000))`。
- 注释中文、说明为什么；本文档 §1、§6 的「禁止事项」改动前必读。
- **UI 约定**：优先 wear compose material 组件（`androidx.wear.compose.material.*`），不要 import 手机版 `androidx.compose.material.*`（本项目未依赖，必炸）。

---

## 10. 常见问题（FAQ / 排坑）

| 现象 | 原因 / 处理 |
|---|---|
| 重启后提醒时间变回 08:00 | 设置页轮子在 DataStore 加载前以默认值组合并被回写（§6.5 加载门禁） |
| 转轮选不中 55 分/23 时 | 用了 firstVisibleItemIndex；改视口中心检测（§6.4） |
| 卡片单击翻面延迟 | detectTapGestures 双击等待；用 detectFlipAndUndo |
| 三击撤销误触/不触发 | 同一检测器同一 ~300ms 窗口；拖拽/长按断链 |
| 头像选图页点按钮没反应 | frameworkpackagestubs 空壳被 resolve；按包名过滤（§6.7） |
| `BitmapFactory.decodeStream` 返回 null | inJustDecodeBounds=true 属正常，用独立流解码 |
| 后台收不到提醒 | 精确闹钟权限 + 电池白名单；dumpsys alarm 查（§6.3） |
| 搜索框打不出字（三星输入法） | 用原生 EditText，禁 BasicTextField |
| PowerShell 截图损坏 | screencap 落设备再 pull（§3） |
| 导航恢复栈序反 | 录制器预置 startDestination；恢复前压缩重复路由（§6.6） |
| 上滑整叠卡一起动 | 只允许顶卡位移，DeckBehind 固定（§6.2） |
| 测试阶段正面滑动没反应 | 设计如此：先翻面再评分 |
| 详情页底部内容被裁 | 独立列表 item + 16dp 水平 padding（§6.8） |
| 例句被提示徽章挤压 | 卡片尺寸下限 0.88/0.78；例句 2 行译文 1 行（§6.11） |
| 内置词书残留 | retireMissingBuiltInBooks 自动退役；确认 assets 文件名与 sourceKey |
| 词书 U10 排在 U2 前 | naturalStringOrder（§6.10） |
| 含中文 .ps1 乱码报错 | UTF-8 with BOM 重写（§3） |
| ColdStart 判断失效 | MainActivity 必须消费标志；进程单例只对单进程 app 有效（§6.6） |
| Gradle 找不到 JDK/SDK | §2.1 环境变量 |

---

## 11. 变更记录（AI 会话接力用）

- **2026-09-10（凌晨，性能优化会话）**：全面性能优化 5 处——① `DictRepository` 词典连接由「每次查询 open/use/close」改为 `@Volatile` 常驻只读连接（双检查锁 + Mutex），打开时设 `PRAGMA cache_size=-4096`（4MB 页缓存）、`temp_store=MEMORY`；② `SearchViewModel` 搜索触发流补上 200ms 防抖（旧代码只有展示文本防抖，每次按键都打库）；③ `ProfileScreen` 头像 `BitmapFactory.decodeFile` 移到 `Dispatchers.IO` 异步解码（inSampleSize=2，按 avatarImageVersion 刷新）；④ `AvatarPickerScreen` 缩略图 256→220px、新增进程级 LruCache（32 张≈6MB）、删除每格 CircularProgressIndicator 无限动画（改留白格）；⑤ `DetailViewModel` 详情 lookup + findWord 串行改并行（async，runCatching 隔离）。验证：编译 + 单测通过；模拟器（Watch4 AVD, SDK33）全功能回归通过（引导/搜索/空结果态/详情页/我的页滚动/学习卡，无崩溃）；DB 微基准（真实 ecdict_core.db）单次查询 0.38ms→0.04ms（约 10×），纯 open/close 0.23ms、冷尾延迟 5.5ms；PSS 78.1→77.9MB 持平；模拟器 gfxinfo 帧率无显著差异（SwiftShader 软件渲染，帧耗时被 IME/Compose 动画主导，GPU 调度改善需真机验证）。优化前完整备份在 `_备份/优化前-20260909-性能优化前/`（APK + 源码快照 zip）；交付 `手表安装包/刻词-0.1BETA-debug-20260910.apk`，旧包归档 `_旧文件待删除/`。
- **2026-09-09（晚，二次会话）**：真机验证通过——冷启动回主页 / 热启动恢复搜索页 / 关于区（图标·v0.1BETA·SouthFish·qoder/zcode/trae·双协议）/ Download 导入。**文件权限结论修正**：`READ_EXTERNAL_STORAGE` 可在手表 UI 授予（许可管理器「文件」/运行时弹窗），授予后直接读 Download，推翻当日早前「免 adb 无解」结论；前提 targetSdk 保持 29。旧文件归档至 `_旧文件待删除/`（含 1GB ecdict 弃用全量库、.verify、perm-test*.json、旧 APK/README）；`手表安装包/` 放入 `刻词-0.1BETA-debug-20260909.apk`；readme 修正 targetSdk 29 说明与过期 IP。
- **2026-09-09**：应用更名「刻词」、包名迁移 `top.southfish.engrave`、版本 0.1BETA；冷/热启动分流（isColdStart）；「我的」页关于区；选图链改两步（厂商图库 → 三星风格应用内网格，移除 Google 照片选择器）；新增 `tools/wordbook-converter.html` 通用词典转换器；文档拆分为 readme-code（人类）/ readme-aicode（本文）。
- **2026-09-08**：ECDICT 词书切换、词书多选、通知权限流程、NumberWheel 触发区、FilePicker 常驻、长按交互定稿、三击撤销。
