// Real typed data entry against a LIVE site via Playwright. NO API seeding — every record is
// typed into a form and submitted. Logs issues + screenshots, continues on failure.
// Robust per-record flow: open a FRESH create form, fill, submit via ngSubmit (Enter), then WAIT
// for the form to close (the app's success signal) before the next record — so the loop never
// re-creates or double-submits.
//
// Config via env: WEB_BASE (default http://16.170.11.41), ROOT_USER, ROOT_PASS (REQUIRED),
//   SHOTS_DIR, N_CUSTOMERS, N_SUPPLIERS, N_PRODUCTS, N_USERS, N_ROUTES. See e2e/README.md.
//   Run:  ROOT_PASS=... node e2e/qa-ui-drive.js
const { chromium } = require('playwright-core');
const fs = require('fs'); const path = require('path');
const PWROOT = process.env.PLAYWRIGHT_BROWSERS_PATH || (process.env.LOCALAPPDATA + '/ms-playwright');
const EXE = fs.readdirSync(PWROOT).filter(d=>/^chromium-\d/.test(d)).sort().pop();
const BIN = ['chrome-win/chrome.exe','chrome-win64/chrome.exe'].map(p=>`${PWROOT}/${EXE}/${p}`).find(fs.existsSync);
const BASE = process.env.WEB_BASE || 'http://16.170.11.41';
const RUSER = process.env.ROOT_USER || 'rootadmin';
const RPASS = process.env.ROOT_PASS || '';
const SHOTS = process.env.SHOTS_DIR || (require('node:os').tmpdir() + '/erp-qa-shots');
const N = { customers:+(process.env.N_CUSTOMERS||50), suppliers:+(process.env.N_SUPPLIERS||10),
            products:+(process.env.N_PRODUCTS||10), users:+(process.env.N_USERS||5), routes:+(process.env.N_ROUTES||3) };
fs.mkdirSync(SHOTS, { recursive: true });
if (!RPASS) { console.error('ROOT_PASS env is required'); process.exit(2); }

const ISSUES = []; const COUNTS = {}; let SHOTN = 0;
const issue = (sev, area, msg, detail) => { ISSUES.push({ sev, area, msg, detail }); console.log(`  [${sev}] ${area}: ${msg}${detail?' — '+detail:''}`); };
const ok = (a) => { COUNTS[a] = (COUNTS[a]||0) + 1; };

(async () => {
  const b = await chromium.launch({ executablePath: BIN, headless: true });
  const pg = await (await b.newContext({ viewport: { width: 1400, height: 1000 } })).newPage();
  const cerr = []; const api5xx = [];
  pg.on('console', m => { if (m.type()==='error') cerr.push(m.text()); });
  pg.on('response', r => { if (r.url().includes('/api/') && r.status()>=500) api5xx.push(`${r.status()} ${r.request().method()} ${r.url().replace(BASE,'')}`); });
  const shot = async (n) => { try { await pg.screenshot({ path: path.join(SHOTS, String(++SHOTN).padStart(2,'0')+'-'+n+'.png'), fullPage: true }); } catch {} };

  async function goto(p){ await pg.goto(BASE+p,{waitUntil:'networkidle'}).catch(()=>{}); await pg.waitForTimeout(400); }
  // Ensure the inline create form is OPEN (idempotent — only clicks the toggle if the form isn't visible).
  async function ensureFormOpen(toggleLabel, anchorSel){
    if (await pg.locator(anchorSel).first().isVisible().catch(()=>false)) return true;
    const btn = pg.locator('button',{hasText:new RegExp('^\\s*'+toggleLabel+'\\s*$','i')}).first();
    if (await btn.count() && await btn.isVisible().catch(()=>false)){ await btn.click(); await pg.waitForTimeout(250); }
    return pg.locator(anchorSel).first().isVisible().catch(()=>false);
  }
  async function setField(sel,val){ const el=pg.locator(sel).first(); if(!await el.count()) throw new Error('no field '+sel);
    const tag=await el.evaluate(n=>n.tagName).catch(()=>''); if(tag==='SELECT'){ await el.selectOption(String(val)).catch(async()=>el.selectOption({label:String(val)})); } else { await el.fill(String(val)); } }
  async function pickFirstReal(sel){ const el=pg.locator(sel).first(); if(await el.count()){ const n=await el.locator('option').count(); if(n>1) await el.selectOption({index:1}); } }
  // Submit via ngSubmit (Enter in a text field) and WAIT for the create form to disappear (success).
  async function submitAndWaitClosed(anchorSel, fieldToEnter){
    await pg.locator(fieldToEnter).first().press('Enter').catch(async()=>{ await pg.locator('button[type="submit"]').filter({hasText:/add|save|create/i}).first().click(); });
    // success = the anchor field is gone (form closed) within 8s
    await pg.locator(anchorSel).first().waitFor({ state:'detached', timeout:8000 });
  }

  try {
    console.log(`=== LOGIN ${RUSER} @ ${BASE} ===`);
    await goto('/login');
    await pg.fill('input[type="text"]', RUSER); await pg.fill('input[type="password"]', RPASS);
    await pg.click('button[type="submit"]'); await pg.waitForTimeout(2000);
    if (pg.url().includes('/login')) { issue('BLOCKER','login','could not log in'); await shot('FAIL-login'); throw new Error('login'); }
    console.log('  ok →', pg.url().replace(BASE,'')); await shot('home');

    // NOTE: units are pre-seeded by bootstrap (17 of them) — we do NOT create units.

    // PRICE LIST (single)
    console.log('=== PRICE LIST ===');
    try { await goto('/admin/price-lists'); await ensureFormOpen('New Price List','#newCode');
      await setField('#newCode','RETAIL'); await setField('#newName','Retail');
      await submitAndWaitClosed('#newCode','#newName'); ok('pricelist');
    } catch(e){ issue('MEDIUM','pricelist','create', String(e.message||e).slice(0,90)); await shot('FAIL-pricelist'); }

    // PRODUCTS
    console.log(`=== PRODUCTS (${N.products}) ===`);
    await goto('/admin/products');
    for (let i=1;i<=N.products;i++){
      try { if(!await ensureFormOpen('New Product','#newName')) throw new Error('form did not open');
        await setField('#newName',`QA Product ${i}`);
        await pickFirstReal('#newBaseUnit'); await pg.locator('#newVatStatus').selectOption('STANDARD').catch(()=>{});
        await submitAndWaitClosed('#newName','#newName'); ok('product');
      } catch(e){ issue(i<=2?'HIGH':'MEDIUM','product',`create ${i}`, String(e.message||e).slice(0,90)); if(i<=2) await shot('FAIL-product-'+i); }
    }

    // CUSTOMERS
    console.log(`=== CUSTOMERS (${N.customers}) ===`);
    await goto('/admin/customers');
    for (let i=1;i<=N.customers;i++){
      try { if(!await ensureFormOpen('New Customer','#newDisplayName')) throw new Error('form did not open');
        await setField('#newDisplayName',`QA Customer ${i}`);
        await pg.locator('#newPartyType').selectOption('INDIVIDUAL').catch(()=>{});
        await pg.locator('#newCustomerKind').selectOption(i%2?'CASH_WALK_IN':'CREDIT_ACCOUNT').catch(()=>{});
        await submitAndWaitClosed('#newDisplayName','#newDisplayName'); ok('customer');
      } catch(e){ issue(i<=2?'HIGH':'MEDIUM','customer',`create ${i}`, String(e.message||e).slice(0,80)); if(i<=2) await shot('FAIL-customer-'+i); }
      if (i%10===0) console.log(`  ...${i}`);
    }
    await shot('customers');

    // SUPPLIERS
    console.log(`=== SUPPLIERS (${N.suppliers}) ===`);
    await goto('/admin/suppliers');
    for (let i=1;i<=N.suppliers;i++){
      try { if(!await ensureFormOpen('New Supplier','#newDisplayName')) throw new Error('form did not open');
        await setField('#newDisplayName',`QA Supplier ${i}`);
        await pg.locator('#newPartyType').selectOption('INDIVIDUAL').catch(()=>{});
        await pickFirstReal('#newSupplierKind');
        await submitAndWaitClosed('#newDisplayName','#newDisplayName'); ok('supplier');
      } catch(e){ issue(i<=2?'HIGH':'MEDIUM','supplier',`create ${i}`, String(e.message||e).slice(0,80)); }
    }
    await shot('suppliers');

    // USERS — form is ALWAYS open (no toggle), submit button = "Add user", no company picker
    console.log(`=== USERS (${N.users}) ===`);
    await goto('/admin/users');
    for (let i=1;i<=N.users;i++){
      try {
        await setField('#newUsername',`qauser${i}`);
        await setField('#newDisplayName',`QA User ${i}`);
        await setField('#newPassword','UserPass12345');
        // submit; the always-open form clears its fields on success — wait for username to empty
        await pg.locator('#newPassword').press('Enter').catch(async()=>pg.locator('button[type="submit"]',{hasText:/add user/i}).first().click());
        await pg.locator('#newUsername').first().waitFor({state:'visible',timeout:5000}).catch(()=>{});
        await pg.waitForFunction(()=>{ const e=document.querySelector('#newUsername'); return e && e.value===''; },{timeout:8000});
        ok('user');
      } catch(e){ issue(i<=2?'HIGH':'MEDIUM','user',`create ${i}`, String(e.message||e).slice(0,80)); if(i<=2) await shot('FAIL-user-'+i); }
    }
    await shot('users');

    // ROUTES — NOTE: the route Save is a type="button" (click)="create()", NOT a form submit,
    // so Enter does nothing here; we must CLICK the Save button. Form closes on success.
    console.log(`=== ROUTES (${N.routes}) ===`);
    await goto('/admin/routes');
    await pickFirstReal('#route-company-select'); // single company auto-selects, but be explicit
    for (let i=1;i<=N.routes;i++){
      try { if(!await ensureFormOpen('New Route','#new-route-name')) throw new Error('form did not open');
        await setField('#new-route-name',`QA Route ${i}`); await setField('#new-route-location',`Area ${i}`);
        await pg.locator('button',{hasText:/^\s*Save\s*$/}).first().click();
        await pg.locator('#new-route-name').first().waitFor({ state:'detached', timeout:8000 });
        ok('route');
      } catch(e){ issue('MEDIUM','route',`create ${i}`, String(e.message||e).slice(0,90)); if(i<=1) await shot('FAIL-route-'+i); }
    }
    await shot('routes');

  } catch(e){ issue('HIGH','FATAL','run aborted', String(e&&e.message||e).slice(0,120)); await shot('FATAL'); }
  finally {
    console.log('\n=== CREATED (typed via UI) ==='); for (const [k,v] of Object.entries(COUNTS)) console.log(`  ${k}: ${v}`);
    console.log('\n=== CONSOLE ERRORS ==='); console.log(cerr.length?[...new Set(cerr)].slice(0,10).join('\n'):'(none)');
    console.log('=== API 5xx ==='); console.log(api5xx.length?[...new Set(api5xx)].join('\n'):'(none)');
    const sev={BLOCKER:0,HIGH:0,MEDIUM:0,LOW:0}; for(const i of ISSUES) sev[i.sev]=(sev[i.sev]||0)+1;
    console.log('\n=== ISSUES ==='); console.log(JSON.stringify(sev));
    fs.writeFileSync(SHOTS+'/qa-issues.json', JSON.stringify({ counts:COUNTS, issues:ISSUES, consoleErrors:[...new Set(cerr)], api5xx:[...new Set(api5xx)] }, null, 2));
    console.log('\nshots+json → '+SHOTS);
    await b.close(); process.exit(0);
  }
})();
