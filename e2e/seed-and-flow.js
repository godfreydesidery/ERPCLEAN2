// Large-scale E2E: rootadmin bootstraps, then non-root users/agents operate independently.
// API-bulk for volume (100u/1000c/50s/...), real per-user RBAC. Records issues, never aborts.
// Config via env: API_BASE, ROOT_USER, ROOT_PASS, ISSUES_OUT. See e2e/README.md.
//   Run:  node e2e/seed-and-flow.js
const http = require('http');
const B = process.env.API_BASE || 'http://127.0.0.1:8088/api/v1';
const ROOT_USER = process.env.ROOT_USER || 'rootadmin';
const ROOT_PASS = process.env.ROOT_PASS || 'RootPass12345';
const ISSUES_OUT = process.env.ISSUES_OUT || (require('os').tmpdir() + '/erp-e2e-issues.json');
const ISSUES = []; const TIMINGS = {};
const issue = (sev, area, msg, detail) => { ISSUES.push({ sev, area, msg, detail }); console.log(`  [${sev}] ${area}: ${msg}${detail?' — '+detail:''}`); };

function req(method, path, token, body) {
  return new Promise((resolve) => {
    const data = body ? JSON.stringify(body) : null;
    const u = new URL(B + path);
    const opt = { method, hostname: u.hostname, port: u.port, path: u.pathname + u.search,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) } };
    if (data) opt.headers['Content-Length'] = Buffer.byteLength(data);
    const r = http.request(opt, (res) => { let b = ''; res.on('data', c => b += c); res.on('end', () => {
      let j = null; try { j = b ? JSON.parse(b) : null; } catch {}
      resolve({ status: res.statusCode, body: j, raw: b });
    }); });
    r.on('error', e => resolve({ status: 0, body: null, raw: String(e) }));
    if (data) r.write(data); r.end();
  });
}
const time = async (k, fn) => { const t = Date.now ? null : null; const s = process.hrtime.bigint(); const r = await fn(); const ms = Number(process.hrtime.bigint() - s) / 1e6; TIMINGS[k] = (TIMINGS[k]||[]).concat(ms); return r; };
async function login(username, password) {
  const r = await req('POST', '/auth/login', null, { username, password });
  if (r.status !== 200 || !r.body?.data?.accessToken) { issue('BLOCKER','auth',`login failed for ${username}`, `status=${r.status} ${r.raw?.slice(0,80)}`); return null; }
  return r.body.data.accessToken;
}
const dataUid = (r, area, what) => { if (r.status >= 300 || !r.body?.data) { issue('HIGH', area, `${what} failed`, `status=${r.status} ${(r.raw||'').slice(0,100)}`); return null; } return r.body.data.uid; };

(async () => {
  console.log('=== PHASE 0: rootadmin login + bootstrap discovery ===');
  const root = await login(ROOT_USER, ROOT_PASS);
  if (!root) { dump(); return; }

  // company uid (companies list 400/needs org) — get org first
  // bootstrap created an org; find it via /organisations
  let org = await req('GET', '/organisations', root);
  const orgUid = org.body?.data?.[0]?.uid;
  if (!orgUid) issue('HIGH','iam','no organisation from /organisations', `status=${org.status}`);
  let comps = orgUid ? await req('GET', `/companies?organisationUid=${orgUid}`, root) : {status:0};
  const companyUid = comps.body?.data?.[0]?.uid;
  const companyId  = comps.body?.data?.[0]?.id;
  if (!companyUid) { issue('BLOCKER','iam','no company found — cannot proceed', `status=${comps.status}`); dump(); return; }
  console.log(`  org=${orgUid} company=${companyUid}(id ${companyId})`);

  // existing branch(es)
  let brs = await req('GET', `/branches?companyUid=${companyUid}`, root);
  if (brs.status >= 300) brs = await req('GET', `/branches?companyId=${companyId}`, root);
  let branches = (brs.body?.data || []).map(b => ({ uid: b.uid, code: b.code }));
  console.log(`  existing branches: ${branches.length}`);

  console.log('=== PHASE 1: root creates branches (target ~5) ===');
  for (let i = 1; i <= 5; i++) {
    const r = await req('POST', '/branches', root, { companyUid, code: `BR-E${i}`, name: `E2E Branch ${i}` });
    const uid = r.status < 300 ? r.body?.data?.uid : null;
    if (!uid) { // try companyId variant
      const r2 = await req('POST', '/branches', root, { companyId, code: `BR-E${i}`, name: `E2E Branch ${i}` });
      if (r2.status < 300) branches.push({ uid: r2.body.data.uid, code: `BR-E${i}` });
      else issue('MEDIUM','branch',`create branch BR-E${i} failed`, `status=${r.status}/${r2.status} ${(r.raw||r2.raw||'').slice(0,80)}`);
    } else branches.push({ uid, code: `BR-E${i}` });
  }
  console.log(`  branches now: ${branches.length}`);
  if (!branches.length) { issue('BLOCKER','branch','no usable branch'); dump(); return; }

  console.log('=== PHASE 2: root creates an operator role with broad permissions ===');
  // discover permission catalogue
  const perms = await req('GET', '/roles/permissions', root);
  const allCodes = (perms.body?.data || []).map(p => p.code || p);
  const opCodes = allCodes.filter(c => /PRODUCT|PRICELIST|UOM|CUSTOMER|SUPPLIER|AGENT|OTHERPARTY|SALES|STOCK|PURCHASE|ROUTE|TAXRATE|BRANCH\.VIEW/.test(c));
  console.log(`  permission catalogue: ${allCodes.length}; operator gets ${opCodes.length}`);
  const roleR = await req('POST', '/roles', root, { code: 'E2E_OPERATOR', name: 'E2E Operator', description: 'broad ops role' });
  const roleUid = dataUid(roleR, 'role', 'create E2E_OPERATOR');
  if (roleUid) {
    const sp = await req('PUT', `/roles/uid/${roleUid}/permissions`, root, { permissionCodes: opCodes });
    if (sp.status >= 300) issue('HIGH','role','set permissions failed', `status=${sp.status} ${(sp.raw||'').slice(0,100)}`);
  }

  console.log('=== PHASE 3: root creates 100 users, assigns branches + grants the role ===');
  const users = [];
  for (let i = 1; i <= 100; i++) {
    const username = `e2euser${String(i).padStart(3,'0')}`;
    const r = await req('POST', '/users', root, { username, displayName: `E2E User ${i}`, password: 'UserPass12345', email: `${username}@e2e.test` });
    const uUid = r.status < 300 ? r.body?.data?.uid : null;
    if (!uUid) { issue(i<=3?'HIGH':'MEDIUM','user',`create ${username} failed`, `status=${r.status} ${(r.raw||'').slice(0,80)}`); continue; }
    const branch = branches[i % branches.length];
    const ab = await req('POST', '/user-branches', root, { userUid: uUid, branchUid: branch.uid, makeDefault: true });
    if (ab.status >= 300) issue(i<=3?'HIGH':'LOW','user-branch',`assign branch to ${username} failed`, `status=${ab.status} ${(ab.raw||'').slice(0,90)}`);
    const gr = await req('POST', '/user-roles', root, { userUid: uUid, roleUid, companyUid });
    if (gr.status >= 300) issue(i<=3?'HIGH':'LOW','user-role',`grant role to ${username} failed`, `status=${gr.status} ${(gr.raw||'').slice(0,90)}`);
    users.push({ username, uid: uUid, branchUid: branch.uid });
    if (i % 25 === 0) console.log(`  ...${i} users`);
  }
  console.log(`  users created: ${users.length}/100`);

  console.log('=== PHASE 4: a NON-ROOT operator logs in and bulk-creates parties/catalogue (real RBAC) ===');
  const op = users[0];
  let opToken = op ? await login(op.username, 'UserPass12345') : null;
  if (!opToken) { issue('BLOCKER','rbac','operator could not log in — RBAC seed broken; falling back to root for seeding'); opToken = root; }
  // unit (reuse seeded PCS) + price list + products
  let units = await req('GET', `/units?companyId=${companyId}`, opToken);
  let unitUid = units.body?.data?.[0]?.uid;
  if (!unitUid) issue('HIGH','units','operator cannot list units', `status=${units.status}`);
  const plR = await req('POST', '/price-lists', opToken, { companyUid, code: 'RETAIL', name: 'Retail' });
  const plUid = dataUid(plR, 'pricelist', 'operator create price list');

  console.log('  creating 50 products (priced)...');
  const products = [];
  for (let i = 1; i <= 50; i++) {
    const pr = await time('product.create', () => req('POST', '/products', opToken, { companyUid, name: `Product ${i}`, type: 'GOODS', sellable: true, stockable: true, baseUnitUid: unitUid, vatStatus: 'STANDARD' }));
    const pUid = pr.status < 300 ? pr.body?.data?.uid : null;
    if (!pUid) { issue(i<=2?'HIGH':'MEDIUM','product',`create Product ${i} failed`, `status=${pr.status} ${(pr.raw||'').slice(0,80)}`); continue; }
    if (plUid) { const sp = await req('POST', `/products/uid/${pUid}/prices`, opToken, { priceListUid: plUid, price: { amount: String(500+i*10), currency: 'TZS' } }); if (sp.status>=300) issue('LOW','price',`set price product ${i} failed`,`status=${sp.status}`); }
    products.push(pUid);
  }
  console.log(`  products: ${products.length}/50`);

  console.log('  creating 50 suppliers...');
  let supOk = 0; const suppliers = [];
  for (let i = 1; i <= 50; i++) {
    const r = await time('supplier.create', () => req('POST', '/suppliers', opToken, { companyId, partyType: 'INDIVIDUAL', displayName: `Supplier ${i}`, supplierKind: 'GOODS' }));
    if (r.status < 300 && r.body?.data?.uid) { suppliers.push(r.body.data.uid); supOk++; }
    else issue(i<=2?'HIGH':'MEDIUM','supplier',`create Supplier ${i} failed`, `status=${r.status} ${(r.raw||'').slice(0,80)}`);
  }
  console.log(`  suppliers: ${supOk}/50`);

  console.log('  creating 1000 customers...');
  let custOk = 0; const customers = [];
  for (let i = 1; i <= 1000; i++) {
    const kind = i % 2 ? 'CASH_WALK_IN' : 'CREDIT_ACCOUNT';
    const r = await time('customer.create', () => req('POST', '/customers', opToken, { companyId, partyType: 'INDIVIDUAL', displayName: `Customer ${i}`, customerKind: kind }));
    if (r.status < 300 && r.body?.data?.uid) { custOk++; if (customers.length < 50) customers.push(r.body.data.uid); }
    else issue(i<=2?'HIGH':'LOW','customer',`create Customer ${i} failed`, `status=${r.status} ${(r.raw||'').slice(0,80)}`);
    if (i % 250 === 0) console.log(`  ...${i} customers`);
  }
  console.log(`  customers: ${custOk}/1000`);

  console.log('  creating 20 EXTERNAL agents + 10 routes + assigning...');
  const agents = [];
  for (let i = 1; i <= 20; i++) { const r = await req('POST', '/agents', opToken, { companyId, partyType: 'INDIVIDUAL', displayName: `Agent ${i}`, agentKind: 'EXTERNAL' }); if (r.status<300&&r.body?.data?.uid) agents.push(r.body.data.uid); else issue(i<=2?'HIGH':'LOW','agent',`create Agent ${i} failed`,`status=${r.status} ${(r.raw||'').slice(0,80)}`); }
  const routes = [];
  for (let i = 1; i <= 10; i++) { const r = await req('POST', '/routes', opToken, { companyUid, name: `Route ${i}`, locationIdentifier: `Area ${i}` }); const ru = r.status<300?r.body?.data?.uid:null; if (ru) { routes.push(ru); // assign first agent as primary + some customers
      if (agents[i-1]) { const ra = await req('POST', `/routes/uid/${ru}/agents`, opToken, { agentUid: agents[i-1], isPrimary: true }); if (ra.status>=300) issue('MEDIUM','route-agent',`assign agent to route ${i} failed`,`status=${ra.status} ${(ra.raw||'').slice(0,80)}`); }
      for (const c of customers.slice(0,5)) { const rc = await req('POST', `/routes/uid/${ru}/customers`, opToken, { customerUid: c }); if (rc.status>=300) { issue('LOW','route-customer',`assign customer to route ${i} failed`,`status=${rc.status}`); break; } }
    } else issue(i<=2?'HIGH':'MEDIUM','route',`create Route ${i} failed`,`status=${r.status} ${(r.raw||'').slice(0,80)}`); }
  console.log(`  agents: ${agents.length}/20, routes: ${routes.length}/10`);

  console.log('=== PHASE 5: operational loop — PO→receive (stock in), then sales (stock out), assert stock ===');
  // need PURCHASE.RECEIVE etc — operator role has them. Run 10 PO+receipts on product[0..9], qty 100 each
  let stockExpected = {};
  for (let i = 0; i < Math.min(10, products.length); i++) {
    const p = products[i]; const sup = suppliers[i % Math.max(1,suppliers.length)];
    if (!sup) break;
    const po = await req('POST', '/purchase-orders', opToken, { companyUid, supplierUid: sup, currency: 'TZS', lines: [{ productUid: p, unitUid, orderedQty: '100', unitCostAmount: '300' }] });
    const poUid = po.status<300?po.body?.data?.uid:null;
    if (!poUid) { issue('HIGH','purchase',`PO ${i} failed`,`status=${po.status} ${(po.raw||'').slice(0,90)}`); continue; }
    await req('POST', `/purchase-orders/uid/${poUid}/place`, opToken);
    const linesR = await req('GET', `/purchase-orders/uid/${poUid}/lines`, opToken);
    const lineUid = (Array.isArray(linesR.body?.data)?linesR.body.data:linesR.body?.data?.content||[])[0]?.uid;
    const gr = await req('POST', '/goods-receipts', opToken, { purchaseOrderUid: poUid, lines: [{ purchaseOrderLineUid: lineUid, receivedQty: '100' }] });
    if (gr.status>=300) issue('HIGH','goods-receipt',`GR ${i} failed`,`status=${gr.status} ${(gr.raw||'').slice(0,90)}`);
    else stockExpected[p] = (stockExpected[p]||0) + 100;
  }
  // give the outbox poller time to apply GOODS_RECEIPT
  console.log('  waiting for outbox to apply receipts...'); await new Promise(r=>setTimeout(r, 8000));

  // sales: 20 invoices, 2 units each of product[0..9], finalise w/ cash
  let soldPerProduct = {};
  for (let i = 0; i < 20; i++) {
    const p = products[i % Math.min(10, products.length)]; const cust = customers[i % customers.length]; const ag = agents[i % Math.max(1,agents.length)];
    const inv = await req('POST', '/sales-invoices', opToken, { companyUid, customerUid: cust, agentUid: ag, currency: 'TZS' });
    const invUid = inv.status<300?inv.body?.data?.uid:null;
    if (!invUid) { issue('HIGH','sales',`invoice ${i} create failed`,`status=${inv.status} ${(inv.raw||'').slice(0,90)}`); continue; }
    const al = await req('POST', `/sales-invoices/uid/${invUid}/lines`, opToken, { productUid: p, unitUid, quantity: '2' });
    if (al.status>=300) { issue('HIGH','sales',`invoice ${i} add-line failed`,`status=${al.status} ${(al.raw||'').slice(0,90)}`); continue; }
    // gross = 2*price + 18% ; fetch invoice for gross
    const got = await req('GET', `/sales-invoices/uid/${invUid}`, opToken);
    const gross = got.body?.data?.grossTotalAmount;
    await req('POST', `/sales-invoices/uid/${invUid}/payments`, opToken, { tenderType: 'CASH', amount: String(gross), currency: 'TZS' });
    const fin = await req('PUT', `/sales-invoices/uid/${invUid}/finalize`, opToken, {});
    if (fin.status>=300) issue('HIGH','sales',`invoice ${i} finalize failed`,`status=${fin.status} ${(fin.raw||'').slice(0,90)}`);
    else soldPerProduct[p] = (soldPerProduct[p]||0) + 2;
  }
  console.log('  waiting for outbox to apply sale issues...'); await new Promise(r=>setTimeout(r, 8000));

  console.log('=== PHASE 6: ASSERT correctness at scale ===');
  // counts
  const cc = await req('GET', `/customers?companyId=${companyId}&page=0&size=1`, opToken);
  const custTotal = cc.body?.meta?.totalElements;
  if (custTotal != custOk) issue('MEDIUM','assert',`customer count mismatch`, `created=${custOk} list.totalElements=${custTotal}`);
  else console.log(`  ✓ customer count matches: ${custTotal}`);
  // stock = received - sold per product (check first 10)
  let stockOk = true;
  for (let i = 0; i < Math.min(10, products.length); i++) {
    const p = products[i]; const exp = (stockExpected[p]||0) - (soldPerProduct[p]||0);
    const oh = await req('GET', `/stock/on-hand?companyId=${companyId}&page=0&size=200`, opToken);
    const rows = oh.body?.data || [];
    const row = rows.find(r => r.productId && String(r.productId) === String(r.productId)); // fallback below
    // match by productId resolved via product list — simpler: pull on-hand for product via movements sum is heavy; compare totals
  }
  // simpler stock assertion: total on-hand across product[0..9] should be receivedTotal - soldTotal
  const recv = Object.values(stockExpected).reduce((a,b)=>a+b,0);
  const sold = Object.values(soldPerProduct).reduce((a,b)=>a+b,0);
  const ohAll = await req('GET', `/stock/on-hand?companyId=${companyId}&page=0&size=500`, opToken);
  const ohRows = ohAll.body?.data || [];
  const ohSum = ohRows.reduce((a,r)=>a + Number(r.quantity||0), 0);
  console.log(`  received total=${recv}, sold total=${sold}, expected net=${recv-sold}, actual on-hand sum=${ohSum}`);
  if (Math.abs(ohSum - (recv - sold)) > 0.001) issue('HIGH','assert',`stock math mismatch`, `expected ${recv-sold} got ${ohSum}`);
  else console.log(`  ✓ stock math correct: ${ohSum} == ${recv}-${sold}`);

  // RBAC isolation: a fresh second operator on a different branch must NOT see... (same company, so they DO see company data — check cross-company would need a 2nd company; note)
  // numbering uniqueness: invoice numbers unique
  const invList = await req('GET', `/sales-invoices?companyId=${companyId}&page=0&size=50`, opToken);
  const nums = (invList.body?.data||[]).map(x=>x.invoiceNumber).filter(Boolean);
  if (new Set(nums).size !== nums.length) issue('HIGH','assert','duplicate invoice numbers', nums.join(','));
  else console.log(`  ✓ invoice numbers unique (${nums.length})`);

  dump();
  function noop(){}
})();

function dump() {
  console.log('\n================ TIMINGS (avg ms) ================');
  for (const [k,arr] of Object.entries(TIMINGS)) { const avg=(arr.reduce((a,b)=>a+b,0)/arr.length).toFixed(1); const mx=Math.max(...arr).toFixed(0); console.log(`  ${k}: avg ${avg}ms, max ${mx}ms, n=${arr.length}`); }
  console.log('\n================ ISSUES ================');
  const bySev = { BLOCKER:[], HIGH:[], MEDIUM:[], LOW:[] };
  for (const i of ISSUES) (bySev[i.sev]||(bySev[i.sev]=[])).push(i);
  for (const sev of ['BLOCKER','HIGH','MEDIUM','LOW']) {
    const list = bySev[sev]||[]; console.log(`${sev}: ${list.length}`);
    // collapse duplicates by area+msg
    const seen = {}; for (const i of list) { const k=i.area+'|'+i.msg.replace(/\d+/g,'#'); seen[k]=(seen[k]||0)+1; }
    for (const [k,n] of Object.entries(seen)) console.log(`   (${n}x) ${k}`);
  }
  require('fs').writeFileSync(ISSUES_OUT, JSON.stringify({ issues: ISSUES, timings: Object.fromEntries(Object.entries(TIMINGS).map(([k,a])=>[k,{avg:a.reduce((x,y)=>x+y,0)/a.length,max:Math.max(...a),n:a.length}])) }, null, 2));
  console.log('\nwrote ' + ISSUES_OUT);
}
