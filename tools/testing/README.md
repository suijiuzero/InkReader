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
