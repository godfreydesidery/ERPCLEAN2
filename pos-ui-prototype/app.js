/* =====================================================================
   OrbixPOS — prototype behaviour. ALL MOCK. No network, no API.
   Three business modes share one shell: supermarket · restaurant · pharmacy.
   The register body is re-rendered per mode; payment/receipt/session are shared.
====================================================================== */
'use strict';

const CCY = 'TZS';
const VAT = 0.18;

/* ----------------------------- mock data ---------------------------- */
// Supermarket catalogue
const PRODUCTS = [
  ['Coca-Cola 500ml','Beverages',1500,'🥤','#fee2e2','high'],
  ['Water 1L','Beverages',1000,'💧','#dbeafe','high'],
  ['Fanta Orange 500ml','Beverages',1500,'🍊','#ffedd5','high'],
  ['Azam Juice 1L','Beverages',2800,'🧃','#fef3c7','low'],
  ['Coffee 250g','Beverages',9500,'☕','#f5f5f4','high'],
  ['Crisps 90g','Snacks',2000,'🥔','#fef9c3','high'],
  ['Biscuits Pack','Snacks',1800,'🍪','#fde68a','high'],
  ['Chocolate Bar','Snacks',3500,'🍫','#e7d3c1','low'],
  ['Rice 2kg','Grocery',6800,'🍚','#f8fafc','high'],
  ['Sugar 1kg','Grocery',3200,'🧁','#f1f5f9','high'],
  ['Cooking Oil 1L','Grocery',7200,'🛢️','#fef08a','low'],
  ['Maize Flour 2kg','Grocery',3900,'🌽','#fef3c7','high'],
  ['Beans 1kg','Grocery',4100,'🫘','#fee2e2','high'],
  ['White Bread','Bakery',2500,'🍞','#fde68a','high'],
  ['Soap Bar','Personal Care',1700,'🧼','#e0f2fe','high'],
  ['Toothpaste','Personal Care',3800,'🦷','#dbeafe','high'],
  ['Tissue 10pk','Household',5500,'🧻','#f1f5f9','high'],
  ['Detergent 1kg','Household',6900,'🧴','#dbeafe','low'],
].map((p, i) => ({ id: 100 + i, kind: 'product', name: p[0], cat: p[1], price: p[2], emoji: p[3], color: p[4], stock: p[5],
                   code: String(1001 + i), barcode: String(8900000000 + i) }));   // code = internal SKU, barcode = scanned EAN

// Restaurant menu
const MENU = [
  ['Vegetable Samosa','Starters',3000,'🥟','#fef3c7'],
  ['Spring Rolls','Starters',4000,'🥢','#dcfce7'],
  ['Soup of the Day','Starters',4500,'🍲','#ffedd5'],
  ['Green Salad','Starters',5000,'🥗','#dcfce7'],
  ['Nyama Choma 500g','Mains',18000,'🍖','#fee2e2'],
  ['Pilau & Beef','Mains',12000,'🍛','#fef9c3'],
  ['Chicken Curry','Mains',14000,'🍗','#fed7aa'],
  ['Fish & Chips','Mains',15000,'🐟','#dbeafe'],
  ['Ugali & Sukuma','Mains',8000,'🍚','#f1f5f9'],
  ['Veg Biryani','Mains',11000,'🍚','#fef3c7'],
  ['Soda 300ml','Drinks',1500,'🥤','#fee2e2'],
  ['Fresh Juice','Drinks',5000,'🧃','#fef08a'],
  ['Coffee','Drinks',3500,'☕','#e7d3c1'],
  ['Tusker Lager','Drinks',6000,'🍺','#fde68a'],
  ['Water 500ml','Drinks',1000,'💧','#dbeafe'],
  ['Ice Cream','Desserts',4500,'🍨','#fce7f3'],
  ['Chocolate Cake','Desserts',6000,'🍰','#f3e8ff'],
  ['Fruit Salad','Desserts',5000,'🍓','#fee2e2'],
].map((m, i) => ({ id: 200 + i, kind: 'dish', name: m[0], course: m[1], price: m[2], emoji: m[3], color: m[4] }));
const COURSES = ['Starters', 'Mains', 'Drinks', 'Desserts'];
const TABLES = [
  ['T1',2,'free'],['T2',4,'occupied'],['T3',4,'bill'],['T4',2,'free'],['T5',6,'occupied'],
  ['T6',4,'free'],['T7',2,'occupied'],['T8',8,'free'],['Bar 1',1,'free'],['Bar 2',1,'occupied'],
].map((t) => ({ name: t[0], seats: t[1], st: t[2] }));

// Pharmacy catalogue (batch / expiry / Rx)
const DRUGS = [
  ['Amoxicillin 500mg','Capsule · 100s',4500,'AMX-2231','2026-08','💊',true],
  ['Paracetamol 500mg','Tablet · 100s',1200,'PCM-9087','2027-01','💊',false],
  ['Ibuprofen 400mg','Tablet · 50s',2200,'IBU-3320','2026-11','💊',false],
  ['Metformin 850mg','Tablet · 60s',5600,'MET-1190','2026-05','💊',true],
  ['Amlodipine 5mg','Tablet · 30s',3800,'AML-7741','2027-03','💊',true],
  ['ORS Sachet','Oral salts',800,'ORS-0521','2028-02','🧂',false],
  ['Cough Syrup 100ml','Syrup',3500,'CGH-4412','2026-09','🧴',false],
  ['Vitamin C 1000mg','Tablet · 30s',2800,'VTC-6610','2027-06','💊',false],
  ['Bandage Roll','Dressing',1500,'BND-2203','2029-01','🩹',false],
  ['Antiseptic 250ml','Solution',4200,'ANT-8830','2027-10','🧴',false],
  ['Hand Sanitizer','Gel 500ml',6000,'SAN-5512','2027-04','🧴',false],
  ['Insulin Pen','Refrigerated',28000,'INS-9921','2025-12','💉',true],
].map((d, i) => ({ id: 300 + i, kind: 'drug', name: d[0], form: d[1], price: d[2], batch: d[3], expiry: d[4], emoji: d[5], rx: d[6] }));

const CUSTOMERS = [
  { id: 1, name: 'Walk-in customer', sub: 'Cash · default', walkin: true },
  { id: 2, name: 'Asha Mwangi', sub: '+255 712 004 119' },
  { id: 3, name: 'Kilimani Traders Ltd', sub: 'TIN 109-882-447', badge: 'Credit' },
  { id: 4, name: 'John Otieno', sub: '+255 754 220 901' },
];

const TILLS = [
  { name: 'Till 1', meta: 'Counter · front', active: true },
  { name: 'Till 2', meta: 'Counter · express', active: true },
  { name: 'Kiosk A', meta: 'Self-serve', active: true },
];

const MODE_LABEL = { supermarket: '🛒 Supermarket', restaurant: '🍽 Restaurant', pharmacy: '💊 Pharmacy' };

/* ------------------------------- state ------------------------------ */
const state = {
  mode: 'supermarket',
  cart: [],                 // { item, qty, discount }
  customer: CUSTOMERS[0],
  table: { name: 'Table 5', covers: 4 },
  course: 'Starters',
  matches: [],              // current line-search matches
  tenders: [], tenderType: 'CASH', payEntry: '',
  invoiceSeq: 100237, lastInvoice: null, _discIdx: null,
  selRow: null, npMode: 'qty', pendingQty: 1, npEntry: '', npFresh: true,   // supermarket number-pad
};

/* ------------------------------ helpers ----------------------------- */
const $  = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const money0 = (n) => Math.round(n).toLocaleString('en-US');
const money  = (n) => `${CCY} ${money0(n)}`;
const catalog = () => (state.mode === 'restaurant' ? MENU : state.mode === 'pharmacy' ? DRUGS : PRODUCTS);
const itemSub = (it) => it.kind === 'drug' ? it.form : it.kind === 'product' ? it.cat : it.course;

function totals() {
  let subtotal = 0, discount = 0, vat = 0;
  for (const l of state.cart) {
    const c = lineCalc(l);   // honours discount clamp + voided lines (which contribute 0)
    subtotal += c.base; discount += c.disc; vat += c.vat;
  }
  return { subtotal, discount, vat, gross: subtotal - discount + vat };
}
function toast(msg, ok = false) {
  const t = $('#toast');
  t.textContent = msg; t.className = 'toast' + (ok ? ' toast--ok' : ''); t.hidden = false;
  clearTimeout(toast._t); toast._t = setTimeout(() => (t.hidden = true), 2200);
}
function show(screen) { $$('.screen').forEach((s) => (s.hidden = s.dataset.screen !== screen)); }
function openModal(n) { const m = $(`[data-modal="${n}"]`); if (m) m.hidden = false; }
function closeModal(el) { (el.closest ? el.closest('.modal') : el).hidden = true; }
function closeAllModals() { $$('.modal').forEach((m) => (m.hidden = true)); }

/* ---------------------------- cart actions -------------------------- */
function addItem(id) {
  const item = catalog().find((x) => x.id === id); if (!item) return;
  const q = state.mode === 'supermarket' ? Math.max(1, state.pendingQty || 1) : 1;   // qty multiplier
  const line = state.cart.find((l) => l.item.id === id);
  if (line) line.qty += q; else state.cart.push({ item, qty: q, discount: 0 });
  state.selRow = state.cart.findIndex((l) => l.item.id === id);
  state.pendingQty = 1; updateQtyBadge();
  renderLines();
  if (item.rx) toast('℞ ' + item.name + ' — prescription required');
}
function changeQty(i, d) { const l = state.cart[i]; if (!l) return; l.qty = Math.max(1, l.qty + d); renderLines(); }
function removeLine(i) { state.cart.splice(i, 1); renderLines(); }
function voidLine(i) { const l = state.cart[i]; if (!l) return; l.void = !l.void; renderLines(); toast(l.void ? 'Line voided' : 'Void undone'); }

/* ============================ RENDER: register ===================== */
function renderRegister() {
  $$('#modeSwitch button').forEach((b) => b.classList.toggle('is-active', b.dataset.mode === state.mode));
  const body = $('#registerBody');
  body.innerHTML = state.mode === 'restaurant' ? tplRestaurant()
    : state.mode === 'pharmacy' ? tplPharmacy()
    : tplSupermarket();
  renderLines();
}

function sideRight(label, value, sub) {
  return `
   <div class="reg-side__head">
     <button class="cust-chip" id="contextBtn">
       <span class="cust-chip__ic">☺</span>
       <span class="cust-chip__txt"><small>${label}</small><b id="ctxValue">${value}</b>
         ${sub ? `<span class="cust-chip__sub">${sub}</span>` : ''}</span>
       <span class="cust-chip__chev">⌄</span>
     </button>
     <button class="iconbtn" id="clearCartBtn" title="Void">🗑</button>
   </div>
   <div class="reg-side__body" id="sideMirror"></div>
   <div class="side-totals">
     <div class="trow"><span>Subtotal (net)</span><span id="sumNet">0</span></div>
     <div class="trow"><span>VAT</span><span id="sumVat">0</span></div>
     <div class="trow"><span>Discount</span><span id="sumDisc">0</span></div>
     <div class="trow trow--grand"><span>Total <small>${CCY}</small></span><span id="sumGross">0</span></div>
     <p class="preview-note">Preview — price, VAT &amp; totals are computed by the ERP.</p>
   </div>
   <div class="side-actions">
     <button class="btn btn--ghost" id="parkBtn">⏸ Park</button>
     <button class="btn btn--ghost" id="discountBtn">% Discount</button>
     <button class="btn btn--pay" id="payBtn" disabled><span>Pay</span><b id="payAmount">${CCY} 0</b></button>
   </div>`;
}

// Supermarket: 75% Excel-style sheet  +  25% number pad.
// Items are added by scan / search only (a real supermarket has thousands of SKUs).
function tplSupermarket() {
  const keys = ['7', '8', '9', '4', '5', '6', '1', '2', '3', '.', '0', 'back'];
  return `
    <div class="reg-pos">
      <div class="pos-left">
        <div class="searchwrap">
          <div class="searchbar">
            <span class="searchbar__ic">⌕</span>
            <input id="lineSearch" placeholder="Scan barcode or type item name / code, then Enter…" autocomplete="off" />
            <span class="qty-badge" id="qtyBadge" hidden>×1</span>
            <button class="searchbar__scan" title="Scan">▣</button>
          </div>
          <ul class="search-results" id="searchResults" hidden></ul>
        </div>
        <div class="xl-wrap">
          <table class="xl">
            <thead><tr>
              <th class="xl-rownum" scope="col">#</th>
              <th class="xl-code" scope="col">Code</th>
              <th class="xl-bc" scope="col">Barcode</th>
              <th scope="col">Item</th>
              <th class="xl-unit" scope="col">Unit</th>
              <th class="r" scope="col">Qty</th>
              <th class="r" scope="col">Unit price</th>
              <th class="r" scope="col">Disc</th>
              <th class="r" scope="col">VAT</th>
              <th class="r" scope="col">Line total</th>
              <th class="xl-act" scope="col" title="Remove line">X</th>
              <th class="xl-act" scope="col" title="Void line">V</th>
            </tr></thead>
            <tbody id="lineBody"></tbody>
          </table>
        </div>
      </div>
      <aside class="numpad-panel">
        <div class="np-total">
          <small>TOTAL ${CCY}</small>
          <div class="np-total-amt" id="sumGross">0</div>
          <div class="np-subs"><span>Net <b id="sumNet">0</b></span><span>VAT <b id="sumVat">0</b></span><span>Disc <b id="sumDisc">0</b></span></div>
        </div>
        <button class="cust-chip" id="contextBtn">
          <span class="cust-chip__ic">☺</span>
          <span class="cust-chip__txt"><small>Customer</small><b id="ctxValue">${state.customer.name}</b></span>
          <span class="cust-chip__chev">⌄</span>
        </button>
        <div class="np-target np-modes" id="npModes">
          <button class="np-tgt is-active" data-npmode="qty">× Qty</button>
          <button class="np-tgt" data-npmode="plu">PLU</button>
          <button class="np-tgt" data-npmode="tender">Tender</button>
        </div>
        <div class="np-display" id="npDisplay">× 1</div>
        <div class="np-change" id="npChange" hidden></div>
        <div class="np-keys">
          ${keys.map((k) => `<button data-np="${k}" aria-label="${k === 'back' ? 'backspace' : k}">${k === 'back' ? '⌫' : k}</button>`).join('')}
        </div>
        <button class="np-go" id="npGo">Set qty for next item</button>
        <div class="np-actions np-actions--3">
          <button class="btn btn--ghost" id="parkBtn">⏸ Park</button>
          <button class="btn btn--ghost" id="functionsBtn">⋯ Functions</button>
          <button class="btn btn--ghost btn--danger" id="clearCartBtn">🗑 Void</button>
        </div>
        <button class="btn btn--pay np-pay" id="payBtn" disabled><span>Pay</span><b id="payAmount">${CCY} 0</b></button>
      </aside>
    </div>`;
}

// Pharmacy: scan/search + dispensing line table (batch · expiry) + Rx header.
function tplPharmacy() {
  const quick = DRUGS.slice(0, 6).map((x) =>
    `<button class="qchip" data-add="${x.id}">${x.emoji} ${x.name.split(' ')[0]} <small>${money0(x.price)}</small></button>`).join('');
  return `
    <div class="reg-split">
      <div class="reg-main">
        <div class="rx-bar">
          <label class="field"><span>Patient</span><input value="Walk-in patient" /></label>
          <label class="field"><span>Prescriber</span><input placeholder="Dr. …" /></label>
          <label class="field"><span>Rx number</span><input placeholder="RX-…" /></label>
          <span class="rx-flag">℞ Rx items present</span>
        </div>
        <div class="searchwrap">
          <div class="searchbar">
            <span class="searchbar__ic">⌕</span>
            <input id="lineSearch" placeholder="Search drug name or batch…" autocomplete="off" />
            <button class="searchbar__scan" title="Scan">▣</button>
          </div>
          <ul class="search-results" id="searchResults" hidden></ul>
        </div>
        <div class="quickrow">${quick}</div>
        <div class="linetable-wrap">
          <table class="linetable">
            <thead><tr>
              <th class="lt-idx">#</th><th>Item</th><th>Batch · Exp</th><th>Qty</th><th>Price</th><th>Amount</th><th></th>
            </tr></thead>
            <tbody id="lineBody"></tbody>
          </table>
        </div>
      </div>
      <aside class="reg-side">${sideRight('Patient', 'Walk-in patient', 'Cash · OTC')}</aside>
    </div>`;
}

// Restaurant: menu tiles + order ticket
function tplRestaurant() {
  return `
    <div class="reg-menu">
      <div class="menu-main">
        <div class="table-bar">
          <button class="table-chip" id="tableBtn">🍽 <b>${state.table.name}</b> · ${state.table.covers} covers ⌄</button>
          <div class="cats" id="courseTabs">
            ${COURSES.map((c) => `<button class="cat ${c === state.course ? 'is-active' : ''}" data-course="${c}">${c}</button>`).join('')}
          </div>
        </div>
        <div class="menu-grid" id="menuGrid"></div>
      </div>
      <aside class="ticket">
        <div class="ticket__head" id="contextBtn">
          <div><b id="ctxValue">${state.table.name}</b><br><small>${state.table.covers} covers · Dine-in</small></div>
          <button class="iconbtn" id="clearCartBtn" title="Void">🗑</button>
        </div>
        <div class="ticket__course">Current order</div>
        <div class="ticket__lines" id="ticketLines"></div>
        <button class="kitchen-btn" id="kitchenBtn">🔥 Send to kitchen</button>
        <div class="side-totals">
          <div class="trow"><span>Subtotal (net)</span><span id="sumNet">0</span></div>
          <div class="trow"><span>VAT</span><span id="sumVat">0</span></div>
          <div class="trow"><span>Service / Discount</span><span id="sumDisc">0</span></div>
          <div class="trow trow--grand"><span>Total <small>${CCY}</small></span><span id="sumGross">0</span></div>
        </div>
        <div class="side-actions">
          <button class="btn btn--ghost" id="parkBtn">⑂ Split bill</button>
          <button class="btn btn--ghost" id="discountBtn">% Discount</button>
          <button class="btn btn--pay" id="payBtn" disabled><span>Pay</span><b id="payAmount">${CCY} 0</b></button>
        </div>
      </aside>
    </div>`;
}

function renderMenu() {
  const grid = $('#menuGrid'); if (!grid) return;
  grid.innerHTML = MENU.filter((m) => m.course === state.course).map((m) => `
    <button class="ptile" data-add="${m.id}">
      <span class="ptile__emoji" style="background:${m.color}">${m.emoji}</span>
      <span class="ptile__name">${m.name}</span>
      <span class="ptile__price">${money0(m.price)} <small>${CCY}</small></span>
    </button>`).join('');
}

/* ----------------------------- line render -------------------------- */
function renderLines() {
  if (state.mode === 'restaurant') { renderMenu(); renderTicket(); }
  else if (state.mode === 'pharmacy') renderPharmacyRows();
  else renderSheet();
  updateTotals();
}

const unitOf = (it) => /\b\d*\s*kg\b/i.test(it.name) ? 'KG' : 'EA';

// line economics: discount floored at the line base; line total INCLUDES VAT
// (so the per-line totals add up to the grand total shown in the numpad).
function lineCalc(l) {
  if (l.void) return { base: 0, disc: 0, net: 0, vat: 0, gross: 0 };   // voided lines are not charged
  const base = l.item.price * l.qty;
  const disc = Math.min(l.discount, base);
  const net = base - disc;
  const vat = net * VAT;
  return { base, disc, net, vat, gross: net + vat };
}

// Supermarket grid: clean cells with Excel-style gridlines; one datum per column.
function renderSheet() {
  const body = $('#lineBody'); if (!body) return;
  if (state.selRow == null || state.selRow >= state.cart.length) state.selRow = state.cart.length - 1;
  body.innerHTML = state.cart.map((l, i) => {
    const c = lineCalc(l);
    const cls = (i === state.selRow ? 'is-sel' : '') + (l.void ? ' is-voided' : '');
    return `<tr data-row="${i}" class="${cls}">
      <td class="xl-rownum">${i + 1}</td>
      <td class="xl-code">${l.item.code}</td>
      <td class="xl-bc">${l.item.barcode || ''}</td>
      <td class="xl-text">${l.item.name}</td>
      <td class="xl-unit">${unitOf(l.item)}</td>
      <td class="r xl-qtyc"><input class="xl-input" data-qty="${i}" value="${l.qty}" inputmode="decimal" aria-label="Quantity row ${i + 1}" ${l.void ? 'disabled' : ''} /></td>
      <td class="r">${money0(l.item.price)}</td>
      <td class="r xl-discc"><input class="xl-input" data-disc="${i}" value="${l.discount}" inputmode="decimal" aria-label="Discount row ${i + 1}" ${l.void ? 'disabled' : ''} /></td>
      <td class="r xl-vat">${money0(c.vat)}</td>
      <td class="r xl-total">${money0(c.gross)}</td>
      <td class="xl-act"><button class="xl-del" data-del="${i}" title="Remove line" aria-label="Remove row ${i + 1}">✕</button></td>
      <td class="xl-act"><input type="checkbox" class="xl-voidchk" data-void="${i}" ${l.void ? 'checked' : ''} title="${l.void ? 'Un-void line' : 'Void line'}" aria-label="Void row ${i + 1}" /></td>
    </tr>`;
  }).join('');
}

// Live-update one row's VAT + total cells without re-rendering (keeps cell focus).
function updateRow(i) {
  const l = state.cart[i]; const tr = $(`#lineBody tr[data-row="${i}"]`); if (!l || !tr) return;
  const c = lineCalc(l);
  tr.querySelector('.xl-vat').textContent = money0(c.vat);
  tr.querySelector('.xl-total').textContent = money0(c.gross);
}

/* ----------------------- supermarket number pad --------------------- */
// Highlight the row a cashier is looking at (cell edits happen inline in the grid).
function selectRow(i) {
  state.selRow = i;
  $$('#lineBody tr[data-row]').forEach((tr) => tr.classList.toggle('is-sel', +tr.dataset.row === i));
}
// The keypad has three supermarket jobs: × Qty multiplier, PLU/code add, and Tender (cash → change).
function setNpMode(m) {
  state.npMode = m; state.npEntry = '';
  $$('#npModes .np-tgt').forEach((x) => x.classList.toggle('is-active', x.dataset.npmode === m));
  refreshNp();
}
function handleNp(k) {
  if (k === 'back') state.npEntry = state.npEntry.slice(0, -1);
  else if (k === '.') { if (!state.npEntry.includes('.')) state.npEntry += state.npEntry ? '.' : '0.'; }
  else state.npEntry += k;
  refreshNp();
}
function refreshNp() {
  const disp = $('#npDisplay'); if (!disp) return;
  const v = state.npEntry;
  disp.textContent = state.npMode === 'qty' ? '× ' + (v || '1') : (v || '0');
  const ch = $('#npChange');
  if (ch) {
    if (state.npMode === 'tender') {
      const cash = Number.parseFloat(v) || 0, due = totals().gross;
      ch.hidden = false;
      ch.innerHTML = `Change <b>${money(Math.max(0, cash - due))}</b>`;
      ch.classList.toggle('ok', cash >= due && due > 0);
    } else { ch.hidden = true; }
  }
  const go = $('#npGo');
  if (go) go.textContent = state.npMode === 'qty' ? 'Set qty for next item'
    : state.npMode === 'plu' ? 'Add item by code' : 'Take cash → finish';
}
// Execute the active keypad mode.
function npGo() {
  const v = state.npEntry.trim();
  if (state.npMode === 'qty') {
    const q = Math.max(1, Number.parseFloat(v) || 1);
    state.pendingQty = q; updateQtyBadge();
    if (state.cart[state.selRow]) { state.cart[state.selRow].qty = q; renderLines(); }
    state.npEntry = ''; refreshNp();
    toast(`Next scanned item × ${q}`);
  } else if (state.npMode === 'plu') {
    if (!v) return;
    const prod = PRODUCTS.find((p) => p.code === v || p.barcode === v || String(p.id) === v);
    if (!prod) { toast(`No item for code ${v}`); return; }
    addItem(prod.id); state.npEntry = ''; refreshNp();
  } else {   // tender
    if (!state.cart.length) { toast('No items to pay for'); return; }
    const cash = Number.parseFloat(v) || 0, due = totals().gross;
    if (cash < due) { toast('Insufficient cash tendered'); return; }
    state.tenders = [{ type: 'CASH', amount: cash }];
    completeSale();
  }
}
function updateQtyBadge() {
  const badge = $('#qtyBadge'); if (!badge) return;
  badge.hidden = state.pendingQty <= 1;
  badge.textContent = '× ' + state.pendingQty;
}

// Pharmacy dispensing rows + running-bill mirror.
function renderPharmacyRows() {
  const body = $('#lineBody'); if (!body) return;
  if (!state.cart.length) {
    body.innerHTML = `<tr class="lt-empty"><td colspan="7">No items yet — scan or search to add a line.</td></tr>`;
  } else {
    body.innerHTML = state.cart.map((l, i) => {
      const amt = Math.max(0, l.item.price * l.qty - l.discount);
      const qty = `<div class="qtycell"><button class="qbtn" data-dec="${i}">−</button><b>${l.qty}</b><button class="qbtn" data-inc="${i}">+</button></div>`;
      return `<tr>
        <td class="lt-idx">${i + 1}</td>
        <td class="lt-name">${l.item.name}${l.item.rx ? ' <span class="rx-flag">℞</span>' : ''}<small>${l.item.form}</small></td>
        <td class="lt-batch"><b>${l.item.batch}</b><small>exp ${l.item.expiry}</small></td>
        <td>${qty}</td><td>${money0(l.item.price)}</td><td class="lt-amt">${money0(amt)}</td>
        <td><span class="lt-x" data-del="${i}" title="Remove">✕</span></td></tr>`;
    }).join('');
  }
  const mirror = $('#sideMirror');
  if (mirror) mirror.innerHTML = state.cart.length
    ? state.cart.map((l) => `<div class="cline"><div class="cline__name">${l.item.name}</div>
        <div class="cline__amt">${money0(Math.max(0, l.item.price * l.qty - l.discount))}</div>
        <div class="cline__meta">${l.qty} × ${money0(l.item.price)}</div></div>`).join('')
    : `<div class="cart__empty"><div class="cart__empty-ic">🧾</div><p>The running bill shows here</p></div>`;
}

function renderTicket() {
  const wrap = $('#ticketLines'); if (!wrap) return;
  if (!state.cart.length) {
    wrap.innerHTML = `<div class="cart__empty"><div class="cart__empty-ic">🍽</div><p>Tap menu items to build the order</p></div>`;
    return;
  }
  wrap.innerHTML = state.cart.map((l, i) => `
    <div class="tline">
      <div class="tline__top"><span class="tline__name">${l.item.name}</span>
        <span class="tline__amt">${money0(Math.max(0, l.item.price * l.qty - l.discount))}</span></div>
      <div class="tline__qty">
        <button class="qbtn" data-dec="${i}">−</button><b>${l.qty}</b><button class="qbtn" data-inc="${i}">+</button>
        <span class="lt-x" data-del="${i}">✕</span>
        <span class="tline__note" data-note="${i}">＋ note</span>
      </div>
    </div>`).join('');
}

function updateTotals() {
  const t = totals();
  if (!$('#sumNet')) return;
  $('#sumNet').textContent = money0(t.subtotal);
  $('#sumVat').textContent = money0(t.vat);
  $('#sumDisc').textContent = t.discount ? '−' + money0(t.discount) : '0';
  $('#sumGross').textContent = money0(t.gross);
  $('#payAmount').textContent = money(t.gross);
  $('#payBtn').disabled = state.cart.length === 0;
}

/* ---------------------------- line search --------------------------- */
function runSearch(q) {
  const res = $('#searchResults'); if (!res) return;
  q = q.trim().toLowerCase();
  if (!q) { res.hidden = true; state.matches = []; return; }
  state.matches = catalog().filter((x) =>
    x.name.toLowerCase().includes(q) || (x.code && x.code.includes(q)) ||
    (x.barcode && x.barcode.includes(q)) || (x.batch && x.batch.toLowerCase().includes(q))
  ).slice(0, 8);
  res.innerHTML = state.matches.map((x, i) => `
    <li data-sr="${x.id}" class="${i === 0 ? 'is-active' : ''}">
      <span class="sr-code">${x.barcode || x.code || x.batch || ''}</span>
      <span class="sr-name">${x.name}${x.rx ? ' ℞' : ''}</span>
      <span class="sr-price">${money0(x.price)}</span></li>`).join('')
    || `<li class="sr-none">No match for “${q}”.</li>`;
  res.hidden = false;
}
function clearSearch() { const r = $('#searchResults'); if (r) r.hidden = true; const i = $('#lineSearch'); if (i) i.value = ''; state.matches = []; }

/* ------------------------------ tills ------------------------------- */
function renderTills() {
  $('#tillGrid').innerHTML = TILLS.filter((t) => t.active).map((t, i) => `
    <button class="till" data-till="${i}"><span class="till__dot">● ACTIVE</span>
      <span class="till__name">${t.name}</span><span class="till__meta">${t.meta}</span></button>`).join('');
}

/* ------------------------------ floor ------------------------------- */
function renderFloor() {
  $('#floorGrid').innerHTML = TABLES.map((t) => `
    <button class="ftable ftable--${t.st}" data-table="${t.name}" data-seats="${t.seats}">
      <span class="fstatus"></span><b>${t.name}</b><small>${t.seats} seats</small>
      <small>${t.st === 'free' ? 'Free' : t.st === 'bill' ? 'Bill printed' : 'Occupied'}</small></button>`).join('');
}

/* ----------------------------- customers ---------------------------- */
function renderCustomers() {
  $('#custModalTitle').textContent = state.mode === 'pharmacy' ? 'Patient' : 'Customer';
  $('#customerList').innerHTML = CUSTOMERS.map((c) =>
    `<li data-cust="${c.id}" class="${c.id === state.customer.id ? 'is-active' : ''}">
       <span><b>${c.name}</b><br><small>${c.sub}</small></span>
       ${c.badge ? `<span class="badge">${c.badge}</span>` : c.walkin ? '<span class="badge">Default</span>' : ''}</li>`).join('');
}

function renderSales() {
  const rows = [
    ['INV-100237','14:21','Walk-in customer',8260],['INV-100236','14:08','Asha Mwangi',3540],
    ['INV-100235','13:55','Walk-in customer',12100],['INV-100234','13:40','John Otieno',5310],
    ['INV-100233','13:22','Walk-in customer',1770],
  ];
  $('#salesTable tbody').innerHTML = rows.map((r) =>
    `<tr><td><b>${r[0]}</b></td><td>${r[1]}</td><td>${r[2]}</td><td class="r">${money0(r[3])}</td>
       <td class="r"><button class="linkbtn" data-reprint="${r[0]}">Reprint</button></td></tr>`).join('');
}

/* ----------------------------- payment ------------------------------ */
function openPayment() {
  state.tenders = []; state.payEntry = ''; state.tenderType = 'CASH';
  const due = totals().gross;
  $('#payDue').textContent = money(due);
  const exact = Math.ceil(due / 1000) * 1000;
  $('#quickCash').innerHTML = [...new Set([due, exact, exact + 5000, 50000])]
    .map((v) => `<button data-cash="${v}">${v === due ? 'Exact' : money0(v)}</button>`).join('');
  $$('.ttype').forEach((b) => b.classList.toggle('is-active', b.dataset.tender === 'CASH'));
  $('#payInput').textContent = '0';
  renderTenders(); openModal('payment');
}
const tendered = () => state.tenders.reduce((s, t) => s + t.amount, 0);
function renderTenders() {
  const due = totals().gross, paid = tendered();
  $('#tenderList').innerHTML = state.tenders.map((t, i) =>
    `<div class="tender-pill"><span><small>${t.type.replace('_', ' ')}</small></span>
       <span>${money(t.amount)} <span class="tender-pill__x" data-rmtender="${i}">✕</span></span></div>`).join('');
  $('#payTendered').textContent = money(paid);
  const over = paid > due;
  $('#changeLabel').textContent = over ? 'Change' : 'Remaining';
  $('#payChange').textContent = money(Math.abs(due - paid));
  $('#completeSaleBtn').disabled = paid < due - 0.01;
}
function addTender() {
  const amt = parseFloat(state.payEntry || '0') || (totals().gross - tendered());
  if (amt <= 0) return;
  state.tenders.push({ type: state.tenderType, amount: amt });
  state.payEntry = ''; $('#payInput').textContent = '0'; renderTenders();
}

/* ----------------------------- receipt ------------------------------ */
function partyLabel() {
  return state.mode === 'restaurant' ? state.table.name + ' · Dine-in'
    : state.mode === 'pharmacy' ? 'Walk-in patient' : state.customer.name;
}
function completeSale() {
  const t = totals();
  const inv = {
    number: 'INV-' + (++state.invoiceSeq),
    when: new Date().toLocaleString('en-GB', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: 'short' }),
    party: partyLabel(), mode: state.mode,
    lines: state.cart.map((l) => ({ name: l.item.name, qty: l.qty, price: l.item.price,
      amt: Math.max(0, l.item.price * l.qty - l.discount) })),
    net: t.subtotal - t.discount, vat: t.vat, gross: t.gross, discount: t.discount,
    tenders: [...state.tenders], change: Math.max(0, tendered() - t.gross),
  };
  state.lastInvoice = inv;
  closeModal($('[data-modal="payment"]'));
  renderReceipt(inv); openModal('receipt');
  state.cart = []; state.selRow = null; state.npEntry = ''; state.npMode = 'qty'; state.pendingQty = 1;
  renderLines(); refreshNp(); updateQtyBadge();
  toast('Sale ' + inv.number + ' finalised', true);
}
function renderReceipt(inv, gift = false) {
  const head = { supermarket: 'ERP QA SUPERMARKET', restaurant: 'ERP QA RESTAURANT', pharmacy: 'ERP QA PHARMACY' }[inv.mode] || 'ERP QA COMPANY';
  const partyKind = inv.mode === 'restaurant' ? 'Table' : inv.mode === 'pharmacy' ? 'Patient' : 'Customer';
  $('#receiptBody').innerHTML = `
    <div class="r-center r-big">${head}</div>
    <div class="r-center r-muted">Head Office · Dar es Salaam</div>
    <div class="r-center r-muted">TIN 100-200-300 · VAT 40-ABC</div>
    <hr>
    <div class="r-row"><span>${inv.number}</span><span class="r-muted">${inv.when}</span></div>
    <div class="r-row"><span>Cashier: J. Kimaro</span><span>Till 1</span></div>
    <div class="r-row r-muted"><span>${partyKind}</span><span>${inv.party}</span></div>
    <hr>
    ${inv.lines.map((l) => `<div class="r-line"><span>${l.name}</span><span>${gift ? '' : money0(l.amt)}</span></div>
      <div class="r-line r-muted"><small>&nbsp;&nbsp;${l.qty} × ${money0(l.price)}</small><small></small></div>`).join('')}
    <hr>
    ${gift ? '<div class="r-center r-muted">— gift receipt · prices hidden —</div>' : `
      <div class="r-row"><span>Net</span><span>${money0(inv.net)}</span></div>
      ${inv.discount ? `<div class="r-row"><span>Discount</span><span>−${money0(inv.discount)}</span></div>` : ''}
      <div class="r-row"><span>VAT 18%</span><span>${money0(inv.vat)}</span></div>
      <div class="r-row b r-grand"><span>TOTAL ${CCY}</span><span>${money0(inv.gross)}</span></div>
      <hr>
      ${inv.tenders.map((t) => `<div class="r-row"><span>${t.type.replace('_', ' ')}</span><span>${money0(t.amount)}</span></div>`).join('')}
      <div class="r-row b"><span>CHANGE</span><span>${money0(inv.change)}</span></div>`}
    <hr>
    <div class="r-center r-muted">Asante sana! · Thank you</div>
    <div class="r-center r-muted" style="margin-top:6px">▌║▌║▌ ${inv.number} ▌║▌║▌</div>`;
}

/* ------------------------------ events ------------------------------ */
function setMode(mode) {
  state.mode = mode; state.cart = []; state.course = 'Starters';
  state.npMode = 'qty'; state.npEntry = ''; state.pendingQty = 1; state.selRow = null;
  $$('#modeCards .mode-card').forEach((c) => c.classList.toggle('is-active', c.dataset.mode === mode));
  if (!$('[data-screen="register"]').hidden) renderRegister();
}

function wire() {
  // login -> openshift -> register
  $('#loginForm').addEventListener('submit', (e) => { e.preventDefault(); show('openshift'); });
  $('#modeCards').addEventListener('click', (e) => { const b = e.target.closest('[data-mode]'); if (b) setMode(b.dataset.mode); });
  $('#tillGrid').addEventListener('click', (e) => {
    const b = e.target.closest('[data-till]'); if (!b) return;
    $$('.till').forEach((t) => t.classList.remove('is-active')); b.classList.add('is-active');
    $('#openSessionBtn').disabled = false;
  });
  $('#openSessionBtn').addEventListener('click', () => { renderRegister(); show('register'); toast('Session POS-0042 opened · ' + MODE_LABEL[state.mode], true); });

  // topbar mode switch
  $('#modeSwitch').addEventListener('click', (e) => { const b = e.target.closest('[data-mode]'); if (b) setMode(b.dataset.mode); });

  // delegated register interactions (markup is re-rendered per mode)
  const reg = $('#registerBody');
  reg.addEventListener('click', (e) => {
    const t = e.target;
    const add = t.closest('[data-add]'); if (add) return addItem(+add.dataset.add);
    const sr = t.closest('[data-sr]'); if (sr) { addItem(+sr.dataset.sr); clearSearch(); return; }
    const inc = t.closest('[data-inc]'); if (inc) return changeQty(+inc.dataset.inc, +1);
    const dec = t.closest('[data-dec]'); if (dec) return changeQty(+dec.dataset.dec, -1);
    const del = t.closest('[data-del]'); if (del) return removeLine(+del.dataset.del);
    const vd = t.closest('[data-void]'); if (vd) return voidLine(+vd.dataset.void);
    const np = t.closest('[data-np]'); if (np) return handleNp(np.dataset.np);
    const md = t.closest('[data-npmode]'); if (md) return setNpMode(md.dataset.npmode);
    if (t.closest('#npGo')) return npGo();
    const qin = t.closest('input[data-qty]'); if (qin) return selectRow(+qin.dataset.qty);
    const din = t.closest('input[data-disc]'); if (din) return selectRow(+din.dataset.disc);
    if (t.closest('[data-note]')) return toast('Add modifier / note (mock)');
    if (t.closest('#payBtn')) return openPayment();
    if (t.closest('#clearCartBtn')) { if (state.cart.length) { state.cart = []; renderLines(); toast('Cleared'); } return; }
    if (t.closest('#functionsBtn')) return openModal('functions');
    if (t.closest('#parkBtn')) { if (state.cart.length) { state.cart = []; renderLines(); toast('Sale parked (this till only)'); } return; }
    if (t.closest('#discountBtn')) return openModal('discount');
    if (t.closest('#kitchenBtn')) return toast('🔥 Order sent to kitchen', true);
    if (t.closest('#tableBtn') || (state.mode === 'restaurant' && t.closest('#contextBtn'))) { renderFloor(); return openModal('floor'); }
    if (t.closest('#contextBtn')) { renderCustomers(); return openModal('customer'); }
    const rowEl = t.closest('tr[data-row]'); if (rowEl) return selectRow(+rowEl.dataset.row);
    const course = t.closest('[data-course]'); if (course) { state.course = course.dataset.course;
      $$('#courseTabs .cat').forEach((c) => c.classList.toggle('is-active', c === course)); renderMenu(); }
  });
  reg.addEventListener('input', (e) => {
    if (e.target.id === 'lineSearch') return runSearch(e.target.value);
    const q = e.target.closest('[data-qty]');
    if (q) { state.cart[+q.dataset.qty].qty = Math.max(0, Number.parseFloat(e.target.value) || 0); updateRow(+q.dataset.qty); updateTotals(); return; }
    const d = e.target.closest('[data-disc]');
    if (d) { state.cart[+d.dataset.disc].discount = Math.max(0, Number.parseFloat(e.target.value) || 0); updateRow(+d.dataset.disc); updateTotals(); }
  });
  reg.addEventListener('keydown', (e) => {
    if (e.target.id === 'lineSearch' && e.key === 'Enter') { e.preventDefault();
      if (state.matches[0]) { addItem(state.matches[0].id); clearSearch(); } }
  });

  // floor table pick
  $('[data-modal="floor"]').addEventListener('click', (e) => {
    const b = e.target.closest('[data-table]'); if (!b) return;
    state.table = { name: b.dataset.table, covers: +b.dataset.seats || 2 };
    closeAllModals(); renderRegister();
  });

  // supermarket functions launcher (design only — tiles open their modal)
  $('[data-modal="functions"]').addEventListener('click', (e) => {
    const f = e.target.closest('[data-fn]'); if (!f) return;
    closeAllModals(); const fn = f.dataset.fn;
    if (fn === 'park') { if (state.cart.length) { state.cart = []; renderLines(); } toast('Sale parked (this till only)'); return; }
    if (fn === 'reprint') { if (state.lastInvoice) { renderReceipt(state.lastInvoice); openModal('receipt'); } else toast('No recent sale to reprint'); return; }
    if (fn === 'refund') { if (state.lastInvoice) $('#refundInv').textContent = `${state.lastInvoice.number} · ${money(state.lastInvoice.gross)}`; openModal('refund'); return; }
    openModal(fn);
  });

  // customer pick
  $('#customerList').addEventListener('click', (e) => {
    const li = e.target.closest('[data-cust]'); if (!li) return;
    state.customer = CUSTOMERS.find((c) => c.id === +li.dataset.cust);
    closeAllModals(); renderRegister();
  });

  // discount apply (mock: 500 off the chosen line, else first line)
  $('[data-modal="discount"]').addEventListener('click', (e) => {
    if (!e.target.closest('.btn--primary')) return;
    const i = state._discIdx ?? 0; if (state.cart[i]) state.cart[i].discount = 500;
    state._discIdx = null; renderLines();
  });

  // payment
  $('#tenderTypes').addEventListener('click', (e) => { const b = e.target.closest('[data-tender]'); if (!b) return;
    state.tenderType = b.dataset.tender; $$('.ttype').forEach((x) => x.classList.toggle('is-active', x === b)); });
  $('#keypad').addEventListener('click', (e) => { const b = e.target.closest('button'); if (!b) return;
    const k = b.dataset.k || b.textContent;
    if (k === 'del') state.payEntry = state.payEntry.slice(0, -1);
    else if (k === '.') { if (!state.payEntry.includes('.')) state.payEntry += state.payEntry ? '.' : '0.'; }
    else state.payEntry += k;
    $('#payInput').textContent = state.payEntry || '0'; });
  $('#quickCash').addEventListener('click', (e) => { const b = e.target.closest('[data-cash]'); if (!b) return;
    state.payEntry = b.dataset.cash; addTender(); });
  $('#addTenderBtn').addEventListener('click', addTender);
  $('#tenderList').addEventListener('click', (e) => { const x = e.target.closest('[data-rmtender]'); if (!x) return;
    state.tenders.splice(+x.dataset.rmtender, 1); renderTenders(); });
  $('#completeSaleBtn').addEventListener('click', completeSale);

  // receipt
  $('#newSaleBtn').addEventListener('click', closeAllModals);
  $('#reprintBtn').addEventListener('click', () => { renderReceipt(state.lastInvoice); toast('Reprinted — no new sale posted'); });
  $('#giftReceiptBtn').addEventListener('click', () => { renderReceipt(state.lastInvoice, true); toast('Gift receipt'); });
  $('#refundBtn').addEventListener('click', () => { $('#refundInv').textContent = `${state.lastInvoice.number} · ${money(state.lastInvoice.gross)}`; openModal('refund'); });
  $('#confirmRefundBtn').addEventListener('click', () => { closeAllModals(); toast('Sale reversed — stock, VAT, cash & revenue', true); });

  // session menu
  $('#sessionMenuBtn').addEventListener('click', () => openModal('session'));
  $('[data-modal="session"]').addEventListener('click', (e) => { const b = e.target.closest('[data-go]'); if (!b) return;
    closeAllModals(); const go = b.dataset.go;
    if (go === 'close' || go === 'reconcile' || go === 'sales') { show(go); if (go === 'sales') renderSales(); }
    else openModal(go); });
  $('#logoutBtn').addEventListener('click', () => { closeAllModals(); show('login'); });
  $('#confirmCloseBtn').addEventListener('click', () => { show('reconcile'); toast('Session CLOSED'); });
  $('#printZBtn').addEventListener('click', () => toast('Z-read printed'));
  $('#salesTable').addEventListener('click', (e) => { const b = e.target.closest('[data-reprint]'); if (!b) return;
    state.lastInvoice = state.lastInvoice || { number: b.dataset.reprint, when: 'today', party: 'Walk-in customer', mode: 'supermarket',
      lines: [], net: 0, vat: 0, gross: 8260, discount: 0, tenders: [{ type: 'CASH', amount: 10000 }], change: 1740 };
    state.lastInvoice.number = b.dataset.reprint; renderReceipt(state.lastInvoice); openModal('receipt'); });

  // generic close / back / backdrop / segmented
  document.addEventListener('click', (e) => {
    if (e.target.closest('[data-close]')) closeModal(e.target);
    const back = e.target.closest('[data-back]'); if (back) show(back.dataset.back);
    if (e.target.classList.contains('modal')) e.target.hidden = true;
    const seg = e.target.closest('.seg__b'); if (seg) $$('.seg__b', seg.parentElement).forEach((b) => b.classList.toggle('is-active', b === seg));
  });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { closeAllModals(); const r = $('#searchResults'); if (r) r.hidden = true; } });

  // clock
  const tick = () => { const d = new Date(); $('#tbClock').textContent = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }); };
  tick(); setInterval(tick, 10000);
}

/* ------------------------------- boot ------------------------------- */
document.addEventListener('DOMContentLoaded', () => { renderTills(); wire(); show('login'); });
