# 模拟器测试工具

这些脚本用于可控的开发模拟器，不是安装到手机后的自动操作程序。ADB 目标固定为 **emulator-5554**，不会自动选择列表中的手机；请确认该编号对应你准备好的测试模拟器。

## 环境与构建

- Python 3.10+、JDK 17、Android SDK platform 34，以及运行中的 Android 模拟器。
- ADB 从 PATH、ANDROID_HOME、ANDROID_SDK_ROOT 或仓库本机 local.properties 的 sdk.dir 发现，不再固定开发者磁盘路径。
- 从仓库根目录运行。以下任务构建主包、独立测试包并检查静态问题：

```powershell
.\gradlew.bat :app:unitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Unix 使用 ./gradlew。两个包必须来自同一次兼容构建和相同签名；不要通过卸载主包来解决签名冲突，以免丢失已有数据。

## 可独立执行的隔离检查

```powershell
python tools/testing/run_full_audit.py device
python tools/testing/run_full_audit.py order
python tools/testing/run_full_audit.py full
python tools/testing/run_full_audit.py additional
python tools/testing/run_full_audit.py soak
```

| 模式 | 内容 |
| --- | --- |
| device | 数据回滚、格式导入、分页、编辑退出保存与阅读界面；不依赖真实电脑语音播放 |
| order | 导入／追加顺序、偏好恢复和文件夹入口数据行为 |
| full | 万章、1050 万字符、千文件真实导入、取消、并发写入、大队列和长段落分页 |
| additional | 大 Word、HTML、Markdown、PDF、EPUB，旧数据库迁移、并发导出和快速切章 |
| soak | 20 轮导入、分组、搜索、批注、编辑、删除，检查残留文件、记录和回收后内存 |

测试主体使用 runner 创建的独立临时数据库与正文目录，结束清理，不复用现有书架正文。部分界面检查会启动主应用并临时渲染测试页面，建议使用专用模拟器。导出测试创建带随机标识的测试文件并在结束时清理其副本。

运行器每约两秒采样一次进程 PSS，保存文本日志、结果 JSON 和内存 CSV；采样最大值不是真正瞬时峰值。输出目前位于历史验收目录 app/build/audit/full-2026-10-24/，同一模式重跑会覆盖对应结果，保留历史前请先复制。

## TXT 语料与实际点击脚本

- [154 份 TXT 语料说明](../../tests/fixtures/txt-corpus/README.md)：独立预期、编码、各种标题和识别能力边界。
- generate_txt_corpus.py --push：生成并复制样本到明确的模拟器；不会替你点击导入。
- verify_emulator_txt.py --expanded：在实际完成语料导入后读回数据库与正文核对，不能拿空书库直接运行。
- exercise_ordered_picker.py、exercise_folder_home.py、exercise_file_batch.py：按阶段操作文件选择界面，依赖预先授权的 INK_PICKER_UI 等目录与对应文件。
- full_audit_ui.py：保留 1.11.23 两千长文件名的复现／复测流程；依赖该验收模拟器已有的样本、授权和 396 本测试书基线，**不是通用的一键初始化工具**。picker-baseline 故意复现旧版崩溃，只应在可丢弃的旧版测试环境运行。
- 其他 exercise_* 脚本是特定验收会话的点击辅助工具，运行前阅读其前置条件；语音相关脚本可能需要已配对的本地电脑服务。

原始输出与截图、生成的大文件、APK、Python 缓存都不提交。仓库中的 TXT 与 manifest 是人工定义预期的测试样本，不是用户书库。压力结果与可推广的范围见 [审计报告](../../docs/1.11.23-全面审计与模拟器压力测试.md)。


## 1.12.0 编码与章节回归

新增金标准位于 `tests/fixtures/recognition-gold/`。预期标题和正文标记独立标注；原有 154 份样本中的能力探测仍按识别限制单独计数。

构建并覆盖安装 debug 主包和 androidTest 包到 `emulator-5554` 后，运行：

```powershell
python tools/testing/run_algorithm_audit.py --rounds 2
```

默认依次运行 recognition、device、order、full、additional、soak 六组，每组每轮分别保存日志和结果到忽略的 `app/build/audit/algorithm-日期-时间/`。可用 `--modes recognition,full` 选择组别，用 `--output` 指定结果目录。驱动固定选择模拟器，不会选择真机；不自动安装、清数据或卸载。单组超时 30 分钟，缺少完整结果按失败处理，不把中断当通过。

recognition 在隔离测试书库实际导入全部旧样本和新标注样本，核对预览与最终章节、手动编码、取消分章点、取消预览、并发分析和错误章节归属。连续压力为有限轮次，不能据此保证所有设备、书源或长期无泄漏。

## 1.13.0 分区评估与磁盘导入

`tests/fixtures/recognition-eval/` 新增64份冻结输入与标注，开发40／留出24；T 为原样标题，L 为规范化后的边界行号，P64 为完整原始段落。核对段落时仅按既定导入契约移除行尾空白。默认 JVM 测试仅选开发分区，设置环境变量 `INK_EVAL_PARTITION=holdout` 单独记录首次留出结果，最终 `all` 跑全部。来源组不能跨分区；按语言分别输出 TP、FP、FN、精确率、召回率和段落错误。生成器不读取解析输出，拒绝覆盖既有样本；这不是第三方盲标。

recognition 组增加 Android 64份实际预览—导入核对、单书记忆重启／关闭／清除、缓存失效、并发源隔离、400章大 TXT 及流式写入故障回滚。只操作 runner 的临时书库。磁盘缓存以完整源字节和选项为键；每个仓库独占缓存会话，旧会话清理不会删除其他活跃仓库文件。

`run_algorithm_audit.py` 现在也约每2秒采样主进程PSS，每组保存内存CSV、样本数和采样最大值；无样本记为null，不能解释为零内存或真正瞬时峰值。当前版本结果见[1.13.0验收记录](../../docs/1.13.0-多语言分章与磁盘导入验收.md)。


## 1.14.0 全书校正与可靠性

真实来源摘录见 `tests/fixtures/real-source/`，出处、原始 HTML 指纹、摘录规则和独立边界均保留。`generate_source_fixtures.py` 不调用分章器，拒绝覆盖现有标注。先运行 `python tools/testing/verify_frozen_fixtures.py`；最终 JVM 设置 `INK_EVAL_PARTITION=all`。

recognition 组新增完整备份往返、路径/校验和/错误设置拒绝、SQL 故障回滚、持久撤销、失败操作保留旧撤销点、153 万字符磁盘重分章的样式/批注/进度、取消清理，以及各自成书/合并/追加的中断恢复和提交凭据原子性。搜索测试报告冷/暖查询用时、跳过章节数量，并校验修改后缓存失效；用时是具体设备测量，不作为所有硬件的承诺。

`exercise_upgrade_ui.py` 是明确限定 emulator-5554 的分阶段点击辅助，支持 show/tap/hold/query/shot/back/scroll。它不会自动选择其他模拟器或手机；需先安装本版主包，准备独立 INK1140_UI 样本并授权其上层目录。若存在本轮 `before/baseline.json` 则隐藏原有书名。没有基线时不要把 show 输出当作可公开日志。完整验收还需在专用样本上实际核对全书校对、重分章/撤销、暂停/杀进程/继续、备份/恢复、阅读翻页和原书库不变。

自动检查配置在 `.github/workflows/android-checks.yml`，本地工作树不能代表远端已触发或已通过；它也不自动执行模拟器。结果与限制见 [1.14.0验收](../../docs/1.14.0-全书校正与可靠性升级.md)。


## 1.14.1 复审反例

`AuditFollowupChecks` 加入较早源失败后的合并／追加顺序、重分章／手工拆章跨界批注、空章节书签进度、仅剩 AtomicFile 备份的读取与导出、已有空书失败追加、结束任务等待活动导入，以及无效重排参数。先在旧主包上跑出 5 个失败，再修复；最终 recognition 每轮 29 项，其余组数量不变。200 JVM、两轮全套 228 安卓检查结果与真实选择器重试流程见 [1.14.1复审记录](../../docs/1.14.1-复审修复与验证.md)。界面辅助输出默认改为 `app/build/audit/emulator-1.14.1/`。
