#!/usr/bin/env bash
# Rebuild the compiled user manual from the per-chapter sources.
# Run after editing any docs/user-manual/NN-*.md chapter.
#
#   bash docs/tools/build-manual.sh
#
# Produces TWO versions:
#   * docs/USER-MANUAL.md / .docx              — text-only, COMMITTED to git.
#   * docs/USER-MANUAL.with-images.md / .docx  — embeds the screenshots, LOCAL-ONLY
#                                                (gitignored, like docs/user-manual/images/).
# Keeping the heavy image-rich build + the PNGs out of git avoids large files in the repo.
# Commit the text-only pair (chapters + USER-MANUAL.md + USER-MANUAL.docx) together so they
# never drift; (re)generate the with-images pair locally — see the screenshot note in
# docs/tools/ / web/e2e/capture-screenshots.mjs.
#
# Requires: python3 with python-docx (pip install python-docx).
set -euo pipefail
cd "$(dirname "$0")/../.."   # repo root

TITLE="ERPCLEAN2 — User Manual"
SUBTITLE="Modular-monolith ERP — Spring Boot · Angular · PostgreSQL"
BODY_TXT="$(mktemp)"
BODY_IMG="$(mktemp)"
trap 'rm -f "$BODY_TXT" "$BODY_IMG"' EXIT

# 1) Text-only (tracked) — strip image lines so no screenshots are embedded or linked.
python docs/tools/assemble_docs.py docs/user-manual docs/USER-MANUAL.md "$BODY_TXT" "$TITLE" --strip-images
python docs/tools/md2docx.py "$BODY_TXT" docs/USER-MANUAL.docx "$TITLE" "$SUBTITLE"

# 2) With-images (local-only) — keep image links; anchor them at docs/user-manual so
#    md2docx embeds the PNGs into the .docx. Skipped if the images directory is absent.
if [ -d docs/user-manual/images ]; then
  python docs/tools/assemble_docs.py docs/user-manual docs/USER-MANUAL.with-images.md "$BODY_IMG" "$TITLE"
  MD2DOCX_IMG_BASE="$(pwd)/docs/user-manual" \
    python docs/tools/md2docx.py "$BODY_IMG" docs/USER-MANUAL.with-images.docx "$TITLE" "$SUBTITLE"
  echo "Rebuilt (tracked, text-only):  docs/USER-MANUAL.md + docs/USER-MANUAL.docx"
  echo "Rebuilt (local-only, images):  docs/USER-MANUAL.with-images.md + docs/USER-MANUAL.with-images.docx"
else
  echo "Rebuilt (tracked, text-only):  docs/USER-MANUAL.md + docs/USER-MANUAL.docx"
  echo "Skipped with-images build: docs/user-manual/images/ not present (run web/e2e/capture-screenshots.mjs to (re)create it)."
fi
