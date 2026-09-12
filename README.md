# 刻词（Engrave）· Wear OS 手表背单词

> ## ⚠️ 本软件由在人工辅助下的 AI 生成
> 
> ## 🤖 由 trae、zcode、qoder 三家 AI 平台共同生成
> 
> 因为高三没有额外时间学习语法；换平台是因为~~免费计划用完了~~
> 
> 人工负责需求、验收与真机测试；代码、文档与词书数据管线由 AI 平台协作产出。

刻词是一款**独立运行于 Wear OS 手表**的背单词应用（无手机伴侣端）：间隔复习、到点提醒。抬腕即学，碎片时间刷一刷。

欢迎加入软件交流群：QQ：832695614

#### [刻词网站](kc.southfish.top)（目前还没有想好作什么用）

# ⚠️目前只在三星watch 4上测试过，兼容性未知

## **真的很希望大家能来一起改善软件！**

软件基于wear os4，sdk29开发

---

## 核心功能

| 功能           | 说明                                                                                                           |
| ------------ | ------------------------------------------------------------------------------------------------------------ |
| 🗓️ 每日学习     | 按计划推送新词：浏览挑选 → 看英文想中文 → 看中文想英文 → 动手拼写，三遍加深记忆                                                                 |
| 🔁 间隔复习（SRS） | 类 SM-2 调度：会的词少出现、不会的词多出现，复习计划自动排期                                                                            |
| ⏰ 每日提醒       | 精确闹钟定时震动提醒，后台被清理也能按时重排                                                                                       |
| ❌ 错题集        | 不熟的词随手收进错题集，支持集中重刷与多选删除                                                                                      |
| 🔍 离线词典搜索    | 内置 ECDICT 词典（59137 条），单词/中文都能搜，词条含音标、释义、例句、词形变换                                                              |
| 📚 词书管理      | 内置初中/高中词书；支持从设备任意文件夹导入自制备 JSON 词书，可导出备份（）[词典工具](https://github.com/SouthFishowo/Engrave_DictionaryConverter) |
| 👤 个人资料      | 头像（长按从相册选）、昵称（长按编辑）、连续天数与学习统计                                                                                |

平台与版本：Wear OS · Kotlin + Jetpack Compose · 包名 `top.southfish.engrave` · 版本 `0.1BETA`

---

## 文档导航

| 文档                                         | 读者       | 内容                    |
| ------------------------------------------ | -------- | --------------------- |
| [readme-user.md](readme-user.md)           | 最终用户     | 安装、上手、各功能用法与常见问题      |
| [readme-code.md](readme-code.md)           | 人类开发者    | 环境搭建、构建运行、代码结构、测试与发布  |
| [readme-aicode.md](readme-aicode.md)       | AI 编码助手  | 工程约定、模块级硬约束、历史踩坑与验证流程 |
| [tools/readme.md](tools/readme.md)         | 想自制词书的用户 | 词典转换工具（网页版）使用说明       |
| [tools/readmecode.md](tools/readmecode.md) | 工具开发者    | 转换器实现原理、数据格式规范、扩展指南   |

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
- 内置词书顺序已随机打乱
