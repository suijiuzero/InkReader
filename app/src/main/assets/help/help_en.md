# Ink Reader — User Guide

Ink Reader supports offline reading without accounts or ads. Optional Computer Xiaoxiao sends the current and next sentence to your paired computer on the same trusted LAN for speech synthesis.

## Computer Xiaoxiao

Open a book → listening panel → Computer Xiaoxiao. Start the Windows voice bridge and enter its 16-character pairing code once. Use the connection test and voice preview buttons; previews play through the phone's media audio. If the computer is offline, listening falls back to phone TTS and retries about every 30 seconds. Keep the computer awake on the same Wi-Fi. Pairing credentials are excluded from system backup.

## Quick start

- **Import books**: tap the button at the bottom-right of the shelf → pick files → choose "Merge into one book" or "Each file becomes a book"
- **Start reading**: tap a cover to open the reader; tap the middle of the screen again to bring up the top and bottom bars

## Supported formats

- **TXT**: auto-detects UTF-8 / UTF-8(BOM) / GBK / GB18030 / UTF-16
- **DOCX**: reads body paragraphs and Heading 1–6 levels
- **Markdown** (`.md`): formatting preserved — headings / bold / italic / lists / quotes / code blocks / tables
- **PDF**: **text-based** only, no OCR
- **HTML**: extracts `<title>` and body, `h1~h6` become chapters
- **EPUB**: reads chapters in spine (reading) order

## Reading controls

- **Turn pages**: swipe, or tap the left/right edge of the screen; in scroll mode just scroll
- **Contents**: the "Contents" button on the bottom bar, with "Chapters / Notes" tabs at the top
- **Progress**: drag the bar above the bottom bar — a chapter preview appears, and the jump happens on release
- **Annotate**: long-press text → drag to extend → four-color highlight / bookmark / note
- **Whole-page tag**: in page mode, pull a whole page down to tag it
- **Volume-key paging**: can be enabled in Settings

## Layout settings

The "Settings" on the shelf menu and the one in the reader's bottom bar are **one shared global set** — change it once and it applies everywhere:

font size, line height, paragraph spacing, letter spacing, margins, font (sans / serif), theme, page mode, page animation, first-line indent, screen brightness.

Five themes: Mist / Paper / Green / Ink / Night.

## Read-aloud

The "Read aloud" button in the reader starts the system TTS engine. Control it from the notification / lock screen; a sleep timer and headset buttons are supported. You can also "read from this paragraph".

## Chapter management

"Chapter management" on the book detail page: view, rename, delete, add manually, and auto-sort chapters. When you append files, a chapter number in the filename is inserted at the matching position automatically.

## Search & export

- **Search**: the shelf's top search box filters by title / author; full-text search works inside a book
- **Export**: export a whole book or a single chapter as a UTF-8(BOM) + CRLF TXT into the Downloads folder

## FAQ

- **Import seems to do nothing**: make sure the extension is supported and the file is under 200MB
- **PDF shows garbage / blank**: scanned (image-based) PDFs contain no extractable text — use a text-based PDF
- **Chapters aren't split**: a chapter title must be on its own line and match a pattern like "Chapter N"

> The full manual (with technical notes, known limitations and the changelog) ships with the package at `docs/使用说明.md`.
