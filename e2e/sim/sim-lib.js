// Shared Playwright harness for the Tembo Group business-operations SIMULATION.
//
// The personas (see .claude/agents/personas/) drive the REAL web UI — every value is TYPED into a
// form, never seeded. This library gives each persona driver: login, robust form/uid-picker helpers,
// and an automatic "problem capture" that turns console errors / API failures / blocked actions into
// structured records the personas file as User Problem Reports (docs/simulation/USER-PROBLEM-REPORT-TEMPLATE.md).
//
// Run requirement: NODE_PATH must include the web app's node_modules (for playwright-core):
//   NODE_PATH=d:/My_Works/ERP/ERPCLEAN2/web/node_modules node e2e/sim/<driver>.js
//
// Env: WEB_BASE (default http://localhost:4200), SIM_OUT (results dir).
const { chromium, devices } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const PWROOT = process.env.PLAYWRIGHT_BROWSERS_PATH || (process.env.LOCALAPPDATA + '/ms-playwright');
const EXE = fs.readdirSync(PWROOT).filter(d => /^chromium-\d/.test(d)).sort().pop();
const BIN = ['chrome-win/chrome.exe', 'chrome-win64/chrome.exe'].map(p => `${PWROOT}/${EXE}/${p}`).find(fs.existsSync);

const BASE = process.env.WEB_BASE || 'http://localhost:4200';
const OUT = process.env.SIM_OUT || (require('node:os').tmpdir() + '/tembo-sim');
fs.mkdirSync(OUT, { recursive: true });

const SIM_PASSWORD = process.env.SIM_PASSWORD || 'Tembo@2026!'; // shared persona password (>=12 chars)

// ---------------------------------------------------------------- problem capture
// A "problem" is a business-visible failure: an action the persona could not complete, an error
// surfaced on screen, a server 4xx/5xx, or a permission block (403). Each becomes a candidate UPR.
function makeRecorder(persona) {
  const problems = [];
  const created = {};
  const usage = {}; // per-component (screen) usage log: { <screen>: { visited, actioned } }
  const ok = (what) => { created[what] = (created[what] || 0) + 1; };
  // Log that this user touched a component/screen. actioned=true when they did real work there (not just opened it).
  const visited = (screen, actioned = false) => {
    const u = usage[screen] || (usage[screen] = { visited: 0, actioned: 0 });
    u.visited++; if (actioned) u.actioned++;
  };
  // severity: BLOCKED (can't do my job) | SLOW (workaround exists) | ANNOY (cosmetic) | HYGIENE (saw an id/uid) | UX (usability)
  const problem = (severity, workflow, screen, detail, evidence) => {
    const rec = { persona: persona.fullName, designation: persona.designation, username: persona.username,
      severity, workflow, screen, detail, evidence: evidence || null };
    problems.push(rec);
    console.log(`  [${severity}] ${screen} :: ${workflow} — ${detail}${evidence ? ' | ' + JSON.stringify(evidence).slice(0, 160) : ''}`);
    return rec;
  };
  return { problems, created, usage, ok, visited, problem };
}

// Attaches live listeners that buffer console/page/api errors so a driver can snapshot them per action.
function watch(page) {
  const buf = { console: [], page: [], api: [] };
  page.on('console', m => { if (m.type() === 'error') buf.console.push(m.text().slice(0, 300)); });
  page.on('pageerror', e => buf.page.push(String(e).slice(0, 300)));
  page.on('response', async r => {
    const u = r.url();
    if (u.includes('/api/') && r.status() >= 400) {
      let body = ''; try { body = (await r.text()).slice(0, 250); } catch {}
      buf.api.push({ url: u.replace(/^https?:\/\/[^/]+/, ''), status: r.status(), method: r.request().method(), body });
    }
  });
  buf.snapshot = () => ({ console: [...buf.console], page: [...buf.page], api: [...buf.api] });
  buf.clear = () => { buf.console.length = 0; buf.page.length = 0; buf.api.length = 0; };
  const NOISE = /favicon|ResizeObserver|net::ERR_|Failed to load resource.*404.*favicon/;
  buf.realConsole = () => buf.console.filter(e => !NOISE.test(e));
  return buf;
}

// ---------------------------------------------------------------- browser / session
async function launch() {
  return chromium.launch({ executablePath: BIN, headless: true });
}
// Device profiles — real users are on desktops, laptops, tablets and phones. Set DEVICE=mobile|tablet|
// laptop|desktop to run a persona at that viewport (tablet/mobile also emulate touch + mobile UA).
const stripDev = (d) => { if (!d) return null; const { defaultBrowserType, ...rest } = d; return rest; };
const DEVICES = {
  desktop: { viewport: { width: 1440, height: 1000 } },
  laptop: { viewport: { width: 1366, height: 768 } },
  tablet: { viewport: { width: 834, height: 1112 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
  mobile: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 },
  // real Playwright device descriptors (realistic UA + dimensions) — DEVICE=pixel|iphone|ipad
  pixel: stripDev(devices['Pixel 5']) || { viewport: { width: 393, height: 851 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2.75 },
  iphone: stripDev(devices['iPhone 13']) || { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 },
  ipad: stripDev(devices['iPad Mini']) || { viewport: { width: 768, height: 1024 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
};
const DEVICE = (process.env.DEVICE || 'desktop').toLowerCase();
async function newSession(browser) {
  const ctx = await browser.newContext(DEVICES[DEVICE] || DEVICES.desktop);
  const page = await ctx.newPage();
  const buf = watch(page);
  return { ctx, page, buf };
}
async function goto(page, p) {
  await page.goto(BASE + p, { waitUntil: 'networkidle' }).catch(() => {});
  await page.waitForTimeout(400);
}
// Returns { ok, landedUrl }. Uses the type-based selectors proven by qa-ui-drive.js.
async function login(page, user, pass) {
  // Retry up to 3x with back-off — under busy-week concurrency the login page/endpoint can flake
  // transiently (fields not ready, load blip); a real user simply tries again.
  for (let attempt = 1; attempt <= 3; attempt++) {
    await goto(page, '/login');
    await page.fill('input[type="text"]', user).catch(() => {});
    await page.fill('input[type="password"]', pass).catch(() => {});
    await page.click('button[type="submit"]').catch(() => {});
    await page.waitForTimeout(2200);
    if (!page.url().includes('/login')) return { ok: true, landedUrl: page.url().replace(BASE, '') };
    await page.waitForTimeout(1500 * attempt);
  }
  return { ok: false, landedUrl: page.url().replace(BASE, '') };
}
async function shot(page, name) {
  const f = path.join(OUT, name.replace(/[^a-z0-9_-]+/gi, '_').slice(0, 80) + '.png');
  try { await page.screenshot({ path: f, fullPage: true }); } catch {}
  return f;
}

// ---------------------------------------------------------------- form helpers
function elTag(el) { return el.evaluate(n => n.tagName).catch(() => ''); }

// Set a field by selector. Handles <select> (incl. app-uid-picker's forwarded inner <select>) and inputs.
async function setField(page, sel, val) {
  const el = page.locator(sel).first();
  if (!await el.count()) throw new Error('no field ' + sel);
  const tag = await elTag(el);
  if (tag === 'SELECT') {
    await el.selectOption(String(val)).catch(async () => el.selectOption({ label: String(val) }));
  } else {
    await el.fill(String(val));
  }
}
// Pick the first real (non-placeholder) option of a <select>.
async function pickFirstReal(page, sel) {
  const el = page.locator(sel).first();
  if (await el.count()) { const n = await el.locator('option').count(); if (n > 1) await el.selectOption({ index: 1 }); }
}
// Pick a select option whose visible label contains `needle` (case-insensitive). Returns true if matched.
async function pickByLabel(page, sel, needle) {
  const el = page.locator(sel).first();
  if (!await el.count()) return false;
  const opts = await el.locator('option').allTextContents();
  const idx = opts.findIndex(t => t.toLowerCase().includes(String(needle).toLowerCase()));
  if (idx > 0) { await el.selectOption({ index: idx }); return true; }
  return false;
}
// Type into a search-autocomplete input and click the first option (role=option / li).
async function searchPick(page, inputSel, text) {
  const el = page.locator(inputSel).first();
  if (!await el.count()) throw new Error('no search ' + inputSel);
  await el.click(); await el.fill(''); await el.type(String(text), { delay: 20 });
  await page.waitForTimeout(700);
  const opt = page.locator('[role="option"], ul li').filter({ hasText: new RegExp(text.slice(0, 6), 'i') }).first();
  if (await opt.count() && await opt.isVisible().catch(() => false)) { await opt.click(); return true; }
  // fallback: first listbox option
  const any = page.locator('[role="option"], ul[role="listbox"] li').first();
  if (await any.count() && await any.isVisible().catch(() => false)) { await any.click(); return true; }
  return false;
}
// Ensure an inline create form is open (idempotent).
async function ensureFormOpen(page, toggleLabel, anchorSel) {
  if (await page.locator(anchorSel).first().isVisible().catch(() => false)) return true;
  const btn = page.locator('button', { hasText: new RegExp(toggleLabel, 'i') }).first();
  if (await btn.count() && await btn.isVisible().catch(() => false)) { await btn.click(); await page.waitForTimeout(300); }
  return page.locator(anchorSel).first().isVisible().catch(() => false);
}
// Submit by clicking a button whose text matches, else pressing Enter in a field.
async function clickButton(page, textRe) {
  const b = page.locator('button', { hasText: textRe }).filter({ has: page.locator(':scope') }).first();
  if (await b.count() && await b.isEnabled().catch(() => false)) { await b.click(); return true; }
  return false;
}
async function submitByEnter(page, fieldSel) {
  await page.locator(fieldSel).first().press('Enter').catch(() => {});
}
async function waitDetached(page, anchorSel, ms = 8000) {
  await page.locator(anchorSel).first().waitFor({ state: 'detached', timeout: ms });
}
async function waitCleared(page, fieldSel, ms = 8000) {
  await page.waitForFunction((s) => { const e = document.querySelector(s); return e && e.value === ''; }, fieldSel, { timeout: ms });
}
// Is a 403/permission block visible? (route guard redirect to /forbidden, or an inline message)
async function looksForbidden(page, buf) {
  // A REAL block = a 403 on the screen's data, or a /forbidden route. We deliberately do NOT match
  // generic "you don't have permission to view X" body text: that is a GRACEFUL inline degradation
  // notice (good UX, e.g. the dashboard's finance-KPI section) — flagging it as forbidden is a false
  // positive (it tripped the FINANCE_DIRECTOR dashboard check even though the page loaded fine).
  const url = page.url();
  if (/forbidden|not-authorized/i.test(url)) return true;
  if (buf && buf.api.some(a => a.status === 403)) return true;
  return false;
}

// ---------------------------------------------------------------- axe a11y (mobile-aware)
// Inject axe-core and run it at the page's current viewport. Includes WCAG 2.2 target-size (the
// mobile tap-target rule); disables color-contrast (can't compute headless without a theme — matches
// the web suite's gate). Returns serious/critical violations.
let _axeSrc = null;
function axeSource() {
  if (_axeSrc === null) {
    const axePath = path.join(path.dirname(require.resolve('axe-core')), 'axe.min.js');
    _axeSrc = fs.readFileSync(axePath, 'utf8');
  }
  return _axeSrc;
}
async function runAxe(page) {
  try {
    await page.evaluate(axeSource());
    const results = await page.evaluate(async () => {
      // eslint-disable-next-line no-undef
      return window.axe.run(document, {
        runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'] },
        rules: { 'color-contrast': { enabled: false } },
      });
    });
    const v = results.violations
      .filter(x => x.impact === 'serious' || x.impact === 'critical')
      .map(x => ({ id: x.id, impact: x.impact, help: x.help, n: x.nodes.length, sample: ((x.nodes[0] || {}).target || []).join(' ').slice(0, 120) }));
    return { ran: true, violations: v };
  } catch (e) {
    return { ran: false, error: String(e.message || e).slice(0, 120), violations: [] };
  }
}

// Users must NEVER see an id or uid in the UI — a uid belongs only in the URL. This scans the RENDERED
// (visible) text and visible field values for ULIDs (26-char Crockford base32) or explicit "uid/id:"
// labels. document.body.innerText is what the user actually SEES (hidden nodes excluded) and never
// includes the address bar, so a hit here is a real leak into the UI. Returns { hits, snippet }.
async function scanVisibleIds(page) {
  try {
    return await page.evaluate(() => {
      const ULID = /\b[0-9A-HJKMNP-TV-Z]{26}\b/gi; // Crockford base32, 26 chars (ULID); case-insensitive
      const text = (document.body && document.body.innerText) || '';
      const hits = new Set();
      let m; while ((m = ULID.exec(text)) !== null) hits.add(m[0]);
      // an explicit "UID: ..." / "ID: <ulid>" label surfaced to the user
      const lbl = /\b(uid|id)\s*[:=]\s*([0-9A-HJKMNP-TV-Z]{26})/gi;
      while ((m = lbl.exec(text)) !== null) hits.add(m[2]);
      // A VISIBLE text box / read-only field literally showing a uid instead of a name is a leak.
      // EXCLUDE <select> (a uid-picker legitimately holds the uid as its value; the user sees the option
      // label, not the value) and hidden/off-screen controls (not seen by the user).
      document.querySelectorAll('input[type=text],input[type=search],input:not([type]),textarea,[contenteditable]').forEach(el => {
        if (el.offsetParent === null || el.type === 'hidden') return; // not visible to the user
        const v = ((el.value != null ? el.value : el.textContent) || '').trim();
        if (/^[0-9A-HJKMNP-TV-Z]{26}$/i.test(v)) hits.add(v);
      });
      // A bare NUMERIC foreign-key id shown where an ENTITY NAME belongs (e.g. a "Product" column showing
      // "4217" instead of the product name/code — the stock-on-hand class). Only entity-name columns are
      // checked (they must show names, not numbers), so a legitimate numeric column isn't flagged.
      const NAME_COL = /\b(product|item|customer|supplier|party|employee|manufacturer|brand|vendor|account name)\b/;
      document.querySelectorAll('table').forEach(tbl => {
        const heads = [...tbl.querySelectorAll('thead th')].map(th => (th.innerText || '').toLowerCase());
        const cols = heads.map((h, i) => (NAME_COL.test(h) ? i : -1)).filter(i => i >= 0);
        if (!cols.length) return;
        tbl.querySelectorAll('tbody tr').forEach(tr => {
          const cells = tr.children;
          cols.forEach(ci => {
            const t = ((cells[ci] || {}).innerText || '').trim();
            if (/^\d{1,9}$/.test(t)) hits.add('id-not-name:' + t); // a bare integer in a name column = leaked FK id
          });
        });
      });
      const arr = [...hits];
      let snippet = '';
      if (arr[0]) { const i = text.indexOf(arr[0]); snippet = text.slice(Math.max(0, i - 35), i + 40).replace(/\s+/g, ' ').trim(); }
      return { hits: arr, snippet };
    });
  } catch (e) { return { hits: [], snippet: '', error: String(e.message || e).slice(0, 80) }; }
}

// Users report a problem THROUGH THE GIVEN FORM — the User Problem Report
// (docs/simulation/USER-PROBLEM-REPORT-TEMPLATE.md). This renders each GENUINE problem the persona hit
// into a filled-in UPR, in the reporter's own work-voice, and writes it to OUT for the triage pipeline.
// Idempotency 409s (a busy day re-creating a record that already exists) are NOT problems and are skipped.
function writeUprs(persona, problems) {
  const genuine = (problems || []).filter(
    p => !(p.severity === 'SLOW' && /\b409\b|already|exist|duplicate|idempot/i.test(p.detail || '')));
  const badness = {
    BLOCKED: 'It stopped me completely — I could not do this at all.',
    SLOW: 'I found a way around it, but it slowed me down.',
    ANNOY: 'Just annoying / cosmetic — I could still work.',
    HYGIENE: 'I could work, but I saw codes / reference numbers on screen that I should not need to see.',
  };
  const onScreen = (ev) => {
    if (!ev || !ev.api) return '(nothing I could read — no short message)';
    const friendly = ev.api.map(a => a.body).find(b => b && !/^\s*[{<]/.test(b) && b.length < 160);
    if (friendly) return friendly.trim();
    const bad = ev.api.find(a => a.status >= 400);
    return bad ? `(a ${bad.status} error — no friendly message was shown)` : '(nothing I could read)';
  };
  const ids = [];
  genuine.forEach((p, i) => {
    const id = `UPR-${persona.slug}-${String(i + 1).padStart(2, '0')}`;
    const body = `# User Problem Report — ${id}

UPR-ID:                  ${id}
Date:                    ${new Date().toISOString().slice(0, 10)}

Reporter
  Name:                  ${persona.fullName}
  Designation:           ${persona.designation}
  Branch:                ${persona.homeBranch}

Role / permissions:      ${persona.role}

What I was trying to do:
  ${p.workflow || 'use one of my screens'}.

What I expected to happen:
  It would let me ${p.workflow || 'do my work'} and show or save the result.

What actually happened:
  ${p.detail}

Screen / menu path:
  ${p.screen}

On-screen reference or number:
  ${onScreen(p.evidence)}

How badly it stopped me:
  [${p.severity}] ${badness[p.severity] || ''}
`;
    fs.writeFileSync(path.join(OUT, `${id}.md`), body);
    ids.push(id);
  });
  return ids;
}

function saveResults(name, payload) {
  const f = path.join(OUT, name);
  fs.writeFileSync(f, JSON.stringify(payload, null, 2));
  return f;
}

module.exports = {
  BASE, OUT, SIM_PASSWORD,
  makeRecorder, watch, launch, newSession, goto, login, shot,
  setField, pickFirstReal, pickByLabel, searchPick, ensureFormOpen, clickButton,
  submitByEnter, waitDetached, waitCleared, looksForbidden, saveResults, runAxe, scanVisibleIds, writeUprs,
};
