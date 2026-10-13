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

# 安装到已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

构建产物与本机配置（`build/`、`local.properties`、`keystore.properties`、`*.jks`）已在 `.gitignore` 中，**不要提交**。

> 工程目录名含中文时 AGP 会拒绝构建，本工程已在 `gradle.properties` 关闭该检查（`android.overridePathCheck=true`），中文目录下也能构建。

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
