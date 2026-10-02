// Real-browser verification of the report screens (read-only: runs reports, exports, checks gating).
// Usage: NODE_PATH=<dir with playwright-core>/node_modules node e2e/reports-ui-verify.js
// Env: WEB_BASE (default http://127.0.0.1:8088), SHOTS_DIR, OUT_JSON, ROOT_USER/ROOT_PASS, USER_PASS, ORG_SUFFIX.
const { chromium } = require('playwright-core');
const fs = require('fs'); const path = require('path'); const os = require('os');

const PW = path.join(process.env.LOCALAPPDATA || 'C:/Users/Godfrey/AppData/Local', 'ms-playwright');
const EXE = fs.readdirSync(PW).filter((d) => /^chromium-\d/.test(d)).sort().pop();
const BIN = [path.join(PW, EXE, 'chrome-win', 'chrome.exe'), path.join(PW, EXE, 'chrome-win64', 'chrome.exe')].find((p) => fs.existsSync(p));
const BASE = process.env.WEB_BASE || 'http://127.0.0.1:8088';
const SHOTS = process.env.SHOTS_DIR || path.join(os.tmpdir(), 'reports-ui-verify-shots');
const OUT = process.env.OUT_JSON || path.join(os.tmpdir(), 'reports-ui-verify.json');
const DL = path.join(SHOTS, 'downloads');
const RUSER = process.env.ROOT_USER || 'rootadmin'; const RPASS = process.env.ROOT_PASS || 'RootPass12345';
const UPASS = process.env.USER_PASS || 'VerifyPass12345'; const ORG = process.env.ORG_SUFFIX || '@default-organisation';
const ONLY = process.env.ONLY ? new RegExp(process.env.ONLY) : null;
fs.mkdirSync(DL, { recursive: true });

const FROM = '2026-01-01', TO = '2026-12-31';
const results = { base: BASE, startedAt: new Date().toISOString(), screens: [], gating: [], phone: [], defects: [] };
const say = (...a) => console.log(...a);

// ── report catalogue ────────────────────────────────────────────────────────────
// pick: special filter handling. totals: a tfoot/total row is expected.
const REPORTS = [
  { route: '/admin/reports/sales-summary', label: 'Sales Summary', totals: true },
  { route: '/admin/reports/payment-summary', label: 'Payment Summary (Cash-up)', totals: true },
  { route: '/admin/reports/reorder', label: 'Reorder Report' },
  { route: '/admin/reports/stock-ageing', label: 'Stock Ageing', totals: true },
  { route: '/admin/reports/sales', label: 'Sales Report', totals: true },
  { route: '/admin/reports/profitability', label: 'Profitability Report', totals: true },
  { route: '/admin/reports/stock', label: 'Stock Report (Current Balances)', totals: true },
  { route: '/admin/reports/stock-movement', label: 'Stock Report (Opening & Closing)', totals: true },
  { route: '/admin/reports/product-list', label: 'Product List' },
  { route: '/admin/reports/stock-value', label: 'Stock Value', totals: true },
  { route: '/admin/reports/payroll-statutory', label: 'Payroll Statutory Report', totals: true },
  { route: '/admin/reports/purchases/goods-received', label: 'Goods Received Register', totals: true },
  { route: '/admin/reports/purchases/by-supplier', label: 'Purchases by Supplier', totals: true },
  { route: '/admin/reports/purchases/open-orders', label: 'Open Purchase Orders' },
  { route: '/admin/reports/purchases/price-variance', label: 'Purchase Price Variance' },
  { route: '/admin/reporting/income-statement', label: 'Income Statement', totals: true },
  { route: '/admin/reporting/balance-sheet', label: 'Balance Sheet', totals: true },
  { route: '/admin/reporting/cash-flow', label: 'Cash-Flow Statement' },
  { route: '/admin/reporting/account-ledger', label: 'Account Ledger', pick: 'account' },
  { route: '/admin/reporting/changes-in-equity', label: 'Changes in Equity' },
  { route: '/admin/reporting/ratios', label: 'Financial Ratios' },
  { route: '/admin/fixed-assets/register', label: 'Fixed Asset Register', totals: true },
  { route: '/admin/ar/statement', label: 'Customer Statement', pick: 'customer' },
  { route: '/admin/ar/ageing', label: 'AR Ageing', totals: true },
  { route: '/admin/ap/statement', label: 'Supplier Statement', pick: 'supplier' },
  { route: '/admin/cash/statement', label: 'Cash Statement', pick: 'cashaccount' },
  { route: '/admin/tax/vat-returns', label: 'VAT Returns', pick: 'open-first', detail: '/admin/tax/vat-returns/uid/' },
  { route: '/admin/tax/wht-register', label: 'WHT Register', pick: 'wht' },
  { route: '/admin/dashboard', label: 'Dashboard', pick: 'dashboard' },
  { route: '/admin/hr/payroll-runs', label: 'Payroll Runs', pick: 'open-first', detail: '/admin/hr/payroll-runs/uid/' },
  { route: '/admin/fixed-assets', label: 'Fixed Assets', pick: 'open-first', detail: '/admin/fixed-assets/uid/' },
];
const SALES_STOCK = ['/admin/reports/sales-summary', '/admin/reports/sales', '/admin/reports/profitability', '/admin/reports/reorder',
  '/admin/reports/stock-ageing', '/admin/reports/stock', '/admin/reports/stock-movement', '/admin/reports/product-list', '/admin/reports/stock-value'];
const FIN_PUR = REPORTS.map((r) => r.route).filter((r) => !SALES_STOCK.includes(r) && !['/admin/reports/payment-summary', '/admin/dashboard'].includes(r));

// ── helpers ──────────────────────────────────────────────────────────────────────
function attach(page, bag) {
  page.on('console', (m) => { if (m.type() === 'error') bag.console.push(m.text().slice(0, 400)); });
  page.on('pageerror', (e) => bag.console.push('PAGEERROR ' + String(e.message || e).slice(0, 400)));
  page.on('response', (r) => {
    const u = r.url();
    if (u.includes('/api/') && r.status() >= 400) bag.network.push(`${r.status()} ${r.request().method()} ${u.replace(BASE, '')}`);
  });
}
const freshBag = () => ({ console: [], network: [] });
const shot = (page, name) => page.screenshot({ path: path.join(SHOTS, name.replace(/[^\w.-]+/g, '_') + '.png'), fullPage: true }).catch(() => {});
const settle = async (page, ms = 600) => { await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {}); await page.waitForTimeout(ms); };

async function newSession(browser, user, pass, viewport = { width: 1440, height: 900 }) {
  const ctx = await browser.newContext({ acceptDownloads: true, viewport });
  const page = await ctx.newPage();
  const bag = freshBag(); attach(page, bag);
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
  await page.fill('#username', user); await page.fill('#password', pass);
  await page.click('button[type="submit"]');
  await page.waitForURL((u) => !String(u).includes('/login'), { timeout: 20000 }).catch(() => {});
  await settle(page, 1200);
  const ok = !page.url().includes('/login');
  return { ctx, page, bag, ok };
}

async function navHrefs(page) {
  return page.$$eval('aside .sidebar-nav a.nav-item', (as) => as.map((a) => ({ href: a.getAttribute('href'), label: a.textContent.trim() })));
}

async function toastText(page) {
  return page.$$eval('.toast-stack .toast-item', (ts) => ts.map((t) => t.textContent.trim()).join(' | ')).catch(() => '');
}

// Fill sensible filters on whatever report form is on screen.
async function fillFilters(page, cfg, note) {
  // dates
  const dates = await page.$$eval('main input[type="date"]', (els) => els.filter((e) => e.offsetParent !== null).map((e) => e.id));
  for (const id of dates) {
    const low = id.toLowerCase();
    if (/cmp/.test(low)) continue; // comparative period: leave blank
    let v = null;
    if (/from|start/.test(low)) v = FROM; else if (/to$|todate|-to|end/.test(low)) v = TO; else if (/asof|as-of|date/.test(low)) v = TO;
    if (v) { await page.fill(`[id="${id}"]`, v).catch(() => {}); }
  }
  // selects (incl. uid-picker inner selects)
  const sels = await page.$$eval('main select', (els) => els.filter((e) => e.offsetParent !== null).map((e, i) => {
    const host = e.closest('app-uid-picker');
    return { i, id: e.id || (host && host.id) || '', opts: [...e.options].map((o) => o.textContent.trim()), value: e.value, idx: e.selectedIndex };
  }));
  const selLoc = (i) => page.locator('main select').filter({ visible: true }).nth(i);
  for (const s of sels) {
    const k = s.id.toLowerCase();
    if (/branch/.test(k)) {
      const j = s.opts.findIndex((o) => /BR-01|Default|Main|Head/i.test(o));
      const pickIdx = j > 0 ? j : (s.opts.length > 1 ? 1 : 0);
      await selLoc(s.i).selectOption({ index: pickIdx }).catch(() => {});
      note.push(`branch=${s.opts[pickIdx]}`);
    } else if (/account/.test(k) && (cfg.pick === 'account' || cfg.pick === 'cashaccount')) {
      const prefs = cfg.pick === 'cashaccount' ? [/CRDB/i, /Bank/i, /Cash/i] : [/Receivable/i, /Cash|Bank|Sales|Revenue/i];
      let j = -1; for (const pref of prefs) { if (j < 0) j = s.opts.findIndex((o, n) => n > 0 && pref.test(o)); }
      if (j < 0) j = s.opts.length > 1 ? 1 : 0;
      await selLoc(s.i).selectOption({ index: j }).catch(() => {});
      note.push(`account=${s.opts[j]}`);
    } else if (/whtperiodmode/.test(k)) {
      await selLoc(s.i).selectOption('range').catch(() => {});
      await page.waitForTimeout(300);
      await page.fill('#whtStart', FROM).catch(() => {}); await page.fill('#whtEnd', TO).catch(() => {});
      note.push('wht=range');
    }
  }
  // party search pickers
  if (cfg.pick === 'customer' || cfg.pick === 'supplier') {
    const sel = cfg.pick === 'customer' ? '#customerSearch' : '#supplierSearch';
    const q = cfg.pick === 'customer' ? 'Duka' : 'Alpha';
    await page.fill(sel, q).catch(() => {});
    await page.waitForTimeout(1200);
    const opt = page.locator('li[role="option"]').first();
    if (await opt.isVisible().catch(() => false)) { note.push('picked=' + (await opt.textContent()).trim()); await opt.click(); await settle(page, 800); }
    else note.push(`no suggestion for "${q}"`);
  }
}

async function clickRun(page) {
  const btn = page.locator('main button').filter({ hasText: /^\s*(Run report|Run|Load Ledger|Load report|Load|Generate|Apply|Show|Refresh statement|Refresh|Apply dates)\s*$/i }).filter({ visible: true }).first();
  if (await btn.count()) {
    if (await btn.isDisabled().catch(() => false)) return 'run-disabled';
    await btn.click().catch(() => {}); await settle(page, 1000);
    return 'clicked:' + (await btn.textContent().catch(() => '')).trim();
  }
  return 'no-run-button';
}

async function assess(page) {
  await page.waitForFunction(() => !/Loading…|Loading\.\.\.|Running…/.test((document.querySelector('main') || document.body).innerText), null, { timeout: 25000 }).catch(() => {});
  await page.waitForTimeout(300);
  return page.evaluate(() => {
    const main = document.querySelector('main') || document.body;
    const vis = (e) => e && e.offsetParent !== null;
    const rows = [...main.querySelectorAll('table tbody tr')].filter(vis).length;
    const tfoot = [...main.querySelectorAll('table tfoot')].filter(vis).length;
    const totalRow = [...main.querySelectorAll('tr, .total, .totals, [class*="total"]')].filter(vis).some((e) => /total/i.test(e.textContent || ''));
    const empties = [...main.querySelectorAll('.erp-empty, .empty-state, [class*="empty"]')].filter(vis).map((e) => e.textContent.trim().replace(/\s+/g, ' ').slice(0, 160));
    const alerts = [...main.querySelectorAll('.alert-danger, [role="alert"].alert')].filter(vis).map((e) => e.textContent.trim().replace(/\s+/g, ' ').slice(0, 200)).filter(Boolean);
    const crash = /undefined is not|cannot read prop/i.test(main.innerText || '');
    const nanHits = (main.innerText.match(/.{0,40}(NaN|\[object Object\]|undefined).{0,40}/g) || []).slice(0, 5);
    const h1 = (main.querySelector('h1, h2, .erp-page-head__title') || {}).textContent;
    return { rows, tfoot, totalRow, empties, alerts, crash, nanHits, heading: h1 && h1.trim().replace(/\s+/g, ' ') };
  });
}

async function exportButtons(page) {
  return page.locator('main button').filter({ hasText: /^\s*(((Export|Ageing|Statement) )?(PDF|Excel|CSV)|Print \/ PDF|Download)\s*$/i }).filter({ visible: true });
}

async function doExports(page, name, rec) {
  const btns = await exportButtons(page);
  const n = await btns.count();
  rec.exports = [];
  if (name.includes('dashboard')) {
    // dashboard: one Download button + a format select
    const fmtSel = page.locator('#exportFormat');
    const opts = (await fmtSel.count()) ? await fmtSel.locator('option').evaluateAll((os) => os.map((o) => o.value)) : [null];
    for (const v of opts) { if (v) await fmtSel.selectOption(v); await oneExport(page, page.locator('main button').filter({ hasText: /Download/ }).first(), `${name}-${v}`, rec, v || 'Download'); }
    return;
  }
  for (let i = 0; i < n; i++) {
    const b = btns.nth(i);
    const label = (await b.textContent()).trim();
    if (/bank/i.test(label)) continue;
    await oneExport(page, b, `${name}-${i}`, rec, label);
  }
}

async function oneExport(page, btn, base, rec, label) {
  const e = { label };
  try {
    const popupP = page.context().waitForEvent('page', { timeout: 25000 }).then((p) => ({ popup: p })).catch(() => null);
    const dlP = page.waitForEvent('download', { timeout: 25000 }).then((d) => ({ dl: d })).catch(() => null);
    await btn.click();
    const r = await Promise.race([dlP, popupP, new Promise((res) => setTimeout(() => res(null), 26000))]);
    if (r && r.dl) {
      const f = path.join(DL, base + '-' + r.dl.suggestedFilename());
      await r.dl.saveAs(f); e.file = f; e.size = fs.statSync(f).size; e.ok = e.size > 0; e.name = r.dl.suggestedFilename();
    } else if (r && r.popup) { e.popup = r.popup.url(); e.ok = true; await r.popup.close().catch(() => {}); }
    else { e.ok = false; e.err = 'no download within 25s'; e.toast = await toastText(page); }
  } catch (err) { e.ok = false; e.err = String(err.message || err).slice(0, 200); }
  await page.waitForTimeout(400);
  rec.exports.push(e);
}

function defect(d) { results.defects.push(d); say('  DEFECT:', JSON.stringify(d)); }

// ── 1. root pass ───────────────────────────────────────────────────────────────────
async function rootPass(browser) {
  const s = await newSession(browser, RUSER, RPASS);
  say('root login', s.ok, s.page.url());
  const hrefs = await navHrefs(s.page);
  for (const cfg of REPORTS) {
    if (ONLY && !ONLY.test(cfg.route)) continue;
    const page = s.page; s.bag.console.length = 0; s.bag.network.length = 0;
    const name = 'root' + cfg.route.replace(/\//g, '-');
    const rec = { user: RUSER, route: cfg.route, label: cfg.label, notes: [] };
    const navEntry = hrefs.find((h) => h.href === cfg.route);
    rec.navFound = !!navEntry; rec.navLabel = navEntry && navEntry.label;
    try {
      await page.goto(BASE + '/admin/home', { waitUntil: 'networkidle' }).catch(() => {});
      if (navEntry) {
        await page.locator(`aside a.nav-item[href="${cfg.route}"]`).first().click();
        await settle(page, 900);
      } else { await page.goto(BASE + cfg.route, { waitUntil: 'networkidle' }); await settle(page); }
      rec.landed = page.url().replace(BASE, '');
      if (cfg.pick === 'open-first') {
        rec.listAssess = await assess(page);
        const link = page.locator(`main a[href^="${cfg.detail}"]`).first();
        if (await link.count()) { await link.click(); }
        else { const tr = page.locator('main table tbody tr').first(); if (await tr.count()) await tr.click().catch(() => {}); }
        await settle(page, 1200);
        rec.detailUrl = page.url().replace(BASE, '');
        rec.openedDetail = rec.detailUrl.startsWith(cfg.detail);
        if (!rec.openedDetail) rec.notes.push('could not open a detail row from the list');
      } else {
        await fillFilters(page, cfg, rec.notes);
        rec.run = await clickRun(page);
        if (cfg.pick === 'dashboard') { await page.fill('#fromDate', FROM).catch(() => {}); await page.fill('#toDate', TO).catch(() => {}); rec.run = await clickRun(page); }
      }
      rec.assess = await assess(page);
      await shot(page, name);
      await doExports(page, name, rec);
      await shot(page, name + '-after-export');
    } catch (err) { rec.error = String(err.message || err).slice(0, 300); await shot(page, name + '-ERR'); }
    rec.console = [...s.bag.console]; rec.network = [...s.bag.network];
    const a = rec.assess || {};
    const exportsOk = (rec.exports || []).length > 0 && rec.exports.every((e) => e.ok);
    const populated = a.rows > 0;
    const fails = [];
    if (!rec.navFound) fails.push('no sidebar entry');
    if (rec.error) fails.push('script error: ' + rec.error);
    if (rec.network.length) fails.push('API errors: ' + rec.network.join('; '));
    if (rec.console.length) fails.push('console errors: ' + rec.console.slice(0, 3).join(' || '));
    if (a.crash || (a.nanHits || []).length) fails.push('suspicious text: ' + (a.nanHits || []).join(' / '));
    if (a.alerts && a.alerts.length) fails.push('alert on page: ' + a.alerts.join(' / '));
    if (!populated && !(a.empties || []).length) fails.push('no rows and no empty state');
    if (cfg.totals && populated && !a.tfoot && !a.totalRow) fails.push('no totals row');
    if (!exportsOk) fails.push('exports: ' + JSON.stringify((rec.exports || []).filter((e) => !e.ok)) + ((rec.exports || []).length ? '' : ' (no export buttons found)'));
    if (cfg.pick === 'open-first' && !rec.openedDetail) fails.push('detail not opened');
    rec.status = fails.length ? 'FAIL' : 'PASS'; rec.fails = fails;
    say(`${rec.status} | root ${cfg.route} | nav=${rec.navLabel || '-'} rows=${a.rows} tfoot=${a.tfoot} exports=${(rec.exports || []).map((e) => `${e.label}:${e.size || e.popup || e.err}`).join(',')} ${fails.join(' ; ')}`);
    results.screens.push(rec);
  }
  await s.ctx.close();
}

// ── 2-4. gating passes ───────────────────────────────────────────────────────────────
async function gatingPass(browser, uname, { expectVisible = [], expectHidden = [], refused = [], branchCheck = false, exportHiddenOn = [] }) {
  const s = await newSession(browser, uname + ORG, UPASS);
  const rec = { user: uname, loginOk: s.ok, checks: [] };
  const C = (name, ok, detail) => { rec.checks.push({ name, ok, detail }); say(`${ok ? 'PASS' : 'FAIL'} | ${uname} | ${name} | ${detail || ''}`); };
  if (!s.ok) { C('login', false, s.page.url()); results.gating.push(rec); await s.ctx.close(); return; }
  const hrefs = (await navHrefs(s.page)).map((h) => h.href);
  for (const r of expectVisible) C(`nav visible ${r}`, hrefs.includes(r));
  for (const r of expectHidden) C(`nav hidden ${r}`, !hrefs.includes(r));
  await shot(s.page, `${uname}-home`);
  const page = s.page;
  if (branchCheck) {
    const chip = await page.locator('.branch-chip').first();
    const chipTxt = (await chip.textContent().catch(() => '')).replace(/\s+/g, ' ').trim();
    const switchable = await chip.evaluate((e) => e.classList.contains('is-switchable')).catch(() => null);
    C('branch chip not switchable (single branch)', switchable === false, chipTxt);
    for (const r of exportHiddenOn) {
      s.bag.console.length = 0; s.bag.network.length = 0;
      await page.goto(BASE + r, { waitUntil: 'networkidle' }); await settle(page);
      const note = [];
      const branchOpts = await page.$$eval('main select', (els) => els.filter((e) => /branch/i.test(e.id || (e.closest('app-uid-picker') || {}).id || '')).map((e) => [...e.options].map((o) => o.textContent.trim())));
      C(`branch picker options on ${r}`, branchOpts.length === 0 || branchOpts.every((o) => o.filter((t) => !/^all|select|—|^$/i.test(t)).every((t) => /BR-01|Default|Main|Head|Dar/i.test(t)) && !o.some((t) => /BR-02|Arusha/i.test(t))), JSON.stringify(branchOpts));
      await fillFilters(page, { route: r }, note);
      const run = await clickRun(page);
      const a = await assess(page);
      const ex = await (await exportButtons(page)).count();
      C(`export buttons hidden on ${r}`, ex === 0, `run=${run} rows=${a.rows} exportButtons=${ex} ${note.join(',')}`);
      C(`no API errors on ${r}`, s.bag.network.length === 0, s.bag.network.join('; '));
      await shot(page, `${uname}${r.replace(/\//g, '-')}`);
    }
  }
  for (const r of refused) {
    s.bag.network.length = 0;
    await page.goto(BASE + '/admin/home', { waitUntil: 'networkidle' }); await settle(page, 300);
    // in-app navigation (as a typed URL in a live session would be after bootstrap) AND a hard load
    await page.goto(BASE + r, { waitUntil: 'networkidle' }); await settle(page, 700);
    const url = page.url().replace(BASE, '');
    const toast = await toastText(page);
    const a = await assess(page).catch(() => ({}));
    const refusedOk = !url.startsWith(r);
    C(`URL refused ${r}`, refusedOk, `landed=${url} toast="${toast.slice(0, 120)}" api4xx=${s.bag.network.join('; ')} heading=${a.heading || ''}`);
    if (!refusedOk) await shot(page, `${uname}-NOT-REFUSED${r.replace(/\//g, '-')}`);
  }
  rec.console = [...s.bag.console];
  results.gating.push(rec);
  await s.ctx.close();
}

// ── 5. phone width ─────────────────────────────────────────────────────────────────────
async function phonePass(browser) {
  const s = await newSession(browser, RUSER, RPASS, { width: 390, height: 844 });
  for (const r of ['/admin/reports/sales-summary', '/admin/reporting/income-statement', '/admin/reports/stock-value']) {
    const page = s.page;
    await page.goto(BASE + r, { waitUntil: 'networkidle' }); await settle(page);
    const note = [];
    await fillFilters(page, { route: r }, note);
    const run = await clickRun(page);
    const m = await page.evaluate(() => {
      const de = document.documentElement;
      const vw = de.clientWidth;
      const ctrls = [...document.querySelectorAll('main input, main select, main button')].filter((e) => e.offsetParent !== null);
      const off = ctrls.filter((e) => { const b = e.getBoundingClientRect(); return b.right > vw + 1 || b.left < -1; })
        .map((e) => `${e.tagName}#${e.id || ''}(${Math.round(e.getBoundingClientRect().left)}..${Math.round(e.getBoundingClientRect().right)})`);
      return { scrollW: de.scrollWidth, bodyScrollW: document.body.scrollWidth, clientW: vw, offscreen: off.slice(0, 10) };
    });
    const a = await assess(page);
    const ok = m.scrollW <= m.clientW + 1 && m.offscreen.length === 0;
    const rec = { route: r, ok, run, rows: a.rows, ...m };
    results.phone.push(rec);
    say(`${ok ? 'PASS' : 'FAIL'} | phone390 ${r} | scrollW=${m.scrollW} clientW=${m.clientW} offscreen=${m.offscreen.join(',')} run=${run} rows=${a.rows}`);
    await shot(page, `phone390${r.replace(/\//g, '-')}`);
  }
  await s.ctx.close();
}

(async () => {
  const browser = await chromium.launch({ executablePath: BIN, headless: true });
  const which = (process.env.PASSES || 'root,clerk,cashier,noperm,fin,phone').split(',');
  try {
    if (which.includes('root')) await rootPass(browser);
    if (which.includes('clerk')) await gatingPass(browser, 'rv_clerk_br1', {
      expectVisible: SALES_STOCK, expectHidden: FIN_PUR, branchCheck: true,
      exportHiddenOn: ['/admin/reports/sales-summary', '/admin/reports/stock-value', '/admin/reports/sales'],
      refused: ['/admin/reporting/income-statement', '/admin/reports/purchases/goods-received', '/admin/ar/statement', '/admin/fixed-assets/register'],
    });
    if (which.includes('cashier')) await gatingPass(browser, 'rv_cashier_br1', {
      expectHidden: ['/admin/reports/payment-summary'], refused: ['/admin/reports/payment-summary'],
    });
    if (which.includes('noperm')) await gatingPass(browser, 'rv_noperm_user', {
      expectHidden: REPORTS.map((r) => r.route), refused: ['/admin/reports/sales-summary', '/admin/reporting/balance-sheet', '/admin/dashboard'],
    });
    if (which.includes('fin')) await gatingPass(browser, 'rv_fin_br1', {
      expectVisible: FIN_PUR.filter((r) => !/payroll-runs$|fixed-assets$/.test(r)), branchCheck: false,
    });
    if (which.includes('phone')) await phonePass(browser);
  } catch (e) { say('FATAL', e && e.stack); results.fatal = String(e && e.stack); }
  finally {
    results.finishedAt = new Date().toISOString();
    fs.writeFileSync(OUT, JSON.stringify(results, null, 2));
    const p = results.screens.filter((x) => x.status === 'PASS').length;
    say(`\n== root screens ${p}/${results.screens.length} PASS; gating checks ${results.gating.flatMap((g) => g.checks).filter((c) => c.ok).length}/${results.gating.flatMap((g) => g.checks).length}; phone ${results.phone.filter((x) => x.ok).length}/${results.phone.length}`);
    say('JSON=' + OUT, 'SHOTS=' + SHOTS);
    await browser.close();
  }
})();
