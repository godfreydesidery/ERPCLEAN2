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
  HR_PAYROLL_OFFICER: [['/admin/hr/employees', 'Employees']], // HR owns employees, NOT IAM system users
};

// SWEEP mode (SWEEP=1): every persona visits EVERY operational screen (the 15 launchpad destinations),
// recording OK / FORBIDDEN / REDIRECTED_HOME per screen — an access matrix to systematically find any
// role silently blocked from a screen its job needs (the Goods-Receipt/PURCHASE.RECEIVE class).
const SWEEP_TARGETS = [
  ['/admin/dashboard', 'Dashboard'], ['/admin/sales-orders', 'Sales orders'], ['/admin/pos/sell', 'POS sell'],
  ['/admin/customers', 'Customers'], ['/admin/purchase-orders', 'Purchase orders'], ['/admin/goods-receipts/create', 'Goods receipt'],
  ['/admin/suppliers', 'Suppliers'], ['/admin/stock', 'Stock on-hand'], ['/admin/stock-counts/create', 'Stock count'],
  ['/admin/stock-transfers/create', 'Stock transfer'], ['/admin/ar/receipts/record', 'Record receipt'],
  ['/admin/ap/payments/record', 'Record payment'], ['/admin/ap/supplier-bills/enter', 'Enter supplier bill'],
  ['/admin/gl/journals/post', 'Post journal'], ['/admin/work-orders', 'Work orders'],
];

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
    // Procurement / master-data owns product master data — sourced AND manufactured — and registers
    // suppliers. Production only requests new SKUs (ISSUE-006 / US-PRODUCTS-001;
    // docs/requirements/product-master-data-ownership.md).
    for (const s of D.SUPPLIERS.slice(0, 5)) {
      await createOne(page, buf, rec, { path: '/admin/suppliers', openLabel: 'New Supplier', anchor: '#newDisplayName',
        fields: [['#newDisplayName', s.name], ['#newPartyType', 'ORGANISATION', 'selectVal'], ['#newSupplierKind', '', 'first']],
        submitField: '#newDisplayName', name: s.name, workflow: 'register supplier' });
    }
    for (const p of [...D.PRODUCTS_SOURCED.slice(0, 5), ...D.PRODUCTS_MANUFACTURED]) {
      await createOne(page, buf, rec, { path: '/admin/products', openLabel: 'New Product', anchor: '#newName',
        fields: [['#newName', p], ['#newBaseUnit', '', 'first'], ['#newVatStatus', 'STANDARD', 'selectVal']],
        submitField: '#newName', name: p, workflow: 'register product master data' });
    }
  }
  // PRODUCTION_OFFICER does NOT create product master data (it needs PRODUCT.MANAGE, a procurement /
  // master-data function). Production views/consumes products and requests new SKUs — its value in this
  // run is the access checks on Work Orders + BOMs. See ISSUE-006 / US-PRODUCTS-001.
  if (persona.role === 'FIELD_SALES_AGENT') {
    await createOne(page, buf, rec, { path: '/admin/customers', openLabel: 'New Customer', anchor: '#newDisplayName',
      fields: [['#newDisplayName', 'Joseph Ulimboka'], ['#newPartyType', 'INDIVIDUAL', 'selectVal'], ['#newCustomerKind', 'CREDIT_ACCOUNT', 'selectVal']],
      submitField: '#newDisplayName', name: 'Joseph Ulimboka', workflow: 'register route customer' });
  }
}

// DEEP transactional missions — the real work each role does once the screens open. Tolerant: a failure
// is captured as a finding (BLOCKED on 5xx/permission, SLOW otherwise), never a crash. Opt-in via DEEP=1.
async function runDeep(page, buf, rec) {
  const today = new Date().toISOString().slice(0, 10);
  const tag = Date.now().toString().slice(-5);
  const worst = (s) => s.api.filter(a => a.status >= 400).sort((a, b) => b.status - a.status)[0];

  // ACCOUNTANT / FINANCE: post a balanced 2-line manual journal (GL posting integrity)
  if (persona.role === 'ACCOUNTANT' || persona.role === 'FINANCE_DIRECTOR') {
    const wf = 'post a manual journal', screen = '/admin/gl/journals/post';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Post Journal', buf.snapshot()); }
    else {
      try {
        buf.clear();
        await L.setField(page, '#postingDate', today).catch(() => {});
        await L.setField(page, '#description', `Sim journal ${tag}`).catch(() => {});
        const addBtn = page.locator('button', { hasText: /add line/i }).first();
        let acc = page.locator('select[id^="lineAccount_"]');
        let guard = 0;
        while ((await acc.count()) < 2 && (await addBtn.count()) && guard++ < 4) { await addBtn.click(); await page.waitForTimeout(250); acc = page.locator('select[id^="lineAccount_"]'); }
        const n = await acc.count();
        const optCount = n ? await acc.first().locator('option').count() : 0;
        if (n < 2) rec.problem('SLOW', wf, screen, 'could not get two journal lines to enter', buf.snapshot());
        else if (optCount < 2) rec.problem('BLOCKED', wf, screen, 'no GL accounts to post against (chart of accounts empty for my company?)', buf.snapshot());
        else {
          const id0 = (await acc.nth(0).getAttribute('id')).replace('lineAccount_', '');
          const id1 = (await acc.nth(1).getAttribute('id')).replace('lineAccount_', '');
          await acc.nth(0).selectOption({ index: 1 }).catch(() => {});
          await acc.nth(1).selectOption({ index: optCount > 2 ? 2 : 1 }).catch(() => {});
          await L.setField(page, `#lineDebit_${id0}`, '10000').catch(() => {});
          await L.setField(page, `#lineCreditAmt_${id1}`, '10000').catch(() => {});
          await page.waitForTimeout(300);
          buf.clear();
          const posted = await L.clickButton(page, /post journal/i);
          await page.waitForTimeout(1300);
          const s = buf.snapshot();
          if (!posted) rec.problem('SLOW', wf, screen, 'Post Journal stayed disabled (not balanced / missing field)', s);
          else if (s.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error posting the journal', s);
          else if (worst(s)) rec.problem('SLOW', wf, screen, `journal rejected (${worst(s).status})`, s);
          else rec.ok('post journal');
        }
      } catch (e) { rec.problem('SLOW', wf, screen, `posting journal: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // ACCOUNTANT: enter a supplier bill (AP) against Mbasha — the daily payables intake
  if (persona.role === 'ACCOUNTANT') {
    const wf = 'enter a supplier bill', screen = '/admin/ap/supplier-bills/enter';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Enter Supplier Bill', buf.snapshot()); }
    else {
      try {
        buf.clear();
        const sup = await L.searchPick(page, '#supplierSearch', 'Mbasha');
        await L.setField(page, '#supplierInvoiceNo', 'BILL-' + Date.now());
        await L.setField(page, '#billDate', today).catch(() => {});
        await L.setField(page, '#dueDate', today).catch(() => {});
        if (!sup) rec.problem('SLOW', wf, screen, 'could not pick supplier Mbasha Holdings on the bill', buf.snapshot());
        else {
          await L.clickButton(page, /add line/i);
          await page.waitForTimeout(500);
          await L.setField(page, '#desc_0', 'Cement supply').catch(() => {});
          await L.setField(page, '#qty_0', '10').catch(() => {});
          await L.setField(page, '#cost_0', '15000').catch(() => {});
          await page.waitForTimeout(400);
          buf.clear();
          await L.clickButton(page, /enter bill/i);
          await page.waitForTimeout(1600);
          const s = buf.snapshot();
          if (s.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error entering the supplier bill', s);
          else if (worst(s)) rec.problem('SLOW', wf, screen, `supplier bill rejected (${worst(s).status})`, s);
          else rec.ok('enter supplier bill');
        }
      } catch (e) { rec.problem('SLOW', wf, screen, `entering bill: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // PROCUREMENT: raise a PO to Mbasha Holdings -> add a line -> place
  if (persona.role === 'PROCUREMENT_OFFICER') {
    const wf = 'raise a purchase order', screen = '/admin/purchase-orders';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Purchase Orders', buf.snapshot()); }
    else {
      try {
        await L.ensureFormOpen(page, 'New Order', '#supplierSearch');
        buf.clear();
        const sup = await L.searchPick(page, '#supplierSearch', 'Mbasha');
        await L.setField(page, '#newExpectedDate', today).catch(() => {});
        if (!sup) rec.problem('SLOW', wf, screen, 'could not pick supplier Mbasha Holdings in the PO form', buf.snapshot());
        else {
          await L.clickButton(page, /create order/i);
          await page.waitForTimeout(1600);
          const s1 = buf.snapshot();
          if (s1.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error creating the PO', s1);
          else if (worst(s1)) rec.problem('SLOW', wf, screen, `PO header rejected (${worst(s1).status})`, s1);
          else {
            // header created -> the app may redirect to the new PO's detail OR land on the list. Make
            // sure we're on the NEW DRAFT PO, then WAIT for the add-line form (it is @if(isDraft &&
            // canCreate) under @default, so it only renders once lines finish loading async).
            if (!/\/purchase-orders\/uid\//.test(page.url())) {
              // create() stays on the list — filter to DRAFT so we open a PO that still has the add-line
              // form (concurrency-safe: never opens a PO another procurement persona just placed/ORDERED).
              await page.selectOption('#statusFilter', 'DRAFT').catch(() => {});
              await page.waitForTimeout(900);
              const link = page.locator('a[href*="/admin/purchase-orders/uid/"]').first();
              if (await link.count()) { await link.click(); }
            }
            await page.locator('#lineProductSearch').waitFor({ state: 'visible', timeout: 6000 }).catch(() => {});
            buf.clear();
            const prod = await L.searchPick(page, '#lineProductSearch', 'Cement');
            if (!prod) rec.problem('SLOW', wf, '/admin/purchase-orders/uid', 'could not pick a product for the PO line (add-line form not shown — PO not DRAFT, no CREATE perm, or still loading)', buf.snapshot());
            else {
              await L.pickFirstReal(page, '#lineUnit').catch(() => {});
              await L.setField(page, '#lineQty', '100').catch(() => {});
              await L.setField(page, '#lineUnitCost', '15000').catch(() => {});
              await L.clickButton(page, /add line/i);
              await page.waitForTimeout(1000);
              const s2 = buf.snapshot();
              if (worst(s2)) rec.problem('SLOW', wf, '/admin/purchase-orders/uid', `could not add the PO line (${worst(s2).status})`, s2);
              else {
                rec.ok('raise PO + line');
                buf.clear();
                await L.clickButton(page, /^\s*place\b/i);
                await page.waitForTimeout(1100);
                const s3 = buf.snapshot();
                if (worst(s3)) rec.problem('SLOW', wf, '/admin/purchase-orders/uid', `could not place the order (${worst(s3).status})`, s3);
                else rec.ok('place PO');
              }
            }
          }
        }
      } catch (e) { rec.problem('SLOW', wf, screen, `raising PO: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // PROCUREMENT (master-data, PRODUCT.MANAGE): price a product into the RETAIL list so it is sellable.
  // A sales order cannot add a line for an unpriced product ("Product has no price configured") — this
  // is the order-to-cash prerequisite a normal user enters in the UI.
  if (persona.role === 'PROCUREMENT_OFFICER') {
    const wf = 'price a product for sale', screen = '/admin/products';
    await L.goto(page, screen);
    try {
      const row = page.locator('tr', { hasText: 'Cement' }).first();
      const link = row.locator('a[href*="/admin/products/uid/"]').first();
      if (!(await link.count())) rec.problem('SLOW', wf, screen, 'could not find the Cement product to price it', buf.snapshot());
      else {
        await link.click(); await page.waitForTimeout(1200);
        buf.clear();
        if (!(await page.locator('#newPriceList').first().count())) rec.problem('SLOW', wf, '/admin/products/uid', 'no Set-price form (pricing needs PRODUCT.MANAGE)', buf.snapshot());
        else {
          if (!(await L.pickByLabel(page, '#newPriceList', 'RETAIL'))) await L.pickFirstReal(page, '#newPriceList');
          await L.setField(page, '#newPriceAmount', '18000').catch(() => {});
          if (!(await L.clickButton(page, /set price|save price|add price/i))) await L.submitByEnter(page, '#newPriceAmount');
          await page.waitForTimeout(1000);
          const s = buf.snapshot();
          if (s.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, '/admin/products/uid', 'server error setting the price', s);
          else if (worst(s) && worst(s).status !== 409) rec.problem('SLOW', wf, '/admin/products/uid', `could not set the price (${worst(s).status})`, s);
          else rec.ok('price product');
        }
      }
    } catch (e) { rec.problem('SLOW', wf, screen, `pricing product: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
  }

  // SALES: create a sales order for Kariakoo -> add a line
  if (persona.role === 'SALES_OFFICER') {
    const wf = 'create a sales order', screen = '/admin/sales-orders';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Sales Orders', buf.snapshot()); }
    else {
      try {
        await L.ensureFormOpen(page, 'New Sales Order', '#customerSearch');
        buf.clear();
        const cust = await L.searchPick(page, '#customerSearch', 'Kariakoo');
        await L.setField(page, '#newOrderDate', today).catch(() => {});
        if (!cust) rec.problem('SLOW', wf, screen, 'could not pick customer Kariakoo Wholesale Mart', buf.snapshot());
        else {
          await L.clickButton(page, /create order/i);
          await page.waitForTimeout(1600);
          const s1 = buf.snapshot();
          if (s1.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error creating the sales order', s1);
          else if (worst(s1)) rec.problem('SLOW', wf, screen, `sales order header rejected (${worst(s1).status})`, s1);
          else {
            // header created -> we're on the list; open the new (top) SO's detail to add a line (DRAFT)
            const link = page.locator('a[href*="/admin/sales-orders/uid/"]').first();
            if (await link.count()) { await link.click(); await page.waitForTimeout(1300); }
            buf.clear();
            // search a product procurement actually registered (the first sourced products are created)
            const prod = await L.searchPick(page, '#lineProductSearch', 'Cement');
            if (!prod) rec.problem('SLOW', wf, '/admin/sales-orders/uid', 'could not pick a product for the SO line', buf.snapshot());
            else {
              await L.pickFirstReal(page, '#lineUnit').catch(() => {});
              await L.setField(page, '#lineQty', '20').catch(() => {});
              await L.setField(page, '#lineUnitPrice', '18000').catch(() => {}); // Cement has no price-list default; supply a selling price
              await L.clickButton(page, /add line/i);
              await page.waitForTimeout(1000);
              const s2 = buf.snapshot();
              if (worst(s2)) rec.problem('SLOW', wf, '/admin/sales-orders/uid', `could not add the SO line (${worst(s2).status})`, s2);
              else rec.ok('create SO + line');
            }
          }
        }
      } catch (e) { rec.problem('SLOW', wf, screen, `creating SO: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // STORES: record a stock opening balance
  if (persona.role === 'STOREKEEPER' || persona.role === 'STORES_SUPERVISOR') {
    const wf = 'record stock opening balance', screen = '/admin/stock';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Stock', buf.snapshot()); }
    else {
      try {
        buf.clear();
        await L.ensureFormOpen(page, 'Opening', '#openingProductSearch').catch(() => {});
        if (!(await page.locator('#openingProductSearch').first().count())) rec.problem('SLOW', wf, screen, 'could not find the opening-balance form', buf.snapshot());
        else {
          const prod = await L.searchPick(page, '#openingProductSearch', 'Cement');
          if (!prod) rec.problem('SLOW', wf, screen, 'could not pick a product for the opening balance', buf.snapshot());
          else {
            await L.setField(page, '#openingQty', '500').catch(() => {});
            await L.clickButton(page, /^\s*record\b/i);
            await page.waitForTimeout(1000);
            const s = buf.snapshot();
            if (s.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error recording opening balance', s);
            else if (worst(s)) rec.problem('SLOW', wf, screen, `opening balance rejected (${worst(s).status})`, s);
            else rec.ok('stock opening');
          }
        }
      } catch (e) { rec.problem('SLOW', wf, screen, `opening balance: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // BRANCH_MANAGER: raise an inter-branch stock transfer — daily logistics
  if (persona.role === 'BRANCH_MANAGER') {
    const wf = 'raise an inter-branch transfer', screen = '/admin/stock-transfers/create';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Stock Transfer', buf.snapshot()); }
    else {
      try {
        buf.clear();
        const srcB = page.locator('select[aria-labelledby="srcBranchLabel"]');
        const branchOpts = await srcB.locator('option').count().catch(() => 0);
        await srcB.selectOption({ index: 1 }).catch(() => {});
        await page.waitForTimeout(800);
        await page.locator('select[aria-labelledby="srcLocLabel"]').selectOption({ index: 1 }).catch(() => {});
        await page.locator('select[aria-labelledby="destBranchLabel"]').selectOption({ index: branchOpts > 2 ? 2 : 1 }).catch(() => {});
        await page.waitForTimeout(800);
        await page.locator('select[aria-labelledby="destLocLabel"]').selectOption({ index: 1 }).catch(() => {});
        await page.locator('#lineProd0').selectOption({ index: 1 }).catch(() => {});
        await L.setField(page, '#lineQty0', '5').catch(() => {});
        await page.waitForTimeout(300);
        if (branchOpts <= 2) { rec.problem('SLOW', wf, screen, 'only one branch is selectable for this manager — cannot pick a different destination for an inter-branch transfer', buf.snapshot()); }
        buf.clear();
        await L.clickButton(page, /create transfer/i);
        await page.waitForTimeout(1500);
        const s = buf.snapshot();
        if (s.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error creating the transfer', s);
        else if (worst(s)) rec.problem('SLOW', wf, screen, `transfer rejected (${worst(s).status}) — likely no stock at the source location to move`, s);
        else rec.ok('raise inter-branch transfer');
      } catch (e) { rec.problem('SLOW', wf, screen, `raising transfer: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // APPROVERS (GM / branch manager / CFO): clear the approval inbox — daily oversight
  if (['GROUP_GM', 'BRANCH_MANAGER', 'FINANCE_DIRECTOR'].includes(persona.role)) {
    const wf = 'decide on pending approvals', screen = '/admin/approvals/inbox';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open the Approval Inbox', buf.snapshot()); }
    else {
      try {
        await page.waitForTimeout(900);
        const decide = page.locator('[aria-label^="Decide on request"], button:has-text("Decide")');
        const n = await decide.count().catch(() => 0);
        if (!n) { rec.ok('approval inbox open (nothing pending to decide)'); }
        else {
          await decide.first().click();
          await page.locator('#lineProductSearch, button:has-text("Approve")').first().waitFor({ state: 'visible', timeout: 4000 }).catch(() => {});
          buf.clear();
          await L.clickButton(page, /^\s*approve\b/i);
          await page.waitForTimeout(1200);
          const s = buf.snapshot();
          if (worst(s)) rec.problem('SLOW', wf, screen, `could not approve the request (${worst(s).status})`, s);
          else rec.ok('approve a pending request');
        }
      } catch (e) { rec.problem('SLOW', wf, screen, `deciding approvals: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // HR/PAYROLL: register a new employee — the daily HR intake
  if (persona.role === 'HR_PAYROLL_OFFICER') {
    const wf = 'register a new employee', screen = '/admin/hr/employees';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Employees', buf.snapshot()); }
    else {
      try {
        await L.pickFirstReal(page, '#companyPicker').catch(() => {});
        await page.waitForTimeout(400);
        await L.clickButton(page, /new employee/i);
        await page.locator('#fFirstName').waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
        buf.clear();
        await L.setField(page, '#fFirstName', 'Asha').catch(() => {});
        await L.setField(page, '#fLastName', 'Mtema').catch(() => {});
        await L.setField(page, '#fHireDate', today).catch(() => {});
        await L.setField(page, '#fJobTitle', 'Sales Clerk').catch(() => {});
        await L.setField(page, '#fNationalId', 'NIDA-' + Date.now()).catch(() => {});
        await L.pickFirstReal(page, '#fGender').catch(() => {});
        await L.pickFirstReal(page, '#fDepartmentId').catch(() => {});
        await L.pickFirstReal(page, '#fBranchId').catch(() => {});
        const deptOpts = await page.locator('#fDepartmentId option').count().catch(() => 0);
        await page.waitForTimeout(300);
        buf.clear();
        await L.clickButton(page, /create employee/i);
        await page.waitForTimeout(1500);
        const s = buf.snapshot();
        if (s.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error creating the employee', s);
        else if (worst(s)) rec.problem('SLOW', wf, screen, `employee rejected (${worst(s).status})${deptOpts <= 1 ? ' — no department options to assign' : ''}`, s);
        else rec.ok('register employee');
      } catch (e) { rec.problem('SLOW', wf, screen, `registering employee: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // PRODUCTION: release a work order — the core daily factory operation
  if (persona.role === 'PRODUCTION_OFFICER') {
    const wf = 'release a work order', screen = '/admin/work-orders';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Work Orders', buf.snapshot()); }
    else {
      try {
        await L.pickFirstReal(page, '#companyPicker').catch(() => {}); // list/form prereq: a company in context
        await page.waitForTimeout(400);
        await L.clickButton(page, /new work order/i);
        await page.locator('#newPlannedQty').waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
        buf.clear();
        const selects = page.locator('#createWorkOrderForm select');
        const ns = await selects.count();
        if (ns >= 1) await selects.nth(0).selectOption({ index: 1 }).catch(() => {}); // finished product to build
        await L.setField(page, '#newPlannedQty', '100').catch(() => {});
        if (ns >= 2) await selects.nth(1).selectOption({ index: 1 }).catch(() => {}); // branch
        await L.setField(page, '#newPlannedDate', today).catch(() => {});
        const prodVal = ns >= 1 ? (await selects.nth(0).inputValue().catch(() => '')) : '';
        if (!prodVal) rec.problem('SLOW', wf, screen, 'no manufactured product available to build (product picker empty)', buf.snapshot());
        else {
          buf.clear();
          await L.clickButton(page, /create work order/i);
          await page.waitForTimeout(1600);
          const s = buf.snapshot();
          if (s.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error creating the work order', s);
          else if (worst(s)) rec.problem('SLOW', wf, screen, `work order rejected (${worst(s).status})`, s);
          else rec.ok('release work order');
        }
      } catch (e) { rec.problem('SLOW', wf, screen, `releasing work order: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // STOREKEEPER: receive goods against a PO (GRN) — the core daily warehouse operation
  if (persona.role === 'STOREKEEPER') {
    const wf = 'receive goods against a PO', screen = '/admin/goods-receipts/create';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Goods Receipt', buf.snapshot()); }
    else {
      try {
        buf.clear();
        const po = await L.searchPick(page, '#poSearch', 'Mbasha');
        await page.waitForTimeout(900);
        const qtyInputs = page.locator('input[id^="qty-"]');
        const n = await qtyInputs.count();
        if (!po) rec.problem('SLOW', wf, screen, 'could not find a PO to receive (none ordered yet?)', buf.snapshot());
        else if (!n) rec.problem('SLOW', wf, screen, 'PO selected but no receivable lines appeared', buf.snapshot());
        else {
          for (let i = 0; i < n; i++) {
            const qid = (await qtyInputs.nth(i).getAttribute('id')) || '';
            const uid = qid.replace('qty-', '');
            await page.locator('#inc-' + uid).check().catch(() => {});
            await qtyInputs.nth(i).fill('10').catch(() => {});
          }
          await L.setField(page, '#grNotes', 'Daily receipt').catch(() => {});
          buf.clear();
          await L.clickButton(page, /record receipt/i);
          await page.waitForTimeout(1600);
          const s = buf.snapshot();
          if (s.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error recording the goods receipt', s);
          else if (worst(s)) rec.problem('SLOW', wf, screen, `goods receipt rejected (${worst(s).status})`, s);
          else rec.ok('receive goods (GRN)');
        }
      } catch (e) { rec.problem('SLOW', wf, screen, `receiving goods: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }

  // CASHIER: record an on-account customer receipt for Joseph Ulimboka
  if (persona.role === 'CASHIER') {
    const wf = 'record a customer receipt', screen = '/admin/ar/receipts/record';
    await L.goto(page, screen);
    if (await L.looksForbidden(page, buf)) { rec.problem('BLOCKED', wf, screen, 'not allowed to open Record Receipt', buf.snapshot()); }
    else {
      try {
        buf.clear();
        const cust = await L.searchPick(page, '#customerSearch', 'Joseph');
        if (!cust) rec.problem('SLOW', wf, screen, 'could not pick customer Joseph Ulimboka', buf.snapshot());
        else {
          await L.setField(page, '#receiptAmount', '50000').catch(() => {});
          await L.setField(page, '#receiptDate', today).catch(() => {});
          await page.locator('#tenderType').selectOption('CASH').catch(() => {});
          await L.clickButton(page, /record receipt/i);
          await page.waitForTimeout(1200);
          const s = buf.snapshot();
          if (s.api.some(a => a.status >= 500)) rec.problem('BLOCKED', wf, screen, 'server error recording the receipt', s);
          else if (worst(s)) rec.problem('SLOW', wf, screen, `receipt rejected (${worst(s).status})`, s);
          else rec.ok('record receipt');
        }
      } catch (e) { rec.problem('SLOW', wf, screen, `recording receipt: ${String(e.message || e).slice(0, 90)}`, buf.snapshot()); }
    }
  }
}

(async () => {
  const browser = await L.launch();
  const { page, buf } = await L.newSession(browser);
  const rec = L.makeRecorder(persona);
  const result = { persona: persona.fullName, username: persona.username, role: persona.role, access: [], axe: [], loggedIn: false };

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

    // ACCESS missions: can I open my own screens? (SWEEP=1 → every operational screen, for the matrix)
    const targets = process.env.SWEEP ? SWEEP_TARGETS : (ACCESS[persona.role] || []);
    for (const [path, label] of targets) {
      buf.clear();
      await L.goto(page, path);
      if (process.env.DEVICE) await L.shot(page, `${persona.slug}-${process.env.DEVICE}-${label}`); // responsive evidence
      const url = page.url().replace(L.BASE, '');
      let outcome = 'OK';
      if (url.includes('/login')) { outcome = 'KICKED_TO_LOGIN'; rec.problem('BLOCKED', `open ${label}`, path, 'I was thrown back to the login screen trying to open my own screen', buf.snapshot()); }
      else if (await L.looksForbidden(page, buf)) { outcome = 'FORBIDDEN'; rec.problem('BLOCKED', `open ${label}`, path, `I'm told I don't have permission to open ${label}, but it's part of my job (${persona.role})`, buf.snapshot()); }
      else if (/\/admin\/home$|\/admin$/.test(url) && !/\/home$|^\/admin$/.test(path)) {
        // the route guard (permission.guard) silently redirects a permission gap to home, not /forbidden —
        // looks like a success unless you notice you landed on home instead of the screen you asked for.
        outcome = 'REDIRECTED_HOME';
        rec.problem('BLOCKED', `open ${label}`, path, `silently bounced to the home screen — I could not reach ${label} (a permission gap the guard hides as a home-redirect, with no message telling me why)`, buf.snapshot());
      }
      else {
        const snap = buf.snapshot();
        const realCon = snap.console.filter(e => !/favicon|ResizeObserver|net::ERR_/.test(e));
        if (snap.api.some(a => a.status >= 500)) { outcome = 'SERVER_ERROR'; rec.problem('BLOCKED', `open ${label}`, path, `${label} failed to load (server error)`, snap); }
        else if (snap.page.length || realCon.length) { outcome = 'JS_ERROR'; rec.problem('SLOW', `open ${label}`, path, `${label} opened but the screen reported an error`, { ...snap, console: realCon }); }
      }
      result.access.push({ path, label, outcome });
      console.log(`  ${outcome.padEnd(16)} ${path}`);
      rec.visited(label, outcome === 'OK'); // per-component usage log
      if (outcome === 'OK') {
        // a user should NEVER see a raw id/uid on screen — uid belongs only in the URL. Report any leak.
        const ids = await L.scanVisibleIds(page);
        if (ids.hits && ids.hits.length) {
          rec.problem('HYGIENE', 'id/uid visible in the UI', path,
            `I can see ${ids.hits.length} raw id/uid(s) on ${label} — users should never see these (a uid belongs only in the URL). e.g. "${ids.snippet}"`,
            { hits: ids.hits.slice(0, 6), snippet: ids.snippet });
        }
      }
      if (process.env.AXE && outcome === 'OK') {
        const ax = await L.runAxe(page);
        result.axe.push({ screen: label, path, device: process.env.DEVICE || 'desktop', ran: ax.ran, violations: ax.violations });
        if (ax.violations.length) {
          console.log(`    axe[${process.env.DEVICE || 'desktop'}]: ${ax.violations.length} serious/critical -> ${ax.violations.map(v => `${v.id}(${v.n})`).join(', ')}`);
          for (const v of ax.violations) rec.problem('SLOW', `a11y on ${label}`, path, `${v.impact} a11y: ${v.id} — ${v.help} (${v.n} node(s))`, { axe: v });
        }
      }
    }

    // ACTION missions: enter the master data I own (skipped in SWEEP — sweep is read-only access matrix)
    if (!process.env.SWEEP) {
      await runActions(page, buf, rec);
      // DEEP missions: actually do my transactional work (opt-in)
      if (process.env.DEEP) await runDeep(page, buf, rec);
    }

  } catch (e) {
    rec.problem('BLOCKED', 'operate', 'fatal', String(e && e.message || e).slice(0, 140), buf.snapshot());
    await L.shot(page, `${persona.slug}-FATAL`);
  } finally {
    result.created = rec.created;
    result.usage = rec.usage; // per-component usage log for this user
    result.problemCount = rec.problems.length;
    console.log(`\n=== ${persona.fullName}: created=${JSON.stringify(rec.created)} problems=${rec.problems.length} ===`);
    L.saveResults(`operate-${persona.slug}.json`, { ...result, problems: rec.problems });
    await browser.close();
    process.exit(0);
  }
})();
