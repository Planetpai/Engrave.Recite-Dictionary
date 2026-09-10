# 刻词词典转换器 · 使用说明

> 把网上下载/自己整理的词典数据，转成「刻词」App 能直接导入的词书 JSON。
> 两个工具都在 `tools/` 文件夹：
> - **wordbook-converter.html** —— 网页版转换器（推荐，双击就能用）
> - **build_wordbooks.py** —— 命令行脚本（批量生成本项目内置词书）

---

## 一、网页版转换器（wordbook-converter.html）

### 功能

- 支持输入 **CSV / TSV / JSON / 自定义分隔符**（等号、冒号、竖线、分号、破折号）文本，自动检测格式
- 自动猜测列映射（单词 / 释义 / 音标 / 例句 / 例句翻译），猜错可手动指定
- 可选处理：按单词去重、音标自动补斜杠、清理 HTML 标签、跳过无释义行
- 实时预览转换结果（前 80 条），显示成功/失败条数
- 一键**下载 JSON** 或**复制到剪贴板**

### 使用方法

1. 双击打开 `wordbook-converter.html`（任意现代浏览器，**完全本地运行，数据不出浏览器**）
2. 把词典文件**拖进左侧输入区**（或点击选择文件，或直接粘贴文本）
3. 检查「列映射」：自动猜测结果不对时，手动把各列对应到 单词/释义/音标/例句/例句翻译
4. 填写词书名称（必填）与描述（可选）
5. 点「下载 JSON」得到词书文件

### 导入到手表

把生成的 JSON 放到手表上任意位置（如 `Download/`，可用 `adb push 词书.json /sdcard/Download/`），然后：

> 刻词 → 我的 → 词书管理 → **从设备导入词书** → 浏览到该文件点击导入

### 注意事项

- **term（单词）与 meaning（释义）是必填列**，缺这两个的行会被跳过
- 非 UTF-8 编码的文件可能乱码，建议先转成 UTF-8
- 单个词书建议不超过几千条：词书越大，手表导入与学习越慢
- 去重逻辑是「同单词保留第一条」，如需保留特定版本请先自行清洗
- 转换结果导入 App 后不可撤回部分条目，只能整本删除（有二次确认）

---

## 二、命令行脚本（build_wordbooks.py）

### 功能

从 `wordbook-source/` 里的 ECDICT 体系源数据（kajweb/dict 例句版 JSON）生成项目**内置词书**，直接写入 `app/src/main/assets/wordbooks/`。自动完成：词性归一化（n./v./adj.…）、例句挑选（第一对英中都非空）、音标优先美式、去重、**固定 seed 随机打乱**（保证词序可复现）。

### 使用方法

```bash
# 依赖：Python 3.8+，无第三方依赖
python tools/build_wordbooks.py
```

输出两个文件（覆盖旧版）：

- `app/src/main/assets/wordbooks/chuzhong-ecdict.json`（初中词汇）
- `app/src/main/assets/wordbooks/gaozhong-ecdict.json`（高中词汇）

### 注意事项

- 修改脚本里的 seed 会改变词序；**改动内置词书后必须重新编译安装 App**（assets 随包打包）
- 内置词书条数目前为：初中 1987 词、高中 3743 词；应用启动时会按 assets 实际文件自动清理数据库中失效的内置词书记录
- 源数据缺失释义的条目会被丢弃，属于预期行为

---

## 数据来源与许可

- 词典数据：[ECDICT](https://github.com/skywind3000/ECDICT)（MIT License）
- 例句：公开词典整理（kajweb/dict，基于 ECDICT 体系）
- 转换器本身：本项目 AI 生成，可自由修改使用
