#!/usr/bin/env node
/**
 * C1 static gate — convention C1: a machine identifier (uid or raw numeric FK id)
 * must NEVER appear as visible text. It belongs only in the URL path / routerLink.
 *
 * Statically scans every web/src/app/features/admin/ **\/*.component.html for
 * {{ expr }} interpolations whose expression ends in a bare machine-id property
 * (ending in *Uid, *uid, bare `uid`, or *Id) and that are NOT resolver-wrapped.
 *
 * Runs as a plain Node script (no test-runner / type deps) so it is portable across
 * `ng test`, vitest and CI:
 *     cd web && npm run c1
 * Exits 1 (with the offender list) if any violation is found, 0 if clean.
 *
 * See docs/testing/ISSUES.md ISSUE-014 + PROJECT-CONVENTIONS.md (Identity / C1).
 */
import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ADMIN_ROOT = path.join(WEB_ROOT, 'src/app/features/admin');

// A machine-id property name ends with: bare `uid`, `*Uid`, or `*Id` (raw numeric FK).
// The (?<![a-z]) guard avoids false matches like "fluid"/"avoid"/"solid"/"valid".
const MACHINE_ID_TAIL = /(?<![a-z])(?:uid|[A-Za-z]+Uid|[A-Za-z]+Id)$/;
// Resolver calls whose RESULT is a human label — allowed:
const RESOLVER_CALL = /\w+(?:Label|Name|Display)\s*\(|shortUid\s*\(|stageName\s*\(|branchDisplay\s*\(/;
// Route-param fallback: `entity()?.code ?? uid()`
const ROUTE_FALLBACK = /\?\?\s*uid\s*\(\s*\)\s*$/;
// Guard ternary rendering string literals: `editingTaskUid() ? 'Edit' : 'New'`
const TERNARY_GUARD = /\?\s*['"][^'"]*['"]\s*:\s*['"][^'"]*['"]\s*$/;

function collectHtml(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...collectHtml(full));
    else if (e.isFile() && e.name.endsWith('.component.html')) out.push(full);
  }
  return out;
}

const stripAttrValues = (line) => line.replace(/=(?:"[^"]*"|'[^']*')/g, '=""');
const stripPipes = (expr) => {
  const i = expr.search(/\s+\|\s+/);
  return i >= 0 ? expr.slice(0, i).trimEnd() : expr;
};

function scan(file) {
  const lines = fs.readFileSync(file, 'utf-8').split('\n');
  const offences = [];
  for (let i = 0; i < lines.length; i++) {
    const stripped = stripAttrValues(lines[i]);
    const tokenRe = /\{\{([^}]*(?:\}[^}][^}]*)*)\}\}/g;
    let m;
    while ((m = tokenRe.exec(stripped)) !== null) {
      const expr = stripPipes(m[1].trim());
      if (!expr) continue;
      if (RESOLVER_CALL.test(expr) || ROUTE_FALLBACK.test(expr) || TERNARY_GUARD.test(expr)) continue;
      if (MACHINE_ID_TAIL.test(expr)) {
        offences.push({ file: path.relative(WEB_ROOT, file), line: i + 1, raw: m[0].trim() });
      }
    }
  }
  return offences;
}

if (!fs.existsSync(ADMIN_ROOT)) {
  console.error(`C1 gate: admin root not found: ${ADMIN_ROOT}`);
  process.exit(2);
}
const files = collectHtml(ADMIN_ROOT);
const offences = files.flatMap(scan);

if (offences.length > 0) {
  console.error(`\nC1 VIOLATIONS (${offences.length} offending interpolation(s)):`);
  for (const o of offences) console.error(`  ${o.file}:${o.line}  ${o.raw}`);
  console.error(
    '\nRule: a machine identifier (uid / FK id) must NEVER appear as visible text.\n' +
      'Fix: render a human name/number/code, link with the uid only in the URL path, or drop the field.\n' +
      'See docs/testing/ISSUES.md ISSUE-014.',
  );
  process.exit(1);
}
console.log(`C1 gate: clean — scanned ${files.length} admin templates, 0 raw machine-id renders.`);
