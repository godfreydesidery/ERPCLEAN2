// PERSONA OPERATION DRIVER — one persona logs into the REAL web UI as THEMSELVES (non-root) and does
// their job: enters master data they own (typed into forms), and opens the transaction screens their
// role should reach. Every block / error / permission-denial is captured as a candidate User Problem
// Report. Running as a non-root persona is the whole point — it surfaces the permission & route-guard
// gaps that root never sees.
//
//   NODE_PATH=d:/My_Works/ERP/ERPCLEAN2/web/node_modules PERSONA=amina-mwanga node e2e/sim/operate.js
//   (or pass the slug as argv[2])
const L = require('./sim-lib');
const D = require('./sim-data');

const slug = process.argv[2] || process.env.PERSONA;
const persona = D.STAFF.find(p => p.slug === slug);
if (!persona) { console.error('unknown persona slug:', slug); process.exit(2); }

// ---- access targets per role: screens this role SHOULD reach (a 403 here is a real finding) ----
const ACCESS = {
  GROUP_GM: [['/admin/home', 'Group dashboard'], ['/admin/sales-orders', 'Sales orders'], ['/admin/purchase-orders', 'Purchase orders'], ['/admin/stock', 'Stock on-hand']],
  FINANCE_DIRECTOR: [['/admin/gl/journals/post', 'Post journal'], ['/admin/ar/receipts/record', 'Record receipt'], ['/admin/ap/payments/record', 'Record payment'], ['/admin/ap/supplier-bills/enter', 'Enter supplier bill']],
  BRANCH_MANAGER: [['/admin/sales-orders', 'Sales orders'], ['/admin/purchase-orders', 'Purchase orders'], ['/admin/stock', 'Stock on-hand'], ['/admin/stock-transfers/create', 'Stock transfer']],
  ACCOUNTANT: [['/admin/gl/journals/post', 'Post journal'], ['/admin/ar/receipts/record', 'Record receipt'], ['/admin/ap/supplier-bills/enter', 'Enter supplier bill']],
  CASHIER: [['/admin/ar/receipts/record', 'Record receipt'], ['/admin/ap/payments/record', 'Record payment']],
  SALES_OFFICER: [['/admin/sales-orders', 'Sales orders'], ['/admin/customers', 'Customers'], ['/admin/pos/sell', 'POS sell'], ['/admin/products', 'Products']],
  FIELD_SALES_AGENT: [['/admin/sales-orders', 'Sales orders'], ['/admin/customers', 'Customers']],
  PROCUREMENT_OFFICER: [['/admin/purchase-orders', 'Purchase orders'], ['/admin/suppliers', 'Suppliers'], ['/admin/products', 'Products']],
  STOREKEEPER: [['/admin/stock', 'Stock on-hand'], ['/admin/goods-receipts/create', 'Goods receipt'], ['/admin/stock-counts/create', 'Stock count']],
  STORES_SUPERVISOR: [['/admin/stock', 'Stock on-hand'], ['/admin/stock-counts/create', 'Stock count'], ['/admin/stock-transfers/create', 'Stock transfer']],
  PRODUCTION_OFFICER: [['/admin/work-orders', 'Work orders'], ['/admin/boms', 'Bills of materials']],
  HR_PAYROLL_OFFICER: [['/admin/users', 'Users (people)']],
};

// ---- helpers reused from qa-ui-drive proven flow ----
async function createOne(page, buf, rec, opts) {
  // opts: {path, openLabel, anchor, fields:[[sel,val,how]], submitField, name, workflow}
  await L.goto(page, opts.path);
  if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', opts.workflow, opts.path, `permission denied opening ${opts.path} (role ${persona.role} should allow this)`, buf.snapshot()); return false; }
  try {
    if (opts.openLabel && !await L.ensureFormOpen(page, opts.openLabel, opts.anchor)) throw new Error('create form did not open');
    buf.clear();
    for (const [sel, val, how] of opts.fields) {
      if (how === 'select') { await L.pickByLabel(page, sel, val).catch(() => L.pickFirstReal(page, sel)); }
      else if (how === 'selectVal') { await page.locator(sel).selectOption(String(val)).catch(() => {}); }
      else if (how === 'first') { await L.pickFirstReal(page, sel); }
      else { await L.setField(page, sel, val); }
    }
    // submit
    if (!await L.clickButton(page, /^\s*(add|save|create|record)\b/i)) await L.submitByEnter(page, opts.submitField || opts.anchor);
    await page.waitForTimeout(900);
    const snap = buf.snapshot();
    if (snap.api.some(a => a.status >= 500)) { rec.problem('BLOCKED', opts.workflow, opts.path, `server error saving "${opts.name}"`, snap); return false; }
    if (snap.api.some(a => a.status === 403)) { rec.problem('BLOCKED', opts.workflow, opts.path, `not allowed to save "${opts.name}"`, snap); return false; }
    if (snap.api.some(a => a.status >= 400)) { rec.problem('SLOW', opts.workflow, opts.path, `could not save "${opts.name}" (validation/${snap.api.find(a => a.status >= 400).status})`, snap); return false; }
    rec.ok(opts.workflow); return true;
  } catch (e) {
    rec.problem('SLOW', opts.workflow, opts.path, `entering "${opts.name}": ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); return false;
  }
}

// ---- per-persona ACTION missions (typed master-data entry by its rightful owner) ----
async function runActions(page, buf, rec) {
  const u = Date.now().toString().slice(-5);
  if (persona.role === 'SALES_OFFICER') {
    // Sabina registers real customers + a price list
    for (const c of D.CUSTOMERS.slice(0, 5)) {
      await createOne(page, buf, rec, { path: '/admin/customers', openLabel: 'New Customer', anchor: '#newDisplayName',
        fields: [['#newDisplayName', c.name], ['#newPartyType', 'INDIVIDUAL', 'selectVal'], ['#newCustomerKind', c.kind, 'selectVal']],
        submitField: '#newDisplayName', name: c.name, workflow: 'register customer' });
    }
    await createOne(page, buf, rec, { path: '/admin/price-lists', openLabel: 'New Price List', anchor: '#newCode',
      fields: [['#newCode', 'RETAIL'], ['#newName', 'Retail price list']], submitField: '#newName', name: 'RETAIL', workflow: 'create price list' });
  }
  if (persona.role === 'PROCUREMENT_OFFICER') {
    // Yusuf / Rehema register real suppliers + sourced products
    for (const s of D.SUPPLIERS.slice(0, 5)) {
      await createOne(page, buf, rec, { path: '/admin/suppliers', openLabel: 'New Supplier', anchor: '#newDisplayName',
        fields: [['#newDisplayName', s.name], ['#newPartyType', 'ORGANISATION', 'selectVal'], ['#newSupplierKind', '', 'first']],
        submitField: '#newDisplayName', name: s.name, workflow: 'register supplier' });
    }
    for (const p of D.PRODUCTS_SOURCED.slice(0, 5)) {
      await createOne(page, buf, rec, { path: '/admin/products', openLabel: 'New Product', anchor: '#newName',
        fields: [['#newName', p], ['#newBaseUnit', '', 'first'], ['#newVatStatus', 'STANDARD', 'selectVal']],
        submitField: '#newName', name: p, workflow: 'create sourced product' });
    }
  }
  if (persona.role === 'PRODUCTION_OFFICER') {
    // Editha registers manufactured products
    for (const p of D.PRODUCTS_MANUFACTURED) {
      await createOne(page, buf, rec, { path: '/admin/products', openLabel: 'New Product', anchor: '#newName',
        fields: [['#newName', p], ['#newBaseUnit', '', 'first'], ['#newVatStatus', 'STANDARD', 'selectVal']],
        submitField: '#newName', name: p, workflow: 'create manufactured product' });
    }
  }
  if (persona.role === 'FIELD_SALES_AGENT') {
    await createOne(page, buf, rec, { path: '/admin/customers', openLabel: 'New Customer', anchor: '#newDisplayName',
      fields: [['#newDisplayName', 'Joseph Ulimboka'], ['#newPartyType', 'INDIVIDUAL', 'selectVal'], ['#newCustomerKind', 'CREDIT_ACCOUNT', 'selectVal']],
      submitField: '#newDisplayName', name: 'Joseph Ulimboka', workflow: 'register route customer' });
  }
}

(async () => {
  const browser = await L.launch();
  const { page, buf } = await L.newSession(browser);
  const rec = L.makeRecorder(persona);
  const result = { persona: persona.fullName, username: persona.username, role: persona.role, access: [], loggedIn: false };

  try {
    console.log(`=== ${persona.fullName} (${persona.designation}) login as ${persona.username} ===`);
    const li = await L.login(page, persona.username, L.SIM_PASSWORD);
    if (!li.ok) {
      rec.problem('BLOCKED', 'sign in', '/login', `${persona.fullName} could not sign in with username ${persona.username}`, buf.snapshot());
      throw new Error('login failed');
    }
    result.loggedIn = true;
    // forced password change on first login?
    if (/change.?password|set.?password|reset/i.test(li.landedUrl)) {
      rec.problem('SLOW', 'sign in', li.landedUrl, 'forced to change password on first login before I could work', buf.snapshot());
    }
    console.log('  landed ->', li.landedUrl);
    await L.shot(page, `${persona.slug}-home`);

    // ACCESS missions: can I open my own screens?
    const targets = ACCESS[persona.role] || [];
    for (const [path, label] of targets) {
      buf.clear();
      await L.goto(page, path);
      const url = page.url().replace(L.BASE, '');
      let outcome = 'OK';
      if (url.includes('/login')) { outcome = 'KICKED_TO_LOGIN'; rec.problem('BLOCKED', `open ${label}`, path, 'I was thrown back to the login screen trying to open my own screen', buf.snapshot()); }
      else if (await L.looksForbidden(page, buf)) { outcome = 'FORBIDDEN'; rec.problem('BLOCKED', `open ${label}`, path, `I'm told I don't have permission to open ${label}, but it's part of my job (${persona.role})`, buf.snapshot()); }
      else {
        const snap = buf.snapshot();
        if (snap.api.some(a => a.status >= 500)) { outcome = 'SERVER_ERROR'; rec.problem('BLOCKED', `open ${label}`, path, `${label} failed to load (server error)`, snap); }
        else if (snap.page.length || snap.realConsole().length) { outcome = 'JS_ERROR'; rec.problem('SLOW', `open ${label}`, path, `${label} opened but the screen reported an error`, snap); }
      }
      result.access.push({ path, label, outcome });
      console.log(`  ${outcome.padEnd(16)} ${path}`);
    }

    // ACTION missions: enter the master data I own
    await runActions(page, buf, rec);

  } catch (e) {
    rec.problem('BLOCKED', 'operate', 'fatal', String(e && e.message || e).slice(0, 140), buf.snapshot());
    await L.shot(page, `${persona.slug}-FATAL`);
  } finally {
    result.created = rec.created;
    result.problemCount = rec.problems.length;
    console.log(`\n=== ${persona.fullName}: created=${JSON.stringify(rec.created)} problems=${rec.problems.length} ===`);
    L.saveResults(`operate-${persona.slug}.json`, { ...result, problems: rec.problems });
    await browser.close();
    process.exit(0);
  }
})();
