#!/usr/bin/env bash
# Rebuild the compiled user manual from the per-chapter sources.
# Run after editing any docs/user-manual/NN-*.md chapter, then commit all three
# (the chapters + USER-MANUAL.md + USER-MANUAL.docx) together so they never drift.
#
#   bash docs/tools/build-manual.sh
#
# Requires: python3 with python-docx (pip install python-docx).
set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

TITLE="ERPCLEAN2 — User Manual"
SUBTITLE="Modular-monolith ERP — Spring Boot · Angular · PostgreSQL"
BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

python docs/tools/assemble_docs.py docs/user-manual docs/USER-MANUAL.md "$BODY" "$TITLE"
# Anchor chapter-relative `images/...` screenshot links at docs/user-manual so md2docx
# can embed them into the .docx regardless of the (temp) body file's location.
MD2DOCX_IMG_BASE="$(pwd)/docs/user-manual" \
  python docs/tools/md2docx.py "$BODY" docs/USER-MANUAL.docx "$TITLE" "$SUBTITLE"

echo "Rebuilt docs/USER-MANUAL.md and docs/USER-MANUAL.docx from the 12 chapters."
