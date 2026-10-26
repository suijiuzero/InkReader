# 有来源的真实作品摘段

三部作品、24个原有章节边界、96个完整原文段落。R01《Alice’s Adventures in Wonderland》与R02《Die Verwandlung》用于开发；R03《阿Q正传》按整部作品留出。来源组不拆到两个分区。

每章保留原有标题行和前四个完整网页段落，Alice的多行副标题继续保留在正文中；去除导航、目录和站点许可模板，HTML排版空白合并为普通空格。这里是章节摘段样本，不是完整作品，也不代表复杂目录、整本长文本或所有版本均通过。

章标题由生成脚本显式列出并与原页面h2核对，T为标题、L为规范化后的一基行号、P64为完整段落的UTF-8 Base64。生成器不导入解析器、不读取解析结果；输入、标注、分区清单和来源记录有冻结哈希，拒绝覆盖已有数据。调参只运行development；首次holdout记录须单独保存，随后已见留出集仅作回归。

来源、原作者、提取方式和缓存页面SHA-256见[sources.json](sources.json)：

- Lewis Carroll：[Project Gutenberg，Alice](https://www.gutenberg.org/ebooks/11)。
- Franz Kafka：[Project Gutenberg，Die Verwandlung](https://www.gutenberg.org/ebooks/22367)。
- 鲁迅：[维基文库，阿Q正传](https://zh.wikisource.org/zh-hans/阿Q正传)。

这些历史作品摘段保留原作品的权利状态与来源署名；仓库代码的Apache许可不改变来源作品的权利信息。原始网页仅保存在忽略的本机缓存中，不把导航、站点标志或许可模板混入正文样本。重新取材需核对来源页面的版权与使用说明。

这是外部真实原文与原章结构形成的标注，仍由同一开发者提取核对，不能称为第三方人工盲标。默认环境变量`INK_EVAL_PARTITION=development`；明确设`holdout`或`all`分别执行。
