---
name: engrave-release-flow
description: 刻词 Engrave 项目（Wear OS + app-phone 双端）改码后的构建/装机/发版/提交流程。用户说打 release 包、装到手表、发版、改版本号、写更新说明、提交推送时使用。不用于功能需求本身的设计与编码。
---

# 刻词双端构建与发布流程（Windows / PowerShell）

项目根：`e:\WearOS-VocabProject`；模块：`:app`（Wear，包名 top.southfish.engrave）、
`:app-phone`（非 Wear 手表，包名 top.southfish.engrave.phone）、`:core`（共享）。
仓库：`https://github.com/Planetpai/Engrave.Recite-Dictionary`（2026-10-15 起 remote 已指向新地址，
发 Release / 查资产都用它；「检查更新」功能也固定查这个仓库的 releases）。

## 0. 总原则

- 用户分工：AI 改码 + 构建 + 安装；用户手动真机测试。**启动模拟器必须先获得用户明确许可**（内存有限会崩机）。
- 提交/推送只在用户明确要求时做；commit 信息与范围拿不准先问。
- 改功能遵循：改前可备份、改后充分测试、不破坏既有功能。

## 1. 构建（必须带 dangerouslyDisableSandbox: true）

Kotlin daemon 要往 `C:\Users\32474\AppData\Local\kotlin\daemon` 写临时文件，沙箱会挡住。

```powershell
# 环境（沙箱禁用时必须显式带上，否则用错 JDK/Gradle 缓存）
$env:JAVA_HOME='E:\AndroidDev\jdk'; $env:GRADLE_USER_HOME='E:\AndroidDev\gradle-home'

# 日常验证：core 单测 + 双端 debug
.\gradlew.bat :core:testDebugUnitTest :app:assembleDebug :app-phone:assembleDebug --console=plain -q

# 发版（⚠️ 2026-10-17 起 :app 有 trans 维度，没有 assembleRelease 这个任务了）：
.\gradlew.bat :app:assembleNoTransRelease :app:assembleWithTransRelease :app-phone:assembleRelease --console=plain -q
```

**绝不并行跑两个 Gradle**（会 JVM crash）；两条命令要串行。
`assembleNoTransRelease` / `assembleWithTransRelease` 均由 `applicationVariants.all` 统一命名产物。

PowerShell 5 约束：**没有** `&&`/`||`，用 `;` 串联；git 多行 commit 用 here-string
`$msg = @' ... '@; $msg | git commit -F -`，不要用 `git commit -m` 塞换行。

### 1.1 内存坑（构建报 DaemonCrashedException / error 1455 时）

症状：`Connection to the Kotlin daemon has been unexpectedly lost`、
`os::commit_memory ... error=1455`（Windows 提交内存不足）。处理：

1. `.\gradlew.bat --stop`（经常显示无 daemon，僵尸进程仍在）
2. `Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Select ProcessId, CommandLine`
   找出**可杀**的僵尸：命令行含 `E:\AndroidDev\jdk` 的 Gradle/Kotlin daemon；
   **不要杀** redhat.java 扩展的 java（路径 `.trae-cn\extensions\redhat.java*`，是 IDE 的 JDT）。
3. `Stop-Process -Id <PID> -Force` 后立即重试构建（daemon 会重新拉起）。

## 2. 设备与安装

```powershell
adb devices                 # 查设备；WiFi 端口每次会变，不知道就问用户要新地址
adb connect 192.168.1.xxx:<port>
adb -s <serial> install -r <apk路径>
```

- `735ac045` 是 vivo X21A 手机、`adb-RFAR90NDFJP*` 是 TLS 设备别名，**都不是手表**，别误装。
- Watch4 近期 WiFi 地址：2026-10-11 为 `192.168.1.100:39019`（端口经常变，连不上先问用户）。
- release/debug 同 debug keystore 签名，可直接 `-r` 覆盖安装保数据。

## 3. release 产物与版本

- `:app`（Wear）自 2026-10-17 起按 flavor × ABI 出 **4 个包**（**两个 flavor 都带 abi 段**：
  AGP 的 ABI 拆分是全局开关、无法只对 noTrans 关掉，若 noTrans 不带 abi 段，v7a/v8a 会写同一
  文件名而打包失败——见 `app/build.gradle` 注释）：
  - `app\build\outputs\apk\noTrans\release\Engrave-<版本号>-wearos-notrans-v7a.apk`（**上架用**，无翻译依赖，实测 12.01MB）
  - `app\build\outputs\apk\noTrans\release\...-notrans-v8a.apk`（实测 12.01MB；**与 v7a 内容完全相同**
    ——无原生库，ABI 无意义。发版**只上传其中一个即可**，改不改名都行：
    「检查更新」按 `wearos-notrans` 标签匹配，允许 `-v7a`/`-v8a` 尾巴）
  - `app\build\outputs\apk\withTrans\release\Engrave-<版本号>-wearos-withtrans-v7a.apk`（**侧载**，含离线翻译，实测 40.12MB）
  - `app\build\outputs\apk\withTrans\release\...-withtrans-v8a.apk`（实测 52.43MB）
- `:app-phone`：`app-phone\build\outputs\apk\release\Engrave-<版本号>-android.apk`（R8 优化、-dontobfuscate、debug 签名）。
- 发版前改 `app/build.gradle` 的 versionCode/versionName；双端同步发版时
  `app-phone/build.gradle` 也要改（两端 code 各自递增，版本号保持一致）。
- 改完版本号**必须重新构建**；用 aapt2 校验包内版本：

```powershell
$aapt = (Get-ChildItem "E:\AndroidDev\sdk\build-tools" -Recurse -Filter aapt2.exe | Select -First 1).FullName
& $aapt dump badging <apk> | Select-String "package:"
```

- 上传 asset 直接用构建产物名（gradle 自动按此命名，勿再手工复制改名；纯英文名的历史教训仍有效：
  中文前缀曾被平台吞掉、前导横线变乱码）。
- **不要**把 APK 复制到 `手表安装包` 目录（曾因文件名/授权问题被安全策略拒绝）。

### 3.1 翻译模型资产（Release 附带，2026-10-17 起）

离线翻译模型**不随包**，作为 Release 资产上传，App 内「翻译引擎」列表里点下载。
资产名固定（App 里硬编码匹配，改名即失效）：

| 资产名 | 内容 | 实测体积 |
| --- | --- | --- |
| `Engrave-model-onnx-en-zh-quick.zip` | 英→中 量化档 | 内容 ≈114MiB（zip 实测 76.98MB） |
| `Engrave-model-onnx-en-zh-full.zip` | 英→中 全精度档 | ≈431MiB |
| `Engrave-model-onnx-zh-en-quick.zip` | 中→英 量化档 | 内容 ≈114MiB（zip 实测 77.17MB） |
| `Engrave-model-onnx-zh-en-full.zip` | 中→英 全精度档 | ≈431MiB |

**v0.4beta.1 实际只上传两个量化档**（`-en-zh-quick` / `-zh-en-quick`，用户 2026-10-17 定，
因 fp32 431MiB/方向过大）；上表 full 两行是预留，待实测质量差异后再补传，**补传无需改 App 代码**。

**2026-10-17 两个量化 zip 已打包验证**（放在 `E:\` 根目录，也留了一份在工程 `_tmp\model-assets\`）：
逐文件 `onnx/encoder_model_quantized.onnx` 50.45MB + `onnx/decoder_model_quantized.onnx` 57.07MB
+ `tokenizer.json` 6.09MB + `generation_config.json`（<1KB），打包时两份 `.onnx` 已改名为
`encoder_model.onnx` / `decoder_model.onnx`。**已核对与 App 引擎匹配**：编码器输入仅 `input_ids`
（**无 `attention_mask`**，引擎按 `inputNames` 自适应，故不受影响）、输出 `last_hidden_state`；
解码器输入 `input_ids` / `encoder_attention_mask` / `encoder_hidden_states`、输出 `logits`；
`generation_config.json` = `decoder_start_token_id 65000` / `eos_token_id 0` / `max_length 512`
（引擎会把 512 收窄到 256）。**用了非 merged 的 `*_quantized.onnx`**，不要换成 `*_merged*`。

zip 内**必须恰好含 4 个文件、放在 zip 根目录（不要套文件夹）**：

```
encoder_model.onnx      # Xenova/opus-mt-<src>-<tgt> 的 onnx/encoder_model.onnx（或 *_quantized 改名）
decoder_model.onnx      # 同上 onnx/decoder_model.onnx（⚠️ 不要 merged 版）
tokenizer.json          # 同上 tokenizer.json
generation_config.json  # 同上 generation_config.json
```

量化档取 `encoder_model_quantized.onnx` / `decoder_model_quantized.onnx`，打包时**改名为**
`encoder_model.onnx` / `decoder_model.onnx`（App 不区分档位，只看这 4 个文件名）。
模型源：`hf-mirror.com/Xenova/opus-mt-en-zh`（及 `...-zh-en`）——HuggingFace 主站本机不可达，
hf-mirror 镜像可达。国内用户拉不到 GitHub 时，也可用 App 内「手动导入」直接从设备选 zip。

## 4. 发布说明与版本记录（三处）

1. `README.md`：平台版本行（`版本 0.3beta.1`）+ 更新日志新增对应小节（历史版本在下方保留）。
2. `readme-aicode.md`：涉及的模块硬约束/新坑同步进 §6 对应小节。
3. 有 `docs/handoff-*.md` 交接文档时，在其顶部补「落地状态」：实际方案与推荐方案的差异要写明。
GitHub Release 页面给用户一段可直接粘贴的纯文本说明；tag 建议指向发版时的 master 最新提交，
移动已有 tag（如 `Beta`）需用户确认（要强推）。

## 5. 提交与推送

```powershell
git fetch origin master
git rev-list --left-right --count origin/master...HEAD   # 远端领先时先评估 rebase
git status --short                                       # 逐文件核对暂存边界
git add <明确文件列表>                                    # 不要 git add -A
$msg | git commit -F -
git push origin master
```

**禁止入库**（工作区常驻的未跟踪项，勿动）：`docs/review-mechanism.md`、`.trae/`
（本 skill 所在目录，已 gitignore）、`PROJECT_STATE.md`、`_20svideo/`、
`tools/_*.py` 等下划线前缀临时脚本。

git mv 之后又编辑过的文件必须重新 `git add`，否则提交的是迁移前旧内容（踩过，见 commit e51cceb/e378a43）。
push 输出里的 `remote: This repository moved...Planetpai/...` 只是仓库迁移重定向提示，成功标志是
`<old>..<new>  master -> master`，exit=0 即成功。

## 6. 功能性自检清单（改设置/学习/TTS 相关时）

- `:core:testDebugUnitTest` 必须过（注意 StudySessionStore 依赖 org.json，JVM 单测是空心实现，
  相关流程只能靠真机端到端验证）。
- release 偶发 `Unresolved reference` 同包内部函数时，跑 `:app:clean :app:assembleRelease`。
- 装到真机后交给用户手测，AI 不代替操作；测试点随功能列出（读写持久化、老配置兼容、双端一致性）。
