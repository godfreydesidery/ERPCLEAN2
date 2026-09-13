#!/usr/bin/env bash
# Rebuild the MS Word copies of the two runbooks from their Markdown sources.
# Run this after editing either runbook, and commit the .md and .docx together so they
# never drift.
#
#   bash docs/tools/build-runbooks.sh
#
# Produces:
#   docs/TECHNICAL-RUNBOOK.docx     from docs/TECHNICAL-RUNBOOK.md
#   docs/OPERATIONAL-RUNBOOK.docx   from docs/OPERATIONAL-RUNBOOK.md
#
# The Markdown "## Contents" list is stripped before conversion: md2docx.py generates its
# own clickable Table of Contents on page 2 (every H1 and H2, hyperlinked to bookmarks),
# so keeping the hand-written one would put two tables of contents in the same document.
#
# Requires: python3 with python-docx (pip install python-docx).
set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

SUBTITLE="OrbixERP — Spring Boot · Angular · PostgreSQL"

strip_contents() {
    # Drop the block from the "## Contents" heading up to and including the '---' that
    # closes it. Everything else passes through untouched.
    python - "$1" "$2" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
out, skipping = [], False
for line in open(src, encoding='utf-8').read().split('\n'):
    if not skipping and line.strip() == '## Contents':
        skipping = True
        continue
    if skipping:
        if line.strip() == '---':
            skipping = False
        continue
    out.append(line)
open(dst, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
PY
}

build() {
    local md="$1" docx="$2" title="$3" body
    body="$(mktemp)"
    trap 'rm -f "$body"' RETURN
    strip_contents "$md" "$body"
    python docs/tools/md2docx.py "$body" "$docx" "$title" "$SUBTITLE"
}

build docs/TECHNICAL-RUNBOOK.md   docs/TECHNICAL-RUNBOOK.docx   "OrbixERP — Technical Runbook"
build docs/OPERATIONAL-RUNBOOK.md docs/OPERATIONAL-RUNBOOK.docx "OrbixERP — Operational Runbook"

echo "Rebuilt: docs/TECHNICAL-RUNBOOK.docx + docs/OPERATIONAL-RUNBOOK.docx"
