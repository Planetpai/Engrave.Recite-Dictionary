# 刻词（Engrave）· Wear OS 手表背单词

> ## ⚠️ 本软件由在人工辅助下的 AI 生成
> ## 🤖 由 trae、zcode、qoder 三家 AI 平台共同生成
>
> 人工负责需求、验收与真机测试；代码、文档与词书数据管线由 AI 平台协作产出。

刻词是一款**独立运行于 Wear OS 手表**的背单词应用（无手机伴侣端）：不连手机也能导入词书、间隔复习、到点提醒。抬腕即学，碎片时间刷一刷。
加入qq应用交流群：832695614

---

## 核心功能

| 功能 | 说明 |
| --- | --- |
| 🗓️ 每日学习 | 按计划推送新词：浏览挑选 → 看英文想中文 → 看中文想英文 → 动手拼写，三遍加深记忆 |
| 🔁 间隔复习（SRS） | 类 SM-2 调度：会的词少出现、不会的词多出现，复习计划自动排期 |
| ⏰ 每日提醒 | 精确闹钟定时震动提醒，后台被清理也能按时重排 |
| ❌ 错题集 | 不熟的词随手收进错题集，支持集中重刷与多选删除 |
| 🔍 离线词典搜索 | 内置 ECDICT 词典（59137 条），单词/中文都能搜，词条含音标、释义、例句、词形变换 |
| 📚 词书管理 | 内置初中/高中词书；支持从设备任意文件夹导入自制备 JSON 词书，可导出备份 |
| 👤 个人资料 | 头像（长按从相册选）、昵称（长按编辑）、连续天数与学习统计 |

平台与版本：Wear OS · Kotlin + Jetpack Compose · 包名 `top.southfish.engrave` · 版本 `0.1BETA`

---

## 文档导航

| 文档 | 读者 | 内容 |
| --- | --- | --- |
| [readme-user.md](readme-user.md) | 最终用户 | 安装、上手、各功能用法与常见问题 |
| [readme-code.md](readme-code.md) | 人类开发者 | 环境搭建、构建运行、代码结构、测试与发布 |
| [readme-aicode.md](readme-aicode.md) | AI 编码助手 | 工程约定、模块级硬约束、历史踩坑与验证流程 |
| [tools/readme.md](tools/readme.md) | 想自制词书的用户 | 词典转换工具（网页版）使用说明 |
| [tools/readmecode.md](tools/readmecode.md) | 工具开发者 | 转换器实现原理、数据格式规范、扩展指南 |

---

## 文件结构

```
WearOS-VocabProject/
├── app/                        # Android 应用主模块
│   └── src/main/
│       ├── java/top/southfish/engrave/
│       │   ├── MainActivity.kt         # 入口（冷/热启动策略）
│       │   ├── data/                   # 数据层：Room、DataStore、词典仓库、备份
│       │   ├── domain/                 # 领域模型与调度器（SRS）
│       │   ├── ui/                     # Compose 界面（home/study/search/books/profile/…）
│       │   ├── importer/               # 外部导入入口 Activity
│       │   └── reminder/               # 每日提醒（精确闹钟 + 重排）
│       ├── assets/wordbooks/           # 内置词书 JSON（初中/高中，ECDICT 数据）
│       └── res/                        # 资源（图标、主题、文案）
├── docs/                       # 专题决策文档（如通知稳定性）
├── tools/                      # 词典转换工具（网页版）+ 内置词书生成脚本
│   ├── wordbook-converter.html #   纯前端转换器：CSV/TSV/JSON → 刻词词书 JSON
│   ├── build_wordbooks.py      #   从 ECDICT 源数据生成内置词书
│   ├── readme.md               #   工具使用说明
│   └── readmecode.md           #   工具开发者说明
├── wordbook-source/            # 词书源数据（kajweb/dict 例句版，ECDICT 体系）
├── 手表安装包/                  # 可直接安装的 APK 交付物
├── _备份/                       # 修改前的源码快照 / APK / 词典库备份
├── build.gradle 等             # Gradle 构建脚本
└── readme.md / readme-user.md / readme-code.md / readme-aicode.md
```

---

## 快速开始（开发者）

```bash
git clone <本仓库>
# Android Studio 打开工程，连接 Wear OS 手表/模拟器后直接 Run
# 或命令行：
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

更多环境细节与发布流程见 [readme-code.md](readme-code.md)。

---

## 数据与许可

- 本项目代码基于 [MIT License](LICENSE) 开源，欢迎自由使用、修改与分发，保留版权声明即可
- 词典数据：[ECDICT](https://github.com/skywind3000/ECDICT)（MIT License）及公开例句整理
- 应用代码：本项目所有代码由 AI 生成，人工辅助验收
- 内置词书顺序已随机打乱（固定 seed，可复现）
