/**
 * C1 Static Gate — convention C1: a machine identifier (uid or raw numeric FK id)
 * must NEVER appear as visible text.
 *
 * This spec statically scans every web/src/app/features/admin/**\/*.component.html
 * for {{ expr }} interpolations whose expression ends in a bare machine-id property
 * (ending in *Uid, *uid, or *Id).
 *
 * ALLOWLIST — the following are intentionally not flagged:
 *   - occurrences inside attribute values (="...", ='...') — not visible text
 *   - expressions ending in a function call, e.g. shortUid(...), productLabel(...),
 *     glAccountLabel(...), companyName()(uid), branchDisplay(...), stageName(...)
 *   - resolver-wrapped expressions: /\w+(Label|Name|Display)\s*\(/, shortUid(,
 *     stageName(, branchDisplay(
 *   - the ?? uid() route-fallback pattern (expression ends in uid())
 *   - boolean guard ternaries: editingTaskUid() ? '...' : '...'
 *     (expression is a ternary whose condition uses a uid but the output is string literals)
 *   - money, qty, counts, dates, status strings — none of those end in Uid/Id
 *
 * The test FAILS and lists every offending file:line:expression if violations are found.
 * It is expected to FIND violations on feat/c1-sweep (the fix agents run in parallel and
 * will clear them); the gate is green only when all fixes are landed.
 *
 * Run via:                 cd web && npm test
 * Single run (no watch):   cd web && npx ng test --watch=false
 * Direct vitest:           cd web && npx vitest run src/app/features/admin/c1-static.spec.ts
 */
import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

// Anchor to the admin features directory.
// process.cwd() in both the Angular/vitest runner and direct vitest is the web/ directory.
// We use a well-known relative path so the scan root is always accurate regardless
// of CJS (__dirname) vs ESM (import.meta.dirname) module context.
const ADMIN_HTML_GLOB_ROOT = path.resolve(process.cwd(), 'src/app/features/admin');

// A machine-id property name ends with one of:
//   *Uid  (camelCase foreign-key uid reference, e.g. supplierBillUid, glEntryUid)
//   *uid  (lowercase end, e.g. .uid as the entity's own key)
//    uid  (bare loop variable named uid, e.g. @for (uid of ...)  --> {{ uid }})
//   *Id   (raw numeric FK, e.g. customerId, fiscalPeriodId, branchId)
//
// Pattern matches the TRIMMED expression after stripping Angular pipes.
// We require a non-lowercase-letter char before the uid/Id suffix to avoid
// matching false positives like "fluid", "avoid", "solid".
const MACHINE_ID_TAIL = /(?<![a-z])(?:uid|[A-Za-z]+Uid|[A-Za-z]+Id)$/;

// Resolver calls whose result replaces the uid — these are ALLOWED:
//   shortUid(...)   — truncated display (audit log shortUid helper)
//   *Label(...)     — e.g. productLabel(row.parentProductUid), glAccountLabel(id)
//   *Name(...)      — e.g. branchName(uid), companyName()(uid)
//   *Display(...)   — e.g. branchDisplay(uid)
//   stageName(...)
//   branchDisplay(...)
const RESOLVER_CALL = /\w+(?:Label|Name|Display)\s*\(|shortUid\s*\(|stageName\s*\(|branchDisplay\s*\(/;

// The ?? uid() route-fallback (angular signal reading route param):
//   e.g.  entity()?.code ?? uid()
const ROUTE_FALLBACK = /\?\?\s*uid\s*\(\s*\)\s*$/;

// Boolean guard ternary: editingTaskUid() ? '...' : '...'
// If the whole expression is a ternary whose branches are string literals, it's
// a UI label toggle — not rendering the uid value.
const TERNARY_GUARD = /\?\s*['"][^'"]*['"]\s*:\s*['"][^'"]*['"]\s*$/;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Recursively collect all .component.html files under a directory. */
function collectHtmlFiles(dir: string): string[] {
  const results: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...collectHtmlFiles(full));
    } else if (entry.isFile() && entry.name.endsWith('.component.html')) {
      results.push(full);
    }
  }
  return results;
}

/**
 * Strip attribute string values from a line of HTML so that {{ }} occurrences
 * INSIDE attribute values (e.g. title="{{ uid }}", id="prefix_{{ uid }}") are
 * not mistaken for visible text interpolations.
 *
 * Replaces  ="..."  and  ='...'  with  =""  (handles single-line attribute values).
 * Multi-line attribute values are not a concern: all known {{ uid }} occurrences
 * in this codebase are single-line text content, not spread across lines.
 */
function stripAttributeValues(line: string): string {
  return line.replace(/=(?:"[^"]*"|'[^']*')/g, '=""');
}

/**
 * Strip Angular pipe transforms from an expression so the tail check is clean.
 *   "r.uid | slice:0:8"          -->  "r.uid"
 *   "val | number:'1.2-2'"       -->  "val"  (won't match anyway)
 * Strips from the first ' | ' (with spaces) to end.
 */
function stripPipes(expr: string): string {
  const pipeIdx = expr.search(/\s+\|\s+/);
  return pipeIdx >= 0 ? expr.slice(0, pipeIdx).trimEnd() : expr;
}

// ---------------------------------------------------------------------------
// Scanner
// ---------------------------------------------------------------------------

interface Offence {
  file: string;
  line: number;
  raw: string; // the full {{ expr }} token
  expr: string; // trimmed expression (post pipe-strip)
}

function scanFile(filePath: string): Offence[] {
  const content = fs.readFileSync(filePath, 'utf-8');
  const lines = content.split('\n');
  const offences: Offence[] = [];

  for (let i = 0; i < lines.length; i++) {
    // Remove attribute string values to avoid false-positives on
    // title="{{ uid }}", id="prefix_{{ uid }}", [binding]="...uid..."
    const stripped = stripAttributeValues(lines[i]);

    // Match every {{ expr }} on the stripped line
    const tokenRe = /\{\{([^}]*(?:\}[^}][^}]*)*)\}\}/g;
    let m: RegExpExecArray | null;

    while ((m = tokenRe.exec(stripped)) !== null) {
      const rawExpr = m[1]; // content between {{ and }}
      const expr = stripPipes(rawExpr.trim());

      if (!expr) continue;

      // Resolver-wrapped — human label is rendered, not the raw id
      if (RESOLVER_CALL.test(expr)) continue;

      // Route param fallback: entity()?.code ?? uid()
      if (ROUTE_FALLBACK.test(expr)) continue;

      // Guard ternary: someUid() ? 'label A' : 'label B'
      if (TERNARY_GUARD.test(expr)) continue;

      // Flag if the trimmed expression ends with a bare machine-id property name
      if (MACHINE_ID_TAIL.test(expr)) {
        offences.push({
          file: filePath,
          line: i + 1,
          raw: m[0],
          expr,
        });
      }
    }
  }

  return offences;
}

// ---------------------------------------------------------------------------
// Test
// ---------------------------------------------------------------------------

describe('C1 static gate — no raw machine-id visible as text', () => {
  it('finds no {{ *Uid }} / {{ *uid }} / {{ *Id }} interpolations rendered as visible text in admin templates', () => {
    if (!fs.existsSync(ADMIN_HTML_GLOB_ROOT)) {
      throw new Error(`Admin HTML root not found: ${ADMIN_HTML_GLOB_ROOT}`);
    }

    const htmlFiles = collectHtmlFiles(ADMIN_HTML_GLOB_ROOT);
    expect(htmlFiles.length).toBeGreaterThan(0); // sanity: confirm files were found

    const allOffences: Offence[] = [];
    for (const file of htmlFiles) {
      allOffences.push(...scanFile(file));
    }

    if (allOffences.length > 0) {
      const relRoot = path.resolve(ADMIN_HTML_GLOB_ROOT, '../../../../'); // = web/
      const lines = allOffences.map(
        (o) => `  ${path.relative(relRoot, o.file)}:${o.line}  ${o.raw.trim()}`,
      );
      const message = [
        '',
        `C1 VIOLATIONS (${allOffences.length} offending interpolation(s)):`,
        ...lines,
        '',
        'Rule: a machine identifier (uid / FK id) must NEVER appear as visible text.',
        'Fix: replace with a human name/number/code, link with the uid only in the URL path,',
        'or drop the field if it has no user value.',
        'See docs/testing/ISSUES.md ISSUE-014 and PROJECT-CONVENTIONS.md §C1.',
      ].join('\n');
      expect.fail(message);
    }
  });
});
