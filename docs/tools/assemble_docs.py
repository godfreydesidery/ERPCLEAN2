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

HEADING_ANY_RE = re.compile(r'^(#{1,6})\s+(.*)$')

def _slug(text, seen):
    """GitHub-flavoured heading anchor: lowercase, drop punctuation (keep word chars,
    spaces, hyphens), spaces→hyphens, and disambiguate duplicates with a -N suffix —
    matching how GitHub anchors headings so every TOC link resolves in the rendered .md."""
    s = re.sub(r'[^\w\s-]', '', text.strip().lower()).replace(' ', '-')
    n = seen.get(s, 0)
    seen[s] = n + 1
    return s if n == 0 else f'{s}-{n}'

def build_toc(body):
    """A navigable table of contents over the assembled body: numbered chapters (H1)
    each followed by indented, linked section entries (H2). Every heading (H1–H6) is
    fed through the slugger in document order so the duplicate-suffix counter matches
    GitHub's, keeping the H2 links correct even when section titles repeat across chapters."""
    seen, out, chapter = {}, [], 0
    for line in body.split('\n'):
        m = HEADING_ANY_RE.match(line)
        if not m:
            continue
        level, text = len(m.group(1)), m.group(2).strip()
        slug = _slug(text, seen)  # advance the counter for EVERY heading, emit only H1/H2
        if level == 1:
            chapter += 1
            out.append(f'{chapter}. [{text}](#{slug})')
        elif level == 2:
            out.append(f'    - [{text}](#{slug})')
    return '\n'.join(out)

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
    # Deep, clickable TOC (chapters + their sections) built from the assembled body so it
    # always reflects the real headings; slugs match GitHub's anchors. `titles` retained
    # above for the per-chapter count in the build log.
    contents = build_toc(body)
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
