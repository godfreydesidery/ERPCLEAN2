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
  const ok = (what) => { created[what] = (created[what] || 0) + 1; };
  // severity: BLOCKED (can't do my job) | SLOW (workaround exists) | ANNOY (cosmetic)
  const problem = (severity, workflow, screen, detail, evidence) => {
    const rec = { persona: persona.fullName, designation: persona.designation, username: persona.username,
      severity, workflow, screen, detail, evidence: evidence || null };
    problems.push(rec);
    console.log(`  [${severity}] ${screen} :: ${workflow} — ${detail}${evidence ? ' | ' + JSON.stringify(evidence).slice(0, 160) : ''}`);
    return rec;
  };
  return { problems, created, ok, problem };
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
  await goto(page, '/login');
  await page.fill('input[type="text"]', user).catch(() => {});
  await page.fill('input[type="password"]', pass).catch(() => {});
  await page.click('button[type="submit"]').catch(() => {});
  await page.waitForTimeout(2200);
  const landedUrl = page.url().replace(BASE, '');
  return { ok: !page.url().includes('/login'), landedUrl };
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

function saveResults(name, payload) {
  const f = path.join(OUT, name);
  fs.writeFileSync(f, JSON.stringify(payload, null, 2));
  return f;
}

module.exports = {
  BASE, OUT, SIM_PASSWORD,
  makeRecorder, watch, launch, newSession, goto, login, shot,
  setField, pickFirstReal, pickByLabel, searchPick, ensureFormOpen, clickButton,
  submitByEnter, waitDetached, waitCleared, looksForbidden, saveResults, runAxe,
};
