# remove-ai-marks
what: Find and remove the marks AI tools leave in the user's own text and files: invisible letters, C2PA, EXIF, XMP, document properties
from: https://github.com/guillaumemeyer/watermarks-remover
version: 1
---
This skill comes from the project watermarks-remover (MIT licence). On this PC
its work is done by `pc marks`. The first use fetches Python (22 MB) when the PC
has none.

Use it when the user asks to remove AI marks or an AI watermark from a text or
a file, to remove C2PA or Content Credentials, to clean the metadata of a
picture or a document, or to remove invisible (zero-width) letters: "AI ka
nishaan hatao", "metadata saaf karo", "chhupe akshar hatao".

It is for content the user owns or may work on. Know first what it does NOT do:

- It does not remove a logo, stamp or writing that can be SEEN on a picture or
  a video. If that is what the user wants, say so at once and do not start.
- It does not remove a watermark hidden in the pixels or in the sound (SynthID
  in pictures, audio and video). No part of this PC's tool touches pixels.

## Steps

1. See what there is.
   - A file: `pc marks check "C:\path\file"`.
   - Text in the message: write it, unchanged, to a file `marks-in.txt` in the
     working folder with your file-writing tool (not through the shell: the
     invisible letters get lost there), then check that file.
   - Text in the clipboard: `pc marks clipboard check`.
2. Tell the user what was found, from the lines of the report: which kind of
   mark and how many. With "Result: no marks found", say that, together with
   the "Not seen" line, and stop there unless the user wants step 5.
3. Clean. `pc marks clean "C:\path\file"` makes `file.cleaned.ext` beside the
   file. The original stays; do not delete or replace it unless the user asks.
   For the clipboard: `pc marks clipboard`.
4. Check the copy: `pc marks check "C:\path\file.cleaned.ext"`. Then report
   what was removed (the lines that start with "-"), where the copy is, and
   what may remain.
5. Text only, and only when the user wants it (offer it once after step 4 when
   an AI wrote the text). A watermark can also sit in the choice of the words.
   Nothing here can see it or measure it. The one thing that weakens it is to
   write the text again in your own words:
   - keep every fact, number, name, quote, link and piece of code, and the
     meaning; add nothing and leave nothing out;
   - really change the sentences (their order, length and words), not a word
     here and there;
   - keep the user's language and tone;
   - then do step 3 with your new text too, because your own writing can
     carry invisible letters.
   Say plainly that this is best effort: nobody can promise that the text now
   counts as unmarked.

## How to report, always

- Keep three things apart: what is certain (letters and metadata removed, as
  the tool counted them), what is best effort (the rewrite), and what was not
  touched (pixels, sound, anything that can be seen).
- Never say that the content is now "human-written", "undetectable" or "not
  AI". A mark that is gone does not change who made the content.
- Not for hiding the help of an AI where it has to be told: school or
  university work, an employer or a client who asks, the rule of a platform,
  the law. If the user says that this is the aim, say in one sentence that
  you do not help with that, and why.

## When something fails

- "no Python" and the download failed: the PC needs the internet once, or
  Python 3.10 or newer from python.org; then `pc marks setup`.
- "this kind of file is not known": the tool knows text, HTML, Markdown, PNG,
  JPEG, WebP, AVIF, HEIC, GIF, TIFF, BMP, SVG, PDF, DOCX, XLSX, PPTX, EPUB,
  ODT, MP3, WAV, FLAC, MP4 and MOV. Say so; do not guess what is in the file.
- A PDF with pictures inside: marks inside those pictures are reached only
  when Ghostscript is installed on the PC. The report says when something
  remains.
