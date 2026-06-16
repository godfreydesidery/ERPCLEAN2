#!/usr/bin/env bash
# check-migrations.sh — Flyway migration hygiene gate (CI + local).
#
# Enforces two rules that protect a DURABLE (non-ephemeral) database. Neither is catchable by the
# normal "build the schema from V1 on an empty DB" test, so they need their own check at PR time.
#
#   1. VERSION COLLISION — no two versioned migrations (V<n>__*.sql) share a version. Two feature
#      branches both authoring V84__ merge to a duplicate version => Flyway fails at startup on
#      EVERY environment. (Scans the working tree; no git history needed.)
#
#   2. IMMUTABILITY — a versioned migration already present on the base branch must NOT be modified,
#      renamed or deleted. Editing an applied migration changes its checksum; with
#      validate-on-migrate the app refuses to boot on staging/prod while fresh CI/dev stays green.
#      Repeatable migrations (R__*.sql) are EXEMPT — they are designed to change and re-run.
#      (Needs a base ref to diff against; pass it via BASE, e.g. BASE=origin/develop.)
#
# Usage:
#   scripts/check-migrations.sh                    # rule 1 only
#   BASE=origin/develop scripts/check-migrations.sh   # rules 1 + 2
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
MIG_DIR="backend/src/main/resources/db/migration"
fail=0

# Extract the Flyway version from a Vx__name.sql basename, normalising '_' separators to '.'
# so 1_1 and 1.1 compare equal (Flyway treats them the same).
version_of() { local b="${1#V}"; b="${b%%__*}"; printf '%s' "$b" | tr '_' '.'; }

# ---------------------------------------------------------------------------
# Rule 1: version collision
# ---------------------------------------------------------------------------
echo "==> Rule 1: duplicate Flyway versions in $MIG_DIR"
all_versions=""
for f in "$MIG_DIR"/V*__*.sql; do
  [ -e "$f" ] || continue
  all_versions="$all_versions$(version_of "$(basename "$f")")
"
done
dupe_versions="$(printf '%s' "$all_versions" | sed '/^$/d' | sort | uniq -d)"
if [ -n "$dupe_versions" ]; then
  while IFS= read -r dv; do
    [ -n "$dv" ] || continue
    echo "ERROR: Flyway version '$dv' is used by more than one migration:"
    for f in "$MIG_DIR"/V*__*.sql; do
      [ -e "$f" ] || continue
      [ "$(version_of "$(basename "$f")")" = "$dv" ] && echo "    $(basename "$f")"
    done
  done <<< "$dupe_versions"
  fail=1
else
  echo "    OK — every versioned migration has a unique version."
fi

# ---------------------------------------------------------------------------
# Rule 2: immutability vs the base branch
# ---------------------------------------------------------------------------
if [ -n "${BASE:-}" ]; then
  echo "==> Rule 2: migration immutability vs $BASE"
  # Three-dot: changes on HEAD since the merge-base with BASE.
  changed="$(git diff --name-status --find-renames "$BASE...HEAD" -- "$MIG_DIR" || true)"
  violations="$(printf '%s\n' "$changed" | awk '
    {
      status=$1; path=$2;                 # for renames (Rxxx) $2 is the OLD path
      n=split(path, p, "/"); name=p[n];
      if (name ~ /^R__/) next;            # repeatable migrations may legitimately change
      if (status ~ /^M/) print "modified  " path;
      else if (status ~ /^D/) print "deleted   " path;
      else if (status ~ /^R/) print "renamed   " path " -> " $3;
    }')"
  if [ -n "$violations" ]; then
    echo "ERROR: applied versioned migration(s) changed (immutability violation):"
    printf '%s\n' "$violations" | sed 's/^/    /'
    echo "    -> Never edit/rename/delete an applied migration. Add a NEW Vn migration that supersedes it."
    echo "    -> If this data is convergent reference data (permissions, grants), put it in a Repeatable R__ migration."
    fail=1
  else
    echo "    OK — no versioned migration was modified, renamed or deleted."
  fi
else
  echo "==> Rule 2 skipped (set BASE=origin/<branch> to enable immutability check)."
fi

exit $fail
