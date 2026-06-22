#!/usr/bin/env python
"""Assemble ordered chapter .md files into a master .md (title + contents + chapters)
and a body-only .md (chapters only, for clean .docx conversion where the converter
supplies its own cover + TOC).

Usage: python assemble_docs.py <chapters_dir> <out_full.md> <out_body.md> "<Title>"
"""
import sys, os, re, glob

def first_h1(path):
    for line in open(path, encoding='utf-8'):
        m = re.match(r'^#\s+(.*)$', line)
        if m:
            return m.group(1).strip()
    return os.path.basename(path)

def main(chapters_dir, out_full, out_body, title):
    files = sorted(glob.glob(os.path.join(chapters_dir, '*.md')))
    files = [f for f in files if not f.endswith('README.md')]
    titles = [first_h1(f) for f in files]

    # body = chapters concatenated, page-break hint (---) between them
    body_parts = []
    for i, f in enumerate(files):
        txt = open(f, encoding='utf-8').read().rstrip()
        body_parts.append(txt)
    body = '\n\n---\n\n'.join(body_parts) + '\n'

    # full = title + metadata + contents + body
    # Chapters reference screenshots relative to themselves (`images/...`, i.e. relative
    # to docs/user-manual/). The monolith lives one level up in docs/, so rewrite those
    # links to `user-manual/images/...` for the full .md to render correctly on GitHub.
    # (The body-only file keeps `images/...`; md2docx resolves it via MD2DOCX_IMG_BASE.)
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
    print(f'{out_full}: {len(files)} chapters, {len(header)+len(body)} chars')
    print(f'{out_body}: body-only for docx')

if __name__ == '__main__':
    main(*sys.argv[1:5])
