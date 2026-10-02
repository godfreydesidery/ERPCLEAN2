#!/usr/bin/env node
/*
 * READ-ONLY report check against a real (restored) customer database.
 *
 * Unlike reports-live-verify.js this seeds nothing: it logs in as root, calls every report and
 * export over a wide window, and asserts only the arithmetic that must hold on ANY data set
 * (cross-report totals, statements that must balance, branch slices that must add up).
 * Run it against a THROWAWAY restored copy, never a live client database.
 *
 * Env: API_BASE (default http://127.0.0.1:8095/api/v1), ROOT_USER, ROOT_PASS, FROM, TO, OUT.
 */
'use strict';
const os = require('os');
const fs = require('fs');

const B = process.env.API_BASE || 'http://127.0.0.1:8095/api/v1';
const FROM = process.env.FROM || '2025-01-01';
const TO = process.env.TO || '2026-12-31';
const OUT = process.env.OUT || (os.tmpdir() + '/reports-realdata-verify.json');
const results = [];
const check = (id, ok, evidence) => { results.push({ id, status: ok ? 'PASS' : 'FAIL', evidence }); console.log(`${ok ? 'PASS' : 'FAIL'} ${id}${ok ? '' : ' ' + JSON.stringify(evidence).slice(0, 300)}`); };
const num = (v) => (v === null || v === undefined || v === '' ? 0 : Number(v));
const near = (a, b, tol = 0.011) => Math.abs(num(a) - num(b)) <= tol;
const sumBy = (arr, k) => (arr || []).reduce((s, x) => s + num(x?.[k]), 0);

let TOKEN = null;
async function req(method, path, body, raw = false) {
  const res = await fetch(B + path, { method, headers: { 'Content-Type': 'application/json', ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}) }, body: body ? JSON.stringify(body) : undefined });
  if (raw) return { status: res.status, type: res.headers.get('content-type') || '', size: (await res.arrayBuffer()).byteLength, disp: res.headers.get('content-disposition') || '' };
  let j = null; try { j = await res.json(); } catch { /* non-JSON */ }
  return { status: res.status, d: j?.data, errors: j?.errors, path };
}
const qs = (o) => Object.entries(o).filter(([, v]) => v !== undefined && v !== null && v !== '').map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
const get = (p, q = {}) => req('GET', `${p}${Object.keys(q).length ? '?' + qs(q) : ''}`);
const list = (r) => (Array.isArray(r?.d) ? r.d : r?.d?.content || r?.d?.rows || r?.d?.items || []);

(async () => {
  const login = await req('POST', '/auth/login', { username: process.env.ROOT_USER || 'rootadmin', password: process.env.ROOT_PASS || 'RootPass12345' });
  TOKEN = login.d?.accessToken;
  if (!TOKEN) { console.error('login failed', login); process.exit(2); }
  const companyId = String(JSON.parse(Buffer.from(TOKEN.split('.')[1], 'base64url').toString()).companyId);
  const co = { companyId };
  const W = { fromDate: FROM, toDate: TO };

  // Discover real ids/uids to drive the per-entity reports.
  const orgUid = (await get('/organisations/current')).d?.uid;
  const companies = list(await get('/companies/accessible', { organisationUid: orgUid }));
  const companyUid = (companies.find((c) => String(c.id) === companyId) || companies[0])?.uid;
  const branches = list(await get('/branches', { companyUid })).filter((b) => b.status !== 'ARCHIVED');
  const customers = list(await get('/customers', { ...co, page: 0, size: 50 }));
  const suppliers = list(await get('/suppliers', { ...co, page: 0, size: 50 }));
  const cashAccts = list(await get('/cash/accounts', { ...co, page: 0, size: 50 }));
  const vatReturns = list(await get('/vat/returns', { ...co, page: 0, size: 20 }));
  const payrollRuns = list(await get('/hr/payroll-runs', { ...co, page: 0, size: 20 }));
  const assets = list(await get('/fixed-assets', { ...co, page: 0, size: 20 }));
  console.log(`company ${companyId}: branches ${branches.length}, customers ${customers.length}, suppliers ${suppliers.length}, cash accts ${cashAccts.length}, VAT returns ${vatReturns.length}, payroll runs ${payrollRuns.length}, assets ${assets.length}`);

  // ---- 1. every report answers 200 as root, and every export renders in all three formats
  const cust = customers[0]; const supp = suppliers[0]; const cash = cashAccts[0];
  const E = [
    ['/reports/sales', W], ['/reports/sales-summary', { ...W, groupBy: 'CUSTOMER' }], ['/reports/profitability', W],
    ['/reports/payment-summary', W], ['/reports/reorder', {}], ['/reports/stock-ageing', {}], ['/stock/report', {}],
    ['/reports/stock-movement', { ...W, mode: 'SUMMARY' }], ['/stock/reports/product-list', {}], ['/stock/reports/stock-value', {}],
    ['/stock/valuation/report', co], ['/reports/purchases/goods-received', W], ['/reports/purchases/by-supplier', W],
    ['/reports/purchases/open-orders', {}], ['/reports/purchases/price-variance', W],
    ['/reports/income-statement', { ...co, ...W }], ['/reports/balance-sheet', { ...co, asAtDate: TO }], ['/reports/cash-flow', { ...co, ...W }],
    ['/reports/changes-in-equity', { ...co, ...W }], ['/reports/ratios', { ...co, ...W }], ['/gl/trial-balance', { ...co, asOfDate: TO }],
    ['/fixed-assets/register', { asOf: TO }], ['/reports/payroll-statutory', W], ['/ar/ageing/by-customer', co],
    ['/wht/register', { ...co, periodStart: FROM, periodEnd: TO }], ['/bi/dashboard', { ...co, from: FROM, to: TO }],
  ];
  if (cust) E.push(['/ar/statement', { ...co, customerUid: cust.uid }]);
  if (supp) E.push(['/ap/statement/ageing', { ...co, supplierUid: supp.uid }]);
  if (cash) E.push([`/cash/statements/accounts/uid/${cash.uid}/statement`, {}]);
  const data = {};
  for (const [p, q] of E) {
    const r = await get(p, q);
    data[p] = r.d;
    check(`view ${p}`, r.status === 200 && r.d !== undefined, { status: r.status, errors: r.errors });
  }
  const X = [
    ['/reports/sales/export', W], ['/reports/sales-summary/export', { ...W, groupBy: 'AGENT' }], ['/reports/profitability/export', W],
    ['/reports/payment-summary/export', W], ['/reports/reorder/export', {}], ['/reports/stock-ageing/export', {}],
    ['/reports/purchases/goods-received/export', W], ['/reports/purchases/by-supplier/export', W], ['/reports/purchases/open-orders/export', {}],
    ['/reports/purchases/price-variance/export', W], ['/reports/income-statement/export', { ...co, ...W }],
    ['/reports/balance-sheet/export', { ...co, asAtDate: TO }], ['/reports/changes-in-equity/export', { ...co, ...W }],
    ['/reports/ratios/export', { ...co, ...W }], ['/fixed-assets/register/export', { asOf: TO }], ['/reports/payroll-statutory/export', W],
    ['/ar/ageing/by-customer/export', co], ['/wht/register/export', { ...co, periodStart: FROM, periodEnd: TO }],
  ];
  if (cust) X.push(['/ar/statement/export', { ...co, customerUid: cust.uid }]);
  if (supp) X.push(['/ap/statement/export', { ...co, supplierUid: supp.uid }]);
  if (vatReturns[0]) X.push([`/vat/returns/uid/${vatReturns[0].uid}/export`, {}]);
  for (const [p, q] of X) for (const format of ['PDF', 'XLSX', 'CSV']) {
    const r = await req('GET', `${p}?${qs({ ...q, format })}`, null, true);
    const okType = format === 'PDF' ? /pdf/.test(r.type) : format === 'XLSX' ? /sheet|excel|octet/.test(r.type) : /csv|text/.test(r.type);
    check(`export ${p} ${format}`, r.status === 200 && okType && r.size > 100 && /filename=/.test(r.disp), { status: r.status, type: r.type, size: r.size });
  }

  // ---- 2. cross-report arithmetic that must hold on any data
  const sr = data['/reports/sales'];
  for (const g of ['CUSTOMER', 'AGENT', 'ROUTE', 'BRANCH', 'DAY', 'CASHIER']) {
    const ss = (await get('/reports/sales-summary', { ...W, groupBy: g })).d;
    check(`sales-summary(${g}) total == sales report total`, ss && sr && near(ss.totals?.grossAmount ?? ss.totals?.gross, sr.totals?.amount, 1) && near(sumBy(ss.rows, 'grossAmount'), ss.totals?.grossAmount, 1),
      { summary: ss?.totals?.grossAmount, rowsSum: sumBy(ss?.rows, 'grossAmount'), salesReport: sr?.totals?.amount });
  }
  const pr = data['/reports/profitability'];
  check('profitability net sales == sales-summary net', pr && near(pr.totals?.netAmount, (await get('/reports/sales-summary', { ...W, groupBy: 'BRANCH' })).d?.totals?.netAmount, 1),
    { profitability: pr?.totals });
  const grn = data['/reports/purchases/goods-received']; const bys = data['/reports/purchases/by-supplier'];
  check('goods-received total == purchases-by-supplier received', grn && bys && near(grn.totals?.value, bys.totals?.receivedValue, 1), { grn: grn?.totals, bySupplier: bys?.totals });
  const age = data['/reports/stock-ageing']; const sv = data['/stock/reports/stock-value'];
  check('stock-ageing value == stock-value cost value', age && sv && near(age.totalValue, sv.totals?.totalCostValue, 1), { ageing: age?.totalValue, stockValue: sv?.totals?.totalCostValue });
  const bs = data['/reports/balance-sheet'];
  check('balance sheet balances', bs && near(bs.totalAssets?.current, num(bs.totalLiabilities?.current) + num(bs.totalEquity?.current), 1) && bs.reconciliation?.ties !== false, { assets: bs?.totalAssets, liabilities: bs?.totalLiabilities, equity: bs?.totalEquity, rec: bs?.reconciliation });
  const ce = data['/reports/changes-in-equity'];
  check('changes-in-equity closing == balance-sheet equity', ce && bs && near(ce.totals?.closing, bs.totalEquity?.current, 1), { soce: ce?.totals?.closing, bsEquity: bs?.totalEquity?.current });
  const is = data['/reports/income-statement'];
  if (branches.length > 1 || true) {
    let sum = 0; const parts = {};
    for (const b of branches) { const r = (await get('/reports/income-statement', { ...co, ...W, branchUid: b.uid })).d; parts[b.code] = r?.netProfit?.current; sum += num(r?.netProfit?.current); }
    const un = (await get('/reports/income-statement', { ...co, ...W, unassigned: true })).d; parts.unassigned = un?.netProfit?.current; sum += num(un?.netProfit?.current);
    check('branch P&Ls + company-level == company P&L (net income)', is && branches.length > 0 && near(sum, is.netProfit?.current, 1), { company: is?.netProfit?.current, parts });
  }
  const far = data['/fixed-assets/register']; const farRec = (await get('/fixed-assets/reconciliation', co)).d;
  check('FA register NBV == FA reconciliation', far && farRec && near(far.grandTotal?.cost ?? 0, farRec.registerCostSum, 1) && near(far.grandTotal?.accumulatedDepreciation ?? 0, farRec.registerAccumDepSum, 1), { register: far?.grandTotal, reconciliation: farRec });
  const ps = data['/reports/payroll-statutory'];
  if (ps) check('payroll statutory period == sum of runs', ['grossTotal', 'payeTotal', 'netTotal'].every((k) => near(ps.totals?.[k], sumBy(ps.runs, k), 1)), { totals: ps.totals });
  const apRec = (await get('/ap/statement/reconciliation', co)).d; const arAge = data['/ar/ageing/by-customer'];
  check('AP sub-ledger vs GL reconciliation answers', apRec && apRec.difference !== undefined, { ap: apRec });
  check('AR ageing rows each carry a currency', Array.isArray(arAge) && arAge.every((r) => !!r.currency), { rows: (arAge || []).length });

  const summary = results.reduce((a, r) => ((a[r.status] = (a[r.status] || 0) + 1), a), {});
  fs.writeFileSync(OUT, JSON.stringify({ api: B, companyId, window: { FROM, TO }, summary, results }, null, 2));
  console.log('\nSUMMARY', JSON.stringify(summary), '->', OUT);
})().catch((e) => { console.error(e); process.exit(1); });
