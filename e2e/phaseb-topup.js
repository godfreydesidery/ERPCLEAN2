// Focused top-up seeder for the two Phase-B modules the generated seeder left empty due to
// its own gating bugs (NOT app bugs — verified: lead/opportunity/dimension creates return 200):
//   - CRM: reuse the 5 bootstrap-seeded pipeline stages; create leads (qualify/disqualify some),
//          then opportunities across stages (the opportunity-number fix is live).
//   - cost-centre: create the COST_CENTRE + DEPARTMENT dimensions (bootstrap seeds none), then values.
//   Run:  API_BASE=http://16.170.11.41/api/v1 ROOT_PASS=... node e2e/phaseb-topup.js
const http = require('http');
const B = process.env.API_BASE || 'http://16.170.11.41/api/v1';
const ROOT_USER = process.env.ROOT_USER || 'rootadmin';
const ROOT_PASS = process.env.ROOT_PASS || '';
if (!ROOT_PASS) { console.error('ROOT_PASS required'); process.exit(2); }
function req(method, path, token, body) {
  return new Promise((resolve) => {
    const data = body ? JSON.stringify(body) : null;
    const u = new URL(B + path);
    const opt = { method, hostname: u.hostname, port: u.port || 80, path: u.pathname + u.search,
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
const ok = (r) => r.status >= 200 && r.status < 300;
const D = (r) => (r && r.body && r.body.data) ? r.body.data : null;
const log = (m) => console.log('  ' + m);

(async () => {
  const lr = await req('POST', '/auth/login', null, { username: ROOT_USER, password: ROOT_PASS });
  const token = D(lr) && D(lr).accessToken;
  if (!token) { console.error('login failed'); process.exit(1); }
  const orgs = await req('GET', '/organisations', token); const org = (D(orgs) || [])[0];
  const comps = await req('GET', '/companies?organisationUid=' + org.uid, token); const company = (D(comps) || [])[0];
  const cid = Number(company.id);
  const brs = await req('GET', '/branches?companyUid=' + company.uid, token);
  const branch = (D(brs) || []).find(b => b.isDefault) || (D(brs) || [])[0];
  const bid = Number(branch.id);
  console.log(`top-up against ${B} — company=${company.uid}(id ${cid}) branch=${branch.uid}(id ${bid})`);
  const counts = {};

  // ---- CRM ----
  console.log('\n=== CRM top-up ===');
  const stR = await req('GET', `/crm/pipeline-stages?companyId=${cid}`, token);
  const stages = (D(stR) || []);
  log(`pipeline stages available: ${stages.length}`);
  // customers from Tier-1
  const cuR = await req('GET', `/customers?companyId=${cid}&page=0&size=20`, token);
  const customers = (D(cuR) || []);
  log(`customers available: ${customers.length}`);

  const leadSpecs = [
    ['Amani Telecom Ltd','WEBSITE','Amani Telecom','John Mwanga'],
    ['Kilimanjaro Traders','REFERRAL','Kilimanjaro Traders','Agnes Shirima'],
    ['Dar es Salaam Motors','COLD_CALL','DSM Motors Ltd','Hassan Ally'],
    ['Zanzibar Spice Exports','CAMPAIGN','ZSE Ltd','Fatuma Juma'],
    ['Moshi Coffee Cooperative','WALK_IN','Moshi Coop','Peter Kimaro'],
    ['Arusha Safari Lodges','EXISTING_CUSTOMER','ASL Holdings','Mary Nkini'],
    ['Iringa Agricultural Hub','REFERRAL','IAH Ltd','Sylvester Mwale'],
    ['Tanga Port Logistics','OTHER','TPL Co','Daudi Hamisi'],
  ];
  const leadUids = []; counts.leads = 0;
  for (const [name, src, co, person] of leadSpecs) {
    const r = await req('POST', '/crm/leads', token, { companyId: cid, displayName: name, leadSource: src,
      companyName: co, contactPerson: person, phone: '+255711' + String(100000 + counts.leads), email: person.split(' ')[0].toLowerCase() + '@' + co.split(' ')[0].toLowerCase() + '.co.tz', notes: 'QA demo lead' });
    if (ok(r) && D(r)) { leadUids.push(D(r).uid); counts.leads++; } else log(`  lead "${name}" failed: ${r.status} ${String(r.raw).slice(0,80)}`);
  }
  log(`leads created: ${counts.leads}`);
  // qualify the first 4, disqualify the 5th, leave rest NEW
  counts.qualified = 0; counts.disqualified = 0;
  for (let i = 0; i < leadUids.length; i++) {
    if (i < 4) { const r = await req('POST', `/crm/leads/uid/${leadUids[i]}/qualify`, token, {}); if (ok(r)) counts.qualified++; }
    else if (i === 4) { const r = await req('POST', `/crm/leads/uid/${leadUids[i]}/disqualify`, token, { reason: 'Budget not available this FY' }); if (ok(r)) counts.disqualified++; }
  }
  log(`leads qualified: ${counts.qualified}, disqualified: ${counts.disqualified}`);
  // opportunities across stages (the opportunity-number fix is live)
  counts.opportunities = 0;
  const oppSpecs = [
    ['Enterprise ERP Rollout', '12000000', 60, '2026-09-30'],
    ['Inventory Module Upgrade', '4500000', 40, '2026-10-15'],
    ['Fleet Management System', '8000000', 50, '2026-11-30'],
    ['CRM Module Subscription', '2400000', 70, '2026-08-31'],
    ['Manufacturing Suite', '15000000', 30, '2026-12-20'],
  ];
  for (let i = 0; i < oppSpecs.length && customers.length && stages.length; i++) {
    const [title, val, prob, close] = oppSpecs[i];
    const cust = customers[i % customers.length];
    const stage = stages[i % stages.length];
    const r = await req('POST', '/crm/opportunities', token, { companyId: cid, branchId: bid, title,
      customerUid: cust.uid, pipelineStageUid: stage.uid, currency: 'TZS', estimatedValueAmount: val,
      expectedCloseDate: close, winProbability: String(prob) });
    if (ok(r) && D(r)) { counts.opportunities++; }
    else log(`  opp "${title}" failed: ${r.status} ${String(r.raw).slice(0,90)}`);
  }
  log(`opportunities created: ${counts.opportunities}`);

  // ---- cost-centre dimension values (dimensions are bootstrap-seeded built-ins; not API-created) ----
  console.log('\n=== cost-centre top-up ===');
  counts.dimensionValues = 0;
  const dimR = await req('GET', `/dimensions?companyId=${cid}`, token);
  const dims = (D(dimR) || []);
  log(`dimensions available: ${dims.length} (${dims.map(d => d.slot || d.code).join(', ')})`);
  const ccDim = dims.find(d => d.slot === 'COST_CENTRE') || dims.find(d => (d.code || '').includes('COST'));
  const deptDim = dims.find(d => d.slot === 'DEPARTMENT') || dims.find(d => (d.code || '').includes('DEPART'));
  const ccVals = [['HO','Head Office'],['OPS','Operations'],['FIN','Finance'],['SAL','Sales'],['WH','Warehouse']];
  const deptVals = [['ENG','Engineering'],['ACC','Accounting'],['MKT','Marketing'],['HR','Human Resources']];
  for (const [dim, vals] of [[ccDim, ccVals], [deptDim, deptVals]]) {
    if (!dim) continue;
    for (const [vc, vn] of vals) {
      const r = await req('POST', '/dimension-values', token, { dimensionUid: dim.uid, code: vc, name: vn, parentUid: null });
      if (ok(r)) counts.dimensionValues++;
      else log(`  dim-value ${dim.slot}/${vc}: ${r.status} ${String(r.raw).slice(0,80)}`);
    }
  }
  log(`dimension values created: ${counts.dimensionValues}`);

  console.log('\n=== TOP-UP SUMMARY ===');
  console.log('  ' + JSON.stringify(counts));
})();
