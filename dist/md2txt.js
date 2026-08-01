#!/usr/bin/env node
'use strict';
/**
 * Markdown -> plain text, for the .txt copies of the client guides.
 * MAINTAINER TOOL - runs at release time, never shipped to a client.
 *
 *   node md2txt.js <directory>
 *
 * Converts every *.md in the directory to a sibling *.txt. The guides are authored once,
 * in Markdown; the .txt copies exist so a client with no Markdown viewer can double-click
 * a file and read it. They are GENERATED, never hand-edited - editing the .md is the only
 * way to change them, so the two can never drift apart.
 *
 * There is one implementation, invoked identically by build-release.sh and
 * build-release.ps1 (via `docker run node:20-alpine`), so the Linux and Windows release
 * paths cannot produce different output.
 *
 * Two deliberate output choices, both for the audience this file exists to serve:
 *
 *   CRLF line endings. Notepad on Windows Server 2019 and older renders an LF-only file
 *   as one endless line. On Linux the worst case is a visible ^M in `less`, which is ugly
 *   but readable - the safer failure by a wide margin.
 *
 *   ASCII only. A UTF-8 file with no byte-order mark is mis-decoded by older Windows
 *   editors, and a BOM leaves a stray character for Unix tools. Transliterating instead
 *   sidesteps the encoding question altogether.
 */

const fs = require('fs');
const path = require('path');

const RULE_WIDTH = 78;   // width of a --- horizontal rule
const TABLE_MAX  = 78;   // wider than this and a table is rendered as labelled blocks
const WRAP       = 78;   // prose is wrapped here; Notepad does not word-wrap by default

// ---------------------------------------------------------------------------
// Wrapping
//
// Applied to prose, blockquotes and the values in labelled blocks. NEVER to code blocks
// (wrapping a shell command corrupts it), aligned tables (it would destroy the columns) or
// headings. A single word longer than the limit - typically a URL - is left over-long
// rather than broken, because a broken URL cannot be copied and pasted.
// ---------------------------------------------------------------------------
function wrap(line, continuationIndent) {
  if (line.length <= WRAP) return [line];

  const leading = line.match(/^\s*/)[0];
  const rest = line.slice(leading.length);

  // A list item's continuation lines hang under the text, not under the bullet.
  const bullet = rest.match(/^([-*]\s+|\d+\.\s+)/);
  const hang = continuationIndent !== undefined
    ? continuationIndent
    : leading + ' '.repeat(bullet ? bullet[0].length : 0);

  const words = rest.split(/\s+/).filter(Boolean);
  const out = [];
  let current = leading;
  let isFirst = true;

  for (const word of words) {
    const prefix = isFirst && current === leading ? '' : ' ';
    if (current === leading || current === hang) {
      current += word;
    } else if ((current + prefix + word).length <= WRAP) {
      current += prefix + word;
    } else {
      out.push(current);
      current = hang + word;
    }
    isFirst = false;
  }
  if (current.trim() !== '') out.push(current);
  return out;
}

// ---------------------------------------------------------------------------
// ASCII transliteration
// ---------------------------------------------------------------------------
const SUBSTITUTIONS = [
  [/[—–]/g, '-'],          // em dash, en dash
  [/→/g, '->'],                 // right arrow
  [/←/g, '<-'],
  [/[‘’]/g, "'"],          // curly single quotes
  [/[“”]/g, '"'],          // curly double quotes
  [/…/g, '...'],                // ellipsis
  [/ /g, ' '],                  // non-breaking space
  [/[✓✔]/g, 'v'],          // check marks
  [/[✗✘]/g, 'x'],
  [/⚠️?/g, '!'],           // warning sign
  [/•/g, '-'],                  // bullet
];

const unknown = new Set();

function toAscii(s) {
  for (const [pattern, replacement] of SUBSTITUTIONS) s = s.replace(pattern, replacement);
  // Anything still non-ASCII is a character nobody planned for. Substitute it so the build
  // does not break, but record it so the maintainer is told rather than shipping a '?'.
  return s.replace(/[^\x00-\x7F]/g, (ch) => { unknown.add(ch); return '?'; });
}

// ---------------------------------------------------------------------------
// Inline markup
// ---------------------------------------------------------------------------
function inline(s) {
  // [text](url). A link to another guide or an anchor carries no useful URL in plain text -
  // the link text already names the file. An external URL does, so it is kept in brackets.
  s = s.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (_m, text, url) =>
    /^https?:/i.test(url) ? `${text} (${url})` : text);

  s = s.replace(/`([^`]+)`/g, '$1');       // inline code
  s = s.replace(/\*\*([^*]+)\*\*/g, '$1'); // bold

  // Single-asterisk emphasis, with guards on both ends: the opening * must follow a space
  // or an opening bracket and be followed by a non-space, and the closing * must be followed
  // by punctuation, a space or the end of the line. Without those guards a shell glob such
  // as *.md or a wildcard in a command would be silently eaten.
  s = s.replace(/(^|[\s([])\*([^*\s][^*]*?)\*(?=[\s.,;:)\]!?]|$)/g, '$1$2');
  s = s.replace(/(^|[\s([])_([^_\s][^_]*?)_(?=[\s.,;:)\]!?]|$)/g, '$1$2');

  // Cross-references must point at the .txt copies, or a client following them lands on a
  // file their machine cannot open - the exact problem these copies exist to solve.
  s = s.replace(/\.md\b/g, '.txt');
  return s;
}

// ---------------------------------------------------------------------------
// Tables
// ---------------------------------------------------------------------------
const isSeparatorRow = (cells) => cells.every((c) => /^:?-{2,}:?$/.test(c));

function splitRow(line) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => inline(c.trim()));
}

function renderTable(lines) {
  const rows = lines.map(splitRow).filter((cells) => !isSeparatorRow(cells));
  if (rows.length === 0) return [];

  const columns = Math.max(...rows.map((r) => r.length));
  rows.forEach((r) => { while (r.length < columns) r.push(''); });

  const widths = [];
  for (let c = 0; c < columns; c++) widths[c] = Math.max(...rows.map((r) => r[c].length));
  const totalWidth = widths.reduce((a, b) => a + b, 0) + 2 * (columns - 1) + 2;

  // A table whose columns fit reads best aligned. One that does not would produce lines
  // hundreds of characters wide, so each row becomes a labelled block instead - far easier
  // to read in a fixed-width window than a table that wraps unpredictably.
  if (totalWidth > TABLE_MAX && columns > 1) return renderTableAsBlocks(rows);

  const out = [];
  const header = rows[0];
  out.push('  ' + header.map((cell, c) => cell.padEnd(widths[c])).join('  ').trimEnd());
  out.push('  ' + widths.map((w) => '-'.repeat(w)).join('  '));
  for (const row of rows.slice(1)) {
    out.push('  ' + row.map((cell, c) => cell.padEnd(widths[c])).join('  ').trimEnd());
  }
  return out;
}

function renderTableAsBlocks(rows) {
  const headers = rows[0];
  const labelled = headers.some((h) => h !== '');
  const out = [];
  for (const row of rows.slice(1)) {
    for (let c = 0; c < row.length; c++) {
      if (row[c] === '') continue;
      const text = labelled && headers[c] ? `  ${headers[c]}: ${row[c]}` : `  ${row[c]}`;
      out.push(...wrap(text, '      '));
    }
    out.push('');
  }
  if (out[out.length - 1] === '') out.pop();
  return out;
}

// ---------------------------------------------------------------------------
// Document
// ---------------------------------------------------------------------------
function convert(markdown) {
  const lines = toAscii(markdown).replace(/\r\n?/g, '\n').split('\n');
  const out = [];

  let inFence = false;
  let tableBuffer = [];

  // Prose is accumulated a paragraph at a time and wrapped as a whole, never line by line.
  // The source is already hand-wrapped at about 95 columns; re-wrapping each of those lines
  // on its own would strand the overflow as orphan words ("...that\nstays\nswitched on").
  // Markdown treats a single newline as a soft break anyway, so joining first and re-flowing
  // is the faithful conversion, not a liberty.
  let para = null;   // { indent, prefix, text }

  // inline() runs HERE, on the joined paragraph - never on the individual source lines.
  // Markdown emphasis may straddle a soft line break ("a **new certificate\nevery 12 hours**"),
  // and matching line by line would find an opening marker with no closing one and leave both
  // in the output. Joining first is the only way to see the whole construct.
  const flushPara = () => {
    if (!para) return;
    const hang = para.indent + ' '.repeat(para.prefix.length);
    out.push(...wrap((para.indent + para.prefix + inline(para.text)).trimEnd(), hang));
    para = null;
  };

  const flushTable = () => {
    if (tableBuffer.length) { out.push(...renderTable(tableBuffer)); tableBuffer = []; }
  };

  for (const raw of lines) {
    // A code fence may sit INSIDE a blockquote, where every line carries a "> " prefix.
    // Detecting the fence on the raw line would miss those, the quote branch below would
    // swallow the ``` markers as ordinary text, and the backticks would reach the output.
    // Stripping the marker first handles quoted and unquoted fences through one path.
    const deQuoted = raw.replace(/^\s*>\s?/, '');

    // Fenced code: emitted verbatim and indented, with no inline processing at all -
    // stripping backticks or asterisks out of a shell command would corrupt it.
    if (/^\s*```/.test(deQuoted)) {
      flushPara(); flushTable();
      inFence = !inFence;
      continue;
    }
    if (inFence) { out.push(deQuoted.trim() === '' ? '' : '      ' + deQuoted); continue; }

    if (/^\s*\|/.test(raw)) { flushPara(); tableBuffer.push(raw); continue; }
    flushTable();

    if (/^\s*$/.test(raw)) { flushPara(); out.push(''); continue; }

    const heading = raw.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      flushPara();
      const level = heading[1].length;
      const text = inline(heading[2]).replace(/\s*#+\s*$/, '');
      if (out.length && out[out.length - 1] !== '') out.push('');
      if (level === 1) {
        const bar = '='.repeat(Math.min(text.length, RULE_WIDTH));
        out.push(bar, text.toUpperCase(), bar);
      } else if (level === 2) {
        out.push(text, '='.repeat(Math.min(text.length, RULE_WIDTH)));
      } else if (level === 3) {
        out.push(text, '-'.repeat(Math.min(text.length, RULE_WIDTH)));
      } else {
        out.push(text + ':');
      }
      out.push('');
      continue;
    }

    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(raw)) {
      flushPara();
      out.push('', '-'.repeat(RULE_WIDTH), '');
      continue;
    }

    // Blockquotes carry the warnings. The '>' marker is kept so a callout still reads as a
    // callout rather than dissolving into the surrounding paragraph.
    const quote = raw.match(/^\s*>\s?(.*)$/);
    if (quote) {
      const text = quote[1].trim();          // inline() deferred to flushPara
      if (text === '') { flushPara(); continue; }
      if (para && para.prefix === '> ') para.text += ' ' + text;
      else { flushPara(); para = { indent: '  ', prefix: '> ', text }; }
      continue;
    }
    if (para && para.prefix === '> ') flushPara();   // quote block ended

    // Everything else is prose. A list marker starts a new block and sets the hanging indent
    // its continuation lines wrap to; anything else continues the paragraph in progress.
    const body = raw.match(/^(\s*)(.*)$/);
    const indent = '  ' + body[1];
    const content = body[2].trim();          // inline() deferred to flushPara
    const bullet = content.match(/^([-*+]\s+|\d+[.)]\s+)/);

    if (bullet) {
      flushPara();
      para = { indent, prefix: bullet[0], text: content.slice(bullet[0].length) };
    } else if (para) {
      para.text += ' ' + content;
    } else {
      para = { indent, prefix: '', text: content };
    }
  }
  flushPara();
  flushTable();

  // Collapse runs of blank lines and trim the ends.
  const collapsed = [];
  for (const line of out) {
    if (line === '' && collapsed[collapsed.length - 1] === '') continue;
    collapsed.push(line);
  }
  while (collapsed.length && collapsed[0] === '') collapsed.shift();
  while (collapsed.length && collapsed[collapsed.length - 1] === '') collapsed.pop();

  return collapsed.join('\r\n') + '\r\n';
}

// ---------------------------------------------------------------------------
const dir = process.argv[2];
if (!dir) { console.error('usage: node md2txt.js <directory>'); process.exit(2); }

const sources = fs.readdirSync(dir).filter((f) => f.toLowerCase().endsWith('.md')).sort();
if (sources.length === 0) { console.error(`no .md files found in ${dir}`); process.exit(1); }

for (const name of sources) {
  const target = name.replace(/\.md$/i, '.txt');
  const text = convert(fs.readFileSync(path.join(dir, name), 'utf8'));

  if (/[^\x00-\x7F]/.test(text)) {
    console.error(`ERROR: ${target} still contains non-ASCII characters after conversion.`);
    process.exit(1);
  }
  fs.writeFileSync(path.join(dir, target), text, 'latin1');
  console.log(`    ${name} -> ${target}`);
}

if (unknown.size) {
  console.error(`WARNING: no ASCII substitution defined for: ${[...unknown].join(' ')}`);
  console.error('         They became "?". Add them to SUBSTITUTIONS in dist/md2txt.js.');
}
