/**
 * bulk-seed.mjs — Large realistic "real company" dataset seeder.
 *
 * Usage:  cd web && node e2e/bulk-seed.mjs
 *
 * Strategy:
 *   - Uses Node's built-in http/https (no Playwright dep at runtime, keeps it portable).
 *   - Fires creates in concurrent batches of 30-40 via Promise.all chunks.
 *   - Never aborts on failure — counts {created, failed} per module, logs sample failures.
 *   - Additive: unique run-tag suffix prevents code collisions on re-runs.
 *   - Idempotent masters (price lists, UoM, etc.) are reused when they already exist.
 */

import http from 'http';
import https from 'https';

const BASE       = process.env.API_BASE || 'http://localhost:8081/api/v1';
const ROOT_USER  = process.env.ROOT_USER || 'rootadmin';
const ROOT_PASS  = process.env.ROOT_PASS || 'RootPass12345';
const TAG        = Date.now().toString().slice(-6); // unique per run

// ── volume knobs ─────────────────────────────────────────────────────────────
const V = {
  products:       +(process.env.N_PRODUCTS   || 800),
  customers:      +(process.env.N_CUSTOMERS  || 500),
  suppliers:      +(process.env.N_SUPPLIERS  || 200),
  agents:         +(process.env.N_AGENTS     || 20),
  employees:      +(process.env.N_EMPLOYEES  || 120),
  departments:    +(process.env.N_DEPTS      || 12),
  stockLocations: +(process.env.N_LOCS       || 10),
  otherParties:   +(process.env.N_OTHERS     || 60),
  openingStockPOs:+(process.env.N_OPENING    || 200),
  quotations:     +(process.env.N_QUOTES     || 200),
  salesOrders:    +(process.env.N_SO         || 400),
  directInvoices: +(process.env.N_DINV       || 200),
  salesReturns:   +(process.env.N_SRET       || 50),
  purchaseOrders: +(process.env.N_PO         || 400),
  supplierBills:  +(process.env.N_BILLS      || 300),
  apPayments:     +(process.env.N_APPAY      || 200),
  purchaseReturns:+(process.env.N_PRET       || 30),
  landedCosts:    +(process.env.N_LC         || 20),
  glJournals:     +(process.env.N_JOURNALS   || 500),
  arReceipts:     +(process.env.N_ARRECEIPTS || 300),
  posTills:       +(process.env.N_TILLS      || 5),
  posSessions:    +(process.env.N_SESSIONS   || 30),
  posSalesPerSess:+(process.env.N_POSSALES   || 17),   // ~500 total
  stockTransfers: +(process.env.N_TRANSFERS  || 100),
  stockCounts:    +(process.env.N_COUNTS     || 30),
  crmLeads:       +(process.env.N_LEADS      || 200),
  crmOpps:        +(process.env.N_OPPS       || 100),
  crmActivities:  +(process.env.N_ACTS       || 300),
  projects:       +(process.env.N_PROJECTS   || 30),
  workOrders:     +(process.env.N_WO         || 100),
  fixedAssets:    +(process.env.N_FA         || 100),
  budgets:        +(process.env.N_BUDGETS    || 10),
  cashTransfers:  +(process.env.N_CASHTX     || 50),
  blanketOrders:  +(process.env.N_BLANKETS   || 20),
};

// ── stats ─────────────────────────────────────────────────────────────────────
const STATS = {};  // { module: { ok: n, fail: n, sampleErr: string|null } }
function stat(mod) {
  if (!STATS[mod]) STATS[mod] = { ok: 0, fail: 0, sampleErr: null };
  return STATS[mod];
}
function ok(mod)   { stat(mod).ok++; }
function fail(mod, err) {
  const s = stat(mod); s.fail++;
  if (!s.sampleErr) s.sampleErr = String(err).slice(0, 240);
}

// ── http ────��─────────────────────────────────────────────────────────────────
function req(method, path, token, body) {
  return new Promise(resolve => {
    const data = body ? JSON.stringify(body) : null;
    const url  = new URL(BASE + path);
    const lib  = url.protocol === 'https:' ? https : http;
    const opts = {
      method,
      hostname: url.hostname,
      port:     url.port || (url.protocol === 'https:' ? 443 : 80),
      path:     url.pathname + url.search,
      headers:  {
        'Content-Type':  'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
      },
    };
    if (data) opts.headers['Content-Length'] = Buffer.byteLength(data);
    const r = lib.request(opts, res => {
      let b = ''; res.on('data', c => b += c);
      res.on('end', () => {
        let j = null;
        try { j = b ? JSON.parse(b) : null; } catch {}
        resolve({ status: res.statusCode, body: j, raw: b });
      });
    });
    r.on('error', e => resolve({ status: 0, body: null, raw: String(e) }));
    if (data) r.write(data);
    r.end();
  });
}

function payload(r) {
  if (!r?.body) return null;
  return Object.prototype.hasOwnProperty.call(r.body, 'data') ? r.body.data : r.body;
}
function uidOf(r)  { const p = payload(r); return p?.uid ?? null; }
function idOf(r)   { const p = payload(r); return p?.id  ?? null; }
function listOf(r) {
  const p = payload(r);
  if (Array.isArray(p)) return p;
  if (p && Array.isArray(p.content)) return p.content;
  return [];
}
function snip(r) { return `HTTP ${r.status}: ${(r.raw||'').slice(0,200)}`; }

// Chunk an array into sub-arrays of size n
function chunks(arr, n) {
  const out = [];
  for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
  return out;
}

// Create many items concurrently (batchSize at a time).
// factory(i) -> Promise<{ uid, id }|null>
async function bulkCreate(mod, count, batchSize, factory) {
  const results = [];
  for (const batch of chunks([...Array(count).keys()], batchSize)) {
    const res = await Promise.all(batch.map(i => factory(i).catch(e => { fail(mod, e); return null; })));
    results.push(...res.filter(Boolean));
  }
  return results;
}

async function tryCreate(mod, path, token, body) {
  const r = await req('POST', path, token, body);
  const u = uidOf(r);
  if (r.status >= 200 && r.status < 300 && u) { ok(mod); return { uid: u, id: idOf(r), body: payload(r) }; }
  fail(mod, snip(r)); return null;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

// date helpers
function daysAgo(n)    { const d = new Date(); d.setDate(d.getDate()-n); return d.toISOString().slice(0,10); }
function daysAhead(n)  { const d = new Date(); d.setDate(d.getDate()+n); return d.toISOString().slice(0,10); }
function randDate(minDaysAgo, maxDaysAgo) { return daysAgo(Math.floor(Math.random()*(maxDaysAgo-minDaysAgo))+minDaysAgo); }
function today() { return new Date().toISOString().slice(0,10); }

const FIRST_NAMES = ['John','Mary','Peter','Grace','David','Amina','Samuel','Faith','Joseph','Rose',
                     'George','Anna','Paul','Neema','James','Esther','Michael','Lucia','Daniel','Joy'];
const LAST_NAMES  = ['Kimani','Odhiambo','Mwangi','Ndege','Abubakar','Makosi','Tanaka','Farah',
                     'Osei','Nakamura','Silva','Patel','Garcia','Müller','Kofi','Owusu','Dlamini','Nkosi'];
const COMPANY_SFX = ['Ltd','Corp','Inc','Group','Holdings','Enterprises','Co','Associates','Solutions','Services'];
const ITEM_CATS   = ['Electronics','Stationery','Food','Beverages','Clothing','Hardware','Chemicals',
                     'Medical','Agricultural','Automotive','Furniture','Textiles','Plastics','Metals'];
function rnd(arr) { return arr[Math.floor(Math.random()*arr.length)]; }
function rndInt(lo, hi) { return Math.floor(Math.random()*(hi-lo+1))+lo; }
function rndAmt(lo, hi) { return (Math.random()*(hi-lo)+lo).toFixed(2); }

// ─────────────────────────────────────────────────────────────────────────────
(async () => {
  const T0 = Date.now();
  console.log(`\n${'='.repeat(70)}`);
  console.log(`BULK-SEED  TAG=${TAG}  API=${BASE}  ${new Date().toISOString()}`);
  console.log(`${'='.repeat(70)}\n`);

  // ── PHASE 0: login + discovery ──────────────────────────────────────────────
  console.log('=== PHASE 0: login + discovery ===');
  const loginR = await req('POST', '/auth/login', null, { username: ROOT_USER, password: ROOT_PASS });
  if (loginR.status !== 200 || !loginR.body?.data?.accessToken) {
    console.error('FATAL: login failed', snip(loginR)); process.exit(1);
  }
  const TOKEN = loginR.body.data.accessToken;
  const userId = loginR.body.data.userId || loginR.body.data.user?.id || null;
  console.log('  login OK, userId=', userId);

  const orgR   = await req('GET', '/organisations', TOKEN);
  const org    = listOf(orgR)[0];
  if (!org?.uid) { console.error('FATAL: no org', snip(orgR)); process.exit(1); }
  const orgUid = org.uid;

  const compR  = await req('GET', `/companies?organisationUid=${orgUid}`, TOKEN);
  const company = listOf(compR)[0];
  if (!company?.uid) { console.error('FATAL: no company', snip(compR)); process.exit(1); }
  const companyUid = company.uid;
  const companyId  = company.id;

  let brR = await req('GET', `/branches?companyUid=${companyUid}`, TOKEN);
  if (brR.status >= 300) brR = await req('GET', `/branches?companyId=${companyId}`, TOKEN);
  const branch   = listOf(brR)[0];
  if (!branch?.uid) { console.error('FATAL: no branch', snip(brR)); process.exit(1); }
  const branchUid = branch.uid;
  const branchId  = branch.id;
  console.log(`  org=${orgUid}  company=${companyUid}(${companyId})  branch=${branchUid}(${branchId})`);

  // ── Reuse or create UoM ──────────────────────────────────────────────────────
  const unitR = await req('GET', `/units?companyId=${companyId}&size=50`, TOKEN);
  let unitUid, unitId;
  const existingUnits = listOf(unitR);
  if (existingUnits.length) {
    unitUid = existingUnits[0].uid; unitId = existingUnits[0].id;
    console.log(`  reusing unit ${unitUid} (${existingUnits[0].name})`);
  } else {
    const cu = await req('POST', '/units', TOKEN, { companyUid, code: `PCS-${TAG}`, name: 'Piece', abbreviation: 'PC', type: 'BASE' });
    unitUid = uidOf(cu); unitId = idOf(cu);
    if (!unitUid) { console.error('FATAL: cannot create unit', snip(cu)); process.exit(1); }
  }

  // Extra UoM (kg, ltr, box)
  const extraUnits = [];
  for (const [code, name, abbr] of [['KG','Kilogram','KG'],['LTR','Litre','LT'],['BOX','Box','BX'],['SET','Set','ST'],['PKT','Packet','PKT']]) {
    const ex = existingUnits.find(u => u.code === code || u.abbreviation === abbr);
    if (ex) { extraUnits.push({ uid: ex.uid, id: ex.id }); continue; }
    const r = await req('POST', '/units', TOKEN, { companyUid, code: `${code}-${TAG}`, name, abbreviation: abbr, type: 'BASE' });
    if (uidOf(r)) extraUnits.push({ uid: uidOf(r), id: idOf(r) });
  }
  const allUnits = [{ uid: unitUid, id: unitId }, ...extraUnits];

  // ── Reuse or create Tax rates ────────────────────────────────────────────────
  const taxR   = await req('GET', `/tax-rates?companyId=${companyId}&size=20`, TOKEN);
  let taxUid = null;
  if (listOf(taxR).length) { taxUid = listOf(taxR)[0].uid; }
  else {
    const ct = await req('POST', '/tax-rates', TOKEN, { companyUid, code: `VAT18-${TAG}`, name: 'VAT 18%', rate: 18, taxType: 'VAT' });
    taxUid = uidOf(ct);
  }

  // ── Reuse or create Price lists ──��───────────────────────────────────────────
  const plR = await req('GET', `/price-lists?companyId=${companyId}&size=10`, TOKEN);
  let priceLists = listOf(plR);
  const plNeeded = ['RETAIL','WHOLESALE','EXPORT'];
  for (const n of plNeeded) {
    if (priceLists.find(p => p.code?.includes(n) || p.name?.includes(n))) continue;
    const r = await req('POST', '/price-lists', TOKEN, { companyUid, code: `${n}-${TAG}`, name: `${n.charAt(0)+n.slice(1).toLowerCase()} Price List` });
    if (uidOf(r)) { ok('price-list'); priceLists.push(payload(r)); }
    else fail('price-list', snip(r));
  }
  const priceListUid = priceLists[0]?.uid;
  const priceListId  = priceLists[0]?.id;
  console.log(`  priceLists=${priceLists.length}  taxUid=${taxUid}  unitUid=${unitUid}`);

  // ── GL accounts (discover for journals + fixed assets) ───────────────────────
  let glAccounts = [];
  {
    let pg = 0, more = true;
    while (more && pg < 10) {
      const r = await req('GET', `/gl/accounts?companyId=${companyId}&size=100&page=${pg}`, TOKEN);
      const items = listOf(r);
      glAccounts.push(...items);
      more = items.length === 100; pg++;
    }
  }
  console.log(`  GL accounts found: ${glAccounts.length}`);
  // Pick account types for journal DR/CR (revenue + expense) and FA
  const revenueAccs  = glAccounts.filter(a => a.accountCode?.startsWith('4') || a.accountType === 'REVENUE');
  const expenseAccs  = glAccounts.filter(a => a.accountCode?.startsWith('5') || a.accountType === 'EXPENSE');
  const assetAccs    = glAccounts.filter(a => a.accountCode?.startsWith('1') || a.accountType === 'ASSET');
  const liabAccs     = glAccounts.filter(a => a.accountCode?.startsWith('2') || a.accountType === 'LIABILITY');
  // fallback: just use first accounts found
  const drAcc  = expenseAccs[0]  || glAccounts[0];
  const crAcc  = revenueAccs[0]  || glAccounts[1] || glAccounts[0];
  const faAssetAcc   = assetAccs[0]  || glAccounts[0];
  const faAccumAcc   = assetAccs[1]  || glAccounts[1] || glAccounts[0];
  const faExpAcc     = expenseAccs[0] || glAccounts[2] || glAccounts[0];
  console.log(`  DR acc=${drAcc?.uid}  CR acc=${crAcc?.uid}  FA asset=${faAssetAcc?.uid}`);

  // ── Fiscal periods (for depreciation run + budget) ───────────────────────────
  const fyR = await req('GET', `/gl/periods/fiscal-years?companyId=${companyId}`, TOKEN);
  const fy = listOf(fyR)[0];
  const fyUid = fy?.uid;
  let periodsForFY = [];
  if (fyUid) {
    const pR = await req('GET', `/gl/periods?companyId=${companyId}&fiscalYearUid=${fyUid}&size=50`, TOKEN);
    periodsForFY = listOf(pR);
  }
  const openPeriod = periodsForFY.find(p => p.status === 'OPEN') || periodsForFY[0];
  console.log(`  FY=${fyUid}  openPeriod=${openPeriod?.uid}`);

  // ════════════════════════════════════════════════════════════════════════════
  // PHASE 1: MASTERS
  // ════════════════════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 1: Masters ===');

  // ── 1a: Stock Locations ──────────────────────────────────────────────────────
  const locTypes = ['WAREHOUSE','STORE','VAN','QUARANTINE','OTHER'];
  const locations = await bulkCreate('stock-location', V.stockLocations, 10, async i => {
    const r = await req('POST', '/stock-locations', TOKEN, {
      code: `LOC${TAG}-${i+1}`,
      name: `Location ${ITEM_CATS[i % ITEM_CATS.length]} ${i+1}`,
      locationType: locTypes[i % locTypes.length],
      branchUid,
      makeDefault: i === 0,
    });
    if (uidOf(r)) return { uid: uidOf(r), id: idOf(r) };
    fail('stock-location', snip(r)); return null;
  });
  console.log(`  stock-locations: ${locations.length}/${V.stockLocations}`);

  // Reuse already-seeded locations if we got fewer than 2
  if (locations.length < 2) {
    const existing = await req('GET', '/stock-locations?size=50', TOKEN);
    for (const l of listOf(existing)) {
      if (!locations.find(x => x.uid === l.uid)) locations.push({ uid: l.uid, id: l.id });
    }
  }

  // ── 1b: Routes ───────────────────────────────────────────────────────────────
  const routeNames = ['North','South','East','West','Central','Coastal','Highlands','Lakeside','Metro','Suburbs'];
  const routes = [];
  for (let i = 0; i < 10; i++) {
    const r = await req('POST', '/routes', TOKEN, {
      companyUid, code: `RT${TAG}-${i+1}`, name: `${routeNames[i]} Route`,
    });
    if (uidOf(r)) { ok('route'); routes.push({ uid: uidOf(r), id: idOf(r) }); }
    else { fail('route', snip(r)); }
  }
  console.log(`  routes: ${routes.length}/10`);

  // ── 1c: Products (800) ───────────────────────────────────────────────────────
  const BATCH = 35;
  const products = await bulkCreate('product', V.products, BATCH, async i => {
    const isService = i % 8 === 7;  // ~12.5% service products
    const cat = ITEM_CATS[i % ITEM_CATS.length];
    const unit = allUnits[i % allUnits.length];
    const r = await req('POST', '/products', TOKEN, {
      companyUid,
      name: `${cat} Product ${TAG}-${i+1}`,
      type: isService ? 'SERVICE' : 'GOODS',
      sellable:   true,
      stockable:  !isService,
      purchasable: true,
      baseUnitUid: unit.uid,
      vatStatus: i % 3 === 0 ? 'EXEMPT' : 'STANDARD',
    });
    if (uidOf(r)) return { uid: uidOf(r), id: idOf(r) };
    fail('product', snip(r)); return null;
  });
  console.log(`  products: ${products.length}/${V.products}`);

  // Set prices on all products (retail price list)
  if (priceListUid && products.length) {
    let priceOk = 0;
    for (const batch of chunks(products, BATCH)) {
      await Promise.all(batch.map(async p => {
        const price = rndAmt(500, 500000);
        const r = await req('POST', `/products/uid/${p.uid}/prices`, TOKEN, {
          priceListUid,
          price: { amount: price, currency: 'TZS' },
        });
        if (r.status >= 200 && r.status < 300) priceOk++;
        else fail('product-price', snip(r));
      }));
    }
    console.log(`  product prices set: ${priceOk}/${products.length}`);
  }

  // ── 1d: Customers (500) ──��───────────────────────────────────────────────────
  const customers = await bulkCreate('customer', V.customers, BATCH, async i => {
    // Use INDIVIDUAL for all — BUSINESS requires a TIN (BR-PARTY-04).
    const name = `${rnd(FIRST_NAMES)} ${rnd(LAST_NAMES)} ${TAG}-${i+1}`;
    const r = await req('POST', '/customers', TOKEN, {
      companyId,
      partyType:    'INDIVIDUAL',
      displayName:  name,
      customerKind: i % 3 === 0 ? 'CASH_WALK_IN' : 'CREDIT_ACCOUNT',
      phone:        `07${rndInt(10000000,99999999)}`,
      email:        `cust${TAG}${i+1}@example.com`,
    });
    if (uidOf(r)) return { uid: uidOf(r), id: idOf(r) };
    fail('customer', snip(r)); return null;
  });
  console.log(`  customers: ${customers.length}/${V.customers}`);

  // ── 1e: Suppliers (200) ─────────────────────────────────────────────────────
  const suppliers = await bulkCreate('supplier', V.suppliers, BATCH, async i => {
    const name = `${rnd(FIRST_NAMES)} ${rnd(LAST_NAMES)} Supplier ${TAG}-${i+1}`;
    const r = await req('POST', '/suppliers', TOKEN, {
      companyId,
      partyType:    'INDIVIDUAL',
      displayName:  name,
      supplierKind: i % 5 === 4 ? 'SERVICE' : 'GOODS',
      phone:        `06${rndInt(10000000,99999999)}`,
    });
    if (uidOf(r)) return { uid: uidOf(r), id: idOf(r) };
    fail('supplier', snip(r)); return null;
  });
  console.log(`  suppliers: ${suppliers.length}/${V.suppliers}`);

  // ── 1f: Agents (20) ��─────────────────────────────────────────────────────────
  const agents = await bulkCreate('agent', V.agents, 10, async i => {
    const r = await req('POST', '/agents', TOKEN, {
      companyId,
      partyType:    'INDIVIDUAL',
      displayName:  `${rnd(FIRST_NAMES)} ${rnd(LAST_NAMES)} Agent ${TAG}-${i+1}`,
      agentKind:    'EXTERNAL',  // INTERNAL requires userId linkage — skip
    });
    if (uidOf(r)) return { uid: uidOf(r), id: idOf(r) };
    fail('agent', snip(r)); return null;
  });
  console.log(`  agents: ${agents.length}/${V.agents}`);

  // ── 1g: Other Parties (60) ───────────────────────────────────────────────────
  // Valid OtherPartyKind enum: BANK, UTILITY, GOVERNMENT, NGO, INVESTOR, OTHER
  const otherKinds = ['BANK','UTILITY','GOVERNMENT','NGO','INVESTOR','OTHER'];
  const otherParties = await bulkCreate('other-party', V.otherParties, BATCH, async i => {
    const r = await req('POST', '/other-parties', TOKEN, {
      companyId,
      partyType:   'INDIVIDUAL',
      displayName: `${rnd(FIRST_NAMES)} ${rnd(LAST_NAMES)} OtherParty ${TAG}-${i+1}`,
      otherKind:   otherKinds[i % otherKinds.length],
    });
    if (uidOf(r)) { ok('other-party'); return { uid: uidOf(r) }; }
    fail('other-party', snip(r)); return null;
  });
  console.log(`  other-parties: ${otherParties.length}/${V.otherParties}`);

  // ── 1h: Departments (12) + Employees (120) ───────────────────────────────────
  const deptNames = ['Finance','Sales','Operations','HR','IT','Procurement','Logistics',
                     'Marketing','Legal','Admin','Production','Quality'];
  const departments = [];
  for (let i = 0; i < V.departments; i++) {
    const r = await req('POST', '/hr/departments', TOKEN, {
      code: `DEPT${TAG}-${i+1}`,
      name: `${deptNames[i % deptNames.length]} Dept ${TAG}-${i+1}`,
    });
    if (uidOf(r)) { ok('hr.department'); departments.push({ uid: uidOf(r), id: idOf(r) }); }
    else fail('hr.department', snip(r));
  }
  console.log(`  departments: ${departments.length}/${V.departments}`);

  // Employees: use batch=1 (sequential) — concurrent creates cause employeeNumber sequence collisions (500 error)
  const employees = await bulkCreate('hr.employee', V.employees, 1, async i => {
    const dept = departments[i % Math.max(departments.length, 1)];
    const r = await req('POST', '/hr/employees', TOKEN, {
      firstName:    rnd(FIRST_NAMES),
      lastName:     rnd(LAST_NAMES) + TAG + i,
      hireDate:     randDate(30, 1800),
      departmentId: dept?.id || null,
      jobTitle:     ['Manager','Officer','Analyst','Clerk','Supervisor','Director'][i%6],
      branchId,
    });
    if (uidOf(r)) return { uid: uidOf(r), id: idOf(r) };
    fail('hr.employee', snip(r)); return null;
  });
  console.log(`  employees: ${employees.length}/${V.employees}`);

  // Contracts for first 30 employees
  for (const emp of employees.slice(0, 30)) {
    const r = await req('POST', `/hr/contracts/employee/${emp.uid}`, TOKEN, {
      contractType:   ['PERMANENT','FIXED_TERM','CASUAL','PROBATION'][rndInt(0,3)],
      baseSalaryAmount: String(rndInt(500000, 5000000)),
      startDate:      randDate(30, 1000),
      payeResident:   true,
      nssfMember:     true,
      heslbBorrower:  false,
      wcfCovered:     true,
      sdlCounted:     true,
    });
    if (uidOf(r)) ok('hr.contract'); else fail('hr.contract', snip(r));
  }
  console.log(`  hr.contracts: ${STATS['hr.contract']?.ok || 0}/30`);

  // ── 1i: CRM Pipeline Stages ──���───────────────────────────────────────────────
  const stageNames = ['Prospecting','Qualification','Proposal','Negotiation','Closed Won','Closed Lost'];
  const pipelineStages = [];
  // First fetch existing stages to reuse them (additive run — stages persist across runs)
  const existingStagesR = await req('GET', `/crm/pipeline-stages?companyId=${companyId}`, TOKEN);
  for (const s of listOf(existingStagesR)) {
    if (!pipelineStages.find(p => p.uid === s.uid)) pipelineStages.push({ uid: s.uid, name: s.name });
  }
  // Create any stages that don't already exist
  for (let i = 0; i < stageNames.length; i++) {
    if (pipelineStages.find(s => s.name === stageNames[i])) continue;
    const r = await req('POST', '/crm/pipeline-stages', TOKEN, {
      companyId,
      name:               stageNames[i],
      displayOrder:       (i+1),
      defaultProbability: [10,25,50,75,100,0][i],
    });
    if (uidOf(r)) { ok('crm.stage'); pipelineStages.push({ uid: uidOf(r), name: stageNames[i] }); }
    else fail('crm.stage', snip(r));
  }
  console.log(`  pipeline stages: ${pipelineStages.length}`);

  // ════════════════════════════════════════════════════════════════════════════
  // PHASE 2: OPENING STOCK  (PO → place → GR) for first 200 products
  // ════════════════════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 2: Opening stock (PO→GR) ===');
  const stockedProducts = [];  // products that have received stock
  const openingCount = Math.min(V.openingStockPOs, products.length, suppliers.length > 0 ? V.openingStockPOs : 0);

  for (const batch of chunks(products.slice(0, openingCount), 20)) {
    await Promise.all(batch.map(async (p, bi) => {
      if (!suppliers.length) return;
      const sup = suppliers[(bi + stockedProducts.length) % suppliers.length];
      if (!sup) return;
      const poR = await req('POST', '/purchase-orders', TOKEN, {
        companyUid, supplierUid: sup.uid, currency: 'TZS',
        lines: [{ productUid: p.uid, unitUid, orderedQty: String(rndInt(50, 500)), unitCostAmount: rndAmt(200, 50000) }],
      });
      const poUid = uidOf(poR);
      if (!poUid) { fail('opening-stock.po', snip(poR)); return; }
      ok('opening-stock.po');

      const placeR = await req('POST', `/purchase-orders/uid/${poUid}/place`, TOKEN);
      if (placeR.status >= 300) { fail('opening-stock.place', snip(placeR)); return; }

      const linesR = await req('GET', `/purchase-orders/uid/${poUid}/lines`, TOKEN);
      const lineUid = listOf(linesR)[0]?.uid;
      if (!lineUid) { fail('opening-stock.grline', 'no PO line uid'); return; }

      const grR = await req('POST', '/goods-receipts', TOKEN, {
        purchaseOrderUid: poUid,
        lines: [{ purchaseOrderLineUid: lineUid, receivedQty: String(rndInt(10, 200)) }],
      });
      if (uidOf(grR) || grR.status < 300) { ok('opening-stock.gr'); stockedProducts.push(p); }
      else fail('opening-stock.gr', snip(grR));
    }));
  }
  console.log(`  stocked products: ${stockedProducts.length}/${openingCount}`);
  console.log(`  waiting 10s for outbox to apply GR stock postings...`);
  await sleep(10000);

  // ════════════════════════════════════════════════════════════════════════════
  // PHASE 3: SALES CYCLE — Quotations, Sales Orders, Deliveries, Invoices, Returns
  // ════════════════════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 3: Sales cycle ===');

  // ── 3a: Quotations (200) ─────────────────────────────────────────────────────
  const quotations = [];
  for (const batch of chunks([...Array(V.quotations).keys()], BATCH)) {
    const res = await Promise.all(batch.map(async i => {
      const cust = customers[i % Math.max(customers.length, 1)];
      const agent = agents[i % Math.max(agents.length, 1)];
      const qDate = randDate(0, 120);
      const r = await req('POST', '/quotations', TOKEN, {
        companyUid,
        customerUid: cust.uid,
        agentUid:    agent?.uid || null,
        currency:    'TZS',
        quoteDate:   qDate,
        validUntil:  daysAhead(30),
        notes:       `Quotation batch ${i}`,
      });
      if (!uidOf(r)) { fail('quotation', snip(r)); return null; }
      const qUid = uidOf(r);
      ok('quotation');

      // Add 1-3 lines
      const nLines = rndInt(1, 3);
      for (let l = 0; l < nLines; l++) {
        const prod = products[(i * 3 + l) % Math.max(products.length, 1)];
        await req('POST', `/quotations/uid/${qUid}/lines`, TOKEN, {
          productUid:        prod.uid,
          unitUid,
          quantity:          rndInt(1, 50),
          unitPriceOverride: rndAmt(500, 100000),
        });
      }
      // Send ~60%
      if (i % 5 !== 0) {
        await req('PUT', `/quotations/uid/${qUid}/send`, TOKEN);
        ok('quotation.send');
      }
      return { uid: qUid };
    }));
    quotations.push(...res.filter(Boolean));
  }
  console.log(`  quotations: ${quotations.length}/${V.quotations}`);

  // ── 3b: Sales Orders (400) — some from accepted quotes, some direct ──────────
  const salesOrders = [];
  // Accept ~50 quotes → SO
  for (const q of quotations.slice(0, 50)) {
    const r = await req('PUT', `/quotations/uid/${q.uid}/accept`, TOKEN);
    const u = uidOf(r);
    if (u) { ok('quotation.accept'); salesOrders.push({ uid: u }); }
    else fail('quotation.accept', snip(r));
  }

  // Direct SOs for the rest
  const directSOCount = V.salesOrders - salesOrders.length;
  for (const batch of chunks([...Array(directSOCount).keys()], BATCH)) {
    const res = await Promise.all(batch.map(async i => {
      const cust  = customers[i % Math.max(customers.length, 1)];
      const agent = agents[i % Math.max(agents.length, 1)];
      const r = await req('POST', '/sales-orders', TOKEN, {
        companyUid,
        customerUid: cust.uid,
        agentUid:    agent?.uid || null,
        currency:    'TZS',
        orderDate:   randDate(0, 90),
        notes:       `SO direct ${TAG}-${i}`,
      });
      if (!uidOf(r)) { fail('sales-order', snip(r)); return null; }
      ok('sales-order');
      const soUid = uidOf(r);

      // Add 1-4 lines
      const nLines = rndInt(1, 4);
      for (let l = 0; l < nLines; l++) {
        const prod = products[(i * 4 + l) % Math.max(products.length, 1)];
        await req('POST', `/sales-orders/uid/${soUid}/lines`, TOKEN, {
          productUid:        prod.uid,
          unitUid,
          quantity:          rndInt(1, 100),
          unitPriceOverride: rndAmt(500, 200000),
        });
      }
      return { uid: soUid };
    }));
    salesOrders.push(...res.filter(Boolean));
  }
  console.log(`  sales orders: ${salesOrders.length}/${V.salesOrders}`);

  // ── 3c: Confirm + Deliver + Invoice a large fraction of SOs ─────────────────
  const deliveries   = [];
  const soInvoices   = [];
  const confirmedSOs = [];

  for (const batch of chunks(salesOrders.slice(0, Math.floor(salesOrders.length * 0.75)), 20)) {
    await Promise.all(batch.map(async so => {
      // Confirm
      const confR = await req('PUT', `/sales-orders/uid/${so.uid}/confirm`, TOKEN);
      if (confR.status >= 300) { fail('sales-order.confirm', snip(confR)); return; }
      ok('sales-order.confirm');
      confirmedSOs.push(so);

      // Get lines for delivery
      const linesR = await req('GET', `/sales-orders/uid/${so.uid}/lines`, TOKEN);
      const soLines = listOf(linesR);
      if (!soLines.length) { fail('delivery', 'no SO lines'); return; }

      // Create delivery
      const delvR = await req('POST', '/deliveries', TOKEN, {
        salesOrderUid: so.uid,
        deliveryDate:  today(),
        notes:         `Delivery ${TAG}`,
        lines: soLines.slice(0, 3).map(l => ({
          salesOrderLineUid: l.uid,
          qtyDelivered:      l.orderedQty || l.quantity || '1',
        })),
      });
      if (!uidOf(delvR)) { fail('delivery', snip(delvR)); return; }
      ok('delivery');
      const delvUid = uidOf(delvR);
      deliveries.push({ uid: delvUid });

      // Create invoice from delivery (~80%)
      if (Math.random() < 0.8) {
        const invR = await req('POST', `/deliveries/uid/${delvUid}/invoice`, TOKEN);
        if (!uidOf(invR)) { fail('invoice.from-delivery', snip(invR)); return; }
        ok('invoice.from-delivery');
        const invUid = uidOf(invR);
        // Must add payment >= grossTotalAmount before finalising (FR-SALES-18)
        const invDet = payload(await req('GET', `/sales-invoices/uid/${invUid}`, TOKEN));
        const invAmt = invDet?.grossTotalAmount || invDet?.netTotalAmount || '50000';
        const payR = await req('POST', `/sales-invoices/uid/${invUid}/payments`, TOKEN, {
          amount: invAmt, currency: 'TZS', tenderType: ['CASH','MOBILE_MONEY'][Math.floor(Math.random()*2)], paymentDate: today(),
        });
        if (payR.status >= 300) { fail('invoice.payment', snip(payR)); soInvoices.push({ uid: invUid }); return; }
        // Finalize invoice
        const finR = await req('PUT', `/sales-invoices/uid/${invUid}/finalize`, TOKEN, {});
        if (finR.status < 300) ok('invoice.finalize');
        else fail('invoice.finalize', snip(finR));
        soInvoices.push({ uid: invUid });
      }
    }));
  }
  console.log(`  confirmed SOs: ${confirmedSOs.length}  deliveries: ${deliveries.length}  SO-invoices: ${soInvoices.length}`);

  // ── 3d: Direct walk-in invoices (200) ────────��───────────────────────────────
  const directInvoices = [];
  for (const batch of chunks([...Array(V.directInvoices).keys()], BATCH)) {
    const res = await Promise.all(batch.map(async i => {
      const cust  = customers[i % Math.max(customers.length, 1)];
      const agent = agents[i % Math.max(agents.length, 1)];
      const r = await req('POST', '/sales-invoices', TOKEN, {
        companyUid,
        customerUid: cust.uid,
        agentUid:    agent?.uid || null,
        currency:    'TZS',
        notes:       `Direct invoice ${TAG}-${i}`,
      });
      if (!uidOf(r)) { fail('direct-invoice', snip(r)); return null; }
      ok('direct-invoice');
      const invUid = uidOf(r);

      // Add 1-3 lines
      for (let l = 0; l < rndInt(1, 3); l++) {
        const prod = products[(i * 3 + l) % Math.max(products.length, 1)];
        await req('POST', `/sales-invoices/uid/${invUid}/lines`, TOKEN, {
          productUid: prod.uid,
          unitUid,
          quantity:   rndInt(1, 20),
        });
      }
      // Finalize ~70% — must add payment first (FR-SALES-18)
      if (i % 10 < 7) {
        const invDet = payload(await req('GET', `/sales-invoices/uid/${invUid}`, TOKEN));
        const invAmt = invDet?.grossTotalAmount || invDet?.netTotalAmount || '50000';
        const payR = await req('POST', `/sales-invoices/uid/${invUid}/payments`, TOKEN, {
          amount: invAmt, currency: 'TZS', tenderType: ['CASH','MOBILE_MONEY'][i % 2], paymentDate: today(),
        });
        if (payR.status < 300) {
          const finR = await req('PUT', `/sales-invoices/uid/${invUid}/finalize`, TOKEN, {});
          if (finR.status < 300) ok('direct-invoice.finalize');
          else fail('direct-invoice.finalize', snip(finR));
        } else fail('direct-invoice.payment', snip(payR));
        return { uid: invUid };
      }
      return { uid: invUid };
    }));
    directInvoices.push(...res.filter(Boolean));
  }
  console.log(`  direct invoices: ${directInvoices.length}/${V.directInvoices}`);

  // ── 3e: Sales Returns (~50) — against first deliveries ───────────────────────
  let retOk = 0;
  for (const delv of deliveries.slice(0, V.salesReturns)) {
    // Get delivery lines
    const detR = await req('GET', `/deliveries/uid/${delv.uid}`, TOKEN);
    const dLines = payload(detR)?.lines || [];
    if (!dLines.length) { fail('sales-return', 'no delivery lines'); continue; }
    const retR = await req('POST', '/sales-returns', TOKEN, {
      deliveryUid: delv.uid,
      returnDate:  today(),
      reason:      'Customer return - quality issue',
      lines: dLines.slice(0, 1).map(l => ({ deliveryLineUid: l.uid, qtyReturned: '1' })),
    });
    if (uidOf(retR) || retR.status < 300) { ok('sales-return'); retOk++; }
    else fail('sales-return', snip(retR));
  }
  console.log(`  sales returns: ${retOk}/${V.salesReturns}`);

  // ── 3f: Blanket Orders (20) ──���───────────────────────────────────────────────
  let blanketOk = 0;
  for (let i = 0; i < V.blanketOrders; i++) {
    const cust = customers[i % Math.max(customers.length, 1)];
    const prod = products[i % Math.max(products.length, 1)];
    const r = await req('POST', '/blanket-orders', TOKEN, {
      companyUid, branchId,
      customerId: cust.id,
      currency:   'TZS',
      validFrom:  today(),
      validTo:    daysAhead(180),
      notes:      `Blanket order ${TAG}-${i+1}`,
      lines: [{ productId: prod.id, unitId, committedQtyBase: rndInt(100, 1000), unitPriceAmount: rndAmt(500, 50000) }],
    });
    if (uidOf(r)) { ok('blanket-order'); blanketOk++; }
    else fail('blanket-order', snip(r));
  }
  console.log(`  blanket orders: ${blanketOk}/${V.blanketOrders}`);

  // ════════════════════════════════════════════════════════════════════════════
  // PHASE 4: PURCHASE CYCLE — POs, GRs, Bills, Bill-Match, AP Payments, Returns, Landed Costs
  // ═════════════════════��══════════════════════════════════════════════════════
  console.log('\n=== PHASE 4: Purchase cycle ===');

  const purchaseOrders = [];
  const goodsReceipts  = [];

  // ── 4a: Purchase Orders + GRs (400) ──────────────────────────────────────────
  if (!suppliers.length) { fail('purchase-order', 'No suppliers seeded — skipping PO/GR phase'); }
  for (const batch of chunks(suppliers.length ? [...Array(V.purchaseOrders).keys()] : [], 20)) {
    await Promise.all(batch.map(async i => {
      const sup  = suppliers[i % suppliers.length];
      const prod = products[i % Math.max(products.length, 1)];
      const poR = await req('POST', '/purchase-orders', TOKEN, {
        companyUid, supplierUid: sup.uid, currency: 'TZS',
        lines: [{ productUid: prod.uid, unitUid, orderedQty: String(rndInt(10, 200)), unitCostAmount: rndAmt(200, 50000) }],
      });
      if (!uidOf(poR)) { fail('purchase-order', snip(poR)); return; }
      ok('purchase-order');
      const poUid = uidOf(poR);
      purchaseOrders.push({ uid: poUid, id: idOf(poR), supplierUid: sup.uid, productUid: prod.uid });

      // Place ~80%
      if (i % 5 < 4) {
        const plR = await req('POST', `/purchase-orders/uid/${poUid}/place`, TOKEN);
        if (plR.status >= 300) { fail('purchase-order.place', snip(plR)); return; }
        ok('purchase-order.place');

        // GR ~70% of placed
        if (i % 10 < 7) {
          const linesR = await req('GET', `/purchase-orders/uid/${poUid}/lines`, TOKEN);
          const lineUid = listOf(linesR)[0]?.uid;
          if (!lineUid) { fail('goods-receipt', 'no PO line uid'); return; }
          const grR = await req('POST', '/goods-receipts', TOKEN, {
            purchaseOrderUid: poUid,
            lines: [{ purchaseOrderLineUid: lineUid, receivedQty: String(rndInt(5, 100)) }],
          });
          if (uidOf(grR) || grR.status < 300) {
            ok('goods-receipt');
            const grUid = uidOf(grR) || payload(grR)?.uid;
            const grLines = payload(grR)?.lines || [];
            goodsReceipts.push({ uid: grUid, supplierUid: sup.uid, productUid: prod.uid, poUid, grLines });
          }
          else fail('goods-receipt', snip(grR));
        }
      }
    }));
  }
  console.log(`  purchase orders: ${STATS['purchase-order']?.ok||0}/${V.purchaseOrders}  GRs: ${goodsReceipts.length}`);
  console.log(`  waiting 5s for outbox GR postings...`);
  await sleep(5000);

  // ── 4b: Supplier Bills (300) + Bill-Match ─────────────────────────────────────
  const supplierBills = [];
  const matchedBills  = [];  // bills that were successfully matched (MATCHED status) — payable
  const billCount = Math.min(V.supplierBills, goodsReceipts.length);
  for (const batch of chunks(goodsReceipts.slice(0, billCount), 20)) {
    await Promise.all(batch.map(async (gr, bi) => {
      const billR = await req('POST', '/ap/supplier-bills', TOKEN, {
        companyUid,
        supplierUid:        gr.supplierUid,
        supplierInvoiceNo:  `INV-${TAG}-${bi+Math.floor(Math.random()*9000)+1000}`,
        purchaseOrderUid:   gr.poUid,
        billDate:           today(),
        dueDate:            daysAhead(30),
        vatAmount:          '0',
        currency:           'TZS',
        tenderType:         'BANK_TRANSFER',
        lines: [{
          description:    `Goods receipt ${bi}`,
          billedQty:      rndInt(5, 50),
          unitCostAmount: rndAmt(200, 20000),
        }],
      });
      if (!uidOf(billR)) { fail('supplier-bill', snip(billR)); return; }
      ok('supplier-bill');
      const billUid = uidOf(billR);
      supplierBills.push({ uid: billUid, supplierUid: gr.supplierUid });

      // Run match ~80% — track which ones match successfully (status = MATCHED = payable)
      if (bi % 5 < 4) {
        const matchR = await req('POST', `/ap/supplier-bills/uid/${billUid}/match/run`, TOKEN);
        if (matchR.status < 300) {
          ok('bill-match');
          // Check if bill moved to MATCHED
          const det = await req('GET', `/ap/supplier-bills/uid/${billUid}`, TOKEN);
          const det2 = payload(det);
          if (det2?.status === 'MATCHED' || det2?.status === 'POSTED') {
            matchedBills.push({ uid: billUid, supplierUid: gr.supplierUid });
          }
        }
        else fail('bill-match', snip(matchR));
      }
    }));
  }
  console.log(`  supplier bills: ${STATS['supplier-bill']?.ok||0}/${billCount}  bill-matches: ${STATS['bill-match']?.ok||0}  matched/payable: ${matchedBills.length}`);

  // ── 4c: AP Payments — only against MATCHED bills ─────────────────────────────
  let apPayOk = 0;
  const payableBills = matchedBills.length ? matchedBills : supplierBills; // fallback
  for (const bill of payableBills.slice(0, V.apPayments)) {
    const r = await req('POST', '/ap/payments/single', TOKEN, {
      companyUid,
      supplierBillUid: bill.uid,
      amount:          rndAmt(10000, 500000),
      paymentDate:     today(),
      tenderType:      ['CASH','MOBILE_MONEY','BANK_TRANSFER'][rndInt(0,2)],
      bankReference:   `PAY-${TAG}-${apPayOk+1}`,
    });
    if (uidOf(r) || r.status < 300) { ok('ap-payment'); apPayOk++; }
    else fail('ap-payment', snip(r));
  }
  console.log(`  AP payments: ${apPayOk}/${Math.min(V.apPayments, payableBills.length)}`);

  // ── 4d: Purchase Returns (30) ────────────────────────────────────────────────
  let purchRetOk = 0;
  for (const gr of goodsReceipts.slice(0, V.purchaseReturns)) {
    // Refetch detail to get GR line uid
    if (!gr.uid) continue;
    const detR = await req('GET', `/goods-receipts/uid/${gr.uid}`, TOKEN);
    const grLines2 = payload(detR)?.lines || gr.grLines || [];
    const grLineUid = grLines2[0]?.uid;
    if (!grLineUid) { fail('purchase-return', 'no GR line'); continue; }
    const prR = await req('POST', '/purchase-returns', TOKEN, {
      companyUid, goodsReceiptUid: gr.uid, reason: 'Defective goods',
      lines: [{ goodsReceiptLineUid: grLineUid, returnedQty: '2' }],
    });
    if (!uidOf(prR)) { fail('purchase-return', snip(prR)); continue; }
    const prUid = uidOf(prR);
    const confR = await req('POST', `/purchase-returns/uid/${prUid}/confirm`, TOKEN);
    if (confR.status < 300) { ok('purchase-return'); purchRetOk++; }
    else { fail('purchase-return.confirm', snip(confR)); }
  }
  console.log(`  purchase returns: ${purchRetOk}/${V.purchaseReturns}`);

  // ── 4e: Landed Costs (20) ────────────────────────────────────────────────────
  let lcOk = 0;
  for (const gr of goodsReceipts.slice(0, V.landedCosts)) {
    if (!gr.uid) continue;
    const lcR = await req('POST', '/landed-costs', TOKEN, {
      companyUid, basis: 'BY_VALUE', notes: `Freight ${TAG}`,
      receiptUids: [gr.uid],
      charges: [{ chargeType: 'FREIGHT', amount: rndAmt(10000, 200000) }],
    });
    if (!uidOf(lcR)) { fail('landed-cost', snip(lcR)); continue; }
    const lcUid = uidOf(lcR);
    const confR = await req('POST', `/landed-costs/uid/${lcUid}/confirm`, TOKEN);
    if (confR.status < 300) { ok('landed-cost'); lcOk++; }
    else fail('landed-cost.confirm', snip(confR));
  }
  console.log(`  landed costs: ${lcOk}/${V.landedCosts}`);

  // ════════════════════════════════════════════════════════════════════════════
  // PHASE 5: AR RECEIPTS (300) against finalized invoices
  // ════════════════════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 5: AR Receipts ===');
  const allInvoices = [...soInvoices, ...directInvoices];
  let arOk = 0;
  for (const inv of allInvoices.slice(0, V.arReceipts)) {
    // Fetch invoice to get customer
    const detR  = await req('GET', `/sales-invoices/uid/${inv.uid}`, TOKEN);
    const invDet = payload(detR);
    const custUid = invDet?.customerUid || customers[arOk % customers.length]?.uid;
    if (!custUid) { fail('ar-receipt', 'no customerUid'); continue; }
    const r = await req('POST', '/ar/receipts', TOKEN, {
      companyUid,
      customerUid:  custUid,
      amount:       rndAmt(5000, 500000),
      currency:     'TZS',
      receiptDate:  today(),
      tenderType:   ['CASH','MOBILE_MONEY','BANK_TRANSFER'][rndInt(0,2)],
      bankReference:`RCP-${TAG}-${arOk+1}`,
      allocations:  [],
    });
    if (uidOf(r) || r.status < 300) { ok('ar-receipt'); arOk++; }
    else fail('ar-receipt', snip(r));
  }
  console.log(`  AR receipts: ${arOk}/${V.arReceipts}`);

  // ═══════���═════════════════════════════���══════════════════════════════════════
  // PHASE 6: GL JOURNALS (500 balanced manual entries)
  // ════════════════════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 6: GL Journals ===');
  let glOk = 0;
  if (drAcc?.uid && crAcc?.uid) {
    for (const batch of chunks([...Array(V.glJournals).keys()], BATCH)) {
      await Promise.all(batch.map(async i => {
        const amt = rndAmt(50000, 2000000);
        const r = await req('POST', '/gl/journals', TOKEN, {
          companyUid,
          postingDate:  randDate(0, 165),  // stay within 2026 open periods
          description:  `Manual journal ${TAG}-${i+1} ${['Accrual','Prepayment','Correction','Reclass','Provision'][i%5]}`,
          sourceType:   'MANUAL',
          sourceRef:    `MJE-${TAG}-${i+1}`,
          lines: [
            { accountUid: drAcc.uid, debitAmount:  amt, creditAmount: 0,   lineMemo: 'DR leg' },
            { accountUid: crAcc.uid, debitAmount:  0,   creditAmount: amt,  lineMemo: 'CR leg' },
          ],
        });
        if (uidOf(r) || r.status < 300) { ok('gl-journal'); glOk++; }
        else fail('gl-journal', snip(r));
      }));
    }
  } else {
    fail('gl-journal', 'No GL accounts available for DR/CR');
  }
  console.log(`  GL journals: ${glOk}/${V.glJournals}`);

  // ���════════════════════════��══════════════════════════════════════════════════
  // PHASE 7: CASH ACCOUNTS + TRANSFERS
  // ══════════════════════���═════════════════════════════════════════════════════
  console.log('\n=== PHASE 7: Cash accounts + transfers ===');
  // Discover existing cash accounts
  let cashAccounts = [];
  {
    const r = await req('GET', `/cash/accounts?companyId=${companyId}`, TOKEN);
    cashAccounts = listOf(r);
  }
  // Create cash accounts using different asset GL accounts (each account needs unique GL link)
  // Try multiple asset GL accounts — skip ones already linked (409)
  const cashGlCandidates = assetAccs.filter(a => a.uid).slice(0, 8);
  let cashAccIdx = 0;
  while (cashAccounts.length < 3 && cashAccIdx < cashGlCandidates.length) {
    const glAcc = cashGlCandidates[cashAccIdx++];
    const isFirst = cashAccounts.length === 0;
    const r = await req('POST', '/cash/accounts', TOKEN, {
      companyUid, branchUid,
      name:          `${isFirst ? 'Petty Cash' : `Bank Account ${cashAccIdx}`} ${TAG}`,
      accountType:   isFirst ? 'CASH' : 'BANK',
      bankName:      isFirst ? null : 'CRDB Bank',
      bankAccountNo: isFirst ? null : `${rndInt(100000000,999999999)}`,
      glAccountUid:  glAcc.uid,
      setAsDefault:  isFirst,
    });
    if (uidOf(r)) { ok('cash-account'); cashAccounts.push(payload(r)); }
    else if (r.status === 409) { /* GL account already linked — try next */ }
    else fail('cash-account', snip(r));
  }
  console.log(`  cash accounts available: ${cashAccounts.length}`);

  // Cash transfers (50) between accounts
  let cashTxOk = 0;
  if (cashAccounts.length >= 2) {
    for (let i = 0; i < V.cashTransfers; i++) {
      const src = cashAccounts[i % cashAccounts.length];
      const dst = cashAccounts[(i + 1) % cashAccounts.length];
      if (src.uid === dst.uid) continue;
      const r = await req('POST', '/cash/transfers', TOKEN, {
        companyUid,
        sourceAccountUid:      src.uid,
        destinationAccountUid: dst.uid,
        amount:                rndAmt(10000, 500000),
        transferDate:          randDate(0, 60),
        reference:             `TRF-${TAG}-${i+1}`,
      });
      if (uidOf(r) || r.status < 300) { ok('cash-transfer'); cashTxOk++; }
      else fail('cash-transfer', snip(r));
    }
  } else {
    fail('cash-transfer', 'Need 2+ cash accounts');
  }
  console.log(`  cash transfers: ${cashTxOk}/${V.cashTransfers}`);

  // ═════════════════════��══════════════════════════════════════════════════════
  // PHASE 8: POS (Tills, Sessions, Sales, Close, Reconcile)
  // ════════════════════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 8: POS ===');

  // Discover stocked product IDs from stock on-hand (productId field, quantity > 0)
  const stockOnHandR = await req('GET', `/stock/on-hand?size=200&companyId=${companyId}`, TOKEN);
  const stockOnHandItems = listOf(stockOnHandR).filter(s => parseFloat(s.quantity) > 0);
  // Build a lookup: productId (string) -> { id (string), uid (may be null) }
  const stockedProdIds = stockOnHandItems.map(s => String(s.productId)).filter(Boolean);
  // Map current-run products by id (string) for fast lookup
  const productById = {};
  for (const p of products) { if (p.id) productById[String(p.id)] = p; }
  // For stocked products not in current-run array, fetch uid from API
  const posProductsRaw = stockedProdIds.slice(0, 30).map(pid => ({
    id: pid,
    uid: productById[pid]?.uid || null,
  }));
  // Resolve missing uids by fetching product details (batched)
  const missingUidPids = posProductsRaw.filter(p => !p.uid).map(p => p.id);
  if (missingUidPids.length > 0) {
    const allProdsR = await req('GET', `/products?companyId=${companyId}&size=300`, TOKEN);
    const allProds = listOf(allProdsR);
    const allProdByIdMap = {};
    for (const p of allProds) { if (p.id) allProdByIdMap[String(p.id)] = p; }
    for (const item of posProductsRaw) {
      if (!item.uid && allProdByIdMap[item.id]) item.uid = allProdByIdMap[item.id].uid;
    }
  }
  const posProducts = posProductsRaw.filter(p => p.uid); // only those with resolved uid
  console.log(`  POS: stocked products available: ${posProducts.length} (from ${stockOnHandItems.length} on-hand records, ${missingUidPids.length} uid-resolved from catalog)`);

  // Ensure we have agents - get from fresh list
  const agentListR = await req('GET', `/agents?companyId=${companyId}&size=50`, TOKEN);
  const allAgents = listOf(agentListR);
  const posAgentId = allAgents[0]?.id || agents[0]?.id;
  console.log(`  POS: using agentId=${posAgentId}, ${allAgents.length} agents available`);

  let posSessionCount = 0, posSaleCount = 0;
  const tills = [];
  for (let t = 0; t < V.posTills; t++) {
    const r = await req('POST', '/pos/tills', TOKEN, {
      companyUid, branchId, name: `Till ${TAG}-${t+1}`,
    });
    if (uidOf(r)) { ok('pos.till'); tills.push({ uid: uidOf(r) }); }
    else fail('pos.till', snip(r));
  }

  // BUG-NOTE: PosSaleServiceImpl.processSale() ignores agentId in the request and passes null
  // agentUid to CreateSalesInvoiceRequest (line 94-95). The service then triggers BR-SALES-06
  // ("No agent specified") for any user without an INTERNAL agent auto-link. rootadmin cannot
  // have an INTERNAL agent (BR-PARTY-10: super-admin cannot be a sales agent). POS sales
  // are therefore blocked until the backend bug is fixed to use agentId from the request.
  if (!posAgentId) {
    fail('pos.sale', 'No agent available for POS sales');
  } else if (!posProducts.length) {
    fail('pos.sale', 'No stocked products for POS sales');
  } else {
    // Pre-record blocked failures so they show in the report
    for (let i = 0; i < V.posSessions * V.posSalesPerSess; i++) {
      fail('pos.sale', 'BLOCKED: PosSaleServiceImpl ignores agentId — BR-SALES-06 fires for rootadmin (no INTERNAL agent). Backend bug, see PosSaleServiceImpl.java:94');
    }
  }

  const sessPerTill = Math.ceil(V.posSessions / Math.max(tills.length, 1));
  for (const till of tills) {
    for (let s = 0; s < sessPerTill && posSessionCount < V.posSessions; s++) {
      const sessR = await req('POST', '/pos/sessions', TOKEN, {
        tillUid: till.uid, openingFloatAmount: rndInt(50000, 200000),
      });
      if (!uidOf(sessR)) { fail('pos.session', snip(sessR)); continue; }
      ok('pos.session');
      const sessUid = uidOf(sessR);
      posSessionCount++;

      // Sales blocked by backend bug — skip sale creation, still close session for data
      let sessOk = 0;
      if (false && posAgentId && posProducts.length) {
        for (let sale = 0; sale < V.posSalesPerSess; sale++) {
          const cust    = customers[sale % Math.max(customers.length, 1)];
          const posProd = posProducts[sale % posProducts.length];
          const unitPrice = rndInt(1000, 50000);
          const qty = rndInt(1, 3);
          const r = await req('POST', '/pos/sales', TOKEN, {
            sessionUid:     sessUid,
            customerId:     cust.id,
            agentId:        posAgentId,     // ignored by service (backend bug)
            currency:       'TZS',
            lines: [{ productId: posProd.id, unitId, quantity: qty, unitPrice, lineDiscountAmount: 0 }],
            tenderedAmount: unitPrice * qty + rndInt(0, 500),
            notes:          `POS sale ${TAG} sess${posSessionCount} #${sale+1}`,
          });
          if (r.status >= 200 && r.status < 300) { ok('pos.sale'); sessOk++; posSaleCount++; }
          else fail('pos.sale', snip(r));
        }
      }

      // Close session
      const closeR = await req('POST', `/pos/sessions/uid/${sessUid}/close`, TOKEN, {
        countedCashAmount: rndInt(100000, 500000), notes: 'end of shift',
      });
      if (closeR.status < 300) ok('pos.close'); else fail('pos.close', snip(closeR));

      // Reconcile ~half
      if (s % 2 === 0) {
        const recR = await req('POST', `/pos/sessions/uid/${sessUid}/reconcile`, TOKEN, { notes: 'reconciled' });
        if (recR.status < 300) ok('pos.reconcile'); else fail('pos.reconcile', snip(recR));
      }
    }
  }
  console.log(`  POS tills: ${tills.length}/${V.posTills}  sessions: ${posSessionCount}/${V.posSessions}  sales: ${posSaleCount}`);

  // ════════════════════════════════════════════════════════════════════════════
  // PHASE 9: STOCK TRANSFERS (100) + STOCK COUNTS (30)
  // ════════════════════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 9: Stock transfers + counts ===');
  let xferOk = 0;
  if (locations.length >= 2 && posProducts.length) {
    for (let i = 0; i < V.stockTransfers; i++) {
      const src = locations[i % locations.length];
      const dst = locations[(i + 1) % locations.length];
      if (src.uid === dst.uid) continue;
      const prod = posProducts[i % posProducts.length];
      const mode = i % 3 === 0 ? 'IN_TRANSIT' : 'INSTANT';
      const r = await req('POST', '/stock-transfers', TOKEN, {
        sourceLocationUid: src.uid,
        destLocationUid:   dst.uid,
        transferDate:      randDate(0, 60),
        transferMode:      mode,
        notes:             `Transfer ${TAG}-${i+1}`,
        lines: [{ productUid: prod.uid, qty: String(rndInt(1, 20)) }],
      });
      if (!uidOf(r)) { fail('stock-transfer', snip(r)); continue; }
      ok('stock-transfer');
      const xUid = uidOf(r);

      if (mode === 'INSTANT') {
        const done = await req('PATCH', `/stock-transfers/uid/${xUid}/complete-instant`, TOKEN);
        if (done.status < 300) ok('stock-transfer.complete'); else fail('stock-transfer.complete', snip(done));
      } else {
        await req('PATCH', `/stock-transfers/uid/${xUid}/dispatch`, TOKEN);
        await req('PATCH', `/stock-transfers/uid/${xUid}/receive`, TOKEN);
      }
      xferOk++;
    }
  } else {
    fail('stock-transfer', `Need 2+ locations and stocked products. locs=${locations.length} stocked=${posProducts.length}`);
  }
  console.log(`  stock transfers: ${xferOk}/${V.stockTransfers}`);

  // Stock Counts (30)
  let countOk = 0;
  for (let i = 0; i < V.stockCounts; i++) {
    const loc = locations[i % Math.max(locations.length, 1)];
    const r = await req('POST', '/stock-counts', TOKEN, {
      locationUid: loc.uid,
      countDate:   randDate(0, 60),
      countType:   i % 2 === 0 ? 'FULL' : 'CYCLE',
      notes:       `Count ${TAG}-${i+1}`,
    });
    if (!uidOf(r)) { fail('stock-count', snip(r)); continue; }
    ok('stock-count');
    const ccUid = uidOf(r);

    // Enter counts
    const detR = await req('GET', `/stock-counts/uid/${ccUid}`, TOKEN);
    const countLines = payload(detR)?.lines || [];
    if (countLines.length) {
      const entries = countLines.slice(0, Math.min(5, countLines.length)).map(l => ({
        lineId:     l.id,
        countedQty: String(Math.max(0, Number(l.systemQty||0) + rndInt(-2, 5))),
        reasonCode: 'BS',
      }));
      await req('PATCH', `/stock-counts/uid/${ccUid}/enter`, TOKEN, { lines: entries });
    }

    // Post ~70%
    if (i % 10 < 7) {
      const postR = await req('PATCH', `/stock-counts/uid/${ccUid}/post?postingDate=${today()}`, TOKEN);
      if (postR.status < 300) { ok('stock-count.post'); countOk++; }
      else fail('stock-count.post', snip(postR));
    } else {
      countOk++;
    }
  }
  console.log(`  stock counts: ${countOk}/${V.stockCounts}`);

  // ════════════════════════════════════════════════════════════════════════════
  // PHASE 10: CRM — Leads, Opportunities, Activities
  // ════════════════��═══════════════════════════════════════════════════════════
  console.log('\n=== PHASE 10: CRM ===');
  const leadSources = ['WEBSITE','REFERRAL','WALK_IN','CAMPAIGN','COLD_CALL','EXISTING_CUSTOMER','OTHER'];

  // Leads (200)
  const leads = await bulkCreate('crm.lead', V.crmLeads, BATCH, async i => {
    const r = await req('POST', '/crm/leads', TOKEN, {
      companyId,
      displayName:   `${rnd(FIRST_NAMES)} ${rnd(LAST_NAMES)} ${TAG}-${i+1}`,
      leadSource:    leadSources[i % leadSources.length],
      contactPerson: rnd(FIRST_NAMES),
      phone:         `07${rndInt(10000000,99999999)}`,
      notes:         `Lead ${TAG}-${i+1}`,
    });
    if (uidOf(r)) return { uid: uidOf(r), id: idOf(r) };
    fail('crm.lead', snip(r)); return null;
  });
  console.log(`  CRM leads: ${leads.length}/${V.crmLeads}`);

  // Opportunities (100) — requires pipeline stages
  const opps = [];
  if (pipelineStages.length && customers.length) {
    for (const batch of chunks([...Array(V.crmOpps).keys()], BATCH)) {
      const res = await Promise.all(batch.map(async i => {
        const cust  = customers[i % customers.length];
        const stage = pipelineStages[i % pipelineStages.length];
        const agent = agents[i % Math.max(agents.length, 1)];
        const r = await req('POST', '/crm/opportunities', TOKEN, {
          companyId,
          customerUid:          cust.uid,
          title:                `Opportunity ${TAG}-${i+1} ${rnd(ITEM_CATS)}`,
          pipelineStageUid:     stage.uid,
          currency:             'TZS',
          estimatedValueAmount: rndAmt(100000, 5000000),
          winProbability:       rndInt(10, 90),
          expectedCloseDate:    daysAhead(rndInt(14, 180)),
          agentUid:             agent?.uid || null,
          notes:                `Opportunity ${TAG}-${i+1}`,
        });
        if (uidOf(r)) { ok('crm.opportunity'); return { uid: uidOf(r) }; }
        fail('crm.opportunity', snip(r)); return null;
      }));
      opps.push(...res.filter(Boolean));
    }
  } else {
    fail('crm.opportunity', `No pipeline stages (${pipelineStages.length}) or customers`);
  }
  console.log(`  CRM opportunities: ${opps.length}/${V.crmOpps}`);

  // Activities (300) — only TASK may have dueDate; others (CALL/EMAIL/MEETING/NOTE) must not
  const actTypes = ['CALL','EMAIL','MEETING','TASK','NOTE'];
  let actOk = 0;
  for (const batch of chunks([...Array(V.crmActivities).keys()], BATCH)) {
    await Promise.all(batch.map(async i => {
      const lead = leads[i % Math.max(leads.length, 1)];
      const aType = actTypes[i % actTypes.length];
      const r = await req('POST', '/crm/activities', TOKEN, {
        companyId,
        activityType: aType,
        leadUid:      lead?.uid || null,
        subject:      `Activity ${TAG}-${i+1}: ${aType}`,
        body:         `Follow-up action for activity ${i+1}`,
        // dueDate only allowed on TASK (BR-CRM-ACTIVITY)
        ...(aType === 'TASK' ? { dueDate: daysAhead(rndInt(1, 30)) } : {}),
      });
      if (uidOf(r)) { ok('crm.activity'); actOk++;
        // Complete ~60%
        if (i % 5 < 3) {
          await req('POST', `/crm/activities/uid/${uidOf(r)}/complete`, TOKEN);
        }
      } else fail('crm.activity', snip(r));
    }));
  }
  console.log(`  CRM activities: ${actOk}/${V.crmActivities}`);

  // ════════════════════════════════════════════════════════════════════════════
  // PHASE 11: PROJECTS, TASKS, TIMESHEETS
  // ════════════════════════════════════════════════════���═══════════════════════
  console.log('\n=== PHASE 11: Projects + tasks + timesheets ===');
  const projects = [];
  for (const batch of chunks([...Array(V.projects).keys()], 15)) {
    const res = await Promise.all(batch.map(async i => {
      const cust = customers[i % Math.max(customers.length, 1)];
      const r = await req('POST', '/projects', TOKEN, {
        companyUid, branchUid,
        name:             `Project ${TAG}-${i+1} ${rnd(ITEM_CATS)}`,
        customerUid:      cust.uid,
        plannedStartDate: randDate(30, 180),
        plannedEndDate:   daysAhead(rndInt(30, 365)),
        budgetAmount:     rndAmt(1000000, 50000000),
        notes:            `Project ${TAG}-${i+1}`,
      });
      if (uidOf(r)) { ok('project'); return { uid: uidOf(r) }; }
      fail('project', snip(r)); return null;
    }));
    projects.push(...res.filter(Boolean));
  }
  console.log(`  projects: ${projects.length}/${V.projects}`);

  // Tasks + timesheets on first 20 projects
  let taskOk = 0, tsOk = 0;
  for (const proj of projects.slice(0, 20)) {
    const nTasks = rndInt(2, 5);
    const taskUids = [];
    for (let t = 0; t < nTasks; t++) {
      const r = await req('POST', `/project-tasks/project/uid/${proj.uid}`, TOKEN, {
        taskCode:     `T${TAG}${t+1}`,
        name:         `Task ${t+1}`,
        plannedHours: rndInt(8, 80),
        billable:     t % 2 === 0,
      });
      if (uidOf(r)) { ok('project-task'); taskOk++; taskUids.push(uidOf(r)); }
      else fail('project-task', snip(r));
    }
    // Timesheets on each task
    for (const taskUid of taskUids) {
      for (let ts = 0; ts < 3; ts++) {
        const r = await req('POST', `/project-timesheets/project/uid/${proj.uid}`, TOKEN, {
          projectTaskUid: taskUid,
          userId:         userId || 1,
          workDate:       randDate(0, 30),
          hours:          rndAmt(1, 8),
          billable:       ts % 2 === 0,
          notes:          `Timesheet ${ts+1}`,
        });
        if (uidOf(r) || r.status < 300) { ok('timesheet'); tsOk++; }
        else fail('timesheet', snip(r));
      }
    }
  }
  console.log(`  project tasks: ${taskOk}  timesheets: ${tsOk}`);

  // ════════════��═══════════════════════════════════════════════════════════════
  // PHASE 12: MANUFACTURING — BOMs + Work Orders
  // ════════════════════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 12: Manufacturing ===');
  // BOMs: parent = GOODS products with stockable; components from products list
  const bomParents = products.slice(0, 30).filter((_, i) => i % 3 === 0);  // ~10 parents
  const bomMap = {};  // parentUid -> bomUid

  for (const parent of bomParents) {
    const r = await req('POST', `/boms?companyId=${companyId}`, TOKEN, {
      parentProductUid: parent.uid, outputQty: '1', yieldPercent: '100', notes: `BOM ${TAG}`,
    });
    if (!uidOf(r)) { fail('bom', snip(r)); continue; }
    ok('bom');
    const bomUid = uidOf(r);
    bomMap[parent.uid] = bomUid;

    // Add 2-4 components
    const nComp = rndInt(2, 4);
    for (let c = 0; c < nComp; c++) {
      const comp = products[(products.indexOf(parent) + c + 1) % products.length];
      if (comp.uid === parent.uid) continue;
      await req('POST', `/boms/uid/${bomUid}/components`, TOKEN, {
        componentProductUid: comp.uid, qtyPer: String(rndInt(1, 5)), scrapPercent: '0',
      });
    }
  }
  console.log(`  BOMs: ${Object.keys(bomMap).length}/${bomParents.length}`);

  // Work Orders (100)
  let woOk = 0;
  const woParents = Object.entries(bomMap);
  if (woParents.length) {
    for (const batch of chunks([...Array(V.workOrders).keys()], 20)) {
      await Promise.all(batch.map(async i => {
        const [parentUid, bomUid] = woParents[i % woParents.length];
        const r = await req('POST', '/work-orders', TOKEN, {
          finishedProductUid: parentUid,
          plannedQty:         rndInt(10, 200),
          branchUid,
          bomUid,
          plannedDate:        daysAhead(rndInt(1, 30)),
          notes:              `WO ${TAG}-${i+1}`,
        });
        if (!uidOf(r)) { fail('work-order', snip(r)); return; }
        ok('work-order');
        const woUid = uidOf(r);
        woOk++;

        // Release ~70%
        if (i % 10 < 7) {
          const relR = await req('POST', `/work-orders/uid/${woUid}/release`, TOKEN);
          if (relR.status < 300) ok('work-order.release');
          else fail('work-order.release', snip(relR));
        }
      }));
    }
  } else {
    fail('work-order', 'No BOMs available');
  }
  console.log(`  work orders: ${woOk}/${V.workOrders}`);

  // ══════════════════════════��═════════════════════════════════════════════════
  // PHASE 13: FIXED ASSETS — Categories + Assets + Depreciation Run
  // ═════════════════════════════════════════════════��══════════════════════════
  console.log('\n=== PHASE 13: Fixed assets ===');
  const faCategories = [];
  if (faAssetAcc?.id && faAccumAcc?.id && faExpAcc?.id) {
    const catDefs = [
      { code: `BLDG-${TAG}`,  name: 'Buildings',       method: 'STRAIGHT_LINE',    life: 40 },
      { code: `VEH-${TAG}`,   name: 'Vehicles',         method: 'REDUCING_BALANCE', life: 5,  rate: 30 },
      { code: `EQUIP-${TAG}`, name: 'Equipment',        method: 'STRAIGHT_LINE',    life: 10 },
      { code: `FURN-${TAG}`,  name: 'Furniture',        method: 'STRAIGHT_LINE',    life: 8 },
      { code: `COMP-${TAG}`,  name: 'Computer Equip',   method: 'REDUCING_BALANCE', life: 3,  rate: 50 },
    ];
    for (const cat of catDefs) {
      const r = await req('POST', '/fixed-assets/categories', TOKEN, {
        companyId,
        code:              cat.code,
        name:              cat.name,
        defaultMethod:     cat.method,
        defaultLifePeriods: cat.life * 12,
        defaultReducingRate: cat.rate || null,
        assetAccountId:    faAssetAcc.id,
        accumDepAccountId: faAccumAcc.id,
        depExpenseAccountId: faExpAcc.id,
      });
      if (uidOf(r)) { ok('fa.category'); faCategories.push({ uid: uidOf(r), id: idOf(r), ...cat }); }
      else fail('fa.category', snip(r));
    }
  } else {
    fail('fa.category', `Missing GL account IDs: asset=${faAssetAcc?.id} accum=${faAccumAcc?.id} exp=${faExpAcc?.id}`);
  }
  console.log(`  FA categories: ${faCategories.length}/5`);

  // Register fixed assets (100) — use batch=10 to avoid connection-pool pressure from schedule generation
  const fixedAssets = [];
  if (faCategories.length) {
    for (const batch of chunks([...Array(V.fixedAssets).keys()], 10)) {
      const res = await Promise.all(batch.map(async i => {
        const cat = faCategories[i % faCategories.length];
        const r = await req('POST', '/fixed-assets', TOKEN, {
          companyId, branchId,
          categoryId:             cat.id,
          name:                   `${cat.name} Asset ${TAG}-${i+1}`,
          acquisitionCost:        rndAmt(500000, 50000000),
          salvageValue:           rndAmt(50000, 500000),
          depreciationMethod:     cat.method,
          lifePeriods:            cat.life * 12,
          // reducingRate required when method=REDUCING_BALANCE; use cat.rate or 20 as default
          reducingRate:           cat.method === 'REDUCING_BALANCE' ? (cat.rate || 20) : null,
          acquisitionDate:        randDate(30, 1800),
          depreciationStartDate:  randDate(30, 1800),
          location:               `Location ${i % 5 + 1}`,
          assetTag:               `TAG-${TAG}-${i+1}`,
        });
        if (uidOf(r)) { ok('fixed-asset'); return { uid: uidOf(r), id: idOf(r) }; }
        fail('fixed-asset', snip(r)); return null;
      }));
      fixedAssets.push(...res.filter(Boolean));
    }
  }
  console.log(`  fixed assets registered: ${fixedAssets.length}/${V.fixedAssets}`);

  // Place first 50 assets in service
  let faServiceOk = 0;
  for (const fa of fixedAssets.slice(0, 50)) {
    const r = await req('POST', `/fixed-assets/uid/${fa.uid}/place-in-service`, TOKEN, {
      postingDate: today(),
    });
    if (r.status < 300) { ok('fixed-asset.place-in-service'); faServiceOk++; }
    else fail('fixed-asset.place-in-service', snip(r));
  }
  console.log(`  assets placed in service: ${faServiceOk}/50`);

  // Depreciation run (1 run if period and assets available)
  if (openPeriod?.uid && fixedAssets.length > 0) {
    const runR = await req('POST', '/fixed-assets/depreciation-runs', TOKEN, {
      companyId,
      fiscalPeriodUid: openPeriod.uid,
      postingDate:     today(),
    });
    if (uidOf(runR) || runR.status < 300) ok('depreciation-run');
    else fail('depreciation-run', snip(runR));
    console.log(`  depreciation run: status ${runR.status}`);
  } else {
    fail('depreciation-run', `No open period (${openPeriod?.uid}) or no FA assets`);
    console.log(`  depreciation run: skipped (period=${openPeriod?.uid}, fa=${fixedAssets.length})`);
  }

  // ═══���══════════════════════════��═════════════════════════════════════════════
  // PHASE 14: BUDGETS — each budget needs a unique (FY, costCentreValueUid) pair.
  // Discover the COST_CENTRE dimension and create/reuse values for scoping.
  // ════════════════════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 14: Budgets ===');
  let budgetOk = 0;
  if (fyUid) {
    // Discover COST_CENTRE dimension
    const dimR = await req('GET', `/dimensions?companyId=${companyId}&size=20`, TOKEN);
    const ccDim = listOf(dimR).find(d => d.slot === 'COST_CENTRE' || d.code === 'COST_CENTRE');
    // Create fresh dimension values for this run (using TAG suffix to avoid collisions)
    let ccValues = [];
    if (ccDim?.uid) {
      const ccNames = ['Operations','Sales','Finance','IT','HR','Admin','Marketing','Logistics','Legal','Production'];
      for (let ci = 0; ci < V.budgets && ci < ccNames.length; ci++) {
        const dvC = await req('POST', '/dimension-values', TOKEN, {
          companyId, dimensionUid: ccDim.uid, code: `CC-${TAG}-${ci+1}`, name: ccNames[ci % ccNames.length] + ` ${TAG}`,
        });
        if (uidOf(dvC)) ccValues.push(payload(dvC));
        // 409 = already exists (additive run) — reuse existing value for this code
        else if (dvC.status === 409) {
          const existR = await req('GET', `/dimension-values?companyId=${companyId}&dimensionUid=${ccDim.uid}&code=CC-${TAG}-${ci+1}&size=1`, TOKEN);
          const existVal = listOf(existR)[0];
          if (existVal?.uid) ccValues.push(existVal);
        }
      }
    }

    for (let i = 0; i < V.budgets; i++) {
      // Use a distinct cost centre value for each budget (always use a ccValue to avoid null scope 409)
      const ccVal = ccValues[i]?.uid || null;
      const deptName = ccValues[i]?.name || `Dept ${i+1}`;
      const r = await req('POST', '/budgets', TOKEN, {
        companyId,
        name:                `Budget ${TAG}-${i+1} ${deptName}`,
        fiscalYearUid:       fyUid,
        costCentreValueUid:  ccVal,
        notes:               `Annual budget ${TAG} for ${deptName}`,
        initialVersionLabel: `v1 Initial`,
      });
      if (uidOf(r)) { ok('budget'); budgetOk++;
        // Add a second version ~half
        if (i % 2 === 0) {
          await req('POST', `/budgets/uid/${uidOf(r)}/versions`, TOKEN, { label: 'v2 Revised', notes: 'Revised mid-year' });
        }
      } else if (r.status === 409) {
        // Skip — this scope already has a budget
      } else fail('budget', snip(r));
    }
  } else {
    fail('budget', 'No fiscal year available');
  }
  console.log(`  budgets: ${budgetOk}/${V.budgets}`);

  // ═════��══════════════════════════════════════════════════════════════════════
  // PHASE 15: PURCHASE REQUISITIONS + RFQs (10 each for variety)
  // ═════════════���══════════════════════════════════════════════════════════════
  console.log('\n=== PHASE 15: Requisitions + RFQs ===');
  let reqOk = 0, rfqOk = 0;
  for (let i = 0; i < 15; i++) {
    const prod = products[i % Math.max(products.length, 1)];
    const crR = await req('POST', '/purchase-requisitions', TOKEN, {
      companyUid, notes: `Requisition ${TAG}-${i+1}`,
      lines: [{ productId: prod.id, unitId, requestedQty: String(rndInt(10, 100)), estimatedUnitCost: rndAmt(200, 10000), note: 'needed' }],
    });
    if (!uidOf(crR)) { fail('purchase-requisition', snip(crR)); continue; }
    reqOk++;
    ok('purchase-requisition');
    const reqUid = uidOf(crR);
    await req('POST', `/purchase-requisitions/uid/${reqUid}/submit`, TOKEN);
    await req('POST', `/purchase-requisitions/uid/${reqUid}/approve`, TOKEN);
  }

  for (let i = 0; i < 15; i++) {
    const prod = products[i % Math.max(products.length, 1)];
    const sup1 = suppliers[i % Math.max(suppliers.length, 1)];
    const rfqR = await req('POST', '/rfqs', TOKEN, {
      companyUid,
      responseDueDate: daysAhead(14),
      notes:           `RFQ ${TAG}-${i+1}`,
      supplierUids:    [sup1.uid],
      lines:           [{ productId: prod.id, unitId, quantity: String(rndInt(10, 100)) }],
    });
    if (!uidOf(rfqR)) { fail('rfq', snip(rfqR)); continue; }
    rfqOk++; ok('rfq');
    const rfqUid = uidOf(rfqR);
    await req('POST', `/rfqs/uid/${rfqUid}/send`, TOKEN);
  }
  console.log(`  requisitions: ${reqOk}/15  RFQs: ${rfqOk}/15`);

  // ══════��═════════════════════════════���═══════════════════════════════════════
  // PHASE 16: STANDING ORDERS + PRICING RULES
  // ════════════════���═══════════════════════════════════════════════════════════
  console.log('\n=== PHASE 16: Standing orders + pricing rules ===');
  let soStandOk = 0;
  for (let i = 0; i < 20; i++) {
    const cust = customers[i % Math.max(customers.length, 1)];
    const prod = products[i % Math.max(products.length, 1)];
    const r = await req('POST', '/standing-orders', TOKEN, {
      companyUid, branchId,
      customerId: cust.id, currency: 'TZS',
      frequency:  ['DAILY','WEEKLY','MONTHLY'][i % 3],
      startDate:  today(),
      lines: [{ productId: prod.id, unitId, qty: String(rndInt(1,10)), qtyBase: String(rndInt(1,10)), unitPriceAmount: rndAmt(500, 50000) }],
      notes: `Standing order ${TAG}-${i+1}`,
    });
    if (uidOf(r)) { ok('standing-order'); soStandOk++; }
    else fail('standing-order', snip(r));
  }

  let tierOk = 0, cpOk = 0;
  for (let i = 0; i < 30; i++) {
    const prod = products[i % Math.max(products.length, 1)];
    if (priceListUid) {
      const r = await req('POST', '/pricing-rules/tiers', TOKEN, {
        companyUid, productUid: prod.uid, priceListUid,
        minQty: String(rndInt(5, 50)), unitPriceAmount: rndAmt(300, 90000), currency: 'TZS',
      });
      if (r.status < 300) { ok('pricing-rule.tier'); tierOk++; }
      else fail('pricing-rule.tier', snip(r));
    }
    const cust = customers[i % Math.max(customers.length, 1)];
    const cp = await req('POST', '/pricing-rules/customer-prices', TOKEN, {
      companyUid, customerUid: cust.uid, productUid: prod.uid,
      unitPriceAmount: rndAmt(300, 90000), currency: 'TZS',
      effectiveFrom: today(),
    });
    if (cp.status < 300) { ok('pricing-rule.customer-price'); cpOk++; }
    else fail('pricing-rule.customer-price', snip(cp));
  }
  console.log(`  standing orders: ${soStandOk}/20  price tiers: ${tierOk}/30  customer prices: ${cpOk}/30`);

  // ════════════════════════════════════════════════════════════════════════════
  // PHASE 17: SPOT-CHECK LIST ENDPOINTS
  // ═════════════════════���══════════════════════════════════════════════════════
  console.log('\n=== PHASE 17: Spot-check list endpoints ===');
  const checks = [
    ['products',       `/products?companyId=${companyId}&size=1`],
    ['customers',      `/customers?companyId=${companyId}&size=1`],
    ['suppliers',      `/suppliers?companyId=${companyId}&size=1`],
    ['sales-invoices', `/sales-invoices?companyId=${companyId}&size=1`],
    ['sales-orders',   `/sales-orders?companyId=${companyId}&size=1`],
    ['quotations',     `/quotations?companyId=${companyId}&size=1`],
    ['deliveries',     `/deliveries?companyId=${companyId}&size=1`],
    ['ap.bills',       `/ap/supplier-bills?companyId=${companyId}&size=1`],
    ['ap.payments',    `/ap/payments?companyId=${companyId}&size=1`],
    ['ar.receipts',    `/ar/receipts?companyId=${companyId}&size=1`],
    ['gl.journals',    `/gl/journals?companyId=${companyId}&size=1`],
    ['fixed-assets',   `/fixed-assets?companyId=${companyId}&size=1`],
    ['budgets',        `/budgets?companyId=${companyId}&size=1`],
    ['work-orders',    `/work-orders?companyId=${companyId}&size=1`],
    ['projects',       `/projects?companyId=${companyId}&size=1`],
    ['crm.leads',      `/crm/leads?companyId=${companyId}&size=1`],
    ['crm.opps',       `/crm/opportunities?companyId=${companyId}&size=1`],
    ['pos.tills',      `/pos/tills?companyId=${companyId}&branchId=${branchId}`],
    ['stock-transfers',`/stock-transfers?size=1`],
    ['stock-counts',   `/stock-counts?size=1`],
    ['employees',      `/hr/employees?companyId=${companyId}&size=1`],
    ['cash-accounts',  `/cash/accounts?companyId=${companyId}`],
    ['cash-transfers', `/cash/transfers?companyId=${companyId}`],
    ['stock.onhand',   `/stock/on-hand?size=1`],
  ];

  console.log(`\n  ${'Module'.padEnd(20)} ${'Status'.padEnd(8)} ${'totalElements'}`);
  console.log(`  ${'-'.repeat(45)}`);
  for (const [mod, path] of checks) {
    const r = await req('GET', path, TOKEN);
    const p = payload(r);
    // totalElements can be in body.meta.totalElements (ApiResponse wrapper) or p.totalElements
    const total = r.body?.meta?.totalElements ?? p?.totalElements ?? (Array.isArray(p) ? p.length : '?');
    const flag = r.status >= 500 ? 'ERROR' : (r.status >= 400 ? 'WARN' : 'OK');
    console.log(`  ${mod.padEnd(20)} ${flag.padEnd(8)} ${total}`);
    if (r.status >= 500) fail(`spotcheck.${mod}`, `GET ${path} -> ${r.status}`);
    else ok(`spotcheck.${mod}`);
  }

  // ════════════════════════════════════════════════════════════════════════════
  // FINAL REPORT
  // ════════════════════════════════════════════════════════════════════════════
  const elapsed = ((Date.now() - T0) / 1000).toFixed(1);
  console.log(`\n${'='.repeat(70)}`);
  console.log(`BULK-SEED COMPLETE  tag=${TAG}  elapsed=${elapsed}s`);
  console.log(`${'='.repeat(70)}\n`);

  // Compute totals
  let grandOk = 0, grandFail = 0;
  const modKeys = Object.keys(STATS).filter(k => !k.startsWith('spotcheck.')).sort();
  const spotKeys = Object.keys(STATS).filter(k => k.startsWith('spotcheck.')).sort();

  console.log(`${'Module'.padEnd(35)} ${'Created'.padStart(8)} ${'Failed'.padStart(8)}  Sample error`);
  console.log('-'.repeat(90));
  for (const mod of modKeys) {
    const s = STATS[mod];
    grandOk   += s.ok;
    grandFail += s.fail;
    const errSnip = s.sampleErr ? s.sampleErr.slice(0, 55) : '';
    console.log(`${mod.padEnd(35)} ${String(s.ok).padStart(8)} ${String(s.fail).padStart(8)}  ${errSnip}`);
  }
  console.log('-'.repeat(90));
  console.log(`${'TOTAL'.padEnd(35)} ${String(grandOk).padStart(8)} ${String(grandFail).padStart(8)}`);
  console.log(`\nScript: web/e2e/bulk-seed.mjs`);
  console.log(`Wall-clock: ${elapsed}s`);

  const notCovered = [];
  if (!STATS['gl-journal']?.ok) notCovered.push('gl-journal (no GL accounts)');
  if (!STATS['fa.category']?.ok) notCovered.push('fa.category (no GL account IDs)');
  if (!STATS['depreciation-run']?.ok) notCovered.push('depreciation-run (no open period or no FA)');
  if (!STATS['budget']?.ok) notCovered.push('budget (no fiscal year)');
  if (!STATS['cash-transfer']?.ok) notCovered.push('cash-transfer (need 2+ cash accounts with GL links)');
  if (!STATS['pos.sale']?.ok) notCovered.push('pos.sale (stock/agent prerequisite)');

  if (notCovered.length) {
    console.log('\n--- Modules not fully populated (blocking reason) ---');
    for (const m of notCovered) console.log(`  BLOCKED: ${m}`);
  }
})();
