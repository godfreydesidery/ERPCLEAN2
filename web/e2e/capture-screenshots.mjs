/**
 * capture-screenshots.mjs — drive the running ERP web app and capture screenshots
 * for the user manual.
 *
 * Logs in once as rootadmin (sees every screen), then visits a curated list of the
 * major screens — one hero shot per distinct screen, deduped from the ~128 routes —
 * and writes a viewport PNG per screen plus a manifest.json grouped by manual chapter.
 *
 * Run from the repo root with the web app up (see docs/tools/build-manual.sh notes):
 *   docker compose up -d db
 *   (backend dev on :8081)
 *   npx ng serve --port 4400 --proxy-config web/proxy.conf.json   # from web/
 *   PW_BASE_URL=http://localhost:4400 node web/e2e/capture-screenshots.mjs
 *
 * Output: docs/user-manual/images/<chapter>/<name>.png  (+ images/manifest.json)
 */
import { chromium } from '@playwright/test';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const BASE = process.env.PW_BASE_URL ?? 'http://localhost:4400';
const ROOT_USER = process.env.ROOT_USER ?? 'rootadmin';
const ROOT_PASS = process.env.ROOT_PASS ?? 'RootPass12345';
// Anchored to this file (web/e2e/) → repo-root docs/, so cwd does not matter.
const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT_ROOT = path.resolve(HERE, '..', '..', 'docs', 'user-manual', 'images');

/**
 * Curated capture map. `route` is relative to /admin unless `raw:true` (then it is an
 * absolute app path such as /login). `chapter` is the manual chapter folder; `name` is
 * the PNG basename; `caption` becomes the figure caption in the .docx.
 */
const SHOTS = [
  // 00 — Getting started
  { chapter: '00-getting-started', name: 'login',            route: '/login', raw: true, caption: 'The sign-in screen' },
  { chapter: '00-getting-started', name: 'home',             route: 'home',             caption: 'The home / system-setup landing page' },

  // 01 — Administration
  { chapter: '01-administration', name: 'companies',         route: 'companies',        caption: 'Companies administration' },
  { chapter: '01-administration', name: 'branches',          route: 'branches',         caption: 'Branches administration' },
  { chapter: '01-administration', name: 'users',             route: 'users',            caption: 'Users list' },
  { chapter: '01-administration', name: 'roles',             route: 'roles',            caption: 'Roles list' },
  { chapter: '01-administration', name: 'role-grants',       route: 'role-grants',      caption: 'Granting roles to a user' },
  { chapter: '01-administration', name: 'audit',             route: 'audit',            caption: 'The audit log' },

  // 02 — Master data
  { chapter: '02-master-data', name: 'customers',            route: 'customers',        caption: 'Customers' },
  { chapter: '02-master-data', name: 'suppliers',            route: 'suppliers',        caption: 'Suppliers' },
  { chapter: '02-master-data', name: 'agents',              route: 'agents',           caption: 'Sales agents' },
  { chapter: '02-master-data', name: 'products',            route: 'products',         caption: 'Products catalogue' },
  { chapter: '02-master-data', name: 'price-lists',         route: 'price-lists',      caption: 'Price lists' },
  { chapter: '02-master-data', name: 'units',               route: 'units',            caption: 'Units of measure' },
  { chapter: '02-master-data', name: 'routes',             route: 'routes',           caption: 'Delivery routes' },
  { chapter: '02-master-data', name: 'tax-rates',           route: 'tax-rates',        caption: 'Tax rates' },
  { chapter: '02-master-data', name: 'pricing-rules',       route: 'pricing-rules',    caption: 'Pricing rules' },

  // 03 — Sales & POS
  { chapter: '03-sales-and-pos', name: 'quotations',        route: 'quotations',       caption: 'Sales quotations' },
  { chapter: '03-sales-and-pos', name: 'sales-orders',      route: 'sales-orders',     caption: 'Sales orders' },
  { chapter: '03-sales-and-pos', name: 'deliveries',        route: 'deliveries',       caption: 'Deliveries' },
  { chapter: '03-sales-and-pos', name: 'sales-invoices',    route: 'sales-invoices',   caption: 'Sales invoices' },
  { chapter: '03-sales-and-pos', name: 'sales-returns',     route: 'sales-returns',    caption: 'Sales returns' },
  { chapter: '03-sales-and-pos', name: 'blanket-orders',    route: 'blanket-orders',   caption: 'Blanket orders' },
  { chapter: '03-sales-and-pos', name: 'pos-sessions',      route: 'pos/sessions',     caption: 'POS sessions / till control' },
  { chapter: '03-sales-and-pos', name: 'pos-sell',          route: 'pos/sell',         caption: 'POS — ringing up a sale' },

  // 04 — Procurement
  { chapter: '04-procurement', name: 'purchase-requisitions', route: 'purchase-requisitions', caption: 'Purchase requisitions' },
  { chapter: '04-procurement', name: 'rfqs',                route: 'rfqs',             caption: 'Requests for quotation' },
  { chapter: '04-procurement', name: 'purchase-orders',     route: 'purchase-orders',  caption: 'Purchase orders' },
  { chapter: '04-procurement', name: 'goods-receipts',      route: 'goods-receipts',   caption: 'Goods receipts' },
  { chapter: '04-procurement', name: 'purchase-returns',    route: 'purchase-returns', caption: 'Purchase returns' },
  { chapter: '04-procurement', name: 'landed-costs',        route: 'landed-costs',     caption: 'Landed costs' },

  // 05 — Inventory & manufacturing
  { chapter: '05-inventory-manufacturing', name: 'stock',           route: 'stock',           caption: 'Stock on hand' },
  { chapter: '05-inventory-manufacturing', name: 'stock-locations', route: 'stock/locations', caption: 'Stock locations' },
  { chapter: '05-inventory-manufacturing', name: 'stock-valuation', route: 'stock/valuation', caption: 'Inventory valuation' },
  { chapter: '05-inventory-manufacturing', name: 'stock-transfers', route: 'stock-transfers', caption: 'Stock transfers' },
  { chapter: '05-inventory-manufacturing', name: 'stock-counts',    route: 'stock-counts',    caption: 'Stock counts' },
  { chapter: '05-inventory-manufacturing', name: 'work-orders',     route: 'work-orders',     caption: 'Manufacturing work orders' },
  { chapter: '05-inventory-manufacturing', name: 'boms',            route: 'boms',            caption: 'Bills of materials' },

  // 06 — Fixed assets
  { chapter: '06-fixed-assets', name: 'asset-categories',   route: 'asset-categories', caption: 'Asset categories' },
  { chapter: '06-fixed-assets', name: 'fixed-assets',       route: 'fixed-assets',     caption: 'Fixed-asset register' },
  { chapter: '06-fixed-assets', name: 'depreciation-runs',  route: 'depreciation-runs', caption: 'Depreciation runs' },

  // 07 — Projects
  { chapter: '07-projects', name: 'projects',               route: 'projects',         caption: 'Projects' },
  { chapter: '07-projects', name: 'projects-wip-report',    route: 'projects/wip-report', caption: 'Project WIP report' },

  // 08 — Finance
  { chapter: '08-finance', name: 'gl-accounts',             route: 'gl/accounts',      caption: 'Chart of accounts' },
  { chapter: '08-finance', name: 'gl-journals',             route: 'gl/journals',      caption: 'GL journals' },
  { chapter: '08-finance', name: 'gl-trial-balance',        route: 'gl/trial-balance', caption: 'Trial balance' },
  { chapter: '08-finance', name: 'ar-invoices',             route: 'ar/invoices',      caption: 'AR invoices' },
  { chapter: '08-finance', name: 'ar-statement',            route: 'ar/statement',     caption: 'Customer statement' },
  { chapter: '08-finance', name: 'ap-supplier-bills',       route: 'ap/supplier-bills', caption: 'Supplier bills' },
  { chapter: '08-finance', name: 'cash-accounts',           route: 'cash/accounts',    caption: 'Cash & bank accounts' },
  { chapter: '08-finance', name: 'cash-reconciliations',    route: 'cash/reconciliations', caption: 'Bank reconciliation' },
  { chapter: '08-finance', name: 'tax-vat-returns',         route: 'tax/vat-returns',  caption: 'VAT returns' },
  { chapter: '08-finance', name: 'fx-rates',                route: 'fx/rates',         caption: 'FX rates' },

  // 09 — CRM
  { chapter: '09-crm', name: 'crm-leads',                   route: 'crm/leads',        caption: 'CRM leads' },
  { chapter: '09-crm', name: 'crm-opportunities',           route: 'crm/opportunities', caption: 'Opportunities' },
  { chapter: '09-crm', name: 'crm-pipeline',                route: 'crm/pipeline',     caption: 'Sales pipeline' },
  { chapter: '09-crm', name: 'crm-activities',              route: 'crm/activities',   caption: 'CRM activities' },

  // 10 — Reporting & BI
  { chapter: '10-reporting-bi', name: 'dashboard',          route: 'dashboard',        caption: 'BI dashboard' },
  { chapter: '10-reporting-bi', name: 'income-statement',   route: 'reporting/income-statement', caption: 'Income statement' },
  { chapter: '10-reporting-bi', name: 'balance-sheet',      route: 'reporting/balance-sheet', caption: 'Balance sheet' },
  { chapter: '10-reporting-bi', name: 'cash-flow',          route: 'reporting/cash-flow', caption: 'Cash-flow statement' },
  { chapter: '10-reporting-bi', name: 'account-ledger',     route: 'reporting/account-ledger', caption: 'Account ledger' },

  // 11 — HR, budgeting & platform
  { chapter: '11-hr-budgeting-platform', name: 'hr-employees',     route: 'hr/employees',     caption: 'Employees' },
  { chapter: '11-hr-budgeting-platform', name: 'hr-payroll-runs',  route: 'hr/payroll-runs',  caption: 'Payroll runs' },
  { chapter: '11-hr-budgeting-platform', name: 'hr-leave-requests', route: 'hr/leave-requests', caption: 'Leave requests' },
  { chapter: '11-hr-budgeting-platform', name: 'budgets',          route: 'budgets',          caption: 'Budgets' },
  { chapter: '11-hr-budgeting-platform', name: 'approvals-inbox',  route: 'approvals/inbox',  caption: 'Approvals inbox' },
  { chapter: '11-hr-budgeting-platform', name: 'notifications',    route: 'notifications',    caption: 'Notifications' },
  { chapter: '11-hr-budgeting-platform', name: 'documents',        route: 'documents',        caption: 'Documents' },
];

const urlFor = (s) => (s.raw ? `${BASE}${s.route}` : `${BASE}/admin/${s.route}`);

async function login(page) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.fill('input[name="username"]', ROOT_USER);
  await page.fill('input[name="password"]', ROOT_PASS);
  await page.click('button[type="submit"]');
  await page.waitForURL(/\/admin/, { timeout: 30_000 });
}

async function settle(page) {
  try { await page.waitForLoadState('networkidle', { timeout: 12_000 }); } catch { /* keep going */ }
  await page.waitForTimeout(1300); // let spinners resolve + animations finish
}

async function main() {
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: { width: 1440, height: 960 },
    deviceScaleFactor: 1.5, // crisper text in the manual
  });
  const page = await context.newPage();

  const manifest = {};
  const failures = [];

  // Capture the login screen BEFORE authenticating.
  fs.mkdirSync(path.join(OUT_ROOT, SHOTS[0].chapter), { recursive: true });
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await settle(page);
  const loginShot = SHOTS[0];
  const loginFile = path.join(OUT_ROOT, loginShot.chapter, `${loginShot.name}.png`);
  await page.screenshot({ path: loginFile });
  (manifest[loginShot.chapter] ??= []).push({ name: loginShot.name, file: `images/${loginShot.chapter}/${loginShot.name}.png`, caption: loginShot.caption, route: loginShot.route });
  console.log(`OK   ${loginShot.chapter}/${loginShot.name}`);

  await login(page);

  for (const s of SHOTS.slice(1)) {
    const dir = path.join(OUT_ROOT, s.chapter);
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `${s.name}.png`);
    try {
      await page.goto(urlFor(s), { waitUntil: 'domcontentloaded', timeout: 30_000 });
      await settle(page);
      // If a hard navigation bounced us to /login, the session didn't restore — re-auth once.
      if (/\/login/.test(page.url())) {
        await login(page);
        await page.goto(urlFor(s), { waitUntil: 'domcontentloaded', timeout: 30_000 });
        await settle(page);
      }
      await page.screenshot({ path: file });
      (manifest[s.chapter] ??= []).push({ name: s.name, file: `images/${s.chapter}/${s.name}.png`, caption: s.caption, route: s.route });
      console.log(`OK   ${s.chapter}/${s.name}`);
    } catch (e) {
      failures.push({ shot: `${s.chapter}/${s.name}`, route: s.route, error: String(e).slice(0, 200) });
      console.log(`FAIL ${s.chapter}/${s.name} — ${String(e).slice(0, 120)}`);
    }
  }

  fs.writeFileSync(path.join(OUT_ROOT, 'manifest.json'), JSON.stringify({ generatedFrom: BASE, chapters: manifest, failures }, null, 2));
  await browser.close();

  const total = SHOTS.length;
  const ok = total - failures.length;
  console.log(`\n=== captured ${ok}/${total}; ${failures.length} failed ===`);
  if (failures.length) console.log(JSON.stringify(failures, null, 2));
}

main().catch((e) => { console.error(e); process.exit(1); });
