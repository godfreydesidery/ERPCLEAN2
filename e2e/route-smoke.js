// UNIT B — UI ROUTE SMOKE (HTTP) + API-backed endpoint probe.
// Two checks, both never-abort, both recorded to issues:
//   (1) HTTP-smoke EVERY admin route at the WEB origin: GET /admin/<path> must return 200 + the
//       SPA shell (index.html: <app-root>/<base href> present), NOT a 5xx or a non-HTML body.
//   (2) For each API-backed LIST screen, hit the underlying /api/v1 endpoint its *.service.ts calls
//       with a rootadmin Bearer + the bootstrapped company id/uid, and assert no 5xx + sane envelope.
//
// Route list is transcribed from web/src/app/features/admin/admin.routes.ts (commit f521dc2).
// Endpoint map is transcribed from each feature's *.service.ts list method (companyId vs companyUid,
// page/size, required filters). Endpoints that REQUIRE per-record params (locationId/productId/
// employeeId/priceListId) can't be smoke-listed in isolation — they're probed for "not 5xx"
// (a clean 400/404 is acceptable; a 500 is an issue), and flagged kind:'needs-params'.
//
// Run:  API_BASE=http://16.170.11.41/api/v1 node e2e/route-smoke.js
//   WEB_BASE defaults to API_BASE minus /api/v1 (same origin, /api proxied).
'use strict';
const http = require('http');
const https = require('https');
const fs = require('fs');

const API_BASE = (process.env.API_BASE || 'http://16.170.11.41/api/v1').replace(/\/$/, '');
const WEB_BASE = (process.env.WEB_BASE || API_BASE.replace(/\/api\/v1$/, '')).replace(/\/$/, '');
const ROOT_USER = process.env.ROOT_USER || 'rootadmin';
const ROOT_PASS = process.env.ROOT_PASS || 'SKp315goPN8Nb0yJtMCCD7cm';
const ISSUES_OUT = process.env.ISSUES_OUT || (require('os').tmpdir() + '/erp-route-smoke-issues.json');

const ISSUES = [];
const issue = (severity, area, message, detail) => {
  ISSUES.push({ severity, area, message, detail });
  console.log(`  [${severity}] ${area}: ${message}${detail ? ' — ' + detail : ''}`);
};

function request(method, fullUrl, token, body) {
  return new Promise((resolve) => {
    const u = new URL(fullUrl);
    const lib = u.protocol === 'https:' ? https : http;
    const data = body ? JSON.stringify(body) : null;
    const opt = {
      method,
      hostname: u.hostname,
      port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search,
      headers: {
        Accept: 'text/html,application/json,*/*',
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
      },
    };
    if (data) opt.headers['Content-Length'] = Buffer.byteLength(data);
    const r = lib.request(opt, (res) => {
      let b = '';
      res.on('data', (c) => (b += c));
      res.on('end', () => {
        let j = null;
        try { j = b ? JSON.parse(b) : null; } catch {}
        resolve({ status: res.statusCode, body: j, raw: b, headers: res.headers });
      });
    });
    r.on('error', (e) => resolve({ status: 0, body: null, raw: String(e), headers: {} }));
    if (data) r.write(data);
    r.end();
  });
}
const api = (method, path, token, body) => request(method, API_BASE + path, token, body);

async function login() {
  const r = await api('POST', '/auth/login', null, { username: ROOT_USER, password: ROOT_PASS });
  if (r.status !== 200 || !r.body?.data?.accessToken) {
    issue('BLOCKER', 'auth', `rootadmin login failed`, `status=${r.status} ${(r.raw || '').slice(0, 120)}`);
    return null;
  }
  return r.body.data.accessToken;
}

// ── ALL admin route paths (from admin.routes.ts). Param tokens get a probe value so we still
//    fetch a concrete URL — the SPA returns index.html for any /admin/** path regardless. ──
const ADMIN_ROUTES = [
  'home', 'companies', 'companies/PROBE/branches', 'roles', 'roles/uid/PROBE', 'role-grants',
  'users', 'users/uid/PROBE', 'audit',
  'customers', 'customers/uid/PROBE', 'suppliers', 'suppliers/uid/PROBE', 'agents', 'agents/uid/PROBE',
  'products', 'products/uid/PROBE', 'price-lists', 'units',
  'routes', 'routes/uid/PROBE',
  'quotations', 'quotations/uid/PROBE', 'sales-orders', 'sales-orders/uid/PROBE',
  'deliveries', 'deliveries/create', 'deliveries/uid/PROBE',
  'sales-returns', 'sales-returns/create', 'sales-returns/uid/PROBE',
  'sales-invoices', 'sales-invoices/uid/PROBE', 'tax-rates',
  'stock', 'stock/locations', 'stock/batches', 'stock/serials',
  'stock/valuation', 'stock/valuation/opening',
  'purchase-orders', 'purchase-orders/uid/PROBE', 'goods-receipts', 'goods-receipts/create', 'goods-receipts/uid/PROBE',
  'gl/accounts', 'gl/journals', 'gl/journals/post', 'gl/journals/uid/PROBE', 'gl/trial-balance',
  'gl/periods', 'gl/config',
  'ar/invoices', 'ar/receipts/record', 'ar/statement', 'ar/opening-balance',
  'ap/supplier-bills', 'ap/supplier-bills/enter', 'ap/supplier-bills/uid/PROBE',
  'ap/payments/record', 'ap/statement', 'ap/opening-balance',
  'cash/accounts', 'cash/transfers/record', 'cash/entries/record', 'cash/cheques',
  'cash/reconciliations', 'cash/statement',
  'tax/vat-returns', 'tax/vat-returns/uid/PROBE', 'tax/wht-types', 'tax/wht-register',
  'reporting/income-statement', 'reporting/balance-sheet', 'reporting/cash-flow', 'reporting/account-ledger',
  'approvals/inbox', 'approvals/policies', 'approvals/policies/uid/PROBE',
  'approvals/requests', 'approvals/requests/uid/PROBE',
  'documents', 'documents/uid/PROBE', 'document-templates', 'document-branding',
  'notifications', 'notification-preferences', 'notification-types', 'notification-deliveries',
  'cost-centre/dimensions', 'cost-centre/values', 'cost-centre/values/uid/PROBE', 'cost-centre/report',
  'asset-categories', 'asset-categories/uid/PROBE', 'fixed-assets', 'fixed-assets/create',
  'fixed-assets/uid/PROBE', 'fixed-assets/reconciliation',
  'depreciation-runs', 'depreciation-runs/post', 'depreciation-runs/uid/PROBE',
  'crm/leads', 'crm/leads/uid/PROBE', 'crm/opportunities', 'crm/opportunities/create',
  'crm/opportunities/uid/PROBE', 'crm/pipeline', 'crm/settings/pipeline-stages',
  'hr/employees', 'hr/employees/uid/PROBE', 'hr/pay-components', 'hr/pay-components/uid/PROBE',
  'hr/payroll-runs', 'hr/payroll-runs/uid/PROBE', 'hr/leave-requests', 'hr/leave-requests/uid/PROBE',
  'hr/loans', 'hr/loans/uid/PROBE',
  'projects', 'projects/uid/PROBE', 'projects/wip-report',
  'budgets', 'budgets/uid/PROBE', 'budget-versions/uid/PROBE', 'budgeting/variance', 'budgeting/departmental-actuals',
  'work-orders', 'work-orders/uid/PROBE', 'work-orders/uid/PROBE/cost-report', 'manufacturing/wip-reconciliation',
  'fx/rates', 'fx/revaluation-runs',
  'dashboard',
  // ── 24 NEW screens ──
  'pos/tills', 'pos/sessions', 'pos/sessions/uid/PROBE', 'pos/sell',
  'stock-transfers', 'stock-transfers/create', 'stock-transfers/uid/PROBE',
  'stock-counts', 'stock-counts/create', 'stock-counts/uid/PROBE',
  'purchase-requisitions', 'purchase-requisitions/create', 'purchase-requisitions/uid/PROBE',
  'rfqs', 'rfqs/create', 'rfqs/uid/PROBE',
  'purchase-returns', 'purchase-returns/create', 'purchase-returns/uid/PROBE',
  'landed-costs', 'landed-costs/create', 'landed-costs/uid/PROBE',
  'purchase-settings',
  'blanket-orders', 'blanket-orders/uid/PROBE', 'blanket-orders/create',
  'boms', 'boms/uid/PROBE',
  'standing-orders', 'standing-orders/create', 'standing-orders/uid/PROBE',
  'pricing-rules',
  'other-parties', 'other-parties/uid/PROBE',
  'crm/activities',
  'hr/departments', 'hr/contracts', 'hr/statutory',
  'gl/year-end',
  'ar/receipts', 'ar/receipts/uid/PROBE', 'ar/ageing',
  'ap/payments', 'ap/payments/uid/PROBE',
  'cash/transfers', 'cash/transfers/uid/PROBE',
];

// The 24 NEW screens — used to tag endpoint findings.
const NEW_SCREENS = new Set([
  'pos', 'stock-transfers', 'stock-counts', 'stock/locations', 'stock/batches', 'stock/serials',
  'purchase-requisitions', 'rfqs', 'purchase-returns', 'landed-costs', 'purchase-settings',
  'blanket-orders', 'boms', 'standing-orders', 'pricing-rules', 'other-parties', 'crm/activities',
  'hr/departments', 'hr/contracts', 'hr/statutory', 'gl/year-end', 'ar/receipts', 'ar/ageing',
  'ap/payments', 'cash/transfers',
]);

// ── Endpoint map: route -> { path(ctx), kind }  (ctx carries companyId/companyUid). ──
// kind: 'list' = plain list smoke; 'needs-params' = endpoint needs per-record params so a clean
//        4xx is OK and only a 5xx is an issue; 'singleton' = config/by-company fetch.
function endpointMap(ctx) {
  const { companyId, companyUid } = ctx;
  const ps = `page=0&size=5`;
  return [
    // baseline / pre-existing (sampled, to compare against the 24 new)
    { route: 'companies', path: `/companies?organisationUid=${ctx.orgUid}`, kind: 'list' },
    { route: 'customers', path: `/customers?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'suppliers', path: `/suppliers?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'agents', path: `/agents?companyId=${companyId}&${ps}`, kind: 'list' },
    // NOTE: products/price-lists/routes/purchase-orders/goods-receipts take companyId (Long),
    // NOT companyUid — confirmed by their *.service.ts list() and a 200 vs 400 probe on QA.
    { route: 'products', path: `/products?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'price-lists', path: `/price-lists?companyId=${companyId}`, kind: 'list' },
    { route: 'units', path: `/units?companyId=${companyId}`, kind: 'list' },
    { route: 'routes', path: `/routes?companyId=${companyId}`, kind: 'list' },
    { route: 'sales-invoices', path: `/sales-invoices?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'purchase-orders', path: `/purchase-orders?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'goods-receipts', path: `/goods-receipts?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'gl/accounts', path: `/gl/accounts?companyId=${companyId}`, kind: 'list' },
    { route: 'gl/journals', path: `/gl/journals?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'gl/periods', path: `/gl/periods?companyId=${companyId}`, kind: 'list' },
    { route: 'ar/invoices', path: `/ar/invoices?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'ap/supplier-bills', path: `/ap/supplier-bills?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'cash/accounts', path: `/cash/accounts?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'tax/vat-returns', path: `/vat/returns?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'tax/wht-types', path: `/wht/types?companyId=${companyId}`, kind: 'list' },
    { route: 'approvals/policies', path: `/approvals/policies?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'approvals/requests', path: `/approvals/requests?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'notifications', path: `/notifications?${ps}`, kind: 'list' },
    { route: 'cost-centre/dimensions', path: `/dimensions?companyId=${companyId}`, kind: 'list' },
    { route: 'fixed-assets', path: `/fixed-assets?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'crm/leads', path: `/crm/leads?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'crm/opportunities', path: `/crm/opportunities?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'hr/employees', path: `/hr/employees?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'hr/payroll-runs', path: `/hr/payroll-runs?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'projects', path: `/projects?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'budgets', path: `/budgets?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'work-orders', path: `/work-orders?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'fx/rates', path: `/fx/rates?companyId=${companyId}`, kind: 'list' },
    { route: 'documents', path: `/documents?companyId=${companyId}&${ps}`, kind: 'list' },

    // ── 24 NEW screens ──
    // pos-till-list.component sends branchId only after branches load; backend MANDATES branchId.
    // Probing the exact FE call (companyId only) documents the hard dependency.
    { route: 'pos/tills', path: `/pos/tills?companyId=${companyId}`, kind: 'contract' },
    { route: 'pos/sessions', path: `/pos/sessions?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'stock-transfers', path: `/stock-transfers?${ps}`, kind: 'list' },
    { route: 'stock-counts', path: `/stock-counts?${ps}`, kind: 'list' },
    { route: 'stock/locations', path: `/stock-locations?${ps}`, kind: 'list' },
    // batches/serials need companyId+locationId+productId — probe for not-5xx only
    { route: 'stock/batches', path: `/stock-batches?companyId=${companyId}&locationId=0&productId=0&${ps}`, kind: 'needs-params' },
    { route: 'stock/serials', path: `/stock-serials?companyId=${companyId}&locationId=0&productId=0&${ps}`, kind: 'needs-params' },
    { route: 'purchase-requisitions', path: `/purchase-requisitions?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'rfqs', path: `/rfqs?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'purchase-returns', path: `/purchase-returns?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'landed-costs', path: `/landed-costs?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'purchase-settings', path: `/purchase-settings/by-company/${companyUid}`, kind: 'singleton' },
    { route: 'blanket-orders', path: `/blanket-orders?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'boms', path: `/boms?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'standing-orders', path: `/standing-orders?companyId=${companyId}&${ps}`, kind: 'list' },
    // pricing-rules list = tiers, needs productId+priceListId — probe for not-5xx only
    { route: 'pricing-rules', path: `/pricing-rules/tiers?companyId=${companyId}&productId=0&priceListId=0`, kind: 'needs-params' },
    { route: 'other-parties', path: `/other-parties?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'crm/activities', path: `/crm/activities/open-tasks?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'hr/departments', path: `/hr/departments?companyId=${companyId}`, kind: 'list' },
    // hr/contracts needs employeeId — probe for not-5xx only
    { route: 'hr/contracts', path: `/hr/contracts?companyId=${companyId}&employeeId=0`, kind: 'needs-params' },
    { route: 'hr/statutory', path: `/hr/statutory/paye-bands?companyId=${companyId}`, kind: 'list' },
    { route: 'hr/statutory(rates)', path: `/hr/statutory/rates?companyId=${companyId}`, kind: 'list' },
    { route: 'gl/year-end', path: `/gl/periods/fiscal-years?companyId=${companyId}`, kind: 'list' },
    { route: 'ar/receipts', path: `/ar/receipts?companyId=${companyId}&${ps}`, kind: 'list' },
    // ar-ageing.component calls getAgeing(companyId) with NO customer on load, but backend
    // MANDATES customerId -> the screen 400s on initial load. Probe the exact FE call.
    { route: 'ar/ageing', path: `/ar/ageing?companyId=${companyId}`, kind: 'contract' },
    { route: 'ap/payments', path: `/ap/payments?companyId=${companyId}&${ps}`, kind: 'list' },
    { route: 'cash/transfers', path: `/cash/transfers?companyId=${companyId}&${ps}`, kind: 'list' },
  ];
}

function looksLikeSpaShell(html) {
  if (!html) return false;
  const h = html.toLowerCase();
  return h.includes('<app-root') || (h.includes('<base href') && h.includes('</html>')) ||
    h.includes('main-') /* hashed bundle */ || (h.includes('<!doctype html') && h.includes('script'));
}

(async () => {
  console.log(`=== ROUTE SMOKE ===  WEB_BASE=${WEB_BASE}  API_BASE=${API_BASE}`);
  const token = await login();
  if (!token) return dump();

  // discover bootstrap context
  const orgR = await api('GET', '/organisations', token);
  const orgUid = orgR.body?.data?.[0]?.uid;
  if (!orgUid) issue('HIGH', 'bootstrap', 'no organisation from /organisations', `status=${orgR.status}`);
  const compR = orgUid ? await api('GET', `/companies?organisationUid=${orgUid}`, token) : { status: 0, body: null };
  const company = compR.body?.data?.[0];
  const companyUid = company?.uid;
  const companyId = company?.id != null ? String(company.id) : undefined;
  if (!companyUid) { issue('BLOCKER', 'bootstrap', 'no company found — cannot probe API endpoints', `status=${compR.status}`); }
  console.log(`  org=${orgUid} company=${companyUid} (id ${companyId})`);
  const ctx = { orgUid, companyUid, companyId };

  // ── CHECK 1: HTTP-smoke every admin route at the WEB origin ──
  console.log(`\n=== CHECK 1: HTTP-smoke ${ADMIN_ROUTES.length} admin routes (SPA shell) ===`);
  let shellOk = 0;
  const routeResults = [];
  for (const rt of ADMIN_ROUTES) {
    const url = `${WEB_BASE}/admin/${rt}`;
    const r = await request('GET', url, null);
    const ct = (r.headers['content-type'] || '').toLowerCase();
    let verdict = 'ok';
    if (r.status === 0) { verdict = 'neterr'; issue('HIGH', 'route', `network error fetching /admin/${rt}`, r.raw?.slice(0, 100)); }
    else if (r.status >= 500) { verdict = '5xx'; issue('HIGH', 'route', `/admin/${rt} returned ${r.status}`, (r.raw || '').slice(0, 120)); }
    else if (r.status !== 200) { verdict = `http${r.status}`; issue('MEDIUM', 'route', `/admin/${rt} returned ${r.status} (expected 200 SPA shell)`, (r.raw || '').slice(0, 120)); }
    else if (!ct.includes('text/html') && !looksLikeSpaShell(r.raw)) {
      verdict = 'not-html'; issue('MEDIUM', 'route', `/admin/${rt} 200 but not the SPA shell`, `content-type=${ct} body=${(r.raw || '').slice(0, 80)}`);
    } else if (!looksLikeSpaShell(r.raw)) {
      verdict = 'no-shell'; issue('LOW', 'route', `/admin/${rt} 200 html but no <app-root>/shell markers`, (r.raw || '').slice(0, 80));
    } else { shellOk++; }
    routeResults.push({ route: rt, status: r.status, verdict });
  }
  console.log(`  SPA-shell OK: ${shellOk}/${ADMIN_ROUTES.length}`);

  // ── CHECK 2: API-backed list endpoints ──
  console.log(`\n=== CHECK 2: API-backed list/data endpoints ===`);
  const eps = endpointMap(ctx);
  let epOk = 0;
  const epResults = [];
  for (const ep of eps) {
    const r = await api('GET', ep.path, token);
    const isNew = NEW_SCREENS.has(ep.route.replace(/\(.*/, '')) ||
      [...NEW_SCREENS].some((n) => ep.route.startsWith(n));
    let verdict = 'ok';
    if (r.status === 0) {
      verdict = 'neterr';
      issue('HIGH', 'endpoint', `network error GET ${ep.path}`, `route=${ep.route}`);
    } else if (r.status >= 500) {
      verdict = '5xx';
      issue('HIGH', 'endpoint', `5xx from ${ep.route} list endpoint`, `GET ${ep.path} -> ${r.status} ${(r.raw || '').slice(0, 140)}`);
    } else if (ep.kind === 'needs-params') {
      // a clean 4xx is fine; success is fine; only 5xx (handled above) is an issue
      verdict = r.status < 300 ? 'ok' : `ok(${r.status})`;
      epOk++;
    } else if (ep.kind === 'contract') {
      // probing the EXACT call the screen makes on load. A 4xx here = a real FE/BE param
      // mismatch (the screen errors on load). 2xx = fine.
      if (r.status >= 400) {
        verdict = `contract${r.status}`;
        issue('HIGH', 'endpoint', `${ep.route} FE/BE contract mismatch — the screen's on-load call gets ${r.status}`, `GET ${ep.path} ${(r.raw || '').slice(0, 140)}`);
      } else { verdict = 'ok'; epOk++; }
    } else if (r.status === 401 || r.status === 403) {
      verdict = `auth${r.status}`;
      issue('HIGH', 'endpoint', `${ep.route} endpoint rejected rootadmin (${r.status}) — RBAC/permission gap`, `GET ${ep.path} ${(r.raw || '').slice(0, 120)}`);
    } else if (r.status === 404) {
      verdict = '404';
      issue('HIGH', 'endpoint', `${ep.route} endpoint not found (404) — route/screen has no backing API`, `GET ${ep.path}`);
    } else if (r.status >= 400) {
      verdict = `http${r.status}`;
      issue('MEDIUM', 'endpoint', `${ep.route} endpoint 4xx (${r.status})`, `GET ${ep.path} ${(r.raw || '').slice(0, 140)}`);
    } else {
      // 2xx — sanity-check envelope shape
      const hasData = r.body && Object.prototype.hasOwnProperty.call(r.body, 'data');
      if (ep.kind === 'singleton') {
        if (!r.body) issue('LOW', 'endpoint', `${ep.route} 2xx but empty body`, `GET ${ep.path}`);
      } else if (!hasData) {
        issue('MEDIUM', 'endpoint', `${ep.route} 2xx but missing 'data' envelope`, `GET ${ep.path} body=${(r.raw || '').slice(0, 100)}`);
        verdict = 'no-envelope';
      }
      epOk++;
    }
    epResults.push({ route: ep.route, kind: ep.kind, new: isNew, status: r.status, verdict });
    console.log(`  ${verdict === 'ok' || verdict.startsWith('ok(') ? '✓' : '✗'} [${isNew ? 'NEW' : '   '}] ${ep.route} -> ${r.status} (${verdict})`);
  }
  console.log(`  endpoints OK (no 5xx/404/auth): ${epOk}/${eps.length}`);

  dump({ routeResults, epResults, shellOk, epOk, total: { routes: ADMIN_ROUTES.length, endpoints: eps.length } });
})();

function dump(extra) {
  console.log('\n================ ISSUE SUMMARY ================');
  const order = ['BLOCKER', 'HIGH', 'MEDIUM', 'LOW'];
  const bySev = {};
  for (const i of ISSUES) (bySev[i.severity] = bySev[i.severity] || []).push(i);
  for (const sev of order) {
    const list = bySev[sev] || [];
    console.log(`${sev}: ${list.length}`);
    for (const i of list) console.log(`   - [${i.area}] ${i.message}${i.detail ? '  ::  ' + i.detail : ''}`);
  }
  const out = { generatedAt: new Date().toISOString(), webBase: WEB_BASE, apiBase: API_BASE, issues: ISSUES, ...(extra || {}) };
  fs.writeFileSync(ISSUES_OUT, JSON.stringify(out, null, 2));
  console.log(`\nwrote ${ISSUES_OUT}`);
}
