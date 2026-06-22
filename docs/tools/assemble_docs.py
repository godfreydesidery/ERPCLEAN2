#!/usr/bin/env python
"""Assemble ordered chapter .md files into a master .md (title + contents + chapters)
and a body-only .md (chapters only, for clean .docx conversion where the converter
supplies its own cover + TOC).

Usage: python assemble_docs.py <chapters_dir> <out_full.md> <out_body.md> "<Title>" [--strip-images]

With --strip-images, block-level Markdown image lines (`![alt](path)`) are removed,
producing the text-only manual that is committed to git (the screenshots and the
image-rich build are kept local-only — see build-manual.sh and .gitignore).
"""
import sys, os, re, glob

# A block-level image on its own line: ![alt](path). Drop the whole line.
IMAGE_LINE_RE = re.compile(r'(?m)^[ \t]*!\[[^\]]*\]\([^)]*\)[ \t]*\n?')

def first_h1(path):
    for line in open(path, encoding='utf-8'):
        m = re.match(r'^#\s+(.*)$', line)
        if m:
            return m.group(1).strip()
    return os.path.basename(path)

def main(chapters_dir, out_full, out_body, title, strip_images=False):
    files = sorted(glob.glob(os.path.join(chapters_dir, '*.md')))
    files = [f for f in files if not f.endswith('README.md')]
    titles = [first_h1(f) for f in files]

    # body = chapters concatenated, page-break hint (---) between them
    body_parts = []
    for i, f in enumerate(files):
        txt = open(f, encoding='utf-8').read().rstrip()
        body_parts.append(txt)
    body = '\n\n---\n\n'.join(body_parts) + '\n'

    if strip_images:
        body = IMAGE_LINE_RE.sub('', body)
        body = re.sub(r'\n{3,}', '\n\n', body)  # collapse blanks left by removed images

    # full = title + metadata + contents + body
    # Chapters reference screenshots relative to themselves (`images/...`, i.e. relative
    # to docs/user-manual/). The monolith lives one level up in docs/, so rewrite those
    # links to `user-manual/images/...` for the full .md to render correctly on GitHub.
    # (The body-only file keeps `images/...`; md2docx resolves it via MD2DOCX_IMG_BASE.)
    # No-op when --strip-images already removed every image link.
    full_body = body.replace('](images/', '](user-manual/images/')
    contents = '\n'.join(f'{i+1}. {t}' for i, t in enumerate(titles))
    header = (
        f'# {title}\n\n'
        f'_ERPCLEAN2 — modular-monolith ERP (Spring Boot + Angular + PostgreSQL). '
        f'Generated from the live codebase + the verified test-case suite._\n\n'
        f'## Contents\n\n{contents}\n\n---\n\n'
    )
    with open(out_full, 'w', encoding='utf-8') as fh:
        fh.write(header + full_body)
    with open(out_body, 'w', encoding='utf-8') as fh:
        fh.write(body)
    kind = 'text-only' if strip_images else 'with images'
    print(f'{out_full}: {len(files)} chapters, {len(header)+len(body)} chars ({kind})')
    print(f'{out_body}: body-only for docx ({kind})')

if __name__ == '__main__':
    strip = '--strip-images' in sys.argv
    pos = [a for a in sys.argv[1:] if a != '--strip-images']
    main(pos[0], pos[1], pos[2], pos[3], strip_images=strip)
