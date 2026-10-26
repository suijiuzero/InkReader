# 贡献指南

感谢你愿意为墨阅（Ink Reader）花时间。这是一个个人维护的轻量项目，流程刻意保持简单。

## 开始之前

- 提 issue 前先搜一下现有 issue（含已关闭的）。
- 改动较大的（新增格式、改动分页 / 排版引擎、动数据库结构），先开 issue 讨论方案再动手。
- 本项目**没有 CI**，所以请务必在本地构建通过、并自行验证再提 PR。

## 本地环境

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | platform 34 |
| Android Studio | Koala 2024.1 或更新（可选） |

```bash
# 构建（Gradle wrapper 自带，无需本机装 Gradle）
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug

# 查看设备后替换 DEVICE_SERIAL，避免同时连接手机和模拟器时装错
adb devices
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

构建产物与本机配置（`build/`、`local.properties`、`keystore.properties`、`*.jks`）已在 `.gitignore` 中，**不要提交**。

> 工程目录名含中文时 AGP 会拒绝构建，本工程已在 `gradle.properties` 关闭该检查（`android.overridePathCheck=true`），中文目录下也能构建。

## 验证与文档同步

```bash
./gradlew :app:unitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Windows 使用 .\gradlew.bat。unitTest 是项目自定义的测试发现入口，自动运行纯 Kotlin/JVM 的 *Test 类；它避免直接执行 Android Gradle 默认 JVM 测试任务时的中文路径问题。不要只新增测试而未确认它实际执行。

需要模拟器验证数据、阅读、导入或压力行为时，安装同一次构建的主包及 AndroidTest 包，再使用 [测试工具说明](tools/testing/README.md) 中的明确目标与模式。隔离测试和真实界面操作的前置条件不同，不能将已有验收会话脚本当作可在任意书库运行的一键工具。截图、日志、内存采样和机器本地结果保存在忽略的 app/build/ 中；提交可复跑的源码、固定样本和准确的结果摘要即可。

修改可见功能时同步：

- 当前完整手册 docs/使用说明.md，以及中英 README。
- 应用内简体中文、英文、日文说明 app/src/main/assets/help/help_*.md；这些资产会打包进 APK，仓库完整手册不是 APK 内的独立文件入口。
- CHANGELOG.md 和 app/build.gradle.kts 的版本；不要把有限模拟器验证写成所有真机均通过。
- 新测试工具需要记录适用设备、初始化条件和是否操作现有书库；不要提交个人路径、设备标识、配对码、语音包、APK 或缓存。

## 发布签名

常规自测使用 assembleDebug。release 打包需要在本机配置 keystore.properties（storeFile、storePassword、keyAlias、keyPassword），缺少配置时发布任务会失败，不会静默使用 debug 签名。密钥和配置保持在忽略列表中；不在日志、文档或提交中展示真实口令。

## 代码约定

**Kotlin / Compose**

- 单 Activity 架构；状态由应用级容器持有，UI 只读。
- Compose 里不要在组合期做 I/O、读数据库或创建重量级对象（解码用缓存，参考 `CoverCache`）。
- 文件、类、函数命名用英文；注释用**中文**（与现有代码一致），只在逻辑不自明处写注释。

**解析层（`parse/`）**

- 新增格式请接到**统一管线**：`DocumentFormat` / `DocumentParsers` 注册表 → `DocumentParser` → 统一 `ParsedDoc` 结果；导入链路（合并 / 分章 / 排序 / 落盘 / 建库）完全复用，不要在这一层另起分支。
- **章节识别规则在 [`ChapterSplitter.kt`](app/src/main/java/com/inkreader/parse/ChapterSplitter.kt)，只做增量扩展，不重写已验证的中文规则**；编号统一解析成整数再排序；序章 / 终章 / 番外等特殊章节归类处理，位置不固定的（`幕間 / Interlude`）钉在原位。
- 优先引入**纯 Java、无 so、无 AWT** 的依赖（如 pdfbox-android、commonmark-java），避免包体膨胀与 dex 风险；能用手写解析 + 系统 `XmlPullParser` / `ZipInputStream` 解决的就不要引入库（docx / HTML / EPUB 均为手写）。

**数据层（`data/`）**

- 存储为 SQLite（手写 DAO，不用 ORM / 注解处理器）+ 正文以 UTF-8 文件存在应用私有目录，数据库只存索引。
- **数据库迁移必须平滑**：用 `ALTER TABLE` 追加列 / 建表，绝不丢用户数据（书架、进度、批注、分组都要保住）。
- **删除等跨多步的写操作必须包裹事务**，中途失败整体回滚，避免「书没了、章节还挂着」这类残缺数据。

**健壮性**

- 导入文件要有**体积上限**（当前 200MB）；ZIP 解析要限制**单条目大小**（防 ZIP 炸弹）。
- 解析失败要**明确说明原因**（走 `ParseErrorCode` 统一文案），不要笼统报「导入失败」，也不要静默降级。

## 提交信息

采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/v1.0.0/)：

```
feat: 新增 xxx
fix: 修复 xxx
refactor: 重构 xxx
docs: 更新 xxx 文档
chore: 构建/依赖杂项
```

正文里写清**为什么**，而不只是做了什么。一个提交一件事。

## PR 流程

1. 从 `main` 切分支：`feat/xxx`、`fix/xxx`、`refactor/xxx`。
2. 本地跑通构建：`./gradlew assembleDebug`，并在真机上自测受影响的路径（导入、跨章翻页、导出、朗读等）。
3. 提交 PR，说明：改动动机、验证方式（真机验证过什么）、是否有破坏性变更（尤其数据库结构）。
4. 涉及版本号变更的，改 [`app/build.gradle.kts`](app/build.gradle.kts) 的 `versionCode` / `versionName`，并在 [CHANGELOG.md](CHANGELOG.md) 补条目。

## 报告问题

提 issue 时请带上：Android 版本与机型、App 版本、复现步骤、导入文件格式与来源，以及——如果涉及崩溃——`logcat` 片段。


### 冻结样本与自动检查

先运行 `python tools/testing/verify_frozen_fixtures.py`，再运行构建、单元测试及 lint。`tests/fixtures/real-source/` 包含真实来源摘录、预期边界与出处，生成器不调用分章器且拒绝覆盖现有标注。不要为通过测试修改冻结答案；发现标注问题须说明依据及首次留出结果。已见过的留出样本应视为后续回归集。

`.github/workflows/android-checks.yml` 在 PR、推送 main 或手动运行时执行上述检查，报告保留 14 天；它构建 Android 测试 APK，但不等于已执行模拟器验收。设备测试应使用测试 APK 的独立存储，明确设备序列号。故障、压力测试脚本限定 `emulator-5554`，不可改成自动选中连接的手机。
