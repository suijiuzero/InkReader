# Ink Reader — User Guide

For version 1.13.0. Reading works offline without an account or ads. A paired Windows computer on your trusted LAN can optionally provide speech.

## Expanded recognition and saved corrections

More native chapter labels and Unicode decimal digits are supported. Bare numerals require a consecutive, spaced series with sufficient body text. Mid-book style changes use neighboring numbers; combined chapters such as 1–2 use their full number range. Source titles remain unchanged.

After unchecking a boundary for one TXT, enable Remember this book’s chapter adjustments. Resplit can reuse, disable or forget them. Exact title and nearby text must still match; changed or ambiguous matches are skipped, and other books are unaffected. Only the first 40 displayed boundaries can be edited and remembered.

Single-TXT previews and separate-book imports use a disk-backed index and validate complete source bytes before reusing a cached plan. Custom rules, multi-file merging and other formats keep their existing paths. Files over 200 MiB or TXT indexes over 500,000 lines are rejected; whole-book resplitting still has a memory limit.

## Quick start

- Tap Import on the shelf, add a book folder, then tap files in your preferred order.
- Each selected file shows its import position. Expand Expected import order to move, remove or clear files.
- Continue import, choose separate books or one merged book, and check both file order and chapter splitting.
- Tap a cover to read. Tap the middle of the page to show or hide the reader bars.

## Encoding and recognition details

In import confirmation, open Recognition details. TXT detection compares UTF, GB18030, Big5, Shift-JIS (Windows-31J) and EUC-JP. If uncertain or garbled, import one TXT at a time, choose a text encoding and check the excerpt. Changing encoding clears boundary edits.

Details show number-gap recovery, volume styles, contents matching, possible missing chapters and size-based splitting. For a single TXT, uncheck any of the first 40 displayed boundaries to keep its heading and text inside surrounding content. Other boundaries keep the current rules. Multi-file details refer to the first file; import it alone to edit. Only the first five files are inspected; remaining counts are estimates.


## Folder home and multiple folders

The * button opens the file picker's Folder home, which is separate from the book shelf. Add several authorized folders and select across them; going back to Folder home keeps your selected files and their order.

Android asks for folder read access. On Android 11 and later, choose a book subfolder inside Downloads if the system refuses access to the Downloads root.

Hold a folder card on Folder home to enter multiple selection, select all, cancel or remove shortcuts. Removing a shortcut does not delete source files. The optional checkbox also deselects files from those folders; it is off by default.

Inside a directory, tap a subfolder to enter it. Holding a subfolder does nothing. The top-right System picker remains available.

## Hold a file for batch selection

- Select from that file to the end, or backward to the start.
- Select all visible files in display order or reverse order.
- Choose a second file to select an inclusive range, following the direction from the starting file to the ending file.

Only matching files in the current folder are included. The current search filter and display order apply; subfolders are excluded. Already selected files keep their positions and new files are appended without duplicates. Cancel makes no selection changes.

## File order and chapter splitting

- Selection order preserves the expected order and skips filename/chapter-number reordering. Appended files go after existing chapters.
- Smart sorting uses recognized filename numbers and related metadata. Chapter management also offers automatic sorting.
- Changing the browser's display sort does not reorder files already selected.
- For scattered files without numbers or headings, choose Merge into one book, One chapter per file and Selection order.
- Recognition includes CJK and English numbering, volumes and special headings such as 最终章（上） and extras. Gap recovery searches only between neighboring headings; missing source chapters are never invented.
- If automatic splitting is unsuitable, try strict headings, numbered headings, fixed length or a custom prefix/regex and inspect the preview.

## Supported formats

TXT supports common UTF-8/BOM, GBK/GB18030 and UTF-16 encodings. DOCX extracts text and heading styles; convert old DOC files first. Markdown preserves supported text formatting. HTML/HTM/XHTML extracts text without running scripts or loading remote images. EPUB follows its spine reading order.

PDF requires extractable text: there is no OCR, and complex columns may need manual checking. Images, tables and original page layout are not fully reproduced. Sources over 200 MiB are rejected; smaller complex or damaged documents can also exceed practical limits.

## Reading and layout

Swipe or tap page edges in paging mode; scroll normally in scroll mode. Reading continues across chapters. Contents includes Chapters and Notes. Drag the progress bar to preview a chapter and release to jump.

Long-press text to extend a selection, highlight, bookmark or annotate. Pull down a whole page to tag it in paging mode. Volume-key paging is optional.

Shelf menu Settings and reader Settings share one global layout: font size, line/paragraph/letter spacing, margins, font, indent, brightness, theme, page mode and animation apply to all books.

## Read-aloud and computer voices

Open Read aloud in the reader to start from the current page or chapter beginning and adjust rate, pitch and sleep timer. Phone TTS depends on installed engines and language data.

- Start the project's Windows voice bridge, keep both devices on the same trusted Wi-Fi, and enter the 16-character pairing code under 电脑语音 (computer voice).
- A manual private IPv4 address is available if discovery fails. 测试连接 tests the connection; 试听语音 plays a preview through the phone's media output.
- 选择朗读模型 opens the model picker beside the unpair action. Choose Auto, Xiaoxiao or Nanami and save; cancelling keeps the old choice.
- Auto uses Xiaoxiao for Chinese/English and prefers Nanami for Japanese. Nanami must be installed on the computer. Japanese written entirely in kanji may need manual Nanami selection.
- Preview uses the new model immediately. Stop and restart existing listening to apply a changed model; pause listening before previewing.
- If the PC is unavailable, the app attempts phone TTS. If the required phone language is unavailable too, listening pauses with a message. Keep the PC awake, signed in and reachable.
- The floating player moves above visible reader controls without resizing the text page.

Only the current sentence and the next prefetched sentence are sent to the paired PC. LAN traffic uses HTTP without TLS encryption. Use a trusted network. Voice pairing credentials are excluded from system backup. Some computer-voice controls currently retain Chinese labels.

## Management, search and export

Shelf groups and title/author search are supported. Each book belongs to at most one group. Chapter management supports adding, editing, moving, merging, splitting, deleting, sorting, re-splitting and appending files; review the target and preview before confirming.

The editor autosaves after about 1.2 seconds of inactivity and waits for a save before returning. Forcibly killing the process immediately after typing can lose the latest unsaved input.

Full-text search jumps to matching text. Large-book parsing, page counting and search take time. Whole-book or chapter export produces UTF-8 BOM + CRLF TXT without private annotations. A share copy may remain available if writing to Downloads fails.

## Troubleshooting and data

Reauthorize expired folders. Removing a shortcut keeps existing read grants. For unexpected order, inspect the expected sequence and Selection order separately from splitting mode. For Japanese speech, install/select Nanami or phone Japanese TTS data.

Inspect per-file import failures and your shelf before retrying: earlier completed imports can remain after interruption. System backup/device transfer may include books, notes, progress and covers. Uninstalling or clearing app data removes the local library; export important content first.

The full manual and changelog are in the GitHub repository at docs/使用说明.md and CHANGELOG.md. Emulator and selected phone checks do not cover every document, device or background policy.
