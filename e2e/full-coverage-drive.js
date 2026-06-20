// =====================================================================================
// FULL-COVERAGE API DRIVER (UNIT A) — exercises the newly-built backend capabilities
// (whose UIs were added in the 6 frontend waves) end-to-end against a LIVE QA stack.
//
//   API_BASE=http://16.170.11.41/api/v1 node e2e/full-coverage-drive.js
//
// RULES (per task brief): NEVER abort on a failure. Record every issue
//   { sev, area, msg, detail } and continue. Use realistic volume. The QA DB already
//   has prior data — ADD to it. Writes a JSON + human summary of findings.
//
// Auth: rootadmin (company-wide ADMIN). Discovers the bootstrapped org / company C1 /
//   branch BR-01 for FK use. Captures BOTH id (Long) and uid (String) for every master —
//   the newer modules are split: some DTOs take companyUid/<uid>, others take
//   companyId/<id> (POS, standing-order, CRM activity, pricing-tier lists, etc).
//
// Response shapes vary: some controllers return the DTO directly (body.uid), others wrap
//   in ApiResponse (body.data.uid). pick()/uidOf() handle both.
// =====================================================================================
'use strict';
const http  = require('http');
const https = require('https');
const fs    = require('fs');
const os    = require('os');

const B          = process.env.API_BASE || 'http://16.170.11.41/api/v1';
const ROOT_USER  = process.env.ROOT_USER || 'rootadmin';
const ROOT_PASS  = process.env.ROOT_PASS || 'SKp315goPN8Nb0yJtMCCD7cm';
const ISSUES_OUT = process.env.ISSUES_OUT || (os.tmpdir() + '/erp-full-coverage-issues.json');

// volume knobs (env-tunable; realistic defaults that ADD to existing data)
const N = {
  customers: +(process.env.N_CUSTOMERS || 30),
  suppliers: +(process.env.N_SUPPLIERS || 30),
  products:  +(process.env.N_PRODUCTS  || 50),
  employees: +(process.env.N_EMPLOYEES || 10),
};

const ISSUES = [];
const MODULE = {}; // { module: { pass:n, fail:n } }
const RUN_TAG = Date.now().toString().slice(-7); // unique suffix for codes/names on re-runs

const issue = (sev, area, msg, detail) => {
  ISSUES.push({ sev, area, msg, detail: detail || '' });
  console.log(`  [${sev}] ${area}: ${msg}${detail ? ' — ' + String(detail).slice(0,200) : ''}`);
};
const pass = (m) => { (MODULE[m] = MODULE[m] || { pass: 0, fail: 0 }).pass++; };
const fail = (m) => { (MODULE[m] = MODULE[m] || { pass: 0, fail: 0 }).fail++; };

function req(method, path, token, body) {
  return new Promise((resolve) => {
    const data = body ? JSON.stringify(body) : null;
    const u = new URL(B + path);
    const lib = u.protocol === 'https:' ? https : http;
    const opt = {
      method, hostname: u.hostname, port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    };
    if (data) opt.headers['Content-Length'] = Buffer.byteLength(data);
    const r = lib.request(opt, (res) => {
      let b = ''; res.on('data', c => b += c);
      res.on('end', () => { let j = null; try { j = b ? JSON.parse(b) : null; } catch {} resolve({ status: res.statusCode, body: j, raw: b }); });
    });
    r.on('error', e => resolve({ status: 0, body: null, raw: String(e) }));
    if (data) r.write(data); r.end();
  });
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

// Unwrap: ApiResponse-wrapped (body.data) OR direct-DTO (body). Returns the payload.
function payload(r) {
  if (!r || !r.body) return null;
  if (Object.prototype.hasOwnProperty.call(r.body, 'data')) return r.body.data;
  return r.body;
}
function uidOf(r) { const p = payload(r); return p && p.uid ? p.uid : null; }
function idOf(r)  { const p = payload(r); return p && (p.id != null) ? p.id : null; }
// list payload (array) for both shapes
function listOf(r) {
  const p = payload(r);
  if (Array.isArray(p)) return p;
  if (p && Array.isArray(p.content)) return p.content;
  return [];
}
const snippet = (r) => `status=${r.status} ${(r.raw || '').slice(0, 160)}`;

// ok() helper: 2xx + a uid => pass, else record an issue at `sev`.
function check(r, mod, what, sev = 'HIGH') {
  const u = uidOf(r);
  if (r.status >= 200 && r.status < 300 && u) { pass(mod); return u; }
  fail(mod); issue(sev, mod, `${what} failed`, snippet(r)); return null;
}
// ok2xx(): just needs a 2xx (used for transitions / void-body endpoints).
function ok2xx(r, mod, what, sev = 'HIGH') {
  if (r.status >= 200 && r.status < 300) { pass(mod); return true; }
  fail(mod); issue(sev, mod, `${what} failed`, snippet(r)); return false;
}

async function login(username, password) {
  const r = await req('POST', '/auth/login', null, { username, password });
  if (r.status !== 200 || !r.body?.data?.accessToken) {
    issue('BLOCKER', 'auth', `login failed for ${username}`, snippet(r)); return null;
  }
  return r.body.data.accessToken;
}

(async () => {
  console.log(`=== FULL-COVERAGE DRIVE @ ${B} (run ${RUN_TAG}) ===`);

  // -----------------------------------------------------------------------------------
  // PHASE 0 — login + discover org/company/branch (ids AND uids)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 0: login + discovery ===');
  const root = await login(ROOT_USER, ROOT_PASS);
  if (!root) return dump();

  const orgR = await req('GET', '/organisations', root);
  const orgUid = listOf(orgR)[0]?.uid;
  if (!orgUid) { issue('BLOCKER', 'iam', 'no organisation', snippet(orgR)); return dump(); }

  const compR = await req('GET', `/companies?organisationUid=${orgUid}`, root);
  const company = listOf(compR)[0];
  if (!company?.uid) { issue('BLOCKER', 'iam', 'no company', snippet(compR)); return dump(); }
  const companyUid = company.uid, companyId = company.id;

  let brR = await req('GET', `/branches?companyUid=${companyUid}`, root);
  if (brR.status >= 300) brR = await req('GET', `/branches?companyId=${companyId}`, root);
  const branch = listOf(brR)[0];
  if (!branch?.uid) { issue('BLOCKER', 'iam', 'no branch', snippet(brR)); return dump(); }
  const branchUid = branch.uid, branchId = branch.id;
  console.log(`  org=${orgUid} company=${companyUid}(id ${companyId}) branch=${branchUid}(id ${branchId}, ${branch.code})`);

  // unit of measure (reuse first seeded)
  const unitR = await req('GET', `/units?companyId=${companyId}&size=20`, root);
  let unit = listOf(unitR)[0];
  if (!unit?.uid) {
    const cu = await req('POST', '/units', root, { companyUid, code: 'FCPCS-' + RUN_TAG, name: 'Piece', abbreviation: 'PC', type: 'BASE' });
    unit = payload(cu);
    if (!unit?.uid) issue('HIGH', 'units', 'no unit available and create failed', snippet(cu));
  }
  const unitUid = unit?.uid, unitId = unit?.id;
  console.log(`  unit=${unitUid}(id ${unitId})`);

  // chart-of-accounts presence check (wire-up read) — COA lives at /gl/accounts
  const coaR = await req('GET', `/gl/accounts?companyId=${companyId}&size=5`, root);
  if (coaR.status >= 500) issue('HIGH', 'coa', 'chart-of-accounts returned 5xx', snippet(coaR));
  else if (listOf(coaR).length === 0 && coaR.status < 300) issue('MEDIUM', 'coa', 'chart-of-accounts is EMPTY — GL postings (stock count, year-end) may fail');
  else console.log(`  COA present: ${listOf(coaR).length >= 5 ? '5+' : listOf(coaR).length} accounts (status ${coaR.status})`);

  // -----------------------------------------------------------------------------------
  // PHASE 1 — SEED VOLUME across masters (add to existing)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 1: seed masters ===');

  // products (capture id + uid)
  const products = []; // {id, uid}
  for (let i = 1; i <= N.products; i++) {
    const r = await req('POST', '/products', root, {
      companyUid, name: `FC Product ${RUN_TAG}-${i}`, type: 'GOODS',
      sellable: true, stockable: true, baseUnitUid: unitUid, vatStatus: 'STANDARD',
    });
    const u = check(r, 'seed.product', `product ${i}`, i <= 2 ? 'HIGH' : 'MEDIUM');
    if (u) products.push({ uid: u, id: idOf(r) });
  }
  console.log(`  products: ${products.length}/${N.products}`);

  // price list (reuse or create)
  let plR = await req('GET', `/price-lists?companyId=${companyId}&size=5`, root);
  let priceList = listOf(plR)[0];
  if (!priceList?.uid) { const c = await req('POST', '/price-lists', root, { companyUid, code: 'FCRETAIL-' + RUN_TAG, name: 'FC Retail' }); priceList = payload(c); if (!priceList?.uid) issue('MEDIUM', 'seed.pricelist', 'price list create failed', snippet(c)); }
  const priceListUid = priceList?.uid, priceListId = priceList?.id;

  // set a sale price on each product (so POS/pricing have a list price to validate against)
  for (const p of products.slice(0, 12)) {
    if (!priceListUid) break;
    const sp = await req('POST', `/products/uid/${p.uid}/prices`, root, { priceListUid, price: { amount: '1500', currency: 'TZS' } });
    if (sp.status >= 300) issue('LOW', 'seed.price', `set price for ${p.uid} failed`, snippet(sp));
  }

  // customers
  const customers = [];
  for (let i = 1; i <= N.customers; i++) {
    const r = await req('POST', '/customers', root, { companyId, partyType: 'INDIVIDUAL', displayName: `FC Customer ${RUN_TAG}-${i}`, customerKind: i % 2 ? 'CASH_WALK_IN' : 'CREDIT_ACCOUNT' });
    const u = check(r, 'seed.customer', `customer ${i}`, i <= 2 ? 'HIGH' : 'MEDIUM');
    if (u) customers.push({ uid: u, id: idOf(r) });
  }
  console.log(`  customers: ${customers.length}/${N.customers}`);

  // suppliers
  const suppliers = [];
  for (let i = 1; i <= N.suppliers; i++) {
    const r = await req('POST', '/suppliers', root, { companyId, partyType: 'INDIVIDUAL', displayName: `FC Supplier ${RUN_TAG}-${i}`, supplierKind: 'GOODS' });
    const u = check(r, 'seed.supplier', `supplier ${i}`, i <= 2 ? 'HIGH' : 'MEDIUM');
    if (u) suppliers.push({ uid: u, id: idOf(r) });
  }
  console.log(`  suppliers: ${suppliers.length}/${N.suppliers}`);

  // one agent (POS sale + standing order need an agentId)
  let agent = null;
  {
    const r = await req('POST', '/agents', root, { companyId, partyType: 'INDIVIDUAL', displayName: `FC Agent ${RUN_TAG}`, agentKind: 'EXTERNAL' });
    const u = check(r, 'seed.agent', 'agent', 'MEDIUM');
    if (u) agent = { uid: u, id: idOf(r) };
  }

  // -----------------------------------------------------------------------------------
  // PHASE 1b — STOCK LOCATIONS (newly-built) + opening stock for transfer/count/POS
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 1b: stock locations ===');
  const locations = []; // {uid}
  for (let i = 1; i <= 2; i++) {
    const r = await req('POST', '/stock-locations', root, {
      code: `FCLOC${RUN_TAG}-${i}`, name: `FC Location ${i}`,
      locationType: i === 1 ? 'WAREHOUSE' : 'STORE', branchUid, makeDefault: i === 1,
    });
    const u = check(r, 'stock-location', `create location ${i}`);
    if (u) locations.push({ uid: u });
  }
  console.log(`  stock-locations: ${locations.length}/2`);
  // list (wire-up read)
  { const r = await req('GET', '/stock-locations?size=50', root); if (r.status >= 500) issue('HIGH', 'stock-location', 'list 5xx', snippet(r)); else pass('stock-location'); }

  // Put stock on hand for the first few products via PO -> place -> goods-receipt (proven flow).
  // This feeds POS sale, stock transfer, and stock count.
  console.log('\n=== PHASE 1c: receive opening stock (PO->GR) for first 5 products ===');
  const stockedProducts = []; // products with stock on hand
  for (let i = 0; i < Math.min(5, products.length) && suppliers.length; i++) {
    const p = products[i], sup = suppliers[i % suppliers.length];
    const po = await req('POST', '/purchase-orders', root, { companyUid, supplierUid: sup.uid, currency: 'TZS', lines: [{ productUid: p.uid, unitUid, orderedQty: '200', unitCostAmount: '300' }] });
    const poUid = uidOf(po);
    if (!poUid) { issue('HIGH', 'stock.seed', `PO ${i} failed`, snippet(po)); continue; }
    await req('POST', `/purchase-orders/uid/${poUid}/place`, root);
    const linesR = await req('GET', `/purchase-orders/uid/${poUid}/lines`, root);
    const lineUid = listOf(linesR)[0]?.uid;
    const gr = await req('POST', '/goods-receipts', root, { purchaseOrderUid: poUid, lines: [{ purchaseOrderLineUid: lineUid, receivedQty: '200' }] });
    if (gr.status >= 300) issue('HIGH', 'stock.seed', `GR ${i} failed`, snippet(gr));
    else stockedProducts.push(p);
  }
  console.log(`  stocked products: ${stockedProducts.length}; waiting 8s for outbox to apply receipts...`);
  await sleep(8000);

  // -----------------------------------------------------------------------------------
  // PHASE 2 — POS (till -> session -> sales -> x-read -> close -> reconcile)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 2: POS ===');
  let tillUid = null, sessionUid = null;
  {
    const r = await req('POST', '/pos/tills', root, { companyUid, branchId, name: `FC Till ${RUN_TAG}` });
    tillUid = check(r, 'pos.till', 'create till');
  }
  if (tillUid) {
    const r = await req('POST', '/pos/sessions', root, { tillUid, openingFloatAmount: 100000 });
    sessionUid = check(r, 'pos.session', 'open session');
  }
  if (sessionUid && stockedProducts.length && customers.length && agent) {
    // process a few sales
    let salesOk = 0;
    for (let i = 0; i < 3; i++) {
      const p = stockedProducts[i % stockedProducts.length];
      const cust = customers[i % customers.length];
      const sale = await req('POST', '/pos/sales', root, {
        sessionUid, customerId: cust.id, agentId: agent.id, currency: 'TZS',
        lines: [{ productId: p.id, unitId, quantity: 1, unitPrice: 1500, lineDiscountAmount: 0 }],
        tenderedAmount: 2000, notes: `FC POS sale ${i}`,
      });
      if (sale.status >= 200 && sale.status < 300) { salesOk++; pass('pos.sale'); }
      else { fail('pos.sale'); issue('HIGH', 'pos.sale', `sale ${i} failed`, snippet(sale)); }
    }
    console.log(`  POS sales ok: ${salesOk}/3`);
    // x-read
    const x = await req('GET', `/pos/sessions/uid/${sessionUid}/x-read`, root);
    ok2xx(x, 'pos.xread', 'x-read', 'MEDIUM');
    // close (declare counted cash = float + sales cash; exact figure not asserted)
    const close = await req('POST', `/pos/sessions/uid/${sessionUid}/close`, root, { countedCashAmount: 104500, notes: 'FC close' });
    ok2xx(close, 'pos.close', 'close session');
    // reconcile (posts variance journal)
    const rec = await req('POST', `/pos/sessions/uid/${sessionUid}/reconcile`, root, { notes: 'FC reconcile' });
    ok2xx(rec, 'pos.reconcile', 'reconcile session');
  } else {
    issue('HIGH', 'pos', 'skipped sales/close/reconcile — missing session/stock/customer/agent prerequisites',
      `session=${sessionUid} stocked=${stockedProducts.length} customers=${customers.length} agent=${!!agent}`);
  }

  // -----------------------------------------------------------------------------------
  // PHASE 3 — STOCK TRANSFER (instant + in-transit)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 3: stock transfer ===');
  if (locations.length >= 2 && stockedProducts.length) {
    const srcU = locations[0].uid, dstU = locations[1].uid;
    const today = new Date().toISOString().slice(0, 10);
    const line = [{ productUid: stockedProducts[0].uid, qty: '5' }];
    // instant
    const cInst = await req('POST', '/stock-transfers', root, { sourceLocationUid: srcU, destLocationUid: dstU, transferDate: today, transferMode: 'INSTANT', notes: 'FC instant', lines: line });
    const instUid = check(cInst, 'stock-transfer', 'create instant transfer');
    if (instUid) { const done = await req('PATCH', `/stock-transfers/uid/${instUid}/complete-instant`, root); ok2xx(done, 'stock-transfer', 'complete-instant'); }
    // in-transit: create -> dispatch -> receive
    const cTr = await req('POST', '/stock-transfers', root, { sourceLocationUid: srcU, destLocationUid: dstU, transferDate: today, transferMode: 'IN_TRANSIT', notes: 'FC in-transit', lines: [{ productUid: stockedProducts[0].uid, qty: '5' }] });
    const trUid = check(cTr, 'stock-transfer', 'create in-transit transfer');
    if (trUid) {
      const disp = await req('PATCH', `/stock-transfers/uid/${trUid}/dispatch`, root); ok2xx(disp, 'stock-transfer', 'dispatch');
      const recv = await req('PATCH', `/stock-transfers/uid/${trUid}/receive`, root); ok2xx(recv, 'stock-transfer', 'receive');
    }
    const l = await req('GET', '/stock-transfers?size=20', root); if (l.status >= 500) issue('HIGH', 'stock-transfer', 'list 5xx', snippet(l));
  } else {
    issue('HIGH', 'stock-transfer', 'skipped — need 2 locations + a stocked product', `locations=${locations.length} stocked=${stockedProducts.length}`);
  }

  // -----------------------------------------------------------------------------------
  // PHASE 4 — STOCK COUNT (create -> enter -> post)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 4: stock count ===');
  if (locations.length) {
    const today = new Date().toISOString().slice(0, 10);
    const cc = await req('POST', '/stock-counts', root, { locationUid: locations[0].uid, countDate: today, countType: 'FULL', notes: 'FC count' });
    const ccUid = check(cc, 'stock-count', 'create count');
    if (ccUid) {
      // fetch lines from the count detail
      const det = await req('GET', `/stock-counts/uid/${ccUid}`, root);
      const lines = (payload(det)?.lines) || [];
      if (lines.length) {
        const entries = lines.slice(0, 5).map(ln => ({ lineId: ln.id, countedQty: String(Number(ln.systemQty || 0) + 1), reasonCode: 'FC' }));
        const en = await req('PATCH', `/stock-counts/uid/${ccUid}/enter`, root, { lines: entries });
        ok2xx(en, 'stock-count', 'enter counts');
      } else {
        issue('MEDIUM', 'stock-count', 'count has no lines to enter (location empty?)');
      }
      const post = await req('PATCH', `/stock-counts/uid/${ccUid}/post?postingDate=${today}`, root);
      ok2xx(post, 'stock-count', 'post count');
    }
    const l = await req('GET', '/stock-counts?size=20', root); if (l.status >= 500) issue('HIGH', 'stock-count', 'list 5xx', snippet(l));
  } else issue('HIGH', 'stock-count', 'skipped — no location');

  // -----------------------------------------------------------------------------------
  // PHASE 5 — PURCHASE REQUISITION (create -> submit -> approve -> convert)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 5: purchase requisition ===');
  let reqUid = null;
  if (products.length) {
    const cr = await req('POST', '/purchase-requisitions', root, {
      companyUid, notes: 'FC requisition',
      lines: [{ productId: products[0].id, unitId, requestedQty: '10', estimatedUnitCost: '300', note: 'fc' }],
    });
    reqUid = check(cr, 'purchase-requisition', 'create');
    if (reqUid) {
      ok2xx(await req('POST', `/purchase-requisitions/uid/${reqUid}/submit`, root), 'purchase-requisition', 'submit');
      ok2xx(await req('POST', `/purchase-requisitions/uid/${reqUid}/approve`, root), 'purchase-requisition', 'approve');
      const conv = await req('POST', `/purchase-requisitions/uid/${reqUid}/convert?targetType=RFQ`, root);
      ok2xx(conv, 'purchase-requisition', 'convert->RFQ');
    }
  } else issue('HIGH', 'purchase-requisition', 'skipped — no products');

  // -----------------------------------------------------------------------------------
  // PHASE 6 — RFQ (create -> send -> capture supplier quote -> award)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 6: RFQ + supplier quote ===');
  if (products.length && suppliers.length) {
    const cr = await req('POST', '/rfqs', root, {
      companyUid, responseDueDate: new Date(Date.now() + 7 * 864e5).toISOString().slice(0, 10),
      notes: 'FC rfq', supplierUids: suppliers.slice(0, 2).map(s => s.uid),
      lines: [{ productId: products[0].id, unitId, quantity: '10' }],
    });
    const rfqUid = check(cr, 'rfq', 'create');
    if (rfqUid) {
      ok2xx(await req('POST', `/rfqs/uid/${rfqUid}/send`, root), 'rfq', 'send');
      // need the rfq line id to capture a quote
      const det = await req('GET', `/rfqs/uid/${rfqUid}`, root);
      const rfqLines = payload(det)?.lines || [];
      const rfqLineId = rfqLines[0]?.id;
      if (rfqLineId) {
        const cap = await req('POST', '/supplier-quotes', root, {
          rfqUid, supplierUid: suppliers[0].uid, validUntil: new Date(Date.now() + 14 * 864e5).toISOString().slice(0, 10),
          leadTimeDays: 7, notes: 'FC quote',
          lines: [{ rfqLineId, quotedQty: '10', unitPriceAmount: '320' }],
        });
        const quoteUid = check(cap, 'supplier-quote', 'capture quote');
        if (quoteUid) {
          const award = await req('POST', `/rfqs/uid/${rfqUid}/award?quoteUid=${quoteUid}`, root);
          ok2xx(award, 'rfq', 'award');
        }
      } else issue('HIGH', 'supplier-quote', 'no rfq line id to quote against', snippet(det));
    }
  } else issue('HIGH', 'rfq', 'skipped — need products + suppliers');

  // -----------------------------------------------------------------------------------
  // PHASE 7 — PURCHASE RETURN + LANDED COST (create -> confirm) against a fresh GR
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 7: purchase return + landed cost ===');
  if (products.length && suppliers.length) {
    // fresh PO->place->GR to return against
    const po = await req('POST', '/purchase-orders', root, { companyUid, supplierUid: suppliers[0].uid, currency: 'TZS', lines: [{ productUid: products[0].uid, unitUid, orderedQty: '20', unitCostAmount: '300' }] });
    const poUid = uidOf(po);
    if (poUid) {
      await req('POST', `/purchase-orders/uid/${poUid}/place`, root);
      const linesR = await req('GET', `/purchase-orders/uid/${poUid}/lines`, root);
      const poLineUid = listOf(linesR)[0]?.uid;
      const gr = await req('POST', '/goods-receipts', root, { purchaseOrderUid: poUid, lines: [{ purchaseOrderLineUid: poLineUid, receivedQty: '20' }] });
      const grUid = uidOf(gr);
      if (grUid) {
        // GR lines come back inside the GR body (no /lines sub-path); refetch detail to be safe.
        let grLineUid = (payload(gr)?.lines || [])[0]?.uid;
        if (!grLineUid) { const grDet = await req('GET', `/goods-receipts/uid/${grUid}`, root); grLineUid = (payload(grDet)?.lines || [])[0]?.uid; }
        // purchase return
        if (grLineUid) {
          const pr = await req('POST', '/purchase-returns', root, { companyUid, goodsReceiptUid: grUid, reason: 'FC defective', lines: [{ goodsReceiptLineUid: grLineUid, returnedQty: '2' }] });
          const prUid = check(pr, 'purchase-return', 'create');
          if (prUid) ok2xx(await req('POST', `/purchase-returns/uid/${prUid}/confirm`, root), 'purchase-return', 'confirm');
        } else issue('HIGH', 'purchase-return', 'no GR line uid', snippet(gr));
        // landed cost over this GR
        const lc = await req('POST', '/landed-costs', root, { companyUid, basis: 'BY_VALUE', notes: 'FC freight', receiptUids: [grUid], charges: [{ chargeType: 'FREIGHT', amount: '50000' }] });
        const lcUid = check(lc, 'landed-cost', 'create', 'MEDIUM');
        if (lcUid) ok2xx(await req('POST', `/landed-costs/uid/${lcUid}/confirm`, root), 'landed-cost', 'confirm', 'MEDIUM');
      } else issue('HIGH', 'purchase-return', 'GR for return failed', snippet(gr));
    } else issue('HIGH', 'purchase-return', 'PO for return failed', snippet(po));
  } else issue('HIGH', 'purchase-return', 'skipped — need products + suppliers');

  // -----------------------------------------------------------------------------------
  // PHASE 8 — BOM (create + add components)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 8: BOM ===');
  if (products.length >= 3) {
    const parent = products[0], c1 = products[1], c2 = products[2];
    const cb = await req('POST', `/boms?companyId=${companyId}`, root, { parentProductUid: parent.uid, outputQty: '1', yieldPercent: '100', notes: 'FC bom' });
    const bomUid = check(cb, 'bom', 'create');
    if (bomUid) {
      const a1 = await req('POST', `/boms/uid/${bomUid}/components`, root, { componentProductUid: c1.uid, qtyPer: '2', scrapPercent: '0' });
      ok2xx(a1, 'bom', 'add component 1');
      const a2 = await req('POST', `/boms/uid/${bomUid}/components`, root, { componentProductUid: c2.uid, qtyPer: '3', scrapPercent: '0' });
      ok2xx(a2, 'bom', 'add component 2');
      const lc = await req('GET', `/boms/uid/${bomUid}/components`, root);
      if (listOf(lc).length < 2 && lc.status < 300) issue('MEDIUM', 'bom', `expected 2 components, got ${listOf(lc).length}`);
    }
  } else issue('HIGH', 'bom', 'skipped — need 3 products');

  // -----------------------------------------------------------------------------------
  // PHASE 9 — STANDING ORDER (create + trigger)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 9: standing order ===');
  if (customers.length && products.length) {
    const so = await req('POST', '/standing-orders', root, {
      companyUid, branchId, customerId: customers[0].id, currency: 'TZS',
      frequency: 'WEEKLY', startDate: new Date().toISOString().slice(0, 10),
      lines: [{ productId: products[0].id, unitId, qty: '1', qtyBase: '1', unitPriceAmount: '1500' }],
      notes: 'FC standing',
    });
    const soUid = check(so, 'standing-order', 'create');
    if (soUid) {
      const trig = await req('POST', `/standing-orders/uid/${soUid}/trigger`, root);
      ok2xx(trig, 'standing-order', 'trigger', 'MEDIUM');
    }
  } else issue('HIGH', 'standing-order', 'skipped — need customer + product');

  // -----------------------------------------------------------------------------------
  // PHASE 10 — PRICING RULES (tier + customer price)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 10: pricing rules ===');
  if (products.length && priceListUid) {
    const tier = await req('POST', '/pricing-rules/tiers', root, { companyUid, productUid: products[0].uid, priceListUid, minQty: '10', unitPriceAmount: '1400', currency: 'TZS' });
    ok2xx(tier, 'pricing-rule', 'create tier');
    // list tiers (needs companyId, productId, priceListId)
    if (products[0].id != null && priceListId != null) {
      const lt = await req('GET', `/pricing-rules/tiers?companyId=${companyId}&productId=${products[0].id}&priceListId=${priceListId}`, root);
      if (lt.status >= 500) issue('HIGH', 'pricing-rule', 'list tiers 5xx', snippet(lt));
    }
  } else issue('MEDIUM', 'pricing-rule', 'tier skipped — need product + price list');
  if (products.length && customers.length) {
    const cp = await req('POST', '/pricing-rules/customer-prices', root, { companyUid, customerUid: customers[0].uid, productUid: products[0].uid, unitPriceAmount: '1300', currency: 'TZS', effectiveFrom: new Date().toISOString().slice(0, 10) });
    ok2xx(cp, 'pricing-rule', 'create customer price');
  } else issue('MEDIUM', 'pricing-rule', 'customer-price skipped — need product + customer');

  // -----------------------------------------------------------------------------------
  // PHASE 11 — OTHER PARTY (create)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 11: other party ===');
  {
    const op = await req('POST', '/other-parties', root, { companyId, partyType: 'INDIVIDUAL', displayName: `FC OtherParty ${RUN_TAG}`, otherKind: 'LANDLORD' });
    check(op, 'other-party', 'create');
  }

  // -----------------------------------------------------------------------------------
  // PHASE 12 — CRM activity (against a lead) + complete
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 12: CRM activity ===');
  {
    const lead = await req('POST', '/crm/leads', root, { companyId, displayName: `FC Lead ${RUN_TAG}`, leadSource: 'WEBSITE', contactPerson: 'FC Contact', phone: '0700000000' });
    const leadUid = check(lead, 'crm.lead', 'create lead');
    if (leadUid) {
      const act = await req('POST', '/crm/activities', root, { companyId, activityType: 'TASK', leadUid, subject: 'FC follow-up', body: 'call back', dueDate: new Date(Date.now() + 2 * 864e5).toISOString().slice(0, 10) });
      const actUid = check(act, 'crm.activity', 'create activity');
      if (actUid) ok2xx(await req('POST', `/crm/activities/uid/${actUid}/complete`, root), 'crm.activity', 'complete activity');
    }
  }

  // -----------------------------------------------------------------------------------
  // PHASE 13 — HR (department, employee, contract, statutory PAYE band set)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 13: HR ===');
  let deptId = null;
  {
    const dep = await req('POST', '/hr/departments', root, { code: `FCDEP${RUN_TAG}`, name: 'FC Department' });
    const depUid = check(dep, 'hr.department', 'create department');
    if (depUid) deptId = idOf(dep);
  }
  // employees (volume) + one contract
  const employees = [];
  for (let i = 1; i <= N.employees; i++) {
    const e = await req('POST', '/hr/employees', root, {
      firstName: `FC${RUN_TAG}`, lastName: `Emp${i}`, hireDate: '2025-01-01',
      departmentId: deptId, jobTitle: 'Officer', branchId,
    });
    const u = check(e, 'hr.employee', `employee ${i}`, i <= 2 ? 'HIGH' : 'MEDIUM');
    if (u) employees.push({ uid: u, id: idOf(e) });
  }
  console.log(`  employees: ${employees.length}/${N.employees}`);
  if (employees.length) {
    const ct = await req('POST', `/hr/contracts/employee/${employees[0].uid}`, root, {
      contractType: 'PERMANENT', baseSalaryAmount: '1500000', startDate: '2025-01-01',
      payeResident: true, nssfMember: true, heslbBorrower: false, wcfCovered: true, sdlCounted: true,
    });
    check(ct, 'hr.contract', 'create contract');
  }
  // statutory PAYE band set (TZ-style 2 bands)
  {
    const pb = await req('POST', '/hr/statutory/paye-bands', root, {
      effectiveFrom: '2025-01-01', taxFreeThreshold: '270000', description: `FC PAYE ${RUN_TAG}`,
      bands: [
        { bandNo: 1, lowerBound: '270000', marginalRate: '8',  cumulativeFixedTax: '0' },
        { bandNo: 2, lowerBound: '520000', marginalRate: '20', cumulativeFixedTax: '20000' },
      ],
    });
    check(pb, 'hr.statutory', 'create PAYE band set', 'MEDIUM');
  }

  // -----------------------------------------------------------------------------------
  // PHASE 14 — GL year-end (list fiscal years only; do NOT close the live year)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 14: GL fiscal years (read-only) ===');
  {
    const fy = await req('GET', `/gl/periods/fiscal-years?companyId=${companyId}`, root);
    if (fy.status >= 500) { fail('gl.year-end'); issue('HIGH', 'gl.year-end', 'list fiscal years 5xx', snippet(fy)); }
    else { pass('gl.year-end'); console.log(`  fiscal years: ${listOf(fy).length} (status ${fy.status}) — NOT closing live year`); }
  }

  // -----------------------------------------------------------------------------------
  // PHASE 15 — WIRE-UP READ ENDPOINTS (must not 500)
  // -----------------------------------------------------------------------------------
  console.log('\n=== PHASE 15: wire-up reads (no-500 assertion) ===');
  const cust0 = customers[0], sup0 = suppliers[0], prod0 = products[0];
  const reads = [
    ['ar.receipts',          `/ar/receipts?companyId=${companyId}&size=10`],
    cust0 && ['ar.ageing',   `/ar/ageing?companyId=${companyId}&customerId=${cust0.id}`],
    ['ap.payments',          `/ap/payments?companyId=${companyId}&size=10`],
    ['cash.reconciliations', `/cash/reconciliations?companyId=${companyId}&accountId=0`], // no account -> expect 4xx not 5xx
    ['cash.transfers',       `/cash/transfers?companyId=${companyId}`],
    ['stock.onhand.byloc',   `/stock/on-hand/by-location?companyId=${companyId}&branchId=${branchId}`],
    prod0 && ['stock.onhand.byproduct', `/stock/on-hand/by-product/uid/${prod0.uid}?companyId=${companyId}`],
    ['stock.onhand',         `/stock/on-hand?size=20`],
  ].filter(Boolean);
  for (const [mod, path] of reads) {
    const r = await req('GET', path, root);
    if (r.status >= 500) { fail(mod); issue('HIGH', mod, `read returned 5xx`, `GET ${path} -> ${snippet(r)}`); }
    else { pass(mod); console.log(`  ${mod}: ${r.status}`); }
  }

  dump();
})();

// =====================================================================================
function dump() {
  console.log('\n================ PER-MODULE PASS/FAIL ================');
  const mods = Object.keys(MODULE).sort();
  for (const m of mods) {
    const v = MODULE[m];
    const flag = v.fail > 0 ? (v.pass > 0 ? 'PARTIAL' : 'FAIL') : 'PASS';
    console.log(`  ${flag.padEnd(7)} ${m}: pass=${v.pass} fail=${v.fail}`);
  }

  console.log('\n================ ISSUES BY SEVERITY ================');
  const bySev = { BLOCKER: [], HIGH: [], MEDIUM: [], LOW: [] };
  for (const i of ISSUES) (bySev[i.sev] = bySev[i.sev] || []).push(i);
  const sevCounts = {};
  for (const sev of ['BLOCKER', 'HIGH', 'MEDIUM', 'LOW']) {
    const list = bySev[sev] || [];
    sevCounts[sev] = list.length;
    console.log(`${sev}: ${list.length}`);
    const seen = {};
    for (const i of list) { const k = i.area + ' | ' + i.msg.replace(/\d+/g, '#'); seen[k] = (seen[k] || 0) + 1; }
    for (const [k, n] of Object.entries(seen)) console.log(`   (${n}x) ${k}`);
  }

  const out = {
    apiBase: B, runTag: RUN_TAG, generatedAt: new Date().toISOString(),
    severityCounts: sevCounts,
    modules: MODULE,
    issues: ISSUES,
  };
  fs.writeFileSync(ISSUES_OUT, JSON.stringify(out, null, 2));
  console.log('\nwrote ' + ISSUES_OUT);
}
