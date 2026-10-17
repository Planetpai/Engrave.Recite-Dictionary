# 方案：抽出 `:core` 共享模块 + 新建 `:app-phone`（非 Wear OS 手表适配）

## Context

「刻词」目前是 Wear OS 专属应用（依赖 `androidx.wear.compose` + `com.google.android.wearable` shared library，后者导致 Play/系统过滤）。用户需要让**没有 Wear OS 库的普通安卓手表**也能用：核心（SM-2 调度、数据层、ViewModel、提醒）保持完全相同，只把 Wear 组件库换成 Material 3。

用户已锁定的决策：
1. **工程结构**：同仓库抽 `:core`（domain+data+reminder+ViewModel），新建 `:app-phone` 模块挂 M3 UI；手表版 `:app` 结构不变、行为不变。
2. **分发**：仅侧载 APK，不上 Play → `targetSdk 29` 不变（传统存储读词书文件最省事）。
3. **包名**：applicationId 独立为 `top.southfish.engrave.phone`；minSdk **26**（支持 Android 8.0，已验证代码与依赖无障碍）。
4. UI 不按大屏手机重排：保持手表形态（450px 级圆/方屏、卡片、大触点），只换组件基座。

硬约束（贯穿全程）：`docs/review-mechanism.md` 永不入库，只用显式 `git add <文件>`；改前备份、改后测试；73 条单测必须保持全绿；模拟器未经许可不启动。

## 探索结论（方案依据）

- Wear 耦合仅存在于 **22 个 UI/入口文件**（`ui/**` + `importer/ImportBookActivity.kt`），集中在 `ScalingLazyColumn`/`Chip`/`ListHeader`/`Scaffold`/`TimeText`/`PositionIndicator`/`MaterialTheme(colors)`/`SwipeDismissableNavHost`。
- `RotarySupport.kt`、`ScreenShape.kt`、`SwipeLeftRow.kt`、`CardGestures.kt` 等只用框架 API（`InputDevice.SOURCE_ROTARY_ENCODER`、`Configuration.isScreenRound`），**无需 Wear 库，原样复用**。
- 所有 `*ViewModel.kt` 无 Compose/Wear import（唯一例外 `ui/VocabViewModels.kt` 是 Compose 工厂，留 app 层）；`ui/study/StudyModels.kt`、`ui/components/Formatters.kt` 是纯 Kotlin。
- core 层资源引用仅 1 处：`reminder/ReminderReceiver.kt:35` 的 `R.drawable.ic_reminder`。
- 资产：`app/src/main/assets/dict/ecdict_core.db`（词典）、`assets/wordbooks/*.json`（2 本种子词书）。
- 导航栈记录/恢复逻辑（`VocabNavHost.kt` L110-145）不依赖 Wear 导航实现，可原样保留，仅换 NavHost 容器。
- 根 `build.gradle` 已声明 `com.android.library 8.2.0`，AGP/Kotlin/KSP 版本齐全。

## 架构

```
:core            com.android.library   namespace top.southfish.engrave（与现 app 同包，零 import 改写）
  java: domain/ data/ reminder/ di/ ui 下的 ViewModel+StudyModels+Formatters（包名不变）
  assets: dict/ecdict_core.db, wordbooks/*.json（库资产自动合并进两个 APK）
  res: drawable/ic_reminder.xml
  test: 现有 73 条单测整体迁入
:app             不动的 Wear 版：只删已迁走的源码/资产，build.gradle 加 implementation project(':core')
:app-phone       新建 com.android.application
  applicationId top.southfish.engrave.phone / versionName 0.1beta.1 / versionCode 1（可调）
  minSdk 26 / targetSdk 29 / compileSdk 34 / debug 签名 / R8 minify + -dontobfuscate
  UI = 现 ui/ 全量拷贝 + 组件替换（见映射表），无 com.google.android.wearable 库
```

包名策略：`:core`、`:app`、`:app-phone` 的 namespace 均为 `top.southfish.engrave`（R 类 FQCN 一致，全部现有 `top.southfish.engrave.R` 引用零改动；三者不同 APK，无类冲突）。若 AGP 报 namespace 冲突告警阻断，备选：core/phone namespace 加后缀并仅改 R 的 import（预计 1-2 处）。

## 实施步骤

### Phase 0 备份
- `git branch backup/pre-phone-port`（指向 d67171a）；确认工作区仅 2 个约定 untracked 文件不动。

### Phase 1 抽 `:core`
1. 新建 `core/build.gradle`（library；minSdk 26；依赖 room+KSP、datastore、coroutines、core-ktx、lifecycle-viewmodel-ktx；`testOptions.unitTests.returnDefaultValues = true`；`ksp { room.schemaLocation = "$projectDir/schemas" }`）、空 `core/src/main/AndroidManifest.xml`。
2. `git mv` 迁移（包名全不变）：
   - `app/src/main/java/.../domain/` `data/` `reminder/` `di/` → `core/src/main/java/`
   - ViewModel 共 14 个文件（home/study/search/detail/stats/mistakes/mistakesDrill/settings/books/filePicker/data/profile/onboarding）→ core 同包路径
   - `ui/study/StudyModels.kt`、`ui/components/Formatters.kt` → core 同包路径
   - `app/src/main/assets/` 全部 → `core/src/main/assets/`
   - `drawable/ic_reminder.xml` → `core/src/main/res/drawable/`
   - `app/src/test/` 全部 → `core/src/test/java/`
3. `app/build.gradle` 增加 `implementation project(':core')`；`settings.gradle` 增加 `include ':core'`。
4. **验证手表版未受影响**：`:core:testReleaseUnitTest`（73/73）、`:app:testReleaseUnitTest`、`:app:assembleRelease` 成功、APK 体积量级不变。

### Phase 2 建 `:app-phone` 骨架
1. `phone/build.gradle`（application；见上配置；依赖 `project(':core')` + compose-bom/ui/foundation/animation + **material3** + `androidx.navigation:navigation-compose` + activity-compose + lifecycle-runtime/viewmodel-compose + core-ktx + coroutines；lint 放宽同 :app）。
2. `AndroidManifest.xml`：**删除** `uses-library com.google.android.wearable` 与 standalone meta-data；保留 `uses-feature watch`、全部权限、`requestLegacyExternalStorage`、主题 `Theme.DeviceDefault`；注册 `MainActivity`、`ImportBookActivity`（同 intent-filter）、receiver `.reminder.ReminderReceiver`/`.reminder.BootReceiver`（core 类）。
3. res 拷贝：launcher mipmaps 全套、`ic_search.xml`、`strings.xml`（app_name=刻词）。
4. 新写：`PhoneApplication`（冷启动标志，逻辑同 `VocabApplication`）、`MainActivity`（同逻辑）、`ImportBookActivity`（M3 极简完成/失败页，调 `appContainer.backupManager.importBook(uri)`）。
5. `ui/VocabViewModels.kt`（rememberAppContainer 工厂）与 `di` 装配原样拷入。

### Phase 3 UI 拷贝与组件替换
- **原样拷贝（框架 API，零改动）**：`components/ScreenShape|RotarySupport|SwipeLeftRow|NumberWheel|ReminderWheels`、`study/CardGestures|StudyCardDeck|CardDeckParts|StudyScreen(部分)` 等未 import wear 的文件。
- **逐文件替换（22 个 wear-import 文件 + NavHost）**，映射表：

| Wear 组件 | M3 替代 |
| --- | --- |
| `wear.material.MaterialTheme` + `colors.*` | `material3.MaterialTheme` + `colorScheme.*`，包一层 `EngravePhoneTheme`（darkColorScheme：黑底/青色主调，贴现有观感） |
| `Text` | `material3.Text` |
| `Button/ButtonDefaults` | `material3.Button` + buttonColors |
| `Scaffold` | `material3.Scaffold`（去掉 TimeText/PositionIndicator 槽位） |
| `ScalingLazyColumn/items/rememberScalingLazyListState` | `LazyColumn/items/rememberLazyListState` |
| `Chip/ChipDefaults` | 自写 `WatchChip`（`Surface`+`Row`，大触点，放在 phone `ui/components/`，一处定义全局替换） |
| `ListHeader` | 普通 `Text` 标题样式 |
| `TimeText`、`PositionIndicator` | 删除 |
| `SwipeDismissableNavHost`/wear navigation | `navigation-compose` 的 `NavHost/composable/rememberNavController`；`swipeToDismissEnabled` 机制删除（学习页防误触改由系统返回手势天然隔离）；**导航栈记录/恢复代码原样保留** |

- 转换期间不改任何布局逻辑、尺寸常量、交互参数（CardGestures/undo 等原样）。

### Phase 4 构建验证
- `:phone:assembleDebug` 迭代修完编译错 → `:phone:assembleRelease` 成功。
- 回归三连：`:core:testReleaseUnitTest` 73/73、`:app:assembleRelease`、确认 `app-release.apk` 可正常安装（不实际重装真机，除非用户要求）。

### Phase 5 设备验证（可选，需用户配合）
- 手表版 Quick 冒烟：现有 Galaxy Watch4（`192.168.1.104:45541`）重装验证学习页/复习路径（applicationId 未变，覆盖安装保数据）。
- phone 版验证载体：Watch4 本身也含 wearable 库，phone APK（独立 applicationId）可与手表版**并存安装**冒烟验证启动/学习/设置；目标设备（无 Wear 库手表）由用户提供后做最终验证。模拟器启动需用户单独许可。

### Phase 6 提交
- 两个 commit（显式 add，禁 `-A/.`）：① `core 抽取：domain/data/reminder/ViewModel 迁入共享模块，:app 行为零变化` ② `新增 :app-phone：Material 3 非 Wear 手表适配（minSdk 26）`。

## 关键风险与对策

| 风险 | 对策 |
| --- | --- |
| :app 迁移后行为回归 | 包名不变 = 零 import 改写；Phase 1 后立即全量单测+assembleRelease 卡点 |
| namespace 同名告警 | 备选方案已备（改 R import 1-2 处） |
| StudyFlowTest 触 Android 壳 | core 同步 `returnDefaultValues=true`（现 app 同配置已绿） |
| M3 视觉偏差 | 先贴 wear 观感做 darkColorScheme，后续按用户反馈微调 |
| 缓存/生成文件误提交 | `.gitignore` 检查 phone/ 与 core/ 的 build、schemas 目录归属 |

## 验证清单（Definition of Done）

1. `:core:testReleaseUnitTest` 73/73 绿
2. `:app:assembleRelease` 成功，Wear 版功能与 d67171a 一致（冒烟：启动/学习/我的页）
3. `:phone:assembleRelease` 成功，APK 可安装启动、完成一次完整学习会话、词书导入与设置可用
4. 手机版 APK 不含 `com.google.android.wearable` 依赖（解包确认）
5. 两个 commit 干净，`docs/review-mechanism.md` 未入库
