# 墨阅 · Ink Reader

支持离线阅读的安卓电子书阅读器，可选连接局域网 Windows 电脑朗读。
An Android e-book reader with offline reading and optional paired LAN computer voices.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-brightgreen.svg)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Version](https://img.shields.io/badge/version-1.14.1-orange.svg)](CHANGELOG.md)

[简体中文](#简体中文) ｜ [English](#english)

## 简体中文

墨阅不需要账号，没有广告。书籍解析、阅读、批注与进度保存在本机；电脑语音是需要单独配对启用的可选功能。界面及应用内使用说明支持简体中文、English、日本語；部分电脑语音操作仍使用中文标签。

### 主要功能

- **六种格式**：TXT、DOCX、Markdown、文字版 PDF、HTML、EPUB。支持常见编码、文档标题结构和部分 Markdown 排版；不做 OCR，不完整复刻原书图片和页面版式。
- **编码与预览纠错**：TXT 支持 UTF 系列、GB18030、Big5、Shift-JIS 和 EUC-JP 自动比较；单个 TXT 可切换编码、核对文字、查看识别疑点；全书校对可分页搜索标题、跳转原文行并增删分章点，原文字保留。
- **章节识别**：扩展多语言章标签、Unicode 十进数字、保守的纯数字标题及中途格式切换；合章按完整编号范围查漏，保留原始标题，不生成不存在的正文。
- **单书纠错记忆**：单 TXT 的手动调整可记忆（最多一万处）；重分章可沿用、关闭或清除，标题和附近正文改变或有歧义时跳过，不跨书套用。
- **TXT 磁盘处理**：预览、独立成书、多文件合并、追加及大书重分章共用磁盘索引；支持自定义规则，完整源字节核验后复用快照。其他格式沿用对应解析器。
- **暂停与恢复导入**：逐文件事务和完成凭据同步提交；重启后跳过已完成文件。源文件失效会报告错误，已完成内容保留。
- **完整备份与分章撤销**：备份正文、样式、批注、进度、封面、分组、识别记忆和阅读设置；恢复添加副本。合并、拆章及重分章保留最近一次完整快照，跨重启可撤销。
- **搜索与页数缓存**：按块搜索并核验实际子串，磁盘索引排除确定不匹配的章节；全书页数按正文和排版签名缓存。
- **按实际选择顺序导入**：文件旁实时编号，底部可调整。无编号散篇可组合「合并为一本书」＋「每个文件作为一章」＋「按选择顺序」。智能排序仍可选。
- **文件夹首页**：添加多个授权目录，跨目录保留选择；长按首页卡片多选移出入口，源文件不删除。
- **长按文件批量选择**：从当前文件正向／反向、全列表正向／反向，或指定两个文件选择含两端的范围；按当前搜索和显示顺序处理，跳过子文件夹，已有队列不重排。
- **阅读与管理**：连续跨章、左右翻页／上下滚动、四种翻页动画、五种主题、全局排版、音量键翻页、批注、整页标签、分组、封面、搜索、编辑、合并与拆章。
- **听书**：手机系统 TTS，或同一可信局域网内的电脑语音；可选自动、晓晓、七海，支持连接测试与试听。悬浮播放条避让阅读工具栏，不推高正文。
- **TXT 导出**：整本／单章导出 UTF-8 BOM、CRLF 文本，下载或分享；不包含私人批注，不等于完整书库备份。

### 上手与边界

书架「导入」→ 添加小说文件夹 → 按顺序点选或长按批量选择 → 核对预计次序 → 继续并选择导入／分章方式。选文件页的「*」仅是文件夹首页，与书架首页不同。

单文件超过 200 MiB、TXT 索引超过 50 万行会拒绝导入，压缩文档另有解压预算；更小的复杂文档也可能受内存或损坏影响。扫描 PDF 不支持；旧 DOC、MOBI、AZW3 不在当前格式列表中。大型书籍识别和后台全书页数统计需要时间，大书重分章按磁盘范围处理，仍受源文件、行数、临时存储和批注端点预算约束；超大单章阅读及非 TXT 解析仍可能受内存限制。

电脑语音需要 Windows 本地音色、电脑保持唤醒，并与手机互通。自动模式中文／英文用晓晓，日语优先七海；电脑需安装对应音色。只发送当前与下一句至配对电脑，LAN 使用 HTTP、没有 TLS 加密，请在可信网络使用。[电脑端说明](tools/natural-voice/README.md)

Android 系统备份／换机迁移可能包含书籍、笔记、进度和封面，取决于系统设置；配对凭据排除在备份之外。设置页可手动创建 `.inkbackup` 完整备份；恢复校验通过后添加书籍副本并恢复阅读设置，不覆盖原书。电脑配对、目录授权和未完成导入任务不包含在此备份中。卸载或清除应用数据前请把备份另存到应用之外。

### 构建与验证

需要 JDK 17、Android SDK platform 34，Android Studio 可选。项目使用 Gradle Wrapper；SDK 路径写入本机 `local.properties`，不要提交。

```bash
./gradlew :app:unitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# Windows: .\gradlew.bat 后接相同任务
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

`DEVICE_SERIAL` 用 `adb devices` 查询并替换。相同签名的覆盖安装保留数据；不要为了更新先卸载。中文工程路径已配置 `android.overridePathCheck=true`。发布任务必须配置正式签名，不会静默回退 debug 签名。

1.14.1 复审修复失败重试顺序、跨章批注截断、空章节合并越界、原子备份恢复和已有空书被误删等问题，并补充任务锁与重排参数保护。200 项 JVM、最终包两轮 228 项安卓检查通过；实际界面复验前项失败后继续的选择顺序。原 396 本书、1089 章、1101 个正文／样式文件和三组设置与测试前一致。详见 [1.14.1复审验证](docs/1.14.1-复审修复与验证.md)；全书校对、备份、恢复及性能工作的初始测量见 [1.14.0记录](docs/1.14.0-全书校正与可靠性升级.md)。GitHub 自动检查覆盖冻结样本、构建、回归及静态检查，远端状态以对应提交的 Actions 为准。


上一版 1.13.0 通过 185 项 JVM 回归、最终包两轮全套模拟器检查及第三轮界面复验，共225项有效安卓检查。新增64份冻结标注，开发40／留出24，132个预期标题；旧24份／81标题和154份语料继续核对，原15项识别限制在这些固定样本上归零，3份空内容仍拒绝。另核对约12 MB／400章实际导入、1050万字符和千文件压力；原书库与正文校验值保持一致。具体指标、首次留出结果、早期失败与限制见 [1.13.0验收记录](docs/1.13.0-多语言分章与磁盘导入验收.md)。

### 文档与结构

| 文档 | 内容 |
| --- | --- |
| [使用说明](docs/使用说明.md) | 当前导入、顺序、批量选择、章节、朗读、数据与限制 |
| [更新日志](CHANGELOG.md) | 逐版本变更 |
| [贡献指南](CONTRIBUTING.md) | 构建、签名、模拟器检查与提交约定 |
| [审计与压力结果](docs/1.11.23-全面审计与模拟器压力测试.md) | 复现问题、修复、测量及未覆盖范围 |
| [测试工具](tools/testing/README.md) | 样本、明确的模拟器目标与运行前置条件 |
| [电脑语音](tools/natural-voice/README.md) | 配对、本地音色、日语模型与安全边界 |

主要代码在 `app/src/main/java/com/inkreader/`：`data/` 负责 SQLite、正文文件、偏好、导出和文件选择草稿，`parse/` 统一解析，`tts/` 负责语音，`ui/` 为 Compose 界面。应用内三语说明位于 `app/src/main/assets/help/`。PDF 使用 pdfbox-android，Markdown 使用 commonmark-java；DOCX、HTML、EPUB 的主要读取逻辑使用系统 XML／ZIP 与项目代码。

## English

Ink Reader reads local books without accounts or ads. Reading, annotations and progress stay on-device. A paired Windows PC on a trusted LAN can optionally synthesize speech. The interface and built-in guide support Chinese, English and Japanese; some computer-voice controls currently retain Chinese labels.

### Features and workflow

- TXT, DOCX, Markdown, text-based PDF, HTML and EPUB; common encodings and structured headings, supported Markdown formatting, no OCR or full original-page reproduction.
- TXT encoding ranking for UTF, GB18030, Big5, Shift-JIS and EUC-JP; a single TXT can override the codec, inspect an excerpt and uncheck displayed boundaries without losing the source text.
- Expanded native chapter labels, Unicode decimal digits, conservative bare numerals and mid-book style changes. Combined chapters use number ranges; source titles remain intact and missing content is never invented.
- Whole-book paged correction can search headings, jump to source lines and add/remove boundaries; up to 10,000 explicit choices can be remembered per book. Resplit can reuse, disable or forget it; exact title/context matches are required, ambiguous or changed text is skipped.
- TXT preview, separate import, merge, append, custom rules and large-book resplitting use disk ranges. Complete source bytes are checked before cache reuse. Indexes are limited to 500,000 lines; source size, temporary storage and annotation endpoints are bounded. Non-TXT parsing and very large individual chapters can still be memory-limited.
- Imports can pause and resume with per-file completion receipts committed atomically with chapters, so completed files are skipped after restart.
- Full `.inkbackup` archives preserve books, styles, annotations, progress, covers, groups, recognition memory and reading settings. Restore adds copies and restores reading settings; PC pairing, directory grants and pending jobs are excluded. Merge/split/resplit retain one persistent undo snapshot per book.
- Streamed exact substring search uses a disk candidate filter; whole-book page counts are cached by source and layout identity.
- Live numbered file selection across authorized folders, editable import order, and batch selection forward/backward, all visible files or an inclusive two-file range. Existing selections stay in order; subfolders are excluded.
- Choose Selection order to avoid filename/chapter reordering. For untitled scattered files, combine Merge into one book, One chapter per file and Selection order.
- The file picker's * Folder home is separate from the shelf. Holding its cards allows multiple shortcut removal without deleting source files.
- Cross-chapter reading, paging/scrolling, themes, global layout, annotations, groups, search and chapter editing. TXT exports exclude private annotations and are not a full-library backup.
- Phone TTS or optional local PC voices: Auto, Xiaoxiao and Nanami. Auto prefers Nanami for Japanese; install the corresponding voice on the PC. The floating player avoids reader controls without resizing the text page.

Open Import, add a book folder, select files, inspect the expected order, then confirm import and splitting options. Sources over 200 MiB are rejected; smaller complex or damaged documents can also exceed practical limits. Scanned PDFs, old DOC, MOBI and AZW3 are not supported.

Computer speech sends only the current sentence and next prefetched sentence to the paired PC over HTTP without TLS. Keep the PC awake and reachable on a trusted network. Android backup/device transfer may include books, annotations, progress and covers; voice credentials are excluded. Uninstalling or clearing data removes the local library.

### Build and documentation

JDK 17 and Android SDK platform 34 are required. Run the Gradle tasks above, or use `gradlew.bat` on Windows. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Use an explicit device serial for installation. Release tasks require a configured release keystore.

Version 1.14.1 fixes retry ordering, crossing annotation loss, empty-chapter offsets, AtomicFile recovery and unintended deletion of an existing empty book. It passes 200 JVM checks and 228 Android checks in two final-package rounds, plus an actual file-picker failure/retry scenario. The original library, file hashes and preferences remain unchanged. See the [1.14.1 audit](docs/1.14.1-复审修复与验证.md) and [initial 1.14.0 measurements](docs/1.14.0-全书校正与可靠性升级.md), including the remaining software-rendering frame-time limit. GitHub checks frozen inputs, regression, APK builds and lint after push; consult the commit’s Actions for its remote result.


The previous version 1.13.0 passes 185 JVM checks and 225 final Android checks, including two complete suite rounds and a third UI round. The new frozen corpus has 40 development and 24 holdout files with 132 expected headings; the older 24-file / 81-heading gold set and 154-file corpus also pass, including the previous 15 recognition probes. Three empty inputs remain rejected. Real 400-chapter TXT import, large-text and thousand-file stress runs preserve expected content, and the original library remains intact. This is authored test data, not third-party blind labelling or proof for every book. See the [audit report](docs/1.13.0-多语言分章与磁盘导入验收.md) for first-holdout results, earlier failures, measurements and limits.

See the [manual](docs/使用说明.md), [changelog](CHANGELOG.md), [contributing guide](CONTRIBUTING.md), [test tools](tools/testing/README.md) and [PC voice setup](tools/natural-voice/README.md). The full repository manual is separate from the localized guides packaged in the APK.

## License

[Apache-2.0](LICENSE). Third-party components retain their respective licenses.
