# 墨阅 · Ink Reader

> 一款简洁、纯本地、支持六种格式的安卓电子书阅读器。
> A clean, fully-offline Android e-book reader supporting six formats.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20(API%2024)-brightgreen.svg)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20(M3)-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Version](https://img.shields.io/badge/version-1.11.8-orange.svg)](CHANGELOG.md)

[简体中文](#简体中文) ｜ [English](#english)

---

## 简体中文

### 这是什么

墨阅是一个**纯本地**的安卓电子书阅读器：不需要账号、不联网、没有广告，导入你自己的书就能读。它只做一件事——把书排好、读舒服。

- **不联网、不上传**：所有解析与阅读都在设备上完成，书籍与笔记只存在应用私有目录里。
- **中文优先**：章节识别对中文（`第X章 / 回 / 節 / 話 / 卷 / 集 / 部 / 篇 / 幕 / 折`）与中/日/英数字编号做了专门处理，`Chapter 10` 排在 `Chapter 2` 之后（按数字而非字符串）。
- **界面多语言**：应用界面随系统语言自动切换 **简体中文 / English / 日本語**，应用内「使用说明」也随之切换。

### 特性

**支持六种格式**

| 格式 | 支持程度 |
|---|---|
| `.txt` | 完整支持。自动识别 UTF-8 / UTF-8(BOM) / GBK / GB18030 / UTF-16 编码 |
| `.docx` | 完整支持。读取正文段落与 Heading 1–6 标题层级，标题自动变章节 |
| `.md` / `.markdown` | 完整支持，且**保留排版**：标题 / 粗体 / 斜体 / 删除线 / 行内代码 / 列表 / 引用 / 代码块 / 表格 / 链接 / 任务列表 |
| `.pdf` | 支持**文字版** PDF。按页面组织阅读顺序、按字号识别标题、自动剔除重复页眉页脚（不做 OCR） |
| `.html` / `.htm` | 支持。提取 `<title>` 与正文，`h1~h6` 转成章节层级 |
| `.epub` | 支持。解析容器与 OPF，按 spine（阅读顺序）逐章提取 XHTML 正文 |

**章节自动识别与排序**

- 解析器自带层级优先（docx 标题样式 / Markdown `#` / HTML·EPUB `h1~h6` / PDF 字号）；
- 中 / 日：`第X章 / 回 / 節 / 节 / 話 / 话 / 巻 / 卷 / 集 / 部 / 編 / 编 / 篇 / 幕 / 折`（阿拉伯、全角、汉字数字都认）；
- 英 / 日：`Chapter / Part / Book / Volume / Episode + 编号`（数字 / 全角 / 罗马数字 / 英文单词，如 `Chapter 1`、`Part Two`、`Book I`、`Volume III`）；
- 特殊章节：`序章 / 楔子 / 引子 / 前言 / 后记 / 尾声 / 番外 / 终章 / 结语 / Prologue / Epilogue / Interlude / Afterword / 幕間 / 番外編 / プロローグ / エピローグ`；
- 编号**统一解析成整数再排序**；前置内容排最前、后置内容排最后、位置不固定的（`幕間 / Interlude`）**钉在原位**；正文里的「第一章里提到的…」这类**引用不会被误判成标题**。

**阅读体验**

- 五套主题：月白 / 纸白 / 护眼 / 深墨 / 纯黑；
- 排版可调：字号、行距、段间距、字距、页边距、无衬线 / 衬线字体、首行缩进；
- 翻页方式：左右翻页 / 上下滚动；翻页动画：平移 / 覆盖 / 仿真 / 无动画；
- **真实页码**：后台用同一套排版参数把全书排一遍再数，`当前页/总页数` 与实际严格一致；
- **跨章连续阅读**：翻到章末继续往后滑直接进下一章，往前滑回到上一章**末尾**；
- 长按选中批注：四色高亮、整句 / 整段扩选、书签、笔记；
- **下拉给整页打标签**（左右翻页模式），标签记的是字符位置，重排后依然跟着那段文字；
- 全文搜索、全文 / 单章导出为 UTF-8(BOM)+CRLF 的 TXT（落下载目录并拉起分享面板）；
- **听书**：调用系统 TTS 引擎朗读，通知栏 / 锁屏可控，支持定时停止与耳机线控；
- 书架**分组**（一本书只属于一个分组）与书名 / 作者搜索；
- 自定义封面（选图 → 拖动 / 双指缩放裁切）。

> 阅读排版在 **v1.10.9** 起统一为一套全局设置：在任意一本书里改完，所有书通用。

### 快速开始

**前置条件**

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | platform 34 |
| Android Studio | Koala 2024.1 或更新（可选，用命令行亦可） |

**构建**

```bash
# 在工程根目录（本仓库根即 InkReader/）
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 工程目录名含中文时 AGP 默认会拒绝构建，本工程已在 `gradle.properties` 中显式关闭该检查（`android.overridePathCheck=true`），因此中文目录名也可正常构建。
>
> 依赖仓库已配置阿里云镜像优先、官方源兜底，国内同步较快。
>
> 版本号唯一来源见 [`app/build.gradle.kts`](app/build.gradle.kts)（`versionCode` / `versionName`）。

### 项目结构

```
app/src/main/java/com/inkreader/
├─ MainActivity.kt        界面栈与路由
├─ InkApp.kt              Application
├─ AppContainer.kt        依赖容器 + 书架状态
├─ data/                  存储层（SQLite + 正文文件 + 阅读偏好 + 导出）
├─ parse/                 统一解析管线（格式识别 → 解析器 → 章节装配）
├─ tts/                   朗读服务、句子切分、全局状态
└─ ui/                    主题 / 通用控件 / 书架 / 阅读器 / 详情 / 编辑器 / 标记 / 使用说明
```

- 存储：SQLite（手写 DAO，未用 ORM / 注解处理器）+ 正文以 UTF-8 文件存在应用私有目录，数据库只存索引；
- docx / HTML / EPUB 解析**未引入第三方库**（用系统 `XmlPullParser` + `ZipInputStream` 与手写状态机）；
- 仅 PDF（pdfbox-android）与 Markdown（commonmark-java）引入第三方库，均为纯 Java、无 so、无 AWT。

### 文档

| 文档 | 内容 |
|---|---|
| [docs/使用说明.md](docs/使用说明.md) | 完整用户手册：安装、导入、格式、操作、排版、朗读、章节管理、技术说明与已知限制 |
| [CHANGELOG.md](CHANGELOG.md) | 各版本落地清单 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 构建、代码约定、提交规范、PR 流程 |

### 贡献

欢迎 issue 与 PR，请先阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

### 许可证

[Apache-2.0](LICENSE)。第三方组件各自遵循其协议（见 [docs/使用说明.md](docs/使用说明.md) 技术说明一节）。

---

## English

### What it is

Ink Reader (墨阅) is a **fully offline** Android e-book reader — no account, no network, no ads. Import your own books and read. It does exactly one thing: lay books out well and make reading comfortable.

- **Offline & private**: all parsing and reading happen on-device; books and notes live only in the app's private storage.
- **Number-aware chapter ordering**: chapter numbers are parsed into integers before sorting, so `Chapter 10` comes after `Chapter 2` (numeric, not lexicographic).
- **Localized UI**: the interface follows your system language — **简体中文 / English / 日本語**, and so does the in-app user guide.

### Features

**Six formats**

| Format | Support |
|---|---|
| `.txt` | Full. Auto-detects UTF-8 / UTF-8(BOM) / GBK / GB18030 / UTF-16 |
| `.docx` | Full. Reads body paragraphs and Heading 1–6 levels; headings become chapters |
| `.md` / `.markdown` | Full, **formatting preserved**: headings, bold, italic, strikethrough, inline code, lists, quotes, code blocks, tables, links, task lists |
| `.pdf` | **Text-based** PDFs only: ordered by page, headings by font size, repeated headers/footers dropped (no OCR) |
| `.html` / `.htm` | Supported. Extracts `<title>` and body; `h1~h6` become chapter levels |
| `.epub` | Supported. Parses the container and OPF; reads XHTML chapters in spine order |

**Automatic chapter detection & ordering**

- Document-provided structure first (docx heading styles / Markdown `#` / HTML·EPUB `h1~h6` / PDF font size);
- CJK: `第X章 / 回 / 節 / 話 / 巻 / 卷 / 集 / 部 / 編 / 篇 / 幕 / 折` (Arabic, full-width and CJK numerals);
- English / Japanese: `Chapter / Part / Book / Volume / Episode + number` (`Chapter 1`, `Part Two`, `Book I`, `Volume III`);
- Special sections: `序章 / 楔子 / 前言 / 后记 / 尾声 / 番外 / 终章 / Prologue / Epilogue / Interlude / Afterword`;
- Numbers are **normalized to integers before sorting**; front matter goes first, back matter last, and position-ambiguous sections (`Interlude`) are **pinned in place** — in-text references like "…第一章里提到的…" are not mistaken for headings.

**Reading experience**

- Five themes, adjustable font size / line height / paragraph spacing / letter spacing / margins / serif toggle / first-line indent;
- Page-turn modes (horizontal paging / vertical scroll) and animations (slide / cover / page-curl / none);
- **Real page numbers** — computed by paginating the whole book with the same settings, so `current/total` matches actual paging;
- **Seamless cross-chapter reading** — continue past a chapter's last page into the next; go back into the previous chapter's **end**;
- Long-press annotations: four-color highlights, sentence/paragraph selection, bookmarks, notes;
- **Pull down to tag a whole page** (in paging mode); tags store character offsets, so they survive re-pagination;
- Full-text search; export a whole book or a single chapter as UTF-8(BOM) + CRLF TXT (saved to Downloads with a share sheet);
- **Read-aloud** via the system TTS engine, controllable from the notification/lock screen, with timer and headset buttons;
- Shelf **groups** (a book belongs to at most one group) and title/author search;
- Custom covers (pick an image → drag / pinch to crop).

> Since **v1.10.9**, reading layout is a single global setting shared by every book.

### Quick start

**Prerequisites**

| Item | Version |
|---|---|
| JDK | 17 |
| Android SDK | platform 34 |
| Android Studio | Koala 2024.1 or newer (optional) |

**Build**

```bash
# From the project root (this repo root is InkReader/)
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> A Chinese project path would normally be rejected by AGP; this project disables that check in `gradle.properties` (`android.overridePathCheck=true`), so a Chinese directory name builds fine.
>
> Version number has a single source of truth in [`app/build.gradle.kts`](app/build.gradle.kts).

### Project structure

```
app/src/main/java/com/inkreader/
├─ MainActivity.kt        screen stack & routing
├─ InkApp.kt             Application
├─ AppContainer.kt       dependency container + shelf state
├─ data/                 storage (SQLite + body files + prefs + export)
├─ parse/                unified parsing pipeline (detect → parse → assemble)
├─ tts/                  read-aloud service, sentence splitting, state
└─ ui/                   theme / components / shelf / reader / detail / editor / marks / guide
```

- Storage: SQLite (hand-written DAO, no ORM / annotation processor) + bodies as UTF-8 files in app-private storage; the DB holds indexes only.
- docx / HTML / EPUB parsing uses **no third-party library** (system `XmlPullParser` + `ZipInputStream` and a hand-written state machine).
- Only PDF (pdfbox-android) and Markdown (commonmark-java) pull in libraries — both pure Java, no `.so`, no AWT.

### Documentation

| Document | Contents |
|---|---|
| [docs/使用说明.md](docs/使用说明.md) | Full user manual (Chinese): install, import, formats, controls, layout, read-aloud, chapter management, technical notes, known limitations |
| [CHANGELOG.md](CHANGELOG.md) | Per-version change list |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Build, conventions, commit style, PR flow |

### Contributing

Issues and PRs are welcome — please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

### License

[Apache-2.0](LICENSE). Third-party components keep their own licenses (see the technical notes in [docs/使用说明.md](docs/使用说明.md)).
