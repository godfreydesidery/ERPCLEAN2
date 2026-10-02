// =====================================================================================
// REPORTS LIVE VERIFY — real-HTTP verification of the feat/reports-no-schema report batch.
//
//   API_BASE=http://127.0.0.1:8088/api/v1 node e2e/reports-live-verify.js
//
// What it does (never aborts on a failure — every step records PASS/FAIL/SKIP + evidence):
//   1. Seeds a small two-branch business THROUGH THE API as root (masters are looked up by
//      their RV- code/name and reused on a re-run; transactions are added again each run,
//      which is fine because every arithmetic check compares two reports over the SAME window).
//   2. Creates RBAC test users (clerk_br1 / fin_user / noperm_user + two cashiers).
//   3. Calls every report endpoint (+ every /export in PDF|XLSX|CSV) as root and as the
//      non-root users, asserting status codes and the branch-refusal wording.
//   4. Cross-checks report arithmetic against other reports and read-only SQL
//      (docker exec <DB_CONTAINER> psql — SELECT only).
//   5. Writes %TEMP%/reports-live-verify.json and prints a PASS/FAIL table.
//
// Env: API_BASE, ROOT_USER, ROOT_PASS, DB_CONTAINER (default erp-verify-db), OUT,
//      SEED_TX=0 to skip the transactional seed (reports-only re-run).
// Node built-ins only.
// =====================================================================================
'use strict';
const http = require('http');
const https = require('https');
const fs = require('fs');
const os = require('os');
const { execFileSync } = require('child_process');

const B = process.env.API_BASE || 'http://127.0.0.1:8088/api/v1';
const ROOT_USER = process.env.ROOT_USER || 'rootadmin';
const ROOT_PASS = process.env.ROOT_PASS || 'RootPass12345';
const DB_CONTAINER = process.env.DB_CONTAINER || 'erp-verify-db';
const OUT = process.env.OUT || (os.tmpdir() + '/reports-live-verify.json');
const SEED_TX = process.env.SEED_TX !== '0';
const USER_PASS = 'VerifyPass12345';
const RUN = Date.now().toString(36).slice(-5).toUpperCase(); // run suffix for transactional refs

const TODAY = new Date().toISOString().slice(0, 10);
const YEAR = Number(TODAY.slice(0, 4));
const MONTH = Number(TODAY.slice(5, 7));
const FROM = `${YEAR}-01-01`;
const TO = `${YEAR}-12-31`;

// ---------------------------------------------------------------------------- results
const CHECKS = [];   // { id, area, status: PASS|FAIL|SKIP, evidence }
const SEEDLOG = [];  // { step, status: OK|FAIL|SKIP, detail }
function check(id, area, ok, evidence) {
  const status = ok === null ? 'SKIP' : (ok ? 'PASS' : 'FAIL');
  CHECKS.push({ id, area, status, evidence });
  const ev = typeof evidence === 'string' ? evidence : JSON.stringify(evidence);
  console.log(`  [${status}] ${id} — ${ev.slice(0, 260)}`);
  return ok;
}
function seed(step, status, detail) {
  SEEDLOG.push({ step, status, detail: detail || '' });
  if (status !== 'OK') console.log(`  (seed ${status}) ${step}: ${String(detail || '').slice(0, 240)}`);
}

// ---------------------------------------------------------------------------- http
function req(method, path, token, body, extraHeaders) {
  return new Promise((resolve) => {
    const data = body !== undefined && body !== null ? JSON.stringify(body) : null;
    const u = new URL(B + path);
    const lib = u.protocol === 'https:' ? https : http;
    const headers = { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(extraHeaders || {}) };
    if (data) headers['Content-Length'] = Buffer.byteLength(data);
    const opt = { method, hostname: u.hostname, port: u.port || (u.protocol === 'https:' ? 443 : 80), path: u.pathname + u.search, headers };
    const r = lib.request(opt, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const buf = Buffer.concat(chunks);
        const raw = buf.toString('utf8');
        let j = null; try { j = raw ? JSON.parse(raw) : null; } catch { /* binary or text */ }
        resolve({ status: res.statusCode, body: j, raw, buf, headers: res.headers, method, path });
      });
    });
    r.on('error', (e) => resolve({ status: 0, body: null, raw: String(e), buf: Buffer.alloc(0), headers: {}, method, path }));
    r.setTimeout(120000, () => r.destroy(new Error('timeout')));
    if (data) r.write(data);
    r.end();
  });
}
const ok2 = (r) => r && r.status >= 200 && r.status < 300;
const P = (r) => (r && r.body && Object.prototype.hasOwnProperty.call(r.body, 'data')) ? r.body.data : (r ? r.body : null);
const L = (r) => { const p = P(r); return Array.isArray(p) ? p : (p && Array.isArray(p.content) ? p.content : []); };
const snip = (r) => `${r.method} ${r.path} -> ${r.status} ${(r.raw || '').slice(0, 220)}`;
const errs = (r) => (r && r.body && Array.isArray(r.body.errors)) ? r.body.errors.join(' | ') : (r ? (r.raw || '').slice(0, 200) : '');
const H = (branchUid) => (branchUid ? { 'X-Branch-Uid': branchUid } : {});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const num = (v) => (v === null || v === undefined || v === '' ? 0 : Number(v));
const r2 = (v) => Math.round(num(v) * 100) / 100;
const near = (a, b, tol = 0.011) => Math.abs(num(a) - num(b)) <= tol;
const qs = (o) => Object.entries(o).filter(([, v]) => v !== undefined && v !== null && v !== '').map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');

async function login(username, password) {
  const r = await req('POST', '/auth/login', null, { username, password });
  return ok2(r) && r.body?.data?.accessToken ? r.body.data.accessToken : null;
}

// read-only SQL (SELECT only) via docker exec; returns rows as arrays of strings
function sql(q) {
  if (!/^\s*(select|with)\b/i.test(q)) throw new Error('read-only SQL only');
  try {
    const out = execFileSync('docker', ['exec', DB_CONTAINER, 'psql', '-U', 'erp', '-d', 'erp', '-At', '-F', '\t', '-c', q], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    return out.split(/\r?\n/).filter((l) => l.length).map((l) => l.split('\t'));
  } catch (e) {
    return { error: String(e.stderr || e.message).slice(0, 300) };
  }
}
function sql1(q) { const r = sql(q); if (!Array.isArray(r)) return { error: r.error }; return r[0] ? r[0] : []; }

// ---------------------------------------------------------------------------- context
const C = {}; // discovered + seeded ids/uids

async function discover(root) {
  const org = L(await req('GET', '/organisations', root))[0];
  const comp = L(await req('GET', `/companies?organisationUid=${org.uid}`, root))[0];
  C.orgUid = org.uid; C.companyUid = comp.uid; C.companyId = comp.id; C.companyIdN = Number(comp.id);
  const brs = L(await req('GET', `/branches?companyUid=${comp.uid}`, root));
  C.br1 = brs.find((b) => b.code === 'BR-01') || brs[0];
  C.br2 = brs.find((b) => b.code === 'BR-02') || null;
  const units = L(await req('GET', `/units?companyId=${C.companyId}&size=50`, root));
  C.unit = units.find((u) => u.code === 'PCS') || units.find((u) => /piece/i.test(u.name)) || units[0];
  const accs = L(await req('GET', `/gl/accounts?companyId=${C.companyId}&size=500`, root));
  C.acc = {}; for (const a of accs) C.acc[a.accountCode] = a;
  const cfg = L(await req('GET', `/gl/configs?companyId=${C.companyId}`, root));
  C.cfg = {}; for (const c of cfg) C.cfg[c.configKey] = c;
  C.cashAccounts = L(await req('GET', `/cash/accounts?companyId=${C.companyId}`, root));
  C.periods = L(await req('GET', `/gl/periods?companyId=${C.companyId}`, root));
  const rootMe = L(await req('GET', '/users', root)).find((u) => u.username === ROOT_USER);
  C.rootUid = rootMe?.uid;
  console.log(`  company=${C.companyUid}(id ${C.companyId}) BR-01=${C.br1?.uid} BR-02=${C.br2?.uid || '-'} unit=${C.unit?.code} accounts=${accs.length} cfg=${cfg.length} periods=${C.periods.length}`);
}

// find-or-create helper for masters with a name/q search
async function findOrCreate(root, label, listPath, match, createPath, body, headers) {
  const found = L(await req('GET', listPath, root)).find(match);
  if (found) return found;
  const r = await req('POST', createPath, root, body, headers);
  if (ok2(r) && P(r)) { seed(label, 'OK'); return P(r); }
  seed(label, 'FAIL', snip(r));
  return null;
}

module.exports = { req, P, L }; // (for ad-hoc REPL use)

// =====================================================================================
// PHASE 1a — foundations + masters (idempotent)
// =====================================================================================
async function seedMasters(root) {
  console.log('\n=== PHASE 1a: foundations + masters ===');
  // BR-02
  if (!C.br2) {
    const r = await req('POST', '/branches', root, { companyUid: C.companyUid, code: 'BR-02', name: 'Arusha Branch' });
    if (ok2(r)) { C.br2 = P(r); seed('branch BR-02', 'OK'); } else seed('branch BR-02', 'FAIL', snip(r));
  }
  // root on BR-02 (so X-Branch-Uid=BR-02 writes pass the branch filter)
  if (C.br2 && C.rootUid) {
    const ub = L(await req('GET', `/user-branches?userUid=${C.rootUid}`, root));
    if (!ub.some((x) => x.branchUid === C.br2.uid || x.branch?.uid === C.br2.uid)) {
      const r = await req('POST', '/user-branches', root, { userUid: C.rootUid, branchUid: C.br2.uid, makeDefault: false });
      seed('root -> BR-02 assignment', ok2(r) || r.status === 409 ? 'OK' : 'FAIL', snip(r));
    }
  }
  // stock locations per branch
  C.loc1 = L(await req('GET', `/stock-locations?branchUid=${C.br1.uid}&size=100`, root)).find((l) => l.isDefault);
  C.loc2 = C.br2 ? L(await req('GET', `/stock-locations?branchUid=${C.br2.uid}&size=100`, root, null, H(C.br2.uid))).find((l) => l.isDefault) : null;
  if (!C.loc2) seed('BR-02 default stock location', 'FAIL', 'none found after branch create');

  // a bank cash account (GL 1100/1010 style) for mobile/card tenders + AP payments
  C.cashAcc = C.cashAccounts.find((a) => a.isDefault) || C.cashAccounts[0];
  const bankGl = C.acc['1100'] || C.acc['1010'] || C.acc['1020'] || Object.values(C.acc).find((a) => a.accountType === 'ASSET' && /bank/i.test(a.name));
  C.bankAcc = C.cashAccounts.find((a) => a.accountType === 'BANK');
  if (!C.bankAcc && bankGl) {
    const r = await req('POST', '/cash/accounts', root, { companyUid: C.companyUid, name: 'RV CRDB Current', accountType: 'BANK', bankName: 'CRDB', bankAccountNo: '0150000001', glAccountUid: bankGl.uid, setAsDefault: false });
    if (ok2(r)) { C.bankAcc = P(r); seed('bank cash account', 'OK'); } else seed('bank cash account', 'FAIL', snip(r));
  }

  // USD enabled + a rate so a USD invoice can be raised
  const fx = L(await req('GET', `/fx/companies/${C.companyUid}/currencies`, root));
  C.usdEnabled = fx.some((c) => c.currencyCode === 'USD' && c.active);
  const rates = L(await req('GET', `/fx/rates?companyId=${C.companyId}`, root));
  if (!rates.some((x) => x.fromCurrency === 'USD')) {
    const r = await req('POST', '/fx/rates', root, { companyId: C.companyIdN, fromCurrency: 'USD', toCurrency: 'TZS', rate: 2500, effectiveDate: `${YEAR}-01-01`, rateType: 'SPOT', source: 'RV' });
    seed('fx rate USD->TZS', ok2(r) ? 'OK' : 'FAIL', snip(r));
  }

  // WHT type (5% services, on payment)
  const whts = L(await req('GET', `/wht/types?companyId=${C.companyId}`, root));
  C.wht = whts.find((w) => w.code === 'RV-WHT5');
  if (!C.wht) {
    const r = await req('POST', '/wht/types', root, { companyUid: C.companyUid, code: 'RV-WHT5', name: 'WHT 5% services', kind: 'WHT_ON_PAYMENT', ratePct: 5 });
    if (ok2(r)) { C.wht = P(r); seed('wht type', 'OK'); } else seed('wht type', 'FAIL', snip(r));
  }

  // suppliers
  const supDefs = [
    { name: 'RV Supplier Alpha Ltd', kind: 'GOODS' },
    { name: 'RV Supplier Beta Traders', kind: 'GOODS' },
    { name: 'RV Supplier Gamma Imports', kind: 'GOODS' },
    { name: 'RV Supplier Delta Services', kind: 'SERVICE' },
  ];
  C.sup = [];
  for (const s of supDefs) {
    const x = await findOrCreate(root, `supplier ${s.name}`, `/suppliers?companyId=${C.companyId}&q=${encodeURIComponent(s.name)}&size=5`, (y) => y.displayName === s.name,
      '/suppliers', { companyId: C.companyIdN, partyType: 'BUSINESS', displayName: s.name, supplierKind: s.kind, paymentTermsDays: 30, defaultCurrency: 'TZS', tin: '1' + String(Math.abs(hash(s.name))).slice(0, 8) });
    if (x) C.sup.push(x);
  }
  // customers (one USD)
  const custDefs = [
    { name: 'RV Customer Walk-in', kind: 'CASH_WALK_IN' },
    { name: 'RV Customer Duka Moja', kind: 'CREDIT_ACCOUNT' },
    { name: 'RV Customer Hotel Kili', kind: 'CREDIT_ACCOUNT' },
    { name: 'RV Customer Shule Bora', kind: 'CREDIT_ACCOUNT' },
    { name: 'RV Customer Safari Lodge USD', kind: 'CREDIT_ACCOUNT', currency: 'USD' },
  ];
  C.cust = [];
  for (const c of custDefs) {
    const x = await findOrCreate(root, `customer ${c.name}`, `/customers?companyId=${C.companyId}&q=${encodeURIComponent(c.name)}&size=5`, (y) => y.displayName === c.name,
      '/customers', { companyId: C.companyIdN, partyType: c.kind === 'CASH_WALK_IN' ? 'INDIVIDUAL' : 'BUSINESS', tin: c.kind === 'CASH_WALK_IN' ? undefined : '2' + String(Math.abs(hash(c.name))).padStart(8, '7').slice(0, 8), displayName: c.name, customerKind: c.kind, paymentTermsDays: c.kind === 'CREDIT_ACCOUNT' ? 30 : undefined, defaultCurrency: c.currency || 'TZS',
        creditLimit: c.kind === 'CREDIT_ACCOUNT' ? { amount: '100000000', currency: c.currency || 'TZS' } : undefined });
    if (x) C.cust.push(x);
  }
  // agents
  C.agents = [];
  for (const n of ['RV Agent Juma', 'RV Agent Neema']) {
    const x = await findOrCreate(root, `agent ${n}`, `/agents?companyId=${C.companyId}&q=${encodeURIComponent(n)}&size=5`, (y) => y.displayName === n,
      '/agents', { companyId: C.companyIdN, partyType: 'INDIVIDUAL', displayName: n, agentKind: 'EXTERNAL' });
    if (x) C.agents.push(x);
  }
  // routes (+ agent + customers)
  C.routes = [];
  for (const [i, n] of ['RV Route Kariakoo', 'RV Route Njiro'].entries()) {
    let x = L(await req('GET', `/routes?companyId=${C.companyId}&q=${encodeURIComponent(n)}&size=5`, root)).find((y) => y.name === n);
    if (!x) {
      const r = await req('POST', '/routes', root, { companyUid: C.companyUid, name: n, locationIdentifier: i ? 'Arusha' : 'Dar' });
      if (ok2(r)) {
        x = P(r); seed(`route ${n}`, 'OK');
        if (C.agents[i]) await req('POST', `/routes/uid/${x.uid}/agents`, root, { agentUid: C.agents[i].uid, isPrimary: true });
        for (const c of C.cust.slice(1 + i * 2, 3 + i * 2)) await req('POST', `/routes/uid/${x.uid}/customers`, root, { customerUid: c.uid });
      } else seed(`route ${n}`, 'FAIL', snip(r));
    }
    if (x) C.routes.push(x);
  }
  for (const [i, x] of C.routes.entries()) for (const c of C.cust.slice(1 + i * 2, 3 + i * 2)) await req('POST', `/routes/uid/${x.uid}/customers`, root, { customerUid: c.uid });
  // party -> BR-02 assignments (so BR-02 can transact with them; harmless if not required)
  if (C.br2) {
    for (const c of C.cust) await req('POST', `/customers/uid/${c.uid}/branches`, root, { branchUid: C.br2.uid });
    for (const s of C.sup) await req('POST', `/suppliers/uid/${s.uid}/branches`, root, { branchUid: C.br2.uid });
    for (const a of C.agents) await req('POST', `/agents/uid/${a.uid}/branches`, root, { branchUid: C.br2.uid });
    for (const r of C.routes) await req('POST', `/routes/uid/${r.uid}/branches`, root, { branchUid: C.br2.uid });
  }
  // default price list
  let pls = L(await req('GET', `/price-lists?companyId=${C.companyId}&size=20`, root));
  C.pl = pls.find((p) => p.code === 'RV-RETAIL') || pls.find((p) => p.isDefault);
  if (!C.pl) {
    const r = await req('POST', '/price-lists', root, { companyUid: C.companyUid, code: 'RV-RETAIL', name: 'RV Retail', currency: 'TZS', isDefault: true, priceIncludesVat: false, scope: 'GLOBAL' });
    if (ok2(r)) { C.pl = P(r); seed('price list', 'OK'); } else seed('price list', 'FAIL', snip(r));
  }
  // products: 10 goods + 1 service. [code, name, cost, price, reorderLevel, supplierIdx, vat]
  const prodDefs = [
    ['RV-P01', 'RV Sugar 1kg', 2200, 3000, 40, 0, 'STANDARD'],
    ['RV-P02', 'RV Rice 5kg', 11000, 14500, 30, 0, 'STANDARD'],
    ['RV-P03', 'RV Cooking Oil 1L', 4800, 6500, 25, 0, 'STANDARD'],
    ['RV-P04', 'RV Maize Flour 2kg', 2600, 3500, null, 1, 'ZERO_RATED'],
    ['RV-P05', 'RV Soap Bar', 900, 1300, 50, 1, 'STANDARD'],
    ['RV-P06', 'RV Toothpaste', 2100, 2900, null, 1, 'STANDARD'],
    ['RV-P07', 'RV Mineral Water 1.5L', 700, 1000, null, 2, 'STANDARD'],
    ['RV-P08', 'RV Tea Leaves 250g', 3100, 4200, 15, 2, 'STANDARD'],
    ['RV-P09', 'RV Biscuits Pack', 1500, 2100, null, 2, 'STANDARD'],
    ['RV-P10', 'RV Exercise Book', 500, 800, null, 2, 'EXEMPT'],
  ];
  C.prod = [];
  const existingProds = L(await req('GET', `/products?companyId=${C.companyId}&q=RV-&size=100`, root));
  for (const [code, name, cost, price, rl, si, vat] of prodDefs) {
    let p = existingProds.find((x) => x.code === code);
    if (!p) {
      const r = await req('POST', '/products', root, { companyUid: C.companyUid, code, name, type: 'GOODS', sellable: true, stockable: true, purchasable: true, baseUnitUid: C.unit.uid,
        cost: { amount: String(cost), currency: 'TZS' }, vatStatus: vat, reorderLevel: rl ?? undefined, reorderQty: rl ? rl * 2 : undefined, preferredSupplierId: C.sup[si] ? Number(C.sup[si].id) : undefined });
      if (ok2(r)) { p = P(r); seed(`product ${code}`, 'OK'); } else { seed(`product ${code}`, 'FAIL', snip(r)); continue; }
      if (C.pl) { const sp = await req('POST', `/products/uid/${p.uid}/prices`, root, { priceListUid: C.pl.uid, price: { amount: String(price), currency: 'TZS' } }); if (!ok2(sp)) seed(`price ${code}`, 'FAIL', snip(sp)); }
      if (C.br2) await req('POST', `/products/uid/${p.uid}/branches`, root, { branchUid: C.br2.uid });
      await req('POST', `/products/uid/${p.uid}/branches`, root, { branchUid: C.br1.uid });
    }
    C.prod.push({ ...p, _cost: cost, _price: price, _rl: rl, _sup: si, _vat: vat });
  }
  // service product
  let svc = existingProds.find((x) => x.code === 'RV-S01');
  if (!svc) {
    const r = await req('POST', '/products', root, { companyUid: C.companyUid, code: 'RV-S01', name: 'RV Delivery Service', type: 'SERVICE', sellable: true, stockable: false, baseUnitUid: C.unit.uid, vatStatus: 'STANDARD' });
    if (ok2(r)) { svc = P(r); seed('service product', 'OK'); if (C.pl) await req('POST', `/products/uid/${svc.uid}/prices`, root, { priceListUid: C.pl.uid, price: { amount: '5000', currency: 'TZS' } }); }
    else seed('service product', 'FAIL', snip(r));
  }
  C.svc = svc ? { ...svc, _price: 5000 } : null;
  console.log(`  suppliers=${C.sup.length} customers=${C.cust.length} agents=${C.agents.length} routes=${C.routes.length} products=${C.prod.length}+${C.svc ? 1 : 0}`);
}
function hash(s) { let h = 0; for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) | 0; return h; }

// =====================================================================================
// PHASE 2 — RBAC users (idempotent): roles -> users -> company membership -> branches -> grants
// =====================================================================================
const MASTER_VIEW = ['PRODUCT.VIEW', 'CUSTOMER.VIEW', 'SUPPLIER.VIEW', 'AGENT.VIEW', 'ROUTE.VIEW', 'PRICELIST.VIEW', 'UOM.VIEW', 'BRANCH.VIEW', 'COMPANY.VIEW', 'TAXRATE.VIEW', 'CURRENCY.VIEW'];
const ROLE_DEFS = {
  RV_CLERK: ['SALES.INVOICE.VIEW', 'STOCK.VIEW', 'INVENTORY.VALUATION.VIEW', 'POS.SESSION.VIEW', 'POS.CASHUP.VIEW', 'BI.VIEW'],
  RV_FIN: ['AP.VIEW', 'AR.VIEW', 'AR.STATEMENT.VIEW', 'CASH.VIEW', 'VAT.VIEW', 'WHT.VIEW', 'FA.VIEW', 'HR.PAYROLL.VIEW', 'REPORT.PL.VIEW', 'REPORT.BS.VIEW',
    'REPORT.CASHFLOW.VIEW', 'REPORT.LEDGER.VIEW', 'REPORT.VIEW', 'REPORT.EXPORT', 'PURCHASE.ORDER.VIEW', 'PURCHASE.GOODS_RECEIPT.VIEW', 'SALES.INVOICE.VIEW', 'STOCK.VIEW',
    'INVENTORY.VALUATION.VIEW', 'POS.SESSION.VIEW', 'POS.CASHUP.VIEW', 'BI.VIEW', 'BI.EXPORT', 'GL.VIEW', ...MASTER_VIEW],
  RV_CASHIER: ['SALES.INVOICE.CREATE', 'SALES.INVOICE.SETTLE', 'SALES.INVOICE.VIEW', 'POS.SALE.CREATE', 'POS.SESSION.OPEN', 'POS.SESSION.CLOSE', 'POS.SESSION.VIEW', 'POS.TILL.VIEW', 'STOCK.VIEW', ...MASTER_VIEW],
};
const USER_DEFS = [
  { username: 'rv_clerk_br1', role: 'RV_CLERK', branches: ['br1'] },
  { username: 'rv_fin_user', role: 'RV_FIN', branches: ['br1', 'br2'] },
  { username: 'rv_noperm_user', role: null, branches: ['br1'] },
  { username: 'rv_cashier_br1', role: 'RV_CASHIER', branches: ['br1'] },
  { username: 'rv_cashier_br2', role: 'RV_CASHIER', branches: ['br2'] },
  { username: 'rv_fin_br1', role: 'RV_FIN', branches: ['br1'] }, // finance codes, BR-01 only: branch guard on finance/purchase reports
];
async function seedUsers(root) {
  console.log('\n=== PHASE 2: RBAC users ===');
  const roles = L(await req('GET', '/roles', root));
  C.roles = {};
  for (const [code, perms] of Object.entries(ROLE_DEFS)) {
    let role = roles.find((r) => r.code === code);
    if (!role) {
      const r = await req('POST', '/roles', root, { code, name: code.replace('RV_', 'RV ').toLowerCase(), description: 'reports-live-verify test role' });
      if (ok2(r)) { role = P(r); seed(`role ${code}`, 'OK'); } else { seed(`role ${code}`, 'FAIL', snip(r)); continue; }
    }
    const sp = await req('PUT', `/roles/uid/${role.uid}/permissions`, root, { permissionCodes: perms });
    if (!ok2(sp)) seed(`role ${code} permissions`, 'FAIL', snip(sp));
    C.roles[code] = role;
  }
  const users = L(await req('GET', '/users?size=500', root));
  C.users = {};
  for (const d of USER_DEFS) {
    let u = users.find((x) => x.username === d.username || String(x.username).startsWith(d.username + '@'));
    if (!u) {
      const r = await req('POST', '/users', root, { username: d.username, displayName: d.username.replace(/_/g, ' '), password: USER_PASS, email: `${d.username}@verify.test` });
      if (ok2(r)) { u = P(r); seed(`user ${d.username}`, 'OK'); } else { seed(`user ${d.username}`, 'FAIL', snip(r)); continue; }
    }
    const uc = L(await req('GET', `/user-companies?userUid=${u.uid}`, root));
    if (!uc.some((x) => (x.companyUid || x.company?.uid) === C.companyUid)) {
      const r = await req('POST', '/user-companies', root, { userUid: u.uid, companyUid: C.companyUid });
      if (!ok2(r) && r.status !== 409) seed(`membership ${d.username}`, 'FAIL', snip(r));
    }
    const ub = L(await req('GET', `/user-branches?userUid=${u.uid}`, root));
    for (const [i, bk] of d.branches.entries()) {
      const b = C[bk]; if (!b) continue;
      if (!ub.some((x) => (x.branchUid || x.branch?.uid) === b.uid)) {
        const r = await req('POST', '/user-branches', root, { userUid: u.uid, branchUid: b.uid, makeDefault: i === 0 });
        if (!ok2(r) && r.status !== 409) seed(`branch ${bk} -> ${d.username}`, 'FAIL', snip(r));
      }
    }
    if (d.role && C.roles[d.role]) {
      const r = await req('POST', '/user-roles', root, { userUid: u.uid, roleUid: C.roles[d.role].uid, companyUid: C.companyUid });
      if (!ok2(r) && r.status !== 409 && !/already/i.test(errs(r))) seed(`grant ${d.role} -> ${d.username}`, 'FAIL', snip(r));
    }
    C.users[d.username] = { ...u, token: await login(u.username || d.username, USER_PASS) };
    if (!C.users[d.username].token) seed(`login ${d.username}`, 'FAIL', 'login failed');
    const dbId = sql1(`select id from app_users where uid='${u.uid}'`);
    C.users[d.username].dbId = dbId[0];
  }
  console.log(`  users: ${Object.entries(C.users).map(([k, v]) => `${k}${v.token ? '' : '(NO LOGIN)'}`).join(', ')}`);
}

// =====================================================================================
// PHASE 1b — transactions (added on every run unless SEED_TX=0)
// =====================================================================================
async function waitOutbox(label, maxMs = 30000) {
  const t0 = Date.now();
  for (;;) {
    const r = sql1(`select count(*) from domain_events where status = 'PENDING'`);
    const n = Array.isArray(r) ? Number(r[0]) : NaN;
    if (n === 0 || Number.isNaN(n) || Date.now() - t0 > maxMs) { if (n > 0) seed(`outbox drain (${label})`, 'FAIL', `${n} events still pending after ${maxMs}ms`); return; }
    await sleep(1000);
  }
}

async function poFlow(root, branch, supplier, lines, opts = {}) {
  const h = H(branch.uid);
  const r = await req('POST', '/purchase-orders', root, { companyUid: C.companyUid, supplierUid: supplier.uid, currency: 'TZS', notes: `RV ${RUN} ${opts.note || ''}`,
    lines: lines.map(([p, qty, cost]) => ({ productUid: p.uid, unitUid: C.unit.uid, orderedQty: qty, unitCostAmount: cost })) }, h);
  if (!ok2(r)) { seed(`PO ${opts.note}`, 'FAIL', snip(r)); return null; }
  const po = P(r);
  const pl = await req('POST', `/purchase-orders/uid/${po.uid}/place`, root, null, h);
  if (!ok2(pl)) {
    const ap = await req('POST', `/purchase-orders/uid/${po.uid}/approve`, root, { companyUid: C.companyUid, reason: 'RV' }, h);
    const pl2 = await req('POST', `/purchase-orders/uid/${po.uid}/place`, root, null, h);
    if (!ok2(pl2)) { seed(`PO place ${opts.note}`, 'FAIL', snip(pl) + ' / ' + snip(ap) + ' / ' + snip(pl2)); return { po, lines: [] }; }
  }
  const pls = L(await req('GET', `/purchase-orders/uid/${po.uid}/lines`, root, null, h));
  seed(`PO ${opts.note} (${po.poNumber || po.uid})`, 'OK');
  return { po, lines: pls };
}
async function receive(root, branch, poRes, qtys, note) {
  if (!poRes || !poRes.lines.length) return null;
  const lines = poRes.lines.map((l, i) => ({ purchaseOrderLineUid: l.uid, receivedQty: qtys[i] })).filter((x) => num(x.receivedQty) > 0);
  const r = await req('POST', '/goods-receipts', root, { purchaseOrderUid: poRes.po.uid, notes: `RV ${RUN} ${note}`, lines }, H(branch.uid));
  if (!ok2(r)) { seed(`GR ${note}`, 'FAIL', snip(r)); return null; }
  seed(`GR ${note}`, 'OK');
  return P(r);
}

async function salesInvoice(token, branch, customer, lines, tenders, opts = {}) {
  const h = H(branch.uid);
  const cur = opts.currency || 'TZS';
  const r = await req('POST', '/sales-invoices', token, { companyUid: C.companyUid, customerUid: customer.uid, agentUid: opts.agent?.uid, routeUid: opts.route?.uid, currency: cur, notes: `RV ${RUN} ${opts.note || ''}` }, h);
  if (!ok2(r)) { seed(`invoice ${opts.note}`, 'FAIL', snip(r)); return null; }
  const inv = P(r);
  for (const [p, qty] of lines) {
    const al = await req('POST', `/sales-invoices/uid/${inv.uid}/lines`, token, { productUid: p.uid, unitUid: C.unit.uid, quantity: qty, unitPriceOverride: opts.usdPrice ? opts.usdPrice : undefined }, h);
    if (!ok2(al)) { seed(`invoice ${opts.note} line ${p.code}`, 'FAIL', snip(al)); return null; }
  }
  const got = P(await req('GET', `/sales-invoices/uid/${inv.uid}`, token, null, h));
  const gross = num(got?.grossTotalAmount);
  // tenders: array of [type, fraction] — fractions of gross; credit sale when empty
  let paid = 0;
  for (const [i, [type, frac]] of tenders.entries()) {
    const amt = i === tenders.length - 1 ? r2(gross - paid) : r2(gross * frac);
    paid += amt;
    const body = { tenderType: type, amount: amt, currency: cur, reference: `RV-${RUN}-${i}` };
    if (type !== 'CASH' && C.bankAcc) body.cashBankAccountId = Number(C.bankAcc.id);
    if (type === 'MOBILE_MONEY') body.mobileMoneyRef = `MM${RUN}${i}`;
    if (type === 'CARD') body.cardRef = `CD${RUN}${i}`;
    const pr = await req('POST', `/sales-invoices/uid/${inv.uid}/payments`, token, body, h);
    if (!ok2(pr)) seed(`invoice ${opts.note} payment ${type}`, 'FAIL', snip(pr));
  }
  const fin = await req('PUT', `/sales-invoices/uid/${inv.uid}/finalize`, token, { belowCostApproved: true }, h);
  if (!ok2(fin)) { seed(`invoice ${opts.note} finalize`, 'FAIL', snip(fin)); return null; }
  seed(`invoice ${opts.note}`, 'OK');
  return P(fin) || got;
}

async function seedTransactions(root) {
  console.log('\n=== PHASE 1b: transactions ===');
  const [p1, p2, p3, p4, p5, p6, p7, p8, p9, p10] = C.prod;
  const [sA, sB, sC, sD] = C.sup;
  const br1 = C.br1, br2 = C.br2;
  C.tx = {};

  // ---------------- purchasing
  // PO1 @BR-01 supplier A: partial receipt then the remainder
  const po1 = await poFlow(root, br1, sA, [[p1, 100, 2200], [p2, 60, 11000], [p3, 50, 4800]], { note: 'PO1-BR1-partial+full' });
  const gr1a = await receive(root, br1, po1, [60, 60, 20], 'GR1a partial');
  const gr1b = await receive(root, br1, po1, [40, 0, 30], 'GR1b remainder');
  // PO2 @BR-02 supplier B: full receipt
  const po2 = br2 ? await poFlow(root, br2, sB, [[p4, 80, 2600], [p5, 120, 900], [p6, 40, 2100]], { note: 'PO2-BR2-full' }) : null;
  const gr2 = br2 ? await receive(root, br2, po2, [80, 120, 40], 'GR2 full @BR2') : null;
  // PO3 @BR-01 supplier C: receive part, leave open (open-orders report)
  const po3 = await poFlow(root, br1, sC, [[p7, 200, 700], [p8, 30, 3100], [p9, 100, 1500], [p10, 150, 500]], { note: 'PO3-BR1-open' });
  const gr3 = await receive(root, br1, po3, [150, 20, 100, 150], 'GR3 partial (open PO)');
  // PO4 @BR-02 supplier A: receive then VOID the receipt
  const po4 = br2 ? await poFlow(root, br2, sA, [[p1, 30, 2200], [p2, 10, 11000]], { note: 'PO4-BR2-voided-GR' }) : null;
  const gr4 = br2 ? await receive(root, br2, po4, [30, 10], 'GR4 to be voided') : null;
  await waitOutbox('receipts');
  if (gr4) {
    const v = await req('POST', `/goods-receipts/uid/${gr4.uid}/void`, root, { reason: `RV ${RUN} void test` }, H(br2.uid));
    seed('GR4 void', ok2(v) ? 'OK' : 'FAIL', snip(v));
    C.tx.voidedGr = gr4;
  }
  // a BR-02 receipt of p1/p2/p3 so BR-02 can sell those too
  const po5 = br2 ? await poFlow(root, br2, sA, [[p1, 50, 2250], [p2, 20, 11100], [p3, 20, 4850]], { note: 'PO5-BR2-full' }) : null;
  const gr5 = br2 ? await receive(root, br2, po5, [50, 20, 20], 'GR5 full @BR2') : null;
  // direct receipt (no PO) @BR-01 supplier B
  const dr = await req('POST', '/goods-receipts/direct', root, { companyUid: C.companyUid, supplierUid: sB.uid, currency: 'TZS', notes: `RV ${RUN} direct`,
    lines: [{ productUid: p6.uid, unitUid: C.unit.uid, receivedQty: 25, unitCostAmount: 2150 }, { productUid: p5.uid, unitUid: C.unit.uid, receivedQty: 40, unitCostAmount: 950 }] }, H(br1.uid));
  seed('direct receipt @BR1', ok2(dr) ? 'OK' : 'FAIL', snip(dr));
  C.tx.directGr = ok2(dr) ? P(dr) : null;
  await waitOutbox('receipts 2');

  // supplier bill on PO1 at a DIFFERENT price (p1 billed 2350 vs PO 2200) -> price variance
  C.tx.po1 = po1; C.tx.gr1a = gr1a; C.tx.gr1b = gr1b;
  if (po1 && gr1a) {
    const grFull = P(await req('GET', `/goods-receipts/uid/${gr1a.uid}`, root, null, H(br1.uid)));
    const grLines = grFull?.lines || [];
    const findGrLine = (poLine) => grLines.find((g) => String(g.purchaseOrderLineId) === String(poLine.id));
    const lines = po1.lines.slice(0, 2).map((l, i) => {
      const g = findGrLine(l);
      return { poLineUid: l.uid, grLineUid: g?.uid, productId: Number(l.productId), description: `RV bill line ${i + 1}`, billedQty: 60, unitCostAmount: i === 0 ? 2350 : 11000, vatStatus: 'STANDARD', vatRate: 0.18 };
    });
    const net = 60 * 2350 + 60 * 11000;
    const bill = await req('POST', '/ap/supplier-bills', root, { companyUid: C.companyUid, supplierUid: sA.uid, supplierInvoiceNo: `RV-INV-${RUN}-1`, purchaseOrderUid: po1.po.uid,
      billDate: TODAY, vatAmount: r2(net * 0.18), currency: 'TZS', lines }, H(br1.uid));
    if (ok2(bill)) {
      const b = P(bill); C.tx.bill1 = b; seed('supplier bill (price variance)', 'OK');
      const m = await req('POST', `/ap/supplier-bills/uid/${b.uid}/match/run`, root, null, H(br1.uid));
      seed('bill match run', ok2(m) ? 'OK' : 'FAIL', snip(m));
      const mm = P(m);
      const varLines = (mm?.lineResults || []).filter((x) => !/^MATCHED$/i.test(String(x.matchStatus || '')));
      let status = mm?.billStatus;
      for (const vl of varLines) {
        const a = await req('POST', `/ap/supplier-bills/uid/${b.uid}/match/accept-variance`, root, { billLineUid: vl.billLineUid }, H(br1.uid));
        if (!ok2(a)) seed('accept variance', 'FAIL', snip(a)); else status = P(a)?.billStatus;
      }
      seed('bill status after match', /POSTED|APPROVED|OPEN|MATCHED/.test(String(status)) ? 'OK' : 'FAIL', `status=${status} lines=${JSON.stringify((mm?.lineResults || []).map((x) => x.matchStatus))}`);
      // pay it in part
      const pay = await req('POST', '/ap/payments/single', root, { companyUid: C.companyUid, supplierBillUid: b.uid, amount: 50000, paymentDate: TODAY, tenderType: 'BANK_TRANSFER', bankReference: `RV-PAY-${RUN}`, cashBankAccountUid: C.bankAcc?.uid }, H(br1.uid));
      seed('supplier payment', ok2(pay) ? 'OK' : 'FAIL', snip(pay));
      // debit note against the bill
      const dn = await req('POST', '/ap/debit-notes', root, { companyUid: C.companyUid, supplierUid: sA.uid, supplierBillUid: b.uid, noteDate: TODAY, netAmount: 10000, vatAmount: 1800, reason: `RV ${RUN} short-shipped` }, H(br1.uid));
      seed('debit note', ok2(dn) ? 'OK' : 'FAIL', snip(dn));
    } else seed('supplier bill (price variance)', 'FAIL', snip(bill));
  }
  // service bill from supplier D (no PO) + payment WITH WHT 5%
  {
    const svcAcc = C.acc['5200'];
    const bill = await req('POST', '/ap/supplier-bills', root, { companyUid: C.companyUid, supplierUid: sD.uid, supplierInvoiceNo: `RV-SVC-${RUN}`, billDate: TODAY, vatAmount: 0, currency: 'TZS',
      lines: [{ description: 'RV cleaning services', billedQty: 1, unitCostAmount: 400000, vatStatus: 'EXEMPT', glAccountUid: svcAcc?.uid }] }, H(br1.uid));
    if (ok2(bill)) {
      const b = P(bill); C.tx.billSvc = b; seed('service bill', 'OK');
      const m = await req('POST', `/ap/supplier-bills/uid/${b.uid}/match/run`, root, null, H(br1.uid));
      seed('service bill match/post', ok2(m) ? 'OK' : 'FAIL', snip(m));
      const pay = await req('POST', '/ap/payments/single', root, { companyUid: C.companyUid, supplierBillUid: b.uid, amount: 400000, whtTypeUid: C.wht?.uid, whtAmount: 20000, paymentDate: TODAY, tenderType: 'BANK_TRANSFER', bankReference: `RV-WHT-${RUN}`, cashBankAccountUid: C.bankAcc?.uid }, H(br1.uid));
      seed('service bill payment with WHT', ok2(pay) ? 'OK' : 'FAIL', snip(pay));
    } else seed('service bill', 'FAIL', snip(bill));
  }

  // ---------------- stock: transfer BR-01 -> BR-02 (instant) + adjustment
  if (C.loc1 && C.loc2) {
    const t = await req('POST', '/stock-transfers', root, { sourceLocationUid: C.loc1.uid, destLocationUid: C.loc2.uid, transferDate: TODAY, transferMode: 'INSTANT', notes: `RV ${RUN}`,
      lines: [{ productUid: p7.uid, qty: 40, unitUid: C.unit.uid }, { productUid: p9.uid, qty: 20, unitUid: C.unit.uid }] }, H(br1.uid));
    if (ok2(t)) {
      const c = await req('PATCH', `/stock-transfers/uid/${P(t).uid}/complete-instant`, root, null, H(br1.uid));
      seed('stock transfer BR1->BR2', ok2(c) ? 'OK' : 'FAIL', snip(c));
    } else seed('stock transfer BR1->BR2', 'FAIL', snip(t));
  }
  {
    const a = await req('POST', '/stock/adjustments', root, { productUid: p10.uid, quantity: -5, reasonCode: 'DAMAGE', note: `RV ${RUN} water damage` }, H(br1.uid));
    seed('stock adjustment -5 @BR1', ok2(a) ? 'OK' : 'FAIL', snip(a));
  }
  await waitOutbox('stock');

  // ---------------- sales (counter invoices) by two cashiers, with agents/routes, mixed tenders
  const cash1 = C.users.rv_cashier_br1?.token || root;
  const cash2 = C.users.rv_cashier_br2?.token || root;
  const [cWalk, cDuka, cHotel, cShule, cUsd] = C.cust;
  const [aJ, aN] = C.agents; const [rK, rN] = C.routes;
  C.tx.inv = [];
  const pushInv = (x) => { if (x) C.tx.inv.push(x); };
  pushInv(await salesInvoice(cash1, br1, cWalk, [[p1, 45], [p3, 30]], [['CASH', 1]], { note: 'INV-BR1-cash', agent: aJ, route: rK }));             // p1 -> 55 left of 100, p3 -> 20 (<25)
  pushInv(await salesInvoice(cash1, br1, cDuka, [[p2, 35], [p8, 10]], [['MOBILE_MONEY', 1]], { note: 'INV-BR1-mobile', agent: aJ, route: rK })); // p2 -> 25 (<30), p8 -> 10 (<15)
  pushInv(await salesInvoice(cash1, br1, cHotel, [[p5, 30], [p6, 10]], [['CARD', 0.5], ['CASH', 0.5]], { note: 'INV-BR1-card+cash', agent: aN }));
  pushInv(await salesInvoice(root, br1, cShule, [[p10, 50], [p9, 10]], [], { note: 'INV-BR1-credit', agent: aN, route: rK }));                // credit sale (AR)
  if (C.svc) pushInv(await salesInvoice(root, br1, cHotel, [[C.svc, 2]], [], { note: 'INV-BR1-service-credit', agent: aJ }));
  if (br2) {
    pushInv(await salesInvoice(cash2, br2, cWalk, [[p4, 20], [p5, 40]], [['CASH', 1]], { note: 'INV-BR2-cash', agent: aN, route: rN }));
    pushInv(await salesInvoice(cash2, br2, cDuka, [[p1, 20], [p7, 15]], [['MOBILE_MONEY', 0.6], ['CASH', 0.4]], { note: 'INV-BR2-mobile+cash', agent: aN, route: rN }));
    pushInv(await salesInvoice(root, br2, cShule, [[p6, 10]], [], { note: 'INV-BR2-credit', agent: aJ }));
  }
  if (cUsd) pushInv(await salesInvoice(root, br1, cUsd, [[p2, 2]], [], { note: 'INV-BR1-USD-credit', currency: 'USD', usdPrice: 6, agent: aJ }));
  await waitOutbox('sales');

  // credit note against the BR-01 credit invoice + a customer receipt allocated to it
  const arInvs = L(await req('GET', `/ar/invoices?companyId=${C.companyId}&size=200`, root));
  const credInv = C.tx.inv.find((i) => /INV-BR1-credit/.test(i.notes || '')) || null;
  const arFor = (inv) => inv && arInvs.find((a) => a.sourceInvoiceUid === inv.uid || a.salesInvoiceUid === inv.uid || a.salesInvoiceId === inv.id || a.invoiceNumber === inv.invoiceNumber || a.sourceRef === inv.invoiceNumber);
  const arInv = arFor(credInv);
  C.tx.arInv = arInv;
  if (arInv) {
    const cn = await req('POST', '/ar/credit-notes', root, { companyUid: C.companyUid, customerUid: cShule.uid, arInvoiceUid: arInv.uid, noteDate: TODAY, netAmount: 4000, vatAmount: 0, currency: 'TZS', reason: `RV ${RUN} damaged books`, origin: 'STANDALONE' }, H(br1.uid));
    seed('AR credit note', ok2(cn) ? 'OK' : 'FAIL', snip(cn));
    const rc = await req('POST', '/ar/receipts', root, { companyUid: C.companyUid, customerUid: cShule.uid, amount: 20000, currency: 'TZS', receiptDate: TODAY, tenderType: 'BANK_TRANSFER', bankReference: `RV-RC-${RUN}`, cashBankAccountUid: C.bankAcc?.uid,
      allocations: [{ arInvoiceUid: arInv.uid, allocatedAmount: 20000 }] }, H(br1.uid));
    seed('AR receipt', ok2(rc) ? 'OK' : 'FAIL', snip(rc));
  } else seed('AR credit note + receipt', 'SKIP', `no AR invoice found for the credit sale (ar list ${arInvs.length})`);

  await seedPos(root);
  await seedGl(root);
  await waitOutbox('final');
}

async function seedPos(root) {
  // a till per branch, a session per cashier, POS sales with different tenders
  for (const [bk, uname] of [['br1', 'rv_cashier_br1'], ['br2', 'rv_cashier_br2']]) {
    const b = C[bk]; const tok = C.users[uname]?.token; if (!b || !tok) { seed(`POS ${bk}`, 'SKIP', 'no branch or cashier token'); continue; }
    let till = L(await req('GET', `/pos/tills?companyId=${C.companyId}&branchId=${b.id}`, root, null, H(b.uid))).find((t) => t.name === `RV Till ${b.code}`);
    if (!till) {
      const r = await req('POST', '/pos/tills', root, { companyUid: C.companyUid, branchId: Number(b.id), name: `RV Till ${b.code}`, cashBankAccountUid: C.cashAcc?.uid }, H(b.uid));
      if (!ok2(r)) { seed(`POS till ${bk}`, 'FAIL', snip(r)); continue; }
      till = P(r);
    }
    // re-use an OPEN session for this till or open one
    let sess = L(await req('GET', `/pos/sessions?companyId=${C.companyId}&status=OPEN&size=50`, tok, null, H(b.uid))).find((s) => String(s.posTillId) === String(till.id));
    if (!sess) {
      const r = await req('POST', '/pos/sessions', tok, { tillUid: till.uid, openingFloatAmount: 50000 }, H(b.uid));
      if (!ok2(r)) { seed(`POS session ${bk}`, 'FAIL', snip(r)); continue; }
      sess = P(r);
    }
    const walk = C.cust[0];
    const plan = bk === 'br1'
      ? [[[[C.prod[0], 3], [C.prod[6], 4]], 'CASH'], [[[C.prod[4], 5]], 'MOBILE_MONEY'], [[[C.prod[8], 2]], 'CARD']]
      : [[[[C.prod[3], 6]], 'CASH'], [[[C.prod[4], 4], [C.prod[6], 2]], 'MOBILE_MONEY']];
    for (const [i, [lines, tender]] of plan.entries()) {
      const items = lines.map(([p, q]) => ({ productId: Number(p.id), unitId: Number(C.unit.id), quantity: q, unitPrice: p._price }));
      const net = lines.reduce((s, [p, q]) => s + p._price * q * (p._vat === 'STANDARD' ? 1.18 : 1), 0);
      const t = { tenderType: tender, amount: r2(net), reference: `RV-POS-${RUN}-${i}` };
      if (tender !== 'CASH' && C.bankAcc) t.cashBankAccountId = Number(C.bankAcc.id);
      if (tender === 'MOBILE_MONEY') t.mobileMoneyRef = `PMM${RUN}${i}`;
      if (tender === 'CARD') t.cardRef = `PCD${RUN}${i}`;
      const r = await req('POST', '/pos/sales', tok, { sessionUid: sess.uid, customerId: Number(walk.id), agentId: C.agents[0] ? Number(C.agents[0].id) : undefined, currency: 'TZS', lines: items, tenders: [t], tenderedAmount: r2(net), notes: `RV ${RUN}`, belowCostApproved: true },
        { ...H(b.uid), 'Idempotency-Key': `rv-${RUN}-${bk}-${i}` });
      if (!ok2(r)) {
        // tender mismatch (rounding / VAT mode) — retry once with the amount the server asks for
        seed(`POS sale ${bk}#${i} ${tender}`, 'FAIL', snip(r));
      } else seed(`POS sale ${bk}#${i} ${tender}`, 'OK');
    }
  }
}

// ---------------- HR / payroll (idempotent: employees by name, runs by period)
async function seedHr(root) {
  console.log('\n=== PHASE 1c: HR / payroll ===');
  const depts = L(await req('GET', `/hr/departments?companyId=${C.companyId}`, root));
  const dep = {};
  for (const [code, name] of [['RV-FIN', 'RV Finance'], ['RV-OPS', 'RV Operations']]) {
    let d = depts.find((x) => x.code === code);
    if (!d) { const r = await req('POST', '/hr/departments', root, { code, name }); if (ok2(r)) { d = P(r); seed(`department ${code}`, 'OK'); } else seed(`department ${code}`, 'FAIL', snip(r)); }
    dep[code] = d;
  }
  const emps = L(await req('GET', `/hr/employees?companyId=${C.companyId}&size=200`, root));
  const defs = [
    ['Rehema', 'Verify', 'RV-FIN', 'br1', 2500000, true, 'FEMALE'],
    ['Baraka', 'Verify', 'RV-OPS', 'br1', 1200000, false, 'MALE'],
    ['Zawadi', 'Verify', 'RV-OPS', 'br2', 850000, true, 'FEMALE'],
  ];
  C.emps = [];
  for (const [i, [fn, ln, dc, bk, sal, heslb, g]] of defs.entries()) {
    let e = emps.find((x) => x.firstName === fn && x.lastName === ln);
    if (!e) {
      const r = await req('POST', '/hr/employees', root, { firstName: fn, lastName: ln, nationalId: `1990010${i}000${i}`, tin: `30000000${i}`, nssfNumber: `NSSF-RV-${i}`, heslbNumber: heslb ? `HESLB-RV-${i}` : undefined,
        dateOfBirth: '1990-01-15', gender: g, hireDate: `${YEAR - 2}-03-01`, departmentId: dep[dc] ? Number(dep[dc].id) : undefined, jobTitle: 'RV staff', branchId: Number(C[bk].id),
        paymentMethod: 'BANK_TRANSFER', bankName: 'CRDB', bankAccountNo: `01500000${i}`, bankAccountName: `${fn} ${ln}` });
      if (!ok2(r)) { seed(`employee ${fn}`, 'FAIL', snip(r)); continue; }
      e = P(r); seed(`employee ${fn}`, 'OK');
      const c = await req('POST', `/hr/contracts/employee/${e.uid}`, root, { contractType: 'PERMANENT', baseSalaryAmount: sal, startDate: `${YEAR - 2}-03-01`, payeResident: true, nssfMember: true, heslbBorrower: heslb, wcfCovered: true, sdlCounted: true });
      seed(`contract ${fn}`, ok2(c) ? 'OK' : 'FAIL', snip(c));
    }
    C.emps.push(e);
  }
  // two payroll runs: the previous two months, both taken to POSTED; the later one disbursed
  const runs = L(await req('GET', `/hr/payroll-runs?companyId=${C.companyId}&size=100`, root));
  C.payrollRuns = [];
  for (const m of [MONTH - 2, MONTH - 1].filter((x) => x >= 1)) {
    const payDate = new Date(Date.UTC(YEAR, m, 0)).toISOString().slice(0, 10);
    let run = runs.find((x) => Number(x.periodYear) === YEAR && Number(x.periodMonth) === m && !x.branchId && !/REVERSED|CANCELLED/.test(x.status));
    if (!run) {
      const r = await req('POST', '/hr/payroll-runs', root, { periodMonth: m, periodYear: YEAR, payDate });
      if (!ok2(r)) { seed(`payroll run ${YEAR}-${m}`, 'FAIL', snip(r)); continue; }
      run = P(r);
    }
    const step = async (name, path, body) => { const r = await req('POST', `/hr/payroll-runs/uid/${run.uid}/${path}`, root, body); if (ok2(r)) { run = P(r); return true; } seed(`payroll ${YEAR}-${m} ${name}`, 'FAIL', snip(r)); return false; };
    if (run.status === 'DRAFT') await step('calculate', 'calculate');
    if (run.status === 'CALCULATED') await step('approve', 'approve');
    if (run.status === 'APPROVED') await step('post', 'post');
    if (run.status === 'POSTED' && m === MONTH - 1 && C.bankAcc) await step('disburse', 'disburse', { cashBankAccountUid: C.bankAcc.uid, txnDate: payDate });
    seed(`payroll run ${YEAR}-${m} -> ${run.status}`, /POSTED|PAID|DISBURSED/.test(run.status) ? 'OK' : 'FAIL', `uid=${run.uid} gross=${run.grossTotal}`);
    C.payrollRuns.push(run);
  }
  await waitOutbox('payroll');
}

// ---------------- Fixed assets (idempotent: category by code, assets by tag, run by period)
async function seedFa(root) {
  console.log('\n=== PHASE 1d: fixed assets ===');
  const cats = L(await req('GET', `/fixed-assets/categories?companyId=${C.companyId}`, root));
  let cat = cats.find((c) => c.code === 'RV-COMP');
  const accOf = (k) => C.cfg[k] ? Number(C.cfg[k].accountId) : null;
  if (!cat) {
    const r = await req('POST', '/fixed-assets/categories', root, { companyId: C.companyIdN, code: 'RV-COMP', name: 'RV Computers & Equipment', defaultMethod: 'STRAIGHT_LINE', defaultLifePeriods: 36,
      assetAccountId: accOf('FIXED_ASSETS'), accumDepAccountId: accOf('ACCUMULATED_DEPRECIATION'), depExpenseAccountId: accOf('DEPRECIATION_EXPENSE') });
    if (ok2(r)) { cat = P(r); seed('FA category', 'OK'); } else { seed('FA category', 'FAIL', snip(r)); return; }
  }
  C.faCat = cat;
  const assets = L(await req('GET', `/fixed-assets?companyId=${C.companyId}&size=200`, root));
  const prevMonthStart = `${YEAR}-${String(MONTH - 1).padStart(2, '0')}-01`;
  const defs = [
    ['RV-FA-001', 'RV Dell Server', 7200000, 0, 'br1', 'STRAIGHT_LINE', 36, null],
    ['RV-FA-002', 'RV Delivery Van', 48000000, 3000000, 'br2', 'REDUCING_BALANCE', 60, 25],
  ];
  C.assets = [];
  for (const [tag, name, cost, salvage, bk, method, life, rate] of defs) {
    let a = assets.find((x) => x.assetTag === tag);
    if (!a) {
      const r = await req('POST', '/fixed-assets', root, { companyId: C.companyIdN, branchId: Number(C[bk].id), categoryId: Number(cat.id), name, acquisitionCost: cost, salvageValue: salvage, depreciationMethod: method,
        lifePeriods: life, reducingRate: rate ?? undefined, acquisitionDate: `${YEAR}-${String(MONTH - 2).padStart(2, '0')}-15`, depreciationStartDate: prevMonthStart, location: `RV ${bk}`, assetTag: tag });
      if (!ok2(r)) { seed(`FA ${tag}`, 'FAIL', snip(r)); continue; }
      a = P(r); seed(`FA ${tag}`, 'OK');
    }
    if (a.status === 'DRAFT') {
      const r = await req('POST', `/fixed-assets/uid/${a.uid}/place-in-service`, root, { postingDate: prevMonthStart });
      if (ok2(r)) a = P(r) || a; else seed(`FA ${tag} place-in-service`, 'FAIL', snip(r));
    }
    C.assets.push(a);
  }
  // depreciation run for the previous month's period (once)
  const period = C.periods.find((p) => String(p.startDate) === prevMonthStart);
  const runs = L(await req('GET', `/fixed-assets/depreciation-runs?companyId=${C.companyId}&size=50`, root));
  if (period && !runs.some((r) => r.fiscalPeriodUid === period.uid || String(r.fiscalPeriodId) === String(period.id))) {
    const r = await req('POST', '/fixed-assets/depreciation-runs', root, { companyId: C.companyIdN, fiscalPeriodUid: period.uid, postingDate: period.endDate });
    seed('depreciation run', ok2(r) ? 'OK' : 'FAIL', snip(r));
  } else if (!period) seed('depreciation run', 'SKIP', `no fiscal period starting ${prevMonthStart}`);
  await waitOutbox('fa');
}

// ---------------- VAT return for the current month (prepare or recompute)
async function seedVat(root) {
  const list = L(await req('GET', `/vat/returns?companyId=${C.companyId}&size=50`, root));
  let vr = list.find((v) => Number(v.periodYear) === YEAR && Number(v.periodMonth) === MONTH);
  if (!vr) {
    const r = await req('POST', '/vat/returns', root, { companyUid: C.companyUid, periodYear: YEAR, periodMonth: MONTH });
    if (ok2(r)) { vr = P(r); seed('VAT return prepared', 'OK'); } else { seed('VAT return prepare', 'FAIL', snip(r)); return; }
  } else if (!/FILED/.test(vr.status)) {
    const r = await req('POST', `/vat/returns/uid/${vr.uid}/recompute`, root);
    if (ok2(r)) vr = P(r); else seed('VAT return recompute', 'FAIL', snip(r));
  }
  C.vatReturn = vr;
}

// ---------------- reorder levels on on-hand lines (re-set every run relative to current qty)
async function seedReorder(root) {
  const plan = [['br1', 0, 'below'], ['br1', 1, 'below'], ['br1', 2, 'below'], ['br1', 7, 'above'], ['br2', 4, 'below'], ['br2', 5, 'above']];
  for (const [bk, pi, mode] of plan) {
    const b = C[bk], p = C.prod[pi]; if (!b || !p) continue;
    const rows = L(await req('GET', `/stock/on-hand?q=${encodeURIComponent(p.code)}&size=50`, root, null, H(b.uid))).filter((x) => String(x.branchId) === String(b.id) && x.productCode === p.code);
    const row = rows.find((x) => C[`loc${bk.slice(-1)}`] && x.locationUid === C[`loc${bk.slice(-1)}`].uid) || rows[0];
    if (!row) { seed(`reorder level ${bk} ${p.code}`, 'SKIP', 'no on-hand line'); continue; }
    const q = num(row.quantity);
    const level = mode === 'below' ? Math.max(p._rl || 0, Math.ceil(q) + 5) : Math.max(0, Math.floor(q / 2));
    const r = await req('PUT', `/stock/on-hand/uid/${row.uid}/reorder-level`, root, { reorderLevel: level }, H(b.uid));
    seed(`reorder level ${bk} ${p.code}=${level} (qty ${q})`, ok2(r) ? 'OK' : 'FAIL', snip(r));
  }
}

async function seedGl(root) {
  // manual journal: owner capital injection Dr Bank / Cr Owner's Capital (moves equity — Changes in Equity)
  const bank = C.acc['1100'], cap = C.acc['3000'], rent = C.acc['5200'], cash = C.acc['1000'];
  if (bank && cap) {
    const r = await req('POST', '/gl/journals', root, { companyUid: C.companyUid, postingDate: TODAY, description: `RV ${RUN} capital injection`, sourceType: 'MANUAL', sourceRef: `RV-JE-${RUN}`,
      lines: [{ accountUid: bank.uid, debitAmount: 5000000, creditAmount: 0, lineMemo: 'capital' }, { accountUid: cap.uid, debitAmount: 0, creditAmount: 5000000, lineMemo: 'capital' }] }, H(C.br1.uid));
    seed('manual journal (capital)', ok2(r) ? 'OK' : 'FAIL', snip(r));
  }
  if (rent && cash && C.br2) {
    const r = await req('POST', '/gl/journals', root, { companyUid: C.companyUid, postingDate: TODAY, description: `RV ${RUN} BR-02 rent`, sourceType: 'MANUAL', sourceRef: `RV-JE2-${RUN}`,
      lines: [{ accountUid: rent.uid, debitAmount: 300000, creditAmount: 0, lineMemo: 'rent' }, { accountUid: cash.uid, debitAmount: 0, creditAmount: 300000, lineMemo: 'rent' }] }, H(C.br2.uid));
    seed('manual journal (BR-02 rent)', ok2(r) ? 'OK' : 'FAIL', snip(r));
  }
}

// =====================================================================================
// PHASE 3 — every report endpoint: root 200 + exports + RBAC
// =====================================================================================
const BRANCH_MSG = /not assigned to that branch/i;
const CT = { PDF: /application\/pdf/i, XLSX: /spreadsheetml|vnd\.ms-excel|officedocument/i, CSV: /text\/csv|text\/plain/i };
const MAGIC = { PDF: (b) => b.slice(0, 4).toString() === '%PDF', XLSX: (b) => b.slice(0, 2).toString() === 'PK', CSV: (b) => b.length > 0 && b[0] !== 0x25 && b.slice(0, 2).toString() !== 'PK' };
const MIN = { PDF: 800, XLSX: 2000, CSV: 40 };

// Build the report catalogue from what was seeded. Each entry:
//   id, path, q (base query), view (role that holds the view code: 'clerk' | 'fin'), export (path or null),
//   branch: how a branch filter is passed ('uid' -> branchUid, 'id' -> branchId, null = none)
function catalogue() {
  const W = { fromDate: FROM, toDate: TO };
  const co = { companyId: C.companyId };
  const sup1 = C.sup[0], cust = C.cust[3];
  const bankUid = C.bankAcc?.uid, asset = C.assets?.[0], run = C.payrollRuns?.[C.payrollRuns.length - 1], vr = C.vatReturn;
  const list = [
    // sales / stock (clerk-visible)
    { id: 'sales-report', path: '/reports/sales', q: W, view: 'clerk', exp: true, branch: 'uid' },
    ...['CUSTOMER', 'AGENT', 'ROUTE', 'BRANCH', 'DAY'].map((g) => ({ id: `sales-summary[${g}]`, path: '/reports/sales-summary', q: { ...W, groupBy: g }, view: 'clerk', exp: true, branch: 'uid' })),
    { id: 'profitability', path: '/reports/profitability', q: W, view: 'clerk', exp: true, branch: 'uid' },
    { id: 'payment-summary', path: '/reports/payment-summary', q: W, view: 'clerk', exp: true, branch: 'uid' },
    { id: 'reorder', path: '/reports/reorder', q: {}, view: 'clerk', exp: true, branch: 'uid' },
    { id: 'stock-ageing', path: '/reports/stock-ageing', q: {}, view: 'clerk', exp: true, branch: 'uid' },
    { id: 'stock-value', path: '/stock/reports/stock-value', q: {}, view: 'clerk', exp: true, branch: 'uid' },
    { id: 'product-list', path: '/stock/reports/product-list', q: {}, view: 'clerk', exp: true, branch: 'uid' },
    { id: 'stock-report', path: '/stock/report', q: {}, view: 'clerk', exp: true, branch: 'uid' },
    { id: 'stock-movement', path: '/reports/stock-movement', q: W, view: 'clerk', exp: true, branch: 'uid' },
    { id: 'bi-dashboard', path: '/bi/dashboard', q: { ...co, from: FROM, to: TO }, view: 'clerk', exp: true, branch: 'id', expPerm: 'BI.EXPORT' },
    // purchases
    { id: 'goods-received', path: '/reports/purchases/goods-received', q: W, view: 'fin', exp: true, branch: 'uid' },
    { id: 'purchases-by-supplier', path: '/reports/purchases/by-supplier', q: W, view: 'fin', exp: true, branch: 'uid' },
    { id: 'open-purchase-orders', path: '/reports/purchases/open-orders', q: {}, view: 'fin', exp: true, branch: 'uid' },
    { id: 'purchase-price-variance', path: '/reports/purchases/price-variance', q: W, view: 'fin', exp: true, branch: 'uid' },
    // statements
    { id: 'income-statement', path: '/reports/income-statement', q: { ...co, ...W }, view: 'fin', exp: true, branch: 'uid' },
    { id: 'balance-sheet', path: '/reports/balance-sheet', q: { ...co, asAtDate: TO }, view: 'fin', exp: true, branch: 'uid' },
    { id: 'cash-flow', path: '/reports/cash-flow', q: { ...co, ...W }, view: 'fin', exp: true, branch: 'uid' },
    { id: 'changes-in-equity', path: '/reports/changes-in-equity', q: { ...co, ...W }, view: 'fin', exp: true, branch: null },
    { id: 'financial-ratios', path: '/reports/ratios', q: { ...co, ...W }, view: 'fin', exp: true, branch: 'uid' },
    // HR / FA
    { id: 'payroll-statutory', path: '/reports/payroll-statutory', q: W, view: 'fin', exp: true, branch: null },
    run && { id: 'payroll-run-statutory', path: `/hr/payroll-runs/uid/${run.uid}/statutory-summary`, q: {}, view: 'fin', exp: true, branch: null },
    { id: 'fa-register', path: '/fixed-assets/register', q: {}, view: 'fin', exp: true, branch: 'uid' },
    { id: 'fa-reconciliation', path: '/fixed-assets/reconciliation', q: co, view: 'fin', exp: false, branch: null },
    asset && { id: 'fa-schedule', path: `/fixed-assets/uid/${asset.uid}/schedule`, q: {}, view: 'fin', exp: true, branch: null },
    // AR / AP / cash / tax
    cust && { id: 'ar-statement', path: '/ar/statement', q: { ...co, customerUid: cust.uid }, view: 'fin', exp: true, branch: null },
    { id: 'ar-ageing', path: '/ar/ageing', q: co, view: 'fin', exp: false, branch: null },
    { id: 'ar-ageing-by-customer', path: '/ar/ageing/by-customer', q: co, view: 'fin', exp: true, branch: null },
    cust && { id: 'ar-balance', path: '/ar/balance', q: { ...co, customerUid: cust.uid }, view: 'fin', exp: false, branch: null },
    sup1 && { id: 'ap-balance', path: '/ap/statement/balance', q: { ...co, supplierUid: sup1.uid }, view: 'fin', exp: false, branch: null },
    sup1 && { id: 'ap-ageing', path: '/ap/statement/ageing', q: { ...co, supplierUid: sup1.uid }, view: 'fin', exp: true, branch: null },
    sup1 && { id: 'ap-statement', path: '/ap/statement', q: { ...co, supplierUid: sup1.uid }, view: 'fin', exp: true, branch: null, noView: true },
    { id: 'ap-reconciliation', path: '/ap/statement/reconciliation', q: co, view: 'fin', exp: false, branch: null },
    C.acc['1300'] && { id: 'account-ledger', path: '/reports/account-ledger', q: { ...co, accountUid: C.acc['1300'].uid, ...W }, view: 'fin', exp: true, branch: null },
    { id: 'cash-balances', path: '/cash/statements/balances', q: co, view: 'fin', exp: false, branch: null },
    { id: 'cash-gl-reconciliation', path: '/cash/statements/gl-reconciliation', q: co, view: 'fin', exp: false, branch: null },
    bankUid && { id: 'cash-statement', path: `/cash/statements/accounts/uid/${bankUid}/statement`, q: {}, view: 'fin', exp: true, branch: null, expQ: W },
    vr && { id: 'vat-return', path: `/vat/returns/uid/${vr.uid}`, q: {}, view: 'fin', exp: true, branch: null },
    { id: 'wht-register', path: '/wht/register', q: { ...co, year: YEAR, month: MONTH }, view: 'fin', exp: true, branch: null },
  ].filter(Boolean);
  return list;
}

const url = (path, q) => { const s = qs(q || {}); return s ? `${path}?${s}` : path; };
const expPath = (e) => (e.path.endsWith('/statement') && e.id === 'ap-statement') ? '/ap/statement/export' : `${e.path}/export`;

async function checkExport(e, token, who, fmt, extraQ) {
  const r = await req('GET', url(expPath(e), { ...e.q, ...(e.expQ || {}), ...(extraQ || {}), format: fmt }), token);
  const cd = String(r.headers['content-disposition'] || '');
  const ct = String(r.headers['content-type'] || '');
  const fname = (cd.match(/filename="?([^";]+)"?/) || [])[1];
  const okk = r.status === 200 && CT[fmt].test(ct) && MAGIC[fmt](r.buf) && r.buf.length >= MIN[fmt] && !!fname;
  check(`export.${e.id}.${fmt}.${who}`, 'export', okk, { req: r.path, status: r.status, contentType: ct, bytes: r.buf.length, filename: fname || null, err: r.status !== 200 ? errs(r) : undefined });
  return r;
}

async function phaseReports() {
  console.log('\n=== PHASE 3: report endpoints, exports, RBAC ===');
  const root = C.root;
  const tok = (u) => C.users[u]?.token;
  const clerk = tok('rv_clerk_br1'), fin = tok('rv_fin_user'), finBr1 = tok('rv_fin_br1'), noperm = tok('rv_noperm_user');
  const br1 = C.br1, br2 = C.br2;
  const branchQ = (e, b) => (e.branch === 'uid' ? { branchUid: b.uid } : e.branch === 'id' ? { branchId: b.id } : {});
  C.reportData = {};
  for (const e of catalogue()) {
    // --- root view
    if (!e.noView) {
      const r = await req('GET', url(e.path, e.q), root);
      const d = P(r);
      check(`view.${e.id}.root`, 'view', r.status === 200 && d !== null && typeof d === 'object', { req: r.path, status: r.status, err: r.status !== 200 ? errs(r) : undefined, keys: d && typeof d === 'object' ? Object.keys(d).slice(0, 8) : null });
      C.reportData[e.id] = d;
    }
    // --- exports as root, all formats
    if (e.exp) for (const f of ['PDF', 'XLSX', 'CSV']) await checkExport(e, root, 'root', f);
    // --- view-role user (clerk for sales/stock, fin_user otherwise)
    const viewer = e.view === 'clerk' ? clerk : fin;
    const vname = e.view === 'clerk' ? 'clerk_br1' : 'fin_user';
    if (viewer && !e.noView) {
      const r = await req('GET', url(e.path, e.q), viewer);
      check(`rbac.${e.id}.${vname}.no-branch`, 'rbac', r.status === 200, { req: r.path, status: r.status, err: errs(r) });
      if (e.branch && br1 && br2) {
        const r1 = await req('GET', url(e.path, { ...e.q, ...branchQ(e, br1) }), viewer);
        check(`rbac.${e.id}.${vname}.BR-01`, 'rbac', r1.status === 200, { req: r1.path, status: r1.status, err: errs(r1) });
        if (e.view === 'clerk') {
          const r2x = await req('GET', url(e.path, { ...e.q, ...branchQ(e, br2) }), viewer);
          check(`rbac.${e.id}.clerk_br1.BR-02-refused`, 'rbac', r2x.status === 403 && BRANCH_MSG.test(errs(r2x)), { req: r2x.path, status: r2x.status, err: errs(r2x) });
        } else {
          const r2x = await req('GET', url(e.path, { ...e.q, ...branchQ(e, br2) }), fin);
          check(`rbac.${e.id}.fin_user.BR-02-allowed`, 'rbac', r2x.status === 200, { req: r2x.path, status: r2x.status, err: errs(r2x) });
          if (finBr1) {
            const r3 = await req('GET', url(e.path, { ...e.q, ...branchQ(e, br2) }), finBr1);
            check(`rbac.${e.id}.fin_br1.BR-02-refused`, 'rbac', r3.status === 403 && BRANCH_MSG.test(errs(r3)), { req: r3.path, status: r3.status, err: errs(r3) });
          }
        }
      }
    }
    // --- cash-up is managers-only (POS.CASHUP.VIEW): a cashier holds POS.SESSION.VIEW but must be refused
    if (e.id === 'payment-summary' && C.users.rv_cashier_br1?.token) {
      const r = await req('GET', url(e.path, e.q), C.users.rv_cashier_br1.token);
      check(`rbac.${e.id}.cashier-refused`, 'rbac', r.status === 403, { req: r.path, status: r.status, err: errs(r) });
    }
    // --- exports: clerk lacks REPORT.EXPORT -> 403; fin_user holds it -> 200 (one format suffices)
    if (e.exp && clerk) {
      const r = await req('GET', url(expPath(e), { ...e.q, ...(e.expQ || {}), format: 'CSV' }), clerk);
      check(`rbac.${e.id}.export.clerk_br1-refused`, 'rbac', r.status === 403, { req: r.path, status: r.status, err: errs(r) });
    }
    if (e.exp && fin) await checkExport(e, fin, 'fin_user', 'CSV');
    // fin_br1 exporting a BR-02 slice must be refused like the view
    if (e.exp && finBr1 && e.view === 'fin' && e.branch === 'uid' && br2) {
      const r = await req('GET', url(expPath(e), { ...e.q, ...branchQ(e, br2), format: 'CSV' }), finBr1);
      check(`rbac.${e.id}.export.fin_br1.BR-02-refused`, 'rbac', r.status === 403 && BRANCH_MSG.test(errs(r)), { req: r.path, status: r.status, err: errs(r) });
    }
    if (e.exp && clerk && e.view === 'clerk' && e.branch && br2) {
      // even with the export code missing, the refusal must not be a 200 leak
      const r = await req('GET', url(expPath(e), { ...e.q, ...branchQ(e, br2), format: 'CSV' }), clerk);
      check(`rbac.${e.id}.export.clerk_br1.BR-02-refused`, 'rbac', r.status === 403, { req: r.path, status: r.status, err: errs(r) });
    }
    // --- clerk on a finance report -> 403 (no code)
    if (e.view === 'fin' && clerk && !e.noView) {
      const r = await req('GET', url(e.path, e.q), clerk);
      check(`rbac.${e.id}.clerk_br1-refused`, 'rbac', r.status === 403, { req: r.path, status: r.status, err: errs(r) });
    }
    // --- no-permission user -> 403 everywhere
    if (noperm) {
      if (!e.noView) {
        const r = await req('GET', url(e.path, e.q), noperm);
        check(`rbac.${e.id}.noperm-refused`, 'rbac', r.status === 403, { req: r.path, status: r.status, err: errs(r) });
      }
      if (e.exp) {
        const r = await req('GET', url(expPath(e), { ...e.q, ...(e.expQ || {}), format: 'PDF' }), noperm);
        check(`rbac.${e.id}.export.noperm-refused`, 'rbac', r.status === 403, { req: r.path, status: r.status, err: errs(r) });
      }
    }
  }
  // BI sub-endpoints that take a branchId filter (salesByBranch / crm) — clerk with BR-02 refused
  for (const p of ['/bi/sales-by-branch', '/bi/crm-summary']) {
    if (!clerk || !br2) break;
    const r = await req('GET', url(p, { companyId: C.companyId, branchId: br2.id, from: FROM, to: TO }), clerk);
    check(`rbac.${p}.clerk_br1.BR-02`, 'rbac', r.status === 403, { req: r.path, status: r.status, err: errs(r), note: 'clerk lacks BI.FINANCE/BI.CRM.VIEW; 403 either way' });
    const rf = await req('GET', url(p, { companyId: C.companyId, branchId: br2.id, from: FROM, to: TO }), finBr1);
    check(`rbac.${p}.fin_br1.BR-02`, 'rbac', rf.status === 403, { req: rf.path, status: rf.status, err: errs(rf) });
  }
  // payroll EFT bank file (HR.PAYROLL.DISBURSE): root 200 text, fin_user (no DISBURSE) 403
  const run = C.payrollRuns?.[C.payrollRuns.length - 1];
  if (run) {
    const r = await req('GET', `/hr/payroll-runs/uid/${run.uid}/eft-export`, root);
    check('export.payroll-eft.root', 'export', r.status === 200 && r.buf.length > 20, { req: r.path, status: r.status, bytes: r.buf.length, contentType: r.headers['content-type'], disposition: r.headers['content-disposition'] || null, runStatus: run.status, err: r.status !== 200 ? errs(r) : undefined });
    C.eftResp = r;
    const rf = await req('GET', `/hr/payroll-runs/uid/${run.uid}/eft-export`, fin);
    check('rbac.payroll-eft.fin_user-refused', 'rbac', rf.status === 403, { req: rf.path, status: rf.status });
  }
  // a bank file must not be produced for a run nobody has approved/posted (a DRAFT run for December)
  {
    const runs = L(await req('GET', `/hr/payroll-runs?companyId=${C.companyId}&size=100`, root));
    let draft = runs.find((x) => Number(x.periodYear) === YEAR && Number(x.periodMonth) === 12 && x.status === 'DRAFT');
    if (!draft && !runs.some((x) => Number(x.periodYear) === YEAR && Number(x.periodMonth) === 12)) {
      const c = await req('POST', '/hr/payroll-runs', root, { periodMonth: 12, periodYear: YEAR, payDate: `${YEAR}-12-31` });
      if (ok2(c)) draft = P(c); else seed('draft payroll run (Dec)', 'FAIL', snip(c));
    }
    if (draft) {
      const r = await req('GET', `/hr/payroll-runs/uid/${draft.uid}/eft-export`, root);
      check('export.payroll-eft.draft-run-refused', 'export', r.status >= 400 && r.status < 500, { req: r.path, status: r.status, runStatus: draft.status, bytes: r.buf.length, err: errs(r) });
    }
  }
}

// =====================================================================================
// PHASE 4 — cross-report arithmetic
// =====================================================================================
async function get(path, q, token) { const r = await req('GET', url(path, q), token || C.root); return { r, d: P(r) }; }
const sumBy = (rows, k) => (rows || []).reduce((s, x) => s + num(x[k]), 0);
function cmp(id, area, pairs, extra, tol) {
  // pairs: [[label, a, b], ...]
  const bad = pairs.filter(([, a, b]) => !near(a, b, tol ?? 0.011));
  return check(id, area, bad.length === 0, { compared: pairs.map(([l, a, b]) => ({ [l]: [r2(a), r2(b)] })), ...(extra || {}) });
}

async function phaseArithmetic() {
  console.log('\n=== PHASE 4: cross-report arithmetic ===');
  const W = { fromDate: FROM, toDate: TO };
  const co = { companyId: C.companyId };
  const scopes = [['company', {}], ['BR-01', { branchUid: C.br1.uid }], ...(C.br2 ? [['BR-02', { branchUid: C.br2.uid }]] : [])];

  // ---- 4.1 Sales Summary (every groupBy) == Sales Report; Profitability consistent with Sales Summary
  for (const [sname, bq] of scopes) {
    const sr = (await get('/reports/sales', { ...W, ...bq })).d;
    const pr = (await get('/reports/profitability', { ...W, ...bq })).d;
    for (const g of ['CUSTOMER', 'AGENT', 'ROUTE', 'BRANCH', 'DAY']) {
      const ss = (await get('/reports/sales-summary', { ...W, groupBy: g, ...bq })).d;
      if (!ss || !sr) { check(`math.sales-summary[${g}]==sales-report.${sname}`, 'math', false, 'report missing'); continue; }
      const t = ss.totals;
      cmp(`math.sales-summary[${g}]==sales-report.${sname}`, 'math', [
        ['gross', t.grossAmount, sr.totals.amount], ['vat', t.vatAmount, sr.totals.vat], ['qty', t.qty, sr.totals.qtySold], ['discount', t.discount, sr.totals.discount], ['margin', t.margin, sr.totals.margin]]);
      cmp(`math.sales-summary[${g}].rows==totals.${sname}`, 'math', [
        ['gross', sumBy(ss.rows, 'grossAmount'), t.grossAmount], ['net', sumBy(ss.rows, 'netAmount'), t.netAmount], ['vat', sumBy(ss.rows, 'vatAmount'), t.vatAmount],
        ['cost', sumBy(ss.rows, 'costOfSales'), t.costOfSales], ['qty', sumBy(ss.rows, 'qty'), t.qty], ['invoiceCount', g === 'DAY' || g === 'BRANCH' ? sumBy(ss.rows, 'invoiceCount') : t.invoiceCount, t.invoiceCount]], { rows: ss.rows.length }, 0.05);
      cmp(`math.sales-summary[${g}].gross==net+vat.${sname}`, 'math', [['gross', t.grossAmount, num(t.netAmount) + num(t.vatAmount)], ['margin', t.margin, num(t.netAmount) - num(t.costOfSales)]], null, 0.05);
      if (g === 'CUSTOMER' && pr) {
        cmp(`math.profitability==sales-summary.${sname}`, 'math', [['grossSales', pr.totals.grossSales, t.grossAmount], ['netAmount', pr.totals.netAmount, t.netAmount], ['vat', pr.totals.vatAmount, t.vatAmount],
          ['costOfSales', pr.totals.costOfSales, t.costOfSales], ['profit', pr.totals.profit, t.margin], ['qty', pr.totals.qtySold, t.qty]]);
        const dep = pr.departments || [];
        cmp(`math.profitability.departments==totals.${sname}`, 'math', [['grossSales', sumBy(dep, 'grossSales'), pr.totals.grossSales], ['cost', sumBy(dep, 'costOfSales'), pr.totals.costOfSales], ['contribution', sumBy(dep, 'netContribution'), pr.totals.profit]], null, 0.05);
      }
    }
  }
  // SQL: finalised invoice totals in window == sales summary gross (base-currency TZS invoices)
  {
    const ss = (await get('/reports/sales-summary', { ...W, groupBy: 'BRANCH' })).d;
    const q = sql1(`select coalesce(sum(coalesce(base_gross_total_amount, gross_total_amount)),0), count(*) from sales_invoices where company_id=${C.companyId} and status in ('FINALISED','FINALIZED','POSTED','PAID','PARTIALLY_PAID') and finalised_at::date between '${FROM}' and '${TO}'`);
    const st = sql(`select status, currency, count(*), sum(gross_total_amount) from sales_invoices where company_id=${C.companyId} group by 1,2 order by 1,2`);
    cmp('math.sales-summary.gross==SQL(finalised invoices, base currency)', 'math', [['gross', ss?.totals?.grossAmount, q[0]]], { sqlInvoiceCount: q[1], reportInvoiceCount: ss?.totals?.invoiceCount, statusMix: st });
    const usd = sql1(`select count(*), coalesce(sum(gross_total_amount),0) from sales_invoices where company_id=${C.companyId} and currency<>'TZS' and finalised_at is not null`);
    const ssc = (await get('/reports/sales-summary', { ...W, groupBy: 'CUSTOMER' })).d;
    const usdCust = C.cust.find((c) => c.defaultCurrency === 'USD');
    const usdRow = usdCust && (ssc?.rows || []).find((x) => x.groupKey === usdCust.uid || x.groupLabel === usdCust.displayName);
    const fx = sql1(`select coalesce(sum(base_gross_total_amount),0), coalesce(sum(gross_total_amount),0) from sales_invoices where company_id=${C.companyId} and currency<>'TZS' and finalised_at is not null`);
    check('math.sales-summary.foreign-currency-not-summed-raw-into-base', 'math', num(usd[0]) === 0 || !usdRow || !near(usdRow.grossAmount, usd[1]), { reportCurrency: ssc?.currency, foreignInvoices: usd[0], foreignGrossInDocCurrency: usd[1], foreignBaseGross: fx[0], usdCustomerRowGross: usdRow?.grossAmount, note: 'a USD row equal to the raw USD amount under a TZS header means USD was added to TZS 1:1' });
  }

  // ---- 4.2 Payment Summary totals == SQL sum of finalised invoice payments
  for (const [sname, bq] of scopes) {
    const ps = (await get('/reports/payment-summary', { ...W, ...bq })).d;
    if (!ps) { check(`math.payment-summary==SQL.${sname}`, 'math', false, 'report missing'); continue; }
    const bfilter = bq.branchUid ? ` and p.branch_id=(select id from branches where uid='${bq.branchUid}')` : '';
    const rows = sql(`select p.currency, p.tender_type, sum(p.amount - coalesce(p.change_amount,0)), count(*) from sales_invoice_payments p join sales_invoices i on i.id=p.invoice_id
      where p.company_id=${C.companyId} and i.finalised_at is not null and i.status not in ('VOIDED','VOID','DRAFT') and p.received_at::date between '${FROM}' and '${TO}'${bfilter} group by 1,2 order by 1,2`);
    const rowsGross = sql(`select p.currency, p.tender_type, sum(p.amount), sum(coalesce(p.change_amount,0)) from sales_invoice_payments p join sales_invoices i on i.id=p.invoice_id
      where p.company_id=${C.companyId} and i.finalised_at is not null and i.status not in ('VOIDED','VOID','DRAFT') and p.received_at::date between '${FROM}' and '${TO}'${bfilter} group by 1,2 order by 1,2`);
    if (!Array.isArray(rows)) { check(`math.payment-summary==SQL.${sname}`, 'math', false, rows); continue; }
    const key = { CASH: 'cash', MOBILE_MONEY: 'mobileMoney', CARD: 'card', CHEQUE: 'cheque' };
    const pairs = [];
    for (const t of ps.totals || []) {
      for (const [tt, k] of Object.entries(key)) {
        const s = rows.find((x) => x[0] === t.currency && x[1] === tt);
        pairs.push([`${t.currency}.${k}`, t[k], s ? s[2] : 0]);
      }
      pairs.push([`${t.currency}.payments`, t.payments, rows.filter((x) => x[0] === t.currency).reduce((a, x) => a + num(x[3]), 0)]);
      pairs.push([`${t.currency}.rows==total`, sumBy((ps.rows || []).filter((x) => x.currency === t.currency), 'total'), t.total]);
    }
    for (const s of rows) if (!(ps.totals || []).some((t) => t.currency === s[0])) pairs.push([`${s[0]}.missing-in-report`, 0, s[2]]);
    cmp(`math.payment-summary==SQL(invoice payments).${sname}`, 'math', pairs, { sqlGrossAndChange: rowsGross });
  }

  // ---- 4.3 Goods Received Register == Purchases by Supplier (received) per scope
  for (const [sname, bq] of scopes) {
    const reg = (await get('/reports/purchases/goods-received', { ...W, ...bq, size: 1000 })).d;
    const bys = (await get('/reports/purchases/by-supplier', { ...W, ...bq })).d;
    if (!reg || !bys) { check(`math.goods-received==by-supplier.${sname}`, 'math', false, 'report missing'); continue; }
    cmp(`math.goods-received==by-supplier.${sname}`, 'math', [['value', reg.totals.value, bys.totals.receivedValue], ['receipts', reg.totals.receipts, bys.totals.receipts]]);
    cmp(`math.goods-received.rows==totals.${sname}`, 'math', [['value', sumBy(reg.rows, 'value'), reg.totals.value], ['lines', (reg.rows || []).length, reg.totalElements]], { voids: reg.totals.voids, note: 'void rows carry negative values' });
    cmp(`math.by-supplier.rows==totals.${sname}`, 'math', [['received', sumBy(bys.rows, 'receivedValue'), bys.totals.receivedValue], ['net', sumBy(bys.rows, 'netPurchases'), bys.totals.netPurchases],
      ['billed', sumBy(bys.rows, 'billedAmount'), bys.totals.billedAmount], ['unpaid', sumBy(bys.rows, 'unpaidAmount'), bys.totals.unpaidAmount]]);
    const bfilter = bq.branchUid ? ` and gr.branch_id=(select id from branches where uid='${bq.branchUid}')` : '';
    const s = sql1(`select coalesce(sum(l.line_cost_amount),0) from goods_receipt_lines l join goods_receipts gr on gr.id=l.goods_receipt_id where gr.company_id=${C.companyId} and gr.status<>'VOID' and gr.received_at::date between '${FROM}' and '${TO}'${bfilter}`);
    cmp(`math.goods-received==SQL(non-void receipts).${sname}`, 'math', [['value', reg.totals.value, s[0]]]);
  }
  // supplier unpaid (by-supplier) == AP sub-ledger (AP reconciliation)
  {
    const bys = (await get('/reports/purchases/by-supplier', W)).d;
    const rec = (await get('/ap/statement/reconciliation', co)).d;
    cmp('math.by-supplier.unpaid==ap-reconciliation.subledger', 'math', [['unpaid', bys?.totals?.unpaidAmount, rec?.subLedgerTotal]], { note: 'window = full year so every bill is in range' });
  }
  // open POs == SQL outstanding on placed/partially-received orders
  {
    const op = (await get('/reports/purchases/open-orders', {})).d;
    const s = sql1(`select coalesce(sum((l.ordered_qty_in_base - coalesce(l.received_qty_in_base,0) - coalesce(l.cancelled_qty,0)) * l.unit_cost_amount),0), count(*) from purchase_order_lines l join purchase_orders o on o.id=l.purchase_order_id
      where o.company_id=${C.companyId} and o.status in ('ORDERED','PARTIALLY_RECEIVED') and l.ordered_qty_in_base > coalesce(l.received_qty_in_base,0) + coalesce(l.cancelled_qty,0)`);
    cmp('math.open-orders.rows==totals', 'math', [['value', sumBy(op?.rows, 'outstandingValue'), op?.totals?.outstandingValue], ['qty*cost', (op?.rows || []).reduce((a, x) => a + num(x.outstandingQty) * num(x.unitCost), 0), op?.totals?.outstandingValue]], null, 0.05);
    cmp('math.open-orders==SQL', 'math', [['value', op?.totals?.outstandingValue, s.error ? NaN : s[0]], ['lines', op?.totals?.lines, s.error ? NaN : s[1]]], { sqlError: s.error });
  }
  // price variance: rows == totals, and bill variance == (billPrice - poPrice) * billedQty
  {
    const pv = (await get('/reports/purchases/price-variance', W)).d;
    const rows = pv?.rows || [];
    cmp('math.price-variance.rows==totals', 'math', [['bill', sumBy(rows, 'billVarianceTotal'), pv?.totals?.billVarianceTotal], ['receipt', sumBy(rows, 'receiptVarianceTotal'), pv?.totals?.receiptVarianceTotal],
      ['bill=(billPrice-poPrice)*billedQty', rows.reduce((a, x) => a + (num(x.billPrice) - num(x.poPrice)) * num(x.billedQty), 0), pv?.totals?.billVarianceTotal]]);
    check('math.price-variance.has-seeded-variance', 'math', rows.some((x) => near(x.billVariancePerUnit, 150)), { rows: rows.length, perUnit: rows.map((x) => x.billVariancePerUnit) });
  }

  // ---- 4.4 Reorder list == SQL (on-hand <= reorder level, active products)
  for (const [sname, bq] of scopes) {
    const ro = (await get('/reports/reorder', bq)).d;
    const bfilter = bq.branchUid ? ` and soh.branch_id=(select id from branches where uid='${bq.branchUid}')` : '';
    const s = sql(`select p.code, soh.branch_id, soh.quantity, soh.reorder_level from stock_on_hand soh join products p on p.id=soh.product_id
      where soh.company_id=${C.companyId} and soh.reorder_level is not null and soh.quantity <= soh.reorder_level and p.status='ACTIVE'${bfilter} order by 1,2`);
    const rep = (ro?.rows || []).map((x) => x.productCode).sort();
    const exp = Array.isArray(s) ? s.map((x) => x[0]).sort() : [];
    check(`math.reorder==SQL(on-hand<=level).${sname}`, 'math', JSON.stringify(rep) === JSON.stringify(exp) && rep.length > 0, { report: rep, sql: exp, itemCount: ro?.itemCount });
    if (ro) cmp(`math.reorder.itemCount==rows.${sname}`, 'math', [['count', ro.itemCount, (ro.rows || []).length]]);
  }
  // ---- 4.5 Stock Ageing buckets == on-hand == Stock Value
  for (const [sname, bq] of scopes) {
    const ag = (await get('/reports/stock-ageing', bq)).d;
    const sv = (await get('/stock/reports/stock-value', bq)).d;
    if (!ag || !sv) { check(`math.stock-ageing==stock-value.${sname}`, 'math', false, 'missing'); continue; }
    const bq2 = (ag.totalBucketQty || []).reduce((a, b) => a + num(b), 0);
    const bv2 = (ag.totalBucketValue || []).reduce((a, b) => a + num(b), 0);
    cmp(`math.stock-ageing.buckets==totals.${sname}`, 'math', [['qty', bq2 + num(ag.uncoveredQty || 0), ag.totalOnHand], ['value', bv2, ag.totalValue]], { uncoveredProducts: ag.uncoveredProducts }, 0.05);
    const badRows = (ag.rows || []).filter((x) => !near((x.bucketQty || []).reduce((a, b) => a + num(b), 0) + num(x.uncoveredQty), x.onHand, 0.001));
    check(`math.stock-ageing.row-buckets==onHand.${sname}`, 'math', badRows.length === 0, { rows: (ag.rows || []).length, bad: badRows.map((x) => x.productCode) });
    cmp(`math.stock-ageing==stock-value.${sname}`, 'math', [['qty', ag.totalOnHand, sv.totals.totalQuantity], ['value', ag.totalValue, sv.totals.totalCostValue]], null, 0.05 + 0.01 * (ag.rows || []).length);
    const bfilter = bq.branchUid ? ` and branch_id=(select id from branches where uid='${bq.branchUid}')` : '';
    const s = sql1(`select coalesce(sum(quantity),0), coalesce(sum(on_hand_value),0) from stock_on_hand where company_id=${C.companyId}${bfilter} and product_id in (select id from products where status='ACTIVE' and stockable)`);
    cmp(`math.stock-value==SQL(stock_on_hand).${sname}`, 'math', [['qty', sv.totals.totalQuantity, s[0]], ['value', sv.totals.totalCostValue, s[1]]], null, 1);
  }

  {
    const sv = (await get('/stock/reports/stock-value', {})).d;
    const inv = sql1(`select coalesce(sum(l.debit_amount - l.credit_amount),0) from journal_lines l join journal_entries e on e.id=l.entry_id join chart_of_accounts a on a.id=l.account_id where e.company_id=${C.companyId} and a.id=${Number(C.cfg.INVENTORY?.accountId || 0)}`);
    const voidVal = sql1(`select coalesce(sum(l.line_cost_amount),0) from goods_receipt_lines l join goods_receipts gr on gr.id=l.goods_receipt_id where gr.company_id=${C.companyId} and gr.status='VOID'`);
    const rev = sql(`select movement_type, sum(quantity), sum(value_amount) from stock_movements where company_id=${C.companyId} and movement_type in ('GOODS_RECEIPT','GOODS_RECEIPT_REVERSAL') group by 1`);
    cmp('math.stock-value==GL inventory (1300)', 'math', [['value', sv?.totals?.totalCostValue, inv[0]]], { voidedReceiptsValue: voidVal[0], receiptMovements: rev }, 1);
    const hi = sql(`select p.code, round(soh.avg_cost,2), (select max(l.unit_cost_amount) from goods_receipt_lines l where l.product_id=p.id) from stock_on_hand soh join products p on p.id=soh.product_id where soh.company_id=${C.companyId} and soh.quantity>0 and soh.avg_cost > 1.001*(select max(l.unit_cost_amount) from goods_receipt_lines l where l.product_id=p.id)`);
    check('math.stock-value.avg-cost<=max receipt cost', 'math', Array.isArray(hi) && hi.length === 0, { productsAboveMaxCost: hi });
    const cb = sql(`select t.txn_number, t.amount, (select coalesce(sum(l.credit_amount - l.debit_amount),0) from journal_lines l join journal_entries e on e.id=l.entry_id where e.uid=t.journal_entry_ref and l.account_id=cba.gl_account_id) from cash_transactions t join cash_bank_accounts cba on cba.id=t.cash_bank_account_id where t.company_id=${C.companyId} and t.txn_type='AP_PAYMENT'`);
    const badCb = Array.isArray(cb) ? cb.filter((x) => !near(x[1], x[2])) : [];
    check('math.cash-book==GL for AP payments (incl. WHT)', 'math', Array.isArray(cb) && badCb.length === 0, { mismatches: badCb.map(([n, a, g]) => ({ txn: n, cashBook: a, glBankCredit: g })), checked: Array.isArray(cb) ? cb.length : cb });
  }
  // ---- paged registers: paging must neither repeat nor drop rows
  for (const [id, path, q, keyFn] of [
    ['goods-received', '/reports/purchases/goods-received', W, (x) => `${x.entryType}|${x.receiptNumber}|${x.productCode}|${x.quantity}|${x.value}`],
    ['stock-movement[DETAIL]', '/reports/stock-movement', { ...W, mode: 'DETAIL' }, (x) => x.movementUid],
  ]) {
    const full = (await get(path, { ...q, page: 0, size: 5000 })).d;
    const rowsOf = (d) => d?.rows || d?.detailRows || d?.lines || [];
    const fk = rowsOf(full).map(keyFn);
    const pk = [];
    for (let p = 0; p * 7 < fk.length && p < 400; p++) pk.push(...rowsOf((await get(path, { ...q, page: p, size: 7 })).d).map(keyFn));
    const dups = pk.length - new Set(pk).size, miss = fk.filter((k) => !pk.includes(k)).length;
    check(`math.${id}.paging-neither-repeats-nor-drops-rows`, 'math', fk.length > 0 && dups === 0 && miss === 0 && new Set(fk).size === fk.length, { rows: fk.length, pagedRows: pk.length, duplicatesAcrossPages: dups, missingFromPages: miss, pageSize: 7, totalElements: full?.totalElements });
  }
  for (const [sname, bq] of scopes) {
    const sm = (await get('/reports/stock-movement', { ...W, ...bq })).d;
    const sv = (await get('/stock/reports/stock-value', bq)).d;
    const t = sm?.totals || {};
    cmp(`math.stock-movement.closing==stock-value.qty.${sname}`, 'math', [['closingQty', t.closingQty, sv?.totals?.totalQuantity], ['opening+in-out+other', num(t.openingQty) + num(t.purchasesIn) - num(t.salesOut) + num(t.adjustmentsOther) + num(t.transfersIn || 0) - num(t.transfersOut || 0), t.closingQty]], { totals: t });
  }
  // ---- account ledger: running balance continues across pages; closing == full ledger == SQL
  if (C.acc['1300']) {
    const lq = { ...co, accountUid: C.acc['1300'].uid, ...W };
    const p0 = (await get('/reports/account-ledger', { ...lq, page: 0, size: 5 })).d;
    const p1 = (await get('/reports/account-ledger', { ...lq, page: 1, size: 5 })).d;
    const all = (await get('/reports/account-ledger', { ...lq, page: 0, size: 5000 })).d;
    const lr = (d) => d?.lines || d?.rows || d?.entries || [];
    const last0 = lr(p0)[lr(p0).length - 1], first1 = lr(p1)[0];
    const gl = sql1(`select coalesce(sum(l.debit_amount - l.credit_amount),0) from journal_lines l join journal_entries e on e.id=l.entry_id where e.company_id=${C.companyId} and l.account_id=${Number(C.acc['1300'].id)} and e.posting_date between '${FROM}' and '${TO}'`);
    if (last0 && first1) cmp('math.account-ledger.running-balance-continues-on-page-2', 'math', [['page1.first.running', first1.runningBalance, num(last0.runningBalance) + num(first1.debit) - num(first1.credit)],
      ['page1.first == full-ledger row 6', first1.runningBalance, lr(all)[5]?.runningBalance]], { page0Last: last0.runningBalance, page1First: first1 });
    // paging must neither repeat nor drop lines (many lines share one posting date here)
    const keyOf = (x) => `${x.entryUid}|${x.lineMemo}|${x.debit}|${x.credit}`;
    const paged = [];
    for (let p = 0; p * 5 < lr(all).length && p < 400; p++) paged.push(...lr((await get('/reports/account-ledger', { ...lq, page: p, size: 5 })).d).map(keyOf));
    const fullKeys = lr(all).map(keyOf);
    const dups = paged.length - new Set(paged).size, missing = fullKeys.filter((k) => !paged.includes(k)).length;
    check('math.account-ledger.paging-neither-repeats-nor-drops-lines', 'math', dups === 0 && missing === 0, { lines: fullKeys.length, pagedLines: paged.length, duplicatesAcrossPages: dups, missingFromPages: missing, pageSize: 5 });
    cmp('math.account-ledger.closing==last running==SQL', 'math', [['last running', lr(all)[lr(all).length - 1]?.runningBalance, all?.closingBalance], ['closing vs SQL', all?.closingBalance, num(all?.openingBalance) + num(gl[0])]], { lines: lr(all).length, totalElements: all?.totalElements });
  }
  // ---- 4.6 FA register == FA reconciliation
  {
    const reg = (await get('/fixed-assets/register', {})).d;
    const rec = (await get('/fixed-assets/reconciliation', co)).d;
    const g = reg?.grandTotal || {};
    cmp('math.fa-register==fa-reconciliation', 'math', [['cost', g.cost, rec?.registerCostSum], ['accumDep', g.accumulatedDepreciation, rec?.registerAccumDepSum], ['nbv', g.nbv, num(rec?.registerCostSum) - num(rec?.registerAccumDepSum)],
      ['cost==GL', rec?.registerCostSum, rec?.glCostBalance], ['accumDep==GL', rec?.registerAccumDepSum, rec?.glAccumDepBalance]], { ties: [rec?.costTies, rec?.accumDepTies] });
    cmp('math.fa-register.rows==grandTotal', 'math', [['cost', sumBy((reg?.rows || []).filter((x) => x.inTotals), 'cost'), g.cost], ['nbv', sumBy((reg?.rows || []).filter((x) => x.inTotals), 'nbv'), g.nbv],
      ['categories', sumBy(reg?.categoryTotals, 'nbv'), g.nbv]]);
    // reducing-balance sanity: one monthly run must not charge a full annual rate
    const van = (reg?.rows || []).find((x) => /Delivery Van/.test(x.name));
    const runs = sql1(`select count(*) from depreciation_runs where company_id=${C.companyId} and status<>'REVERSED'`);
    if (van) {
      const charged = num(van.accumulatedDepreciation), annual = 0.25 * num(van.cost), monthly = annual / 12;
      check('info.fa.reducing-balance-rate-is-per-period', 'math', null, { asset: van.name, cost: van.cost, accumulatedDepreciation: charged, runs: runs[0], expectedApproxPerMonth: r2(monthly), annualRateCharge: annual, note: '25% reducing-balance asset after one monthly run' });
    }
  }

  // ---- 4.7 Statements: CIE closing == BS equity; P&L branch split; ratios inputs
  const pl = (await get('/reports/income-statement', { ...co, ...W })).d;
  const bs = (await get('/reports/balance-sheet', { ...co, asAtDate: TO })).d;
  const cie = (await get('/reports/changes-in-equity', { ...co, ...W })).d;
  if (pl && bs && cie) {
    cmp('math.changes-in-equity.closing==balance-sheet.equity', 'math', [['closing', cie.totals.closing, bs.totalEquity.current], ['profit', cie.totals.profitForPeriod, pl.netProfit.current], ['reportedBSclosing', cie.balanceSheetClosingEquity, bs.totalEquity.current]]);
    const rows = cie.rows || [];
    cmp('math.changes-in-equity.rows==totals', 'math', [['closing', sumBy(rows, 'closing'), cie.totals.closing], ['capital', sumBy(rows, 'capitalIntroduced'), cie.totals.capitalIntroduced],
      ['row closing = opening+movements', rows.reduce((a, x) => a + num(x.opening) + num(x.profitForPeriod) + num(x.openingBalancesPosted) + num(x.capitalIntroduced) + num(x.drawingsAndDividends) + num(x.transfers), 0), cie.totals.closing]]);
    const capJe = sql1(`select coalesce(sum(l.credit_amount - l.debit_amount),0) from journal_lines l join journal_entries e on e.id=l.entry_id join chart_of_accounts a on a.id=l.account_id where e.company_id=${C.companyId} and a.account_code='3000' and e.posting_date between '${FROM}' and '${TO}'`);
    cmp('math.changes-in-equity.capital==SQL(3000 credits)', 'math', [['capital', cie.totals.capitalIntroduced, capJe.error ? NaN : capJe[0]]], { sqlError: capJe.error });
    cmp('math.balance-sheet.A==L+E', 'math', [['A', bs.totalAssets.current, num(bs.totalLiabilities.current) + num(bs.totalEquity.current)]]);
  }
  // branch P&L: BR-01 + BR-02 + unassigned == company
  if (pl && C.br2) {
    const p1 = (await get('/reports/income-statement', { ...co, ...W, branchUid: C.br1.uid })).d;
    const p2 = (await get('/reports/income-statement', { ...co, ...W, branchUid: C.br2.uid })).d;
    const pu = (await get('/reports/income-statement', { ...co, ...W, unassigned: true })).d;
    if (p1 && p2 && pu) {
      const sec = (d, k) => num((d.sections || []).find((s) => s.sectionKey === k)?.subtotal?.current);
      const keys = (pl.sections || []).map((s) => s.sectionKey);
      cmp('math.income-statement.BR01+BR02+unassigned==company', 'math', [
        ['netProfit', num(p1.netProfit.current) + num(p2.netProfit.current) + num(pu.netProfit.current), pl.netProfit.current],
        ['grossProfit', num(p1.grossProfit.current) + num(p2.grossProfit.current) + num(pu.grossProfit.current), pl.grossProfit.current],
        ...keys.map((k) => [k, sec(p1, k) + sec(p2, k) + sec(pu, k), sec(pl, k)])],
        { labels: [p1.header?.branchLabel, p2.header?.branchLabel, pu.header?.branchLabel], parts: { br1: p1.netProfit.current, br2: p2.netProfit.current, unassigned: pu.netProfit.current } });
      check('math.income-statement.branch-split-nonzero', 'math', num(p1.netProfit.current) !== 0 && num(p2.netProfit.current) !== 0, { br1: p1.netProfit.current, br2: p2.netProfit.current, unassigned: pu.netProfit.current });
    } else check('math.income-statement.branch-split', 'math', false, 'branch P&L missing');
    const b1 = (await get('/reports/balance-sheet', { ...co, asAtDate: TO, branchUid: C.br1.uid })).d;
    const b2 = (await get('/reports/balance-sheet', { ...co, asAtDate: TO, branchUid: C.br2.uid })).d;
    const bu = (await get('/reports/balance-sheet', { ...co, asAtDate: TO, unassigned: true })).d;
    if (b1 && b2 && bu && bs) cmp('math.balance-sheet.BR01+BR02+unassigned==company', 'math', [
      ['assets', num(b1.totalAssets.current) + num(b2.totalAssets.current) + num(bu.totalAssets.current), bs.totalAssets.current],
      ['liabilities', num(b1.totalLiabilities.current) + num(b2.totalLiabilities.current) + num(bu.totalLiabilities.current), bs.totalLiabilities.current],
      ['equity', num(b1.totalEquity.current) + num(b2.totalEquity.current) + num(bu.totalEquity.current), bs.totalEquity.current]], { branchTies: [b1.reconciliation?.ties, b2.reconciliation?.ties, bu.reconciliation?.ties] });
    const c1 = (await get('/reports/cash-flow', { ...co, ...W, branchUid: C.br1.uid })).d;
    const c2 = (await get('/reports/cash-flow', { ...co, ...W, branchUid: C.br2.uid })).d;
    const cu = (await get('/reports/cash-flow', { ...co, ...W, unassigned: true })).d;
    const cc = (await get('/reports/cash-flow', { ...co, ...W })).d;
    const net = (d) => num(d?.netChangeInCash?.current);
    if (c1 && c2 && cu && cc) {
      cmp('math.cash-flow.BR01+BR02+unassigned==company', 'math', [['netChange', net(c1) + net(c2) + net(cu), net(cc)]], { parts: [net(c1), net(c2), net(cu)] });
      const cashLines = (bs?.sections || []).flatMap((x) => x.lines || []).filter((l) => ['1000', '1100'].includes(l.accountCode));
      cmp('math.cash-flow.closing==balance-sheet cash+bank; net==closing-opening', 'math', [['closing', cc.closingCash?.current, cashLines.reduce((a, l) => a + num(l.amounts?.current), 0)], ['net', net(cc), num(cc.closingCash?.current) - num(cc.openingCash?.current)]], { ties: cc.reconciliation?.ties });
    }
    // depreciation lands on the asset's branch: FA register (branch) accumulated dep == branch P&L depreciation expense account
    const depAcc = C.cfg.DEPRECIATION_EXPENSE?.accountCode;
    for (const [bn, b] of [['BR-01', C.br1], ['BR-02', C.br2]]) {
      const fr = (await get('/fixed-assets/register', { branchUid: b.uid })).d;
      const pb = (await get('/reports/income-statement', { ...co, ...W, branchUid: b.uid })).d;
      const line = (pb?.sections || []).flatMap((x) => x.lines || []).find((l) => l.accountCode === depAcc);
      cmp(`math.depreciation-on-asset-branch.${bn}: FA register accum dep == branch P&L ${depAcc}`, 'math', [['dep', fr?.grandTotal?.accumulatedDepreciation, line?.amounts?.current]], { note: 'assets placed in service this year, so accumulated dep == the charge of this year' });
    }
    const mj = sql(`select e.source_ref, e.branch_id, count(*) from journal_entries e where e.company_id=${C.companyId} and e.source_type='MANUAL' and e.source_ref like 'RV-JE2-%' group by 1,2`);
    check('info.manual-journal-branch', 'math', null, { note: 'PostJournalRequest has no branch field; the X-Branch-Uid=BR-02 rent journal lands unassigned', rows: mj });
  }
  // ratios inputs == statement figures
  {
    const ra = (await get('/reports/ratios', { ...co, ...W })).d;
    if (ra && bs && pl) {
      const sec = (d, k) => num((d.sections || []).find((s) => s.sectionKey === k)?.subtotal?.current);
      const inputs = {}; for (const r of ra.ratios || []) for (const i of r.inputs || []) (inputs[i.label] = inputs[i.label] || new Set()).add(r2(i.amount));
      const one = (label) => { const s = inputs[label]; return s ? [...s] : null; };
      const pairs = [];
      const want = { 'Current assets': sec(bs, 'CURRENT_ASSETS'), 'Current liabilities': sec(bs, 'CURRENT_LIABILITIES'), 'Revenue': sec(pl, 'REVENUE'), 'Net profit': pl.netProfit.current, 'Gross profit': pl.grossProfit.current, 'Total assets': bs.totalAssets.current, 'Total equity': bs.totalEquity.current, 'Total liabilities': bs.totalLiabilities.current };
      for (const [label, v] of Object.entries(want)) { const got = one(label); if (got) pairs.push([label, got.length === 1 ? got[0] : NaN, v]); }
      cmp('math.ratios.inputs==statements', 'math', pairs, { inputLabels: Object.keys(inputs), multiValued: Object.entries(inputs).filter(([, s]) => s.size > 1).map(([k, s]) => ({ [k]: [...s] })), ties: [ra.incomeStatementTies, ra.balanceSheetTies] });
      const cr = (ra.ratios || []).find((x) => x.key === 'CURRENT_RATIO');
      if (cr && sec(bs, 'CURRENT_LIABILITIES')) cmp('math.ratios.current-ratio', 'math', [['value', cr.value, r2(sec(bs, 'CURRENT_ASSETS') / sec(bs, 'CURRENT_LIABILITIES'))]], null, 0.011);
    }
  }

  // ---- 4.8 Payroll statutory: per-run == SQL(payroll_lines); period == sum of runs; per-run endpoint == period row
  {
    const ps = (await get('/reports/payroll-statutory', W)).d;
    const runs = ps?.runs || [];
    for (const r of runs) {
      const s = sql1(`select count(*), sum(gross_amount), sum(paye_amount), sum(nssf_employee_amount), sum(nssf_employer_amount), sum(wcf_employer_amount), sum(sdl_employer_amount), sum(heslb_amount), sum(net_amount)
        from payroll_lines where payroll_run_id=(select id from payroll_runs where uid='${r.runUid}')`);
      cmp(`math.payroll-statutory.run ${r.runNumber}==SQL(payroll_lines)`, 'math', [['employees', r.employeeCount, s[0]], ['gross', r.grossTotal, s[1]], ['paye', r.payeTotal, s[2]], ['nssfEe', r.nssfEmployeeTotal, s[3]], ['nssfEr', r.nssfEmployerTotal, s[4]],
        ['wcf', r.wcfTotal, s[5]], ['sdl', r.sdlTotal, s[6]], ['heslb', r.heslbTotal, s[7]], ['net', r.netTotal, s[8]], ['employerCost', r.employerCostTotal, num(s[4]) + num(s[5]) + num(s[6])]]);
      const one = (await get(`/hr/payroll-runs/uid/${r.runUid}/statutory-summary`, {})).d;
      if (one) cmp(`math.payroll-run-statutory ${r.runNumber}==period-row`, 'math', [['gross', one.summary.grossTotal, r.grossTotal], ['paye', one.summary.payeTotal, r.payeTotal], ['net', one.summary.netTotal, r.netTotal],
        ['lines.gross', sumBy(one.lines, 'grossAmount'), one.summary.grossTotal], ['lines.paye', sumBy(one.lines, 'payeAmount'), one.summary.payeTotal], ['lines.net', sumBy(one.lines, 'netAmount'), one.summary.netTotal]]);
    }
    if (ps) {
      const t = ps.totals;
      cmp('math.payroll-statutory.period==sum(runs)', 'math', ['grossTotal', 'payeTotal', 'nssfEmployeeTotal', 'nssfEmployerTotal', 'wcfTotal', 'sdlTotal', 'heslbTotal', 'netTotal', 'employerCostTotal'].map((k) => [k, t[k], sumBy(runs, k)]).concat([['runCount', t.runCount, runs.length], ['payslipCount', t.payslipCount, sumBy(runs, 'employeeCount')]]));
      const glPaye = sql1(`select coalesce(sum(l.credit_amount - l.debit_amount),0) from journal_lines l join journal_entries e on e.id=l.entry_id join chart_of_accounts a on a.id=l.account_id where e.company_id=${C.companyId} and a.account_code='2500' and e.posting_date between '${FROM}' and '${TO}'`);
      cmp('math.payroll-statutory.paye==GL 2500 credits', 'math', [['paye', t.payeTotal, glPaye[0]]]);
    }
  }

  // ---- 4.9 AR: statement export closing == /ar/statement == /ar/balance == ageing-by-customer total
  {
    const byCust = (await get('/ar/ageing/by-customer', co)).d || [];
    const ageing = (await get('/ar/ageing', co)).d || [];
    // compare per currency (the ageing may carry one row per currency once currencies are separated)
    const curs = [...new Set([...ageing, ...byCust].map((x) => x.currency || 'TZS'))];
    cmp('math.ar.ageing-buckets==sum(by-customer) per currency', 'math', curs.map((cu) => [cu, sumBy(ageing.filter((x) => (x.currency || 'TZS') === cu), 'amount'), sumBy(byCust.filter((x) => (x.currency || 'TZS') === cu), 'total')]));
    for (const c of C.cust) {
      const cur = c.defaultCurrency || 'TZS';
      const st = (await get('/ar/statement', { ...co, customerUid: c.uid })).d;
      const bal = (await get('/ar/balance', { ...co, customerUid: c.uid })).d;
      const rows = byCust.filter((x) => String(x.customerId) === String(c.id));
      const row = rows.find((x) => (x.currency || 'TZS') === cur) || (rows.length === 1 ? rows[0] : null);
      const exp = await req('GET', url('/ar/statement/export', { ...co, customerUid: c.uid, currency: cur, format: 'CSV' }), C.root);
      const open = sql1(`select coalesce(sum(outstanding_amount),0) from ar_invoices where customer_id=${c.id} and currency='${cur}' and status not in ('VOID','VOIDED','WRITTEN_OFF')`);
      cmp(`math.ar.${c.displayName}: export-closing==statement==balance==ageing`, 'math', [['export-closing(customer currency) vs statement', csvClosing(exp.raw), st?.totalOutstanding], ['statement vs balance', st?.totalOutstanding, bal?.balance],
        ['ageing(by-customer) vs statement', row ? row.total : 0, st?.totalOutstanding], ['statement ageing buckets', sumBy(st?.ageing, 'amount'), st?.totalOutstanding]],
        { currency: cur, statementCurrency: st?.currency, ageingRowCurrencies: rows.map((x) => x.currency), sqlOpenInvoices: open[0], exportStatus: exp.status });
      if (cur !== 'TZS') {
        const e0 = await req('GET', url('/ar/statement/export', { ...co, customerUid: c.uid, format: 'CSV' }), C.root);
        check(`math.ar.${c.displayName}: default export shows the customer's own currency`, 'math', new RegExp(`Currency: ${cur}`).test(e0.raw) || near(csvClosing(e0.raw), csvClosing(exp.raw)),
          { defaultExportClosing: csvClosing(e0.raw), currencyLine: (e0.raw.match(/Currency: \w+/) || [])[0], closingInOwnCurrency: csvClosing(exp.raw) });
        check(`math.ar.${c.displayName}: statement + ageing labelled in ${cur}`, 'math', st?.currency === cur && rows.length > 0 && rows.every((x) => x.currency === cur),
          { statementCurrency: st?.currency, statementTotal: st?.totalOutstanding, ageingRows: rows.map((x) => ({ currency: x.currency, total: x.total })), sqlOpenInDocCurrency: open[0] });
      }
    }
    const usdC = C.cust.find((x) => x.defaultCurrency === 'USD');
    if (usdC) {
      const e2 = await req('GET', url('/ar/statement/export', { ...co, customerUid: usdC.uid, currency: 'USD', format: 'CSV' }), C.root);
      const s2 = (await get('/ar/statement', { ...co, customerUid: usdC.uid })).d;
      const docSum = sql1(`select coalesce(sum(outstanding_amount),0) from ar_invoices where customer_id=${usdC.id} and currency='USD'`);
      cmp('math.ar.USD customer: export(currency=USD) closing==SQL open USD', 'math', [['closing', csvClosing(e2.raw), docSum[0]]], { statementCurrencyLabel: s2?.currency, statementTotal: s2?.totalOutstanding });
    }
    // invoice reference on the printed statement
    const c = C.cust[3];
    const exp = await req('GET', url('/ar/statement/export', { ...co, customerUid: c.uid, format: 'CSV' }), C.root);
    const invLines = exp.raw.split(/\r?\n/).filter((l) => /^\d{4}-\d\d-\d\d,Invoice,/.test(l));
    const withRef = invLines.filter((l) => /^\d{4}-\d\d-\d\d,Invoice,[^,"]+,/.test(l));
    check('math.ar.statement-export.invoice-lines-carry-reference', 'math', invLines.length > 0 && withRef.length === invLines.length, { invoiceLines: invLines.length, withReference: withRef.length, sample: invLines[0] });
  }
  // ---- 4.10 AP: statement export closing == balance == ageing; reconciliation difference reported
  {
    const rec = (await get('/ap/statement/reconciliation', co)).d;
    let subSum = 0;
    for (const s of C.sup) {
      const bal = (await get('/ap/statement/balance', { ...co, supplierUid: s.uid })).d;
      const age = (await get('/ap/statement/ageing', { ...co, supplierUid: s.uid })).d || [];
      const exp = await req('GET', url('/ap/statement/export', { ...co, supplierUid: s.uid, format: 'CSV' }), C.root);
      const closing = csvClosing(exp.raw);
      subSum += num(bal?.outstandingBalance);
      cmp(`math.ap.${s.displayName}: export-closing==balance==ageing`, 'math', [['export-closing vs balance', closing, bal?.outstandingBalance], ['ageing vs balance', sumBy(age, 'amount'), bal?.outstandingBalance]], { exportStatus: exp.status });
    }
    if (rec) {
      cmp('math.ap.reconciliation.subledger==sum(supplier balances)', 'math', [['subLedger', rec.subLedgerTotal, subSum]]);
      const gl = sql1(`select coalesce(sum(l.credit_amount - l.debit_amount),0) from journal_lines l join journal_entries e on e.id=l.entry_id join chart_of_accounts a on a.id=l.account_id where e.company_id=${C.companyId} and a.account_code='2100'`);
      cmp('math.ap.reconciliation.gl==SQL(2100)', 'math', [['gl', rec.glControlBalance, gl[0]], ['difference', rec.difference, num(rec.subLedgerTotal) - num(rec.glControlBalance)]], { reportedDifference: rec.difference });
    }
  }
  // ---- 4.11 VAT return / WHT register / BI / cash
  {
    const vr = C.vatReturn ? (await get(`/vat/returns/uid/${C.vatReturn.uid}`, {})).d : null;
    const mFrom = `${YEAR}-${String(MONTH).padStart(2, '0')}-01`, mTo = new Date(Date.UTC(YEAR, MONTH, 0)).toISOString().slice(0, 10);
    const ss = (await get('/reports/sales-summary', { fromDate: mFrom, toDate: mTo, groupBy: 'BRANCH' })).d;
    if (vr && ss) cmp('math.vat-return.output==sales-summary.vat (month)', 'math', [['outputVat', vr.outputVat, ss.totals.vatAmount], ['bands', sumBy(vr.bands, 'outputVat'), vr.outputVat], ['net', vr.netVat, num(vr.outputVat) - num(vr.inputVat) + num(vr.adjustmentsTotal) - num(vr.openingCredit)]], { status: vr.status, note: 'VAT return recomputed this run' });
    const wr = (await get('/wht/register', { ...co, year: YEAR, month: MONTH })).d;
    const s = sql1(`select coalesce(sum(wht_amount),0), count(*) from wht_transactions where company_id=${C.companyId} and kind='WHT_ON_PAYMENT' and certificate_date between '${mFrom}' and '${mTo}'`);
    if (wr) {
      cmp('math.wht-register.payable==SQL', 'math', [['payable', wr.totalPayable, s[0]], ['rows', (wr.payableRows || []).length, s[1]], ['rows==total', sumBy(wr.payableRows, 'whtAmount'), wr.totalPayable]]);
      const names = (wr.payableRows || []).map((x) => x.partyName);
      check('math.wht-register.party-name-is-the-supplier', 'math', names.length > 0 && names.every((n) => C.sup.some((sp) => sp.displayName === n)), { partyNames: names, expected: C.sup[3]?.displayName });
    }
    const bi = (await get('/bi/dashboard', { ...co, from: FROM, to: TO })).d;
    const ssAll = (await get('/reports/sales-summary', { ...W, groupBy: 'BRANCH' })).d;
    if (bi && ssAll && pl) {
      const sb = bi.salesByBranch || {};
      cmp('math.bi.sales-by-branch==sales-summary[BRANCH]', 'math', [['grand', sb.grandTotal, ssAll.totals.grossAmount], ['count', sb.invoiceCount, ssAll.totals.invoiceCount],
        ...(sb.rows || []).map((r) => [`branch ${r.branchCode}`, r.total, (ssAll.rows || []).find((x) => x.groupLabel === r.branchName || x.groupCode === r.branchCode)?.grossAmount])]);
      cmp('math.bi.finance==income-statement', 'math', [['revenue', bi.finance?.revenue, (pl.sections || []).find((s) => s.sectionKey === 'REVENUE')?.subtotal?.current], ['netProfit', bi.finance?.netProfit, pl.netProfit.current]]);
      check('info.bi.health', 'math', null, { health: bi.health, stock: bi.inventory, workingCapital: bi.workingCapital, cashTies: bi.finance?.cash?.cashTies, cashGlDifference: bi.finance?.cash?.cashGlDifference });
    }
    if (C.bankAcc) {
      const st = (await get(`/cash/statements/accounts/uid/${C.bankAcc.uid}/statement`, {})).d;
      const bal = (await get(`/cash/statements/accounts/uid/${C.bankAcc.uid}/balance`, {})).d;
      const tx = st?.transactions || [];
      const net = tx.reduce((a, x) => a + (x.direction === 'IN' ? 1 : -1) * num(x.amount), 0);
      const exp = await req('GET', url(`/cash/statements/accounts/uid/${C.bankAcc.uid}/statement/export`, { ...W, format: 'CSV' }), C.root);
      cmp('math.cash-statement: balance==statement==sum(txns)==export-closing', 'math', [['balance vs statement', bal?.bookBalance, st?.currentBalance], ['sum(txns)', net, st?.currentBalance], ['export closing', csvClosing(exp.raw), st?.currentBalance]], { txns: tx.length, exportStatus: exp.status });
    }
  }
}

// last "Closing balance" row's last numeric cell in a CSV statement export
function csvClosing(raw) {
  const line = String(raw || '').split(/\r?\n/).reverse().find((l) => /closing balance/i.test(l));
  if (!line) return NaN;
  const cells = line.match(/"[^"]*"|[^,]+/g) || [];
  const nums = cells.map((c) => c.replace(/"/g, '').replace(/,/g, '').trim()).filter((c) => /^-?\(?[\d.]+\)?$/.test(c)).map((c) => (c.startsWith('(') ? -Number(c.replace(/[()]/g, '')) : Number(c)));
  return nums.length ? nums[nums.length - 1] : NaN;
}

// =====================================================================================
// MAIN
// =====================================================================================
(async () => {
  console.log(`=== REPORTS LIVE VERIFY @ ${B} run ${RUN} (today ${TODAY}) ===`);
  const root = await login(ROOT_USER, ROOT_PASS);
  if (!root) { check('auth.root', 'auth', false, 'root login failed'); return finish(); }
  C.root = root;
  await discover(root);
  await seedMasters(root);
  await seedUsers(root);
  if (SEED_TX) await seedTransactions(root);
  await seedHr(root);
  await seedFa(root);
  await seedVat(root);
  await seedReorder(root);
  await phaseReports();
  await phaseArithmetic();
  finish();
})().catch((e) => { console.error(e); check('script.crash', 'script', false, String(e && e.stack || e)); finish(); });

function finish() {
  const by = { PASS: 0, FAIL: 0, SKIP: 0 };
  for (const c of CHECKS) by[c.status]++;
  console.log(`\n=== RESULT: ${by.PASS} PASS / ${by.FAIL} FAIL / ${by.SKIP} SKIP  (seed steps: ${SEEDLOG.filter((s) => s.status === 'OK').length} ok, ${SEEDLOG.filter((s) => s.status === 'FAIL').length} fail, ${SEEDLOG.filter((s) => s.status === 'SKIP').length} skip)`);
  const areas = {};
  for (const c of CHECKS) { const a = (areas[c.area] = areas[c.area] || { PASS: 0, FAIL: 0, SKIP: 0 }); a[c.status]++; }
  console.log('  area        PASS  FAIL  SKIP');
  for (const [a, v] of Object.entries(areas)) console.log(`  ${a.padEnd(10)} ${String(v.PASS).padStart(5)} ${String(v.FAIL).padStart(5)} ${String(v.SKIP).padStart(5)}`);
  for (const c of CHECKS.filter((x) => x.status === 'FAIL')) console.log(`  FAIL ${c.id}: ${(typeof c.evidence === 'string' ? c.evidence : JSON.stringify(c.evidence)).slice(0, 400)}`);
  fs.writeFileSync(OUT, JSON.stringify({ run: RUN, api: B, today: TODAY, window: { FROM, TO }, summary: by, seed: SEEDLOG, checks: CHECKS }, null, 2));
  console.log(`wrote ${OUT}`);
}
