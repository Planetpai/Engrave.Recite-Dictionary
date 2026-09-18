# 刻词（Engrave）开发者文档（readme-code.md）

> 面向**人类开发者**：环境搭建、构建运行、代码结构、测试与发布。
> 模块级硬约束、历史踩坑与 AI 协作规范见 [readme-aicode.md](readme-aicode.md)；使用说明见 [readme-user.md](readme-user.md)。

刻词（Engrave）是一款**独立运行于 Wear OS 手表**（不依赖手机伴侣端）的背单词应用：词书导入、SRS 间隔复习、错题集、每日提醒、头像/昵称、学习统计。技术栈 Kotlin + Jetpack Compose for Wear OS + Room + DataStore。

---

## 1. 项目概览

| 项目                              | 内容                                     |
| ------------------------------- | -------------------------------------- |
| 应用名                             | 刻词（Engrave）                            |
| 包名                              | `top.southfish.engrave`                |
| 版本                              | 目前最新版本`0.21Beta`（versionCode 3）         |
| minSdk / targetSdk / compileSdk | 28 / **29** / 34（targetSdk 故意压在 29，见下） |

> **为什么 targetSdk 是 29**：One UI Watch 的「设置 → 安全与隐私 → 许可管理器 → 文件」只对 targetSdk≤29 的传统存储应用开放存储权限授予（运行时弹窗同理）；targetSdk 30+ 在手表上没有任何用户可授予的文件权限（无「所有文件访问」页、SAF 是空壳）。targetSdk 29 + `requestLegacyExternalStorage` + 授予 `READ_EXTERNAL_STORAGE` 即可用 `java.io.File` 直接读 Download（2026-09-09 真机验证通过）。**不要升 targetSdk**，除非找到替代的文件访问方案。
> | UI | Jetpack Compose（BOM 2023.10.01）+ Wear Compose 1.2.1 |
> | 本地存储 | Room 2.6.1（KSP）+ DataStore Preferences 1.0.0 |
> | 测试 | JUnit 4 + kotlinx-coroutines-test |
> | 构建 | Gradle（Groovy DSL，`gradlew.bat`） |
> | 真机 | 三星 Galaxy Watch4（WiFi adb，IP 见 readme-user） |

---

## 2. 环境搭建

### 2.1 必需软件

| 软件                                              | 本机路径（参考）                         |
| ----------------------------------------------- | -------------------------------- |
| JDK 17                                          | `E:\AndroidDev\jdk`              |
| Android SDK（emulator、platform-tools、Wear OS 镜像） | `E:\AndroidDev\sdk`              |
| Gradle 缓存目录                                     | `E:\AndroidDev\gradle-home`      |
| AVD 目录                                          | `E:\AndroidDev\android-home\avd` |

### 2.2 环境变量（Windows PowerShell）

```powershell
$env:JAVA_HOME='E:\AndroidDev\jdk'
$env:ANDROID_HOME='E:\AndroidDev\sdk'
$env:ANDROID_SDK_ROOT='E:\AndroidDev\sdk'
$env:GRADLE_USER_HOME='E:\AndroidDev\gradle-home'
```

`start-emulator.bat` 已内置上述变量，双击即可启动模拟器；黑屏时改用软件渲染：`start-emulator.bat swiftshader_indirect`。

辅助脚本：`check-env.bat`（环境自检）、`create-avd.bat`（创建 AVD）、`install-hypervisor.*`（Hypervisor 加速）、`patch-avd-config.*`（修 AVD 配置）。

### 2.3 连接真机手表

手表与电脑须在同一 WiFi。手表：设置 → 开发者选项 → ADB 调试 / 通过 WiFi 调试，记下 `IP:端口`，然后：

```powershell
adb connect <手表IP>:<端口>          # IP/端口都会变（曾用 192.168.31.118:44767、192.168.1.104:36417），以手表上当前显示为准
adb devices                          # 确认 device 状态
```

WiFi adb 不稳定，掉线（device offline）时 `adb disconnect` 后重连。

---

## 3. 常用命令

```powershell
# 构建 Debug APK（输出 app/build/outputs/apk/debug/app-debug.apk）
.\gradlew.bat :app:assembleDebug

# 单元测试
.\gradlew.bat :app:testDebugUnitTest

# 安装到手表 / 模拟器
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 启动 / 强杀
adb shell monkey -p top.southfish.engrave -c android.intent.category.LAUNCHER 1
adb shell am force-stop top.southfish.engrave

# 截图（PowerShell 重定向会损坏 PNG，必须先落盘再 pull）
adb shell screencap -p /sdcard/t.png; adb pull /sdcard/t.png ./t.png

# 查看提醒闹钟是否已排
adb shell dumpsys alarm | findstr engrave
```

---

## 4. 代码结构

分层架构：`data`（持久化）→ `domain`（纯业务）→ `ui`（Compose），依赖方向单向向内，`domain` 不 import 任何 Android/UI 类型。

```
app/src/main/java/top/southfish/engrave/
├── MainActivity.kt            # 唯一 Activity；区分冷启动/热启动
├── VocabApplication.kt        # Application：DI、词书播种、闹钟兜底重排
├── di/AppContainer.kt         # 手写 DI 容器
│
├── domain/                    # 纯 Kotlin 业务层（可单测）
│   ├── model/                 # Word / StudyItem / Grade / ReviewState ...
│   ├── repository/            # 仓库接口
│   ├── srs/                   # Sm2Scheduler 间隔重复算法
│   └── usecase/               # GetStudySession / SubmitReview / UndoReview ...
│
├── data/
│   ├── local/                 # Room：DAO、Entity、DayIndex（凌晨4点换日）
│   ├── repository/            # 仓库实现
│   ├── session/               # 学习会话快照（跨天作废）
│   ├── dictionary/            # 离线词典（搜索/详情页）
│   ├── backup/                # 备份/恢复
│   ├── importer/              # ImportBookActivity（系统「打开/分享」JSON 入口）
│   ├── mapper/  seed/         # 映射、内置词书种子
│
├── reminder/                  # 通知提醒（精确闹钟 + 四层重排）
│
└── ui/
    ├── navigation/            # 导航 + 冷热启动 + 栈记忆
    ├── home/                  # 首页（学习页 + 我的页 Pager）
    ├── study/                 # 学习状态机、卡片手势、拼写
    ├── settings/              # 学习设置（转轮）
    ├── components/            # NumberWheel、表圈滚动支持
    ├── search/                # 搜索 + 单词详情
    ├── mistakes/  books/  stats/  data/  onboarding/
    ├── profile/               # 我的页（含关于区）、头像选图链
    └── VocabViewModels.kt     # VM 工厂
```

```
数据流：Compose Screen → ViewModel(StateFlow) → UseCase → Repository
                                            ├─ Room（词/复习状态/日志）
                                            └─ DataStore（设置/导航栈）
```

配套工具（`tools/`）：

- `build_wordbooks.py`：从开源 ECDICT 生成内置词书（初中/高中）。
- `wordbook-converter.html`：通用词典转换器（纯前端单文件，可部署到任意网站），把 CSV/TSV/JSON/自定义分隔符词典转换为刻词词书 JSON。

---

## 5. 核心模块速览

| 模块       | 位置                                 | 说明                         |
| -------- | ---------------------------------- | -------------------------- |
| SRS 间隔复习 | `domain/srs/Sm2Scheduler.kt`       | SM-2 变体；新词与重记词单步 1 小时学习步 |
| 学习状态机    | `ui/study/`                        | 浏览选词 → 背新词（英→中→拼写）→ 复习     |
| 卡片手势     | `ui/study/CardGestures.kt`         | 卡内单击翻面/长按错题/滑动评分，卡片外双击撤销 |
| 数字转轮     | `ui/components/NumberWheel.kt`     | 视口中心选中 + 文本宽度触发区           |
| 提醒调度     | `reminder/`                        | 精确闹钟 + 四层重排兜底              |
| 导航/启动    | `ui/navigation/VocabNavHost.kt`    | 冷启动回主页、热启动恢复页面、导航栈持久化      |
| 选图链      | `ui/profile/AvatarPickerScreen.kt` | 厂商图库 ACTION_PICK → 应用内两列网格 |
| 词书导入     | `ui/books/`、`data/importer/`       | 应用目录免权限导入 + 系统分享入口         |
| 备份恢复     | `data/backup/BackupManager.kt`     | JSON 导出/导入（含设置项）           |

各模块的**实现细节与硬约束**（禁止事项、踩坑）见 [readme-aicode.md](readme-aicode.md) 第 6 节。

---

## 6. 词书 JSON 格式

内置词书与用户导入词书共用同一格式：

```json
{
  "name": "词书名称",
  "description": "词书描述（可选）",
  "words": [
    { "term": "apple", "meaning": "n. 苹果",
      "phonetic": "/ˈæp.əl/", "example": "...", "exampleTranslation": "..." }
  ]
}
```

- `term`、`meaning` 必填，其余可选；解析失败条目自动跳过。
- 导入途径：① 把 JSON 放进手表 `Android/data/top.southfish.engrave/files/books/`（免权限）；② 文件管理器「打开/分享」JSON 选择刻词。
- 在线转换：用 `tools/wordbook-converter.html`（浏览器打开即可，离线可用）。

---

## 7. 测试

- 单元测试：`.\gradlew.bat :app:testDebugUnitTest`（DayIndex / Sm2Scheduler / 学习队列 / 学习流程 / 拼写切分，共 72 个用例）。

---

## 8. 版本发布

1. 改版本：`app/build.gradle` → `defaultConfig` 的 `versionName` / `versionCode`。
2. 构建：`.\gradlew.bat :app:assembleDebug`（发布正式版用 `assembleRelease` + 签名配置）。
3. 真机验证：安装 → 冷启动/热启动行为 → 核心流程回归（见 §7 手工清单）。
4. 注意：applicationId 一经发布不可更改；改 versionCode 才能覆盖安装升级。

---

## 9. 常见问题（环境类）

| 现象                                             | 处理                                                                             |
| ---------------------------------------------- | ------------------------------------------------------------------------------ |
| Gradle 找不到 JDK/SDK                             | 设置 §2.2 四个环境变量                                                                 |
| `adb install` 只报 "Performing Streamed Install" | 常见于 WiFi adb 抖动/掉线；重连后重试，仍失败抓 logcat：`adb logcat -d \| findstr PackageManager` |
| 设备 offline                                     | `adb disconnect` → `adb connect` 重连                                            |
| 模拟器黑屏                                          | `start-emulator.bat swiftshader_indirect` 软件渲染                                 |
| 截图打不开                                          | 用 §3 的「先 screencap 落盘再 pull」方式                                                 |

更多功能层面的排坑见 [readme-aicode.md](readme-aicode.md) §10。
