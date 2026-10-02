#!/usr/bin/env bash
# Rebuild the compiled OrbixPOS user manual from the per-chapter sources.
# Run after editing any docs/pos-user-manual/NN-*.md chapter, then commit all
# three (the chapters + POS-USER-MANUAL.md + POS-USER-MANUAL.docx) together so
# they never drift.
#
#   bash docs/tools/build-pos-manual.sh
#
# Requires: python3 with python-docx (pip install python-docx).
set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

TITLE="OrbixPOS — User Manual"
# Client-facing: this docx ships inside every OrbixPOS zip, so no stack or platform jargon.
SUBTITLE="The till app for OrbixERP — for cashiers, supervisors and managers"
BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

python docs/tools/assemble_docs.py docs/pos-user-manual docs/POS-USER-MANUAL.md "$BODY" "$TITLE"
python docs/tools/md2docx.py "$BODY" docs/POS-USER-MANUAL.docx "$TITLE" "$SUBTITLE"

echo "Rebuilt docs/POS-USER-MANUAL.md and docs/POS-USER-MANUAL.docx from docs/pos-user-manual/."
