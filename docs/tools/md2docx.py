#!/usr/bin/env python
"""
md2docx.py — convert a (controlled-subset) Markdown file to a styled MS Word .docx.

Supports the subset the ERPCLEAN2 docs use: ATX headings (#..####), '- ' bullets,
'1. ' numbered lists, GitHub pipe tables, **bold**, `inline code`, ``` fenced code,
'---' horizontal rules, and a leading cover (first H1) + a static, clickable Table of
Contents (chapters + sections, hyperlinked to per-heading bookmarks — always visible on
open, no field update needed).

Usage:
    python docs/tools/md2docx.py <input.md> <output.docx> ["Document Title"] ["Subtitle"]

Requires: python-docx (pip install python-docx).
"""
import os
import sys
import re
from docx import Document
from docx.shared import Pt, RGBColor, Inches
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

HEADING_RE = re.compile(r'^(#{1,6})\s+(.*)$')
TABLE_SEP_RE = re.compile(r'^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$')
BULLET_RE = re.compile(r'^(\s*)[-*]\s+(.*)$')
NUM_RE = re.compile(r'^(\s*)\d+\.\s+(.*)$')
INLINE_RE = re.compile(r'(\*\*.+?\*\*|`[^`]+`)')
# A block-level Markdown image on its own line: ![alt text](relative/path.png)
IMAGE_RE = re.compile(r'^\s*!\[(.*?)\]\(([^)]+)\)\s*$')
# Page text width on Letter with default 1-inch margins ≈ 6.5"; cap a touch under that.
IMAGE_MAX_WIDTH_IN = 6.2
# Table-of-contents depth: chapters (H1) + sections (H2). Kept in lock-step between the
# TOC list and the per-heading bookmarks so their ordinal anchors line up.
TOC_DEPTH = 2


def collect_toc(lines, max_level=3):
    """Headings (levels 1..max_level) in document order, skipping fenced code blocks —
    the source for the static Table of Contents (and matched 1:1 by the bookmarks the
    body render adds to each heading, so the ordinal-based anchors line up)."""
    entries, in_code = [], False
    for line in lines:
        if line.strip().startswith('```'):
            in_code = not in_code
            continue
        if in_code:
            continue
        m = HEADING_RE.match(line)
        if m and len(m.group(1)) <= max_level:
            entries.append((len(m.group(1)), m.group(2).strip()))
    return entries


def add_bookmark(paragraph, name, bmid):
    """Wrap a heading paragraph in a Word bookmark so TOC hyperlinks can jump to it."""
    start = OxmlElement('w:bookmarkStart'); start.set(qn('w:id'), str(bmid)); start.set(qn('w:name'), name)
    end = OxmlElement('w:bookmarkEnd'); end.set(qn('w:id'), str(bmid))
    paragraph._p.insert(0, start)
    paragraph._p.append(end)


def add_toc_entry(doc, text, anchor, level):
    """A clickable, indented TOC line that links to the heading's bookmark. Rendered
    statically at build time so the Table of Contents is ALWAYS visible and navigable on
    open — no 'right-click → Update Field' step (which matters for the image-rich manual
    that is the one mostly used)."""
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Inches(0.28 * (level - 1))
    p.paragraph_format.space_after = Pt(2)
    hy = OxmlElement('w:hyperlink'); hy.set(qn('w:anchor'), anchor)
    r = OxmlElement('w:r'); rpr = OxmlElement('w:rPr')
    if level == 1:
        rpr.append(OxmlElement('w:b'))
    color = OxmlElement('w:color'); color.set(qn('w:val'), '1155CC'); rpr.append(color)
    u = OxmlElement('w:u'); u.set(qn('w:val'), 'single'); rpr.append(u)
    r.append(rpr)
    t = OxmlElement('w:t'); t.set(qn('xml:space'), 'preserve'); t.text = text
    r.append(t); hy.append(r); p._p.append(hy)


def add_runs(paragraph, text):
    """Render inline **bold** and `code` within a paragraph."""
    for part in INLINE_RE.split(text):
        if not part:
            continue
        if part.startswith('**') and part.endswith('**'):
            r = paragraph.add_run(part[2:-2]); r.bold = True
        elif part.startswith('`') and part.endswith('`'):
            r = paragraph.add_run(part[1:-1]); r.font.name = 'Consolas'; r.font.size = Pt(9.5)
            r.font.color.rgb = RGBColor(0xB0, 0x30, 0x60)
        else:
            paragraph.add_run(part)


def add_image(doc, alt, src, base):
    """Embed a block-level Markdown image, centered, with the alt text as a caption.

    Paths are resolved relative to `base` (the image base dir — set by the build to
    docs/user-manual so chapter-relative `images/...` links resolve). A missing or
    unreadable file degrades to an italic placeholder rather than aborting the build.
    """
    path = src if os.path.isabs(src) else os.path.normpath(os.path.join(base, src))
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    if os.path.exists(path):
        try:
            p.add_run().add_picture(path, width=Inches(IMAGE_MAX_WIDTH_IN))
        except Exception as exc:  # corrupt/unsupported image — don't fail the whole doc
            r = p.add_run(f'[image unavailable: {alt or src} — {exc}]'); r.italic = True
    else:
        r = p.add_run(f'[missing image: {src}]'); r.italic = True
        sys.stderr.write(f'WARN md2docx: missing image {path}\n')
    if alt:
        cap = doc.add_paragraph()
        cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
        cr = cap.add_run(alt)
        cr.italic = True
        cr.font.size = Pt(9)
        cr.font.color.rgb = RGBColor(0x55, 0x55, 0x55)


def flush_table(doc, rows):
    if not rows:
        return
    cols = max(len(r) for r in rows)
    tbl = doc.add_table(rows=0, cols=cols)
    tbl.style = 'Light Grid Accent 1'
    tbl.alignment = WD_TABLE_ALIGNMENT.LEFT
    for ri, row in enumerate(rows):
        cells = tbl.add_row().cells
        for ci in range(cols):
            cell_text = row[ci] if ci < len(row) else ''
            para = cells[ci].paragraphs[0]
            add_runs(para, cell_text.strip())
            if ri == 0:
                for run in para.runs:
                    run.bold = True
    doc.add_paragraph()


def convert(md_path, docx_path, title=None, subtitle=None):
    # Image base: where chapter-relative `images/...` links are anchored. The build sets
    # MD2DOCX_IMG_BASE=docs/user-manual; fall back to the input file's own directory.
    img_base = os.environ.get('MD2DOCX_IMG_BASE') or os.path.dirname(os.path.abspath(md_path))

    with open(md_path, encoding='utf-8') as f:
        lines = f.read().split('\n')

    doc = Document()
    # base font
    style = doc.styles['Normal']
    style.font.name = 'Calibri'
    style.font.size = Pt(10.5)

    # ── Cover page ──
    if title:
        for _ in range(6):
            doc.add_paragraph()
        h = doc.add_paragraph(); h.alignment = WD_ALIGN_PARAGRAPH.CENTER
        r = h.add_run(title); r.bold = True; r.font.size = Pt(30)
        if subtitle:
            s = doc.add_paragraph(); s.alignment = WD_ALIGN_PARAGRAPH.CENTER
            sr = s.add_run(subtitle); sr.font.size = Pt(15); sr.font.color.rgb = RGBColor(0x55, 0x55, 0x55)
        doc.add_page_break()
        # Table of contents — static, clickable entries (H1–H3) linking to per-heading
        # bookmarks, so it is visible and navigable the moment the document opens.
        doc.add_heading('Contents', level=1)
        for idx, (lvl, text) in enumerate(collect_toc(lines, max_level=TOC_DEPTH)):
            add_toc_entry(doc, text, f'_Toc{idx:04d}', lvl)
        doc.add_page_break()

    i = 0
    toc_idx = 0  # ordinal of the next H1–H3 heading — must track collect_toc()'s order
    table_buf = []
    in_code = False
    code_buf = []
    while i < len(lines):
        line = lines[i]

        # fenced code blocks
        if line.strip().startswith('```'):
            if in_code:
                p = doc.add_paragraph()
                r = p.add_run('\n'.join(code_buf))
                r.font.name = 'Consolas'; r.font.size = Pt(9)
                # light shading
                shd = OxmlElement('w:shd'); shd.set(qn('w:fill'), 'F2F2F2')
                p._p.get_or_add_pPr().append(shd)
                code_buf = []; in_code = False
            else:
                in_code = True
            i += 1
            continue
        if in_code:
            code_buf.append(line); i += 1; continue

        # table accumulation
        if line.strip().startswith('|') and '|' in line.strip()[1:]:
            if TABLE_SEP_RE.match(line):
                i += 1; continue  # skip the |---|---| separator
            cells = [c for c in line.strip().strip('|').split('|')]
            table_buf.append(cells); i += 1; continue
        elif table_buf:
            flush_table(doc, table_buf); table_buf = []

        m = HEADING_RE.match(line)
        if m:
            level = len(m.group(1))
            h = doc.add_heading(m.group(2).strip(), level=min(level, 4))
            if level <= TOC_DEPTH:  # bookmark it so the matching TOC entry can link here
                add_bookmark(h, f'_Toc{toc_idx:04d}', toc_idx)
                toc_idx += 1
            i += 1; continue

        im = IMAGE_RE.match(line)
        if im:
            add_image(doc, im.group(1).strip(), im.group(2).strip(), img_base)
            i += 1; continue

        if line.strip() in ('---', '***', '___'):
            doc.add_paragraph().add_run().add_break()
            i += 1; continue

        bm = BULLET_RE.match(line)
        if bm:
            p = doc.add_paragraph(style='List Bullet')
            if len(bm.group(1)) >= 2:
                p.paragraph_format.left_indent = Inches(0.5)
            add_runs(p, bm.group(2)); i += 1; continue

        nm = NUM_RE.match(line)
        if nm:
            p = doc.add_paragraph(style='List Number')
            add_runs(p, nm.group(2)); i += 1; continue

        if line.strip() == '':
            i += 1; continue

        # plain paragraph (also strip leading '> ' blockquote marker)
        text = re.sub(r'^\s*>\s?', '', line)
        p = doc.add_paragraph()
        add_runs(p, text)
        i += 1

    if table_buf:
        flush_table(doc, table_buf)

    doc.save(docx_path)
    print(f'Wrote {docx_path}')


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(2)
    convert(sys.argv[1], sys.argv[2],
            sys.argv[3] if len(sys.argv) > 3 else None,
            sys.argv[4] if len(sys.argv) > 4 else None)
