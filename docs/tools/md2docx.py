#!/usr/bin/env python
"""
md2docx.py — convert a (controlled-subset) Markdown file to a styled MS Word .docx.

Supports the subset the ERPCLEAN2 docs use: ATX headings (#..####), '- ' bullets,
'1. ' numbered lists, GitHub pipe tables, **bold**, `inline code`, ``` fenced code,
'---' horizontal rules, and a leading cover (first H1) + an auto Table of Contents field.

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


def add_toc(doc):
    p = doc.add_paragraph()
    run = p.add_run()
    fld = OxmlElement('w:fldSimple')
    fld.set(qn('w:instr'), r'TOC \o "1-3" \h \z \u')
    note = OxmlElement('w:r')
    t = OxmlElement('w:t')
    t.text = "Updating… (if this line persists, right-click → Update Field)."
    note.append(t)
    fld.append(note)
    p._p.append(fld)


def set_update_fields_on_open(doc):
    """Ask Word to refresh all fields (including the TOC) when the file is opened, so the
    Table of Contents populates automatically instead of needing a manual right-click →
    Update Field. Word shows a one-time 'update fields?' prompt and then fills the TOC."""
    settings = doc.settings.element
    el = settings.find(qn('w:updateFields'))
    if el is None:
        el = OxmlElement('w:updateFields')
        settings.append(el)
    el.set(qn('w:val'), 'true')


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
        # TOC
        th = doc.add_heading('Contents', level=1)
        add_toc(doc)
        doc.add_page_break()

    i = 0
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
            doc.add_heading(m.group(2).strip(), level=min(level, 4))
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

    if title:  # a cover+TOC was emitted → have Word build the TOC on open
        set_update_fields_on_open(doc)
    doc.save(docx_path)
    print(f'Wrote {docx_path}')


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(2)
    convert(sys.argv[1], sys.argv[2],
            sys.argv[3] if len(sys.argv) > 3 else None,
            sys.argv[4] if len(sys.argv) > 4 else None)
