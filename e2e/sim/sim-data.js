// Canonical simulation data — transcribed from docs/simulation/COMPANY-SCENARIO.md (Tembo Group Ltd).
// Drivers read this so what's typed into the UI matches the world bible and the persona agent-defs.
// Permission assignment: each role lists keyword tokens; the onboarding driver checks every permission
// checkbox whose CODE or whose fieldset legend matches a token (broad-but-scoped, realistic admin grant).

const BRANCHES = [
  { name: 'Dar es Salaam HQ', code: 'HQ-DAR', region: 'Dar es Salaam', makeDefault: true },
  { name: 'Arusha', code: 'BR-ARU', region: 'Arusha' },
  { name: 'Mwanza', code: 'BR-MWZ', region: 'Mwanza' },
  { name: 'Dodoma', code: 'BR-DOD', region: 'Dodoma' },
  { name: 'Mbeya', code: 'BR-MBE', region: 'Mbeya' },
  { name: 'Mtwara', code: 'BR-MTW', region: 'Mtwara' },
  { name: 'Zanzibar', code: 'BR-ZNZ', region: 'Zanzibar' },
  { name: 'Morogoro', code: 'BR-MOR', region: 'Morogoro' },
  { name: 'Tanga', code: 'BR-TGA', region: 'Tanga' },
];

// keywords matched (case-insensitive, substring) against permission CODE and fieldset legend text.
const ROLES = [
  { key: 'GROUP_GM', name: 'Group General Manager', desc: 'Group-wide oversight, dashboards and final approvals',
    keywords: ['sales', 'purchas', 'stock', 'inventory', 'ledger', 'gl.', 'report', 'approv', 'dashboard', 'bi.', 'customer', 'supplier', 'product', 'branch', 'view', 'read'] },
  { key: 'FINANCE_DIRECTOR', name: 'Finance Director', desc: 'Full finance: GL, AR, AP, cash/bank, tax, assets, budgeting',
    keywords: ['ledger', 'gl.', 'journal', 'receivable', 'ar.', 'payable', 'ap.', 'cash', 'bank', 'tax', 'vat', 'asset', 'budget', 'fx', 'cost', 'report', 'approv', 'credit'],
    extraPerms: ['CUSTOMER.VIEW', 'SUPPLIER.VIEW'] }, // F22 role-read-dependency: AR/AP party pickers
  { key: 'BRANCH_MANAGER', name: 'Branch Manager', desc: 'Branch sales/purchase approvals, stock view & transfers, reports',
    keywords: ['sales', 'purchas', 'stock', 'inventory', 'transfer', 'report', 'approv', 'customer', 'supplier', 'branch'] },
  { key: 'ACCOUNTANT', name: 'Accountant', desc: 'GL postings, AR/AP invoicing, tax, period-end, financial reports',
    keywords: ['ledger', 'gl.', 'journal', 'receivable', 'ar.', 'payable', 'ap.', 'tax', 'vat', 'invoice', 'report', 'bank'],
    extraPerms: ['CUSTOMER.VIEW', 'SUPPLIER.VIEW'] }, // F22: AR/AP party pickers
  { key: 'CASHIER', name: 'Cashier / Cash & Bank Officer', desc: 'Cash & bank receipts, payments, petty cash, deposits',
    keywords: ['cash', 'bank', 'receipt', 'payment', 'petty', 'deposit'],
    extraPerms: ['CUSTOMER.VIEW', 'AR.VIEW'] }, // F22 read-closure of Record Receipt: customer picker + open-items query
  { key: 'SALES_OFFICER', name: 'Sales Officer', desc: 'Quotations, sales orders/invoices, deliveries, POS, customers',
    keywords: ['sales', 'quotation', 'order', 'invoice', 'delivery', 'customer', 'party', 'pos', 'receipt'] },
  { key: 'FIELD_SALES_AGENT', name: 'Field / Route Sales Agent', desc: 'Route sales orders & invoices, cash collections',
    keywords: ['sales', 'order', 'invoice', 'route', 'agent', 'cash', 'collection', 'customer'] },
  { key: 'PROCUREMENT_OFFICER', name: 'Procurement Officer', desc: 'Requisitions, RFQs, purchase orders, suppliers, GRN init',
    keywords: ['purchas', 'requisition', 'rfq', 'order', 'supplier', 'party', 'goods', 'receipt', 'return'] },
  { key: 'STOREKEEPER', name: 'Storekeeper / Stock Controller', desc: 'Goods receipt, issues, counts, locations, batches, transfers',
    keywords: ['stock', 'inventory', 'goods', 'receipt', 'issue', 'count', 'location', 'batch', 'transfer', 'adjust'],
    extraPerms: ['PURCHASE.RECEIVE'] }, // receiving goods is the core job; route+endpoint gate on PURCHASE.RECEIVE ('receive' != 'receipt' keyword)
  { key: 'STORES_SUPERVISOR', name: 'Stores / Warehouse Supervisor', desc: 'Storekeeper duties + approve adjustments/transfers, valuation',
    keywords: ['stock', 'inventory', 'goods', 'receipt', 'issue', 'count', 'location', 'batch', 'transfer', 'adjust', 'valuation', 'approv'],
    extraPerms: ['PURCHASE.RECEIVE'] }, // oversees receiving
  { key: 'PRODUCTION_OFFICER', name: 'Production Officer', desc: 'Work orders, BOM consumption, finished-goods receipt, costing',
    keywords: ['manufactur', 'work', 'bom', 'production', 'stock', 'cost'] },
  { key: 'HR_PAYROLL_OFFICER', name: 'HR / Payroll Officer', desc: 'Employees, contracts, leave, attendance, payroll, payslips',
    keywords: ['hr.', 'employee', 'contract', 'leave', 'attendance', 'payroll', 'payslip'] },
];

// 16 STAFF personas (those who log in). homeBranch must match a BRANCHES.name.
const STAFF = [
  { slug: 'bakari-mbaga', fullName: 'Bakari Mbaga', designation: 'Group General Manager', username: 'bmbaga', homeBranch: 'Dar es Salaam HQ', role: 'GROUP_GM' },
  { slug: 'grace-mhina', fullName: 'Grace Mhina', designation: 'Finance Director (CFO)', username: 'gmhina', homeBranch: 'Dar es Salaam HQ', role: 'FINANCE_DIRECTOR' },
  { slug: 'halima-juma', fullName: 'Halima Juma', designation: 'Branch Manager — Dar', username: 'hjuma', homeBranch: 'Dar es Salaam HQ', role: 'BRANCH_MANAGER' },
  { slug: 'emanuel-mushi', fullName: 'Emanuel Mushi', designation: 'Branch Manager — Arusha', username: 'emushi', homeBranch: 'Arusha', role: 'BRANCH_MANAGER' },
  { slug: 'daudi-kessy', fullName: 'Daudi Kessy', designation: 'Group Sales Manager', username: 'dkessy', homeBranch: 'Dar es Salaam HQ', role: 'BRANCH_MANAGER' },
  { slug: 'rehema-salum', fullName: 'Rehema Salum', designation: 'Procurement Manager', username: 'rsalum', homeBranch: 'Dar es Salaam HQ', role: 'PROCUREMENT_OFFICER' },
  { slug: 'editha-mhagama', fullName: 'Editha Mhagama', designation: 'Production Manager', username: 'emhagama', homeBranch: 'Dar es Salaam HQ', role: 'PRODUCTION_OFFICER' },
  { slug: 'neema-kileo', fullName: 'Neema Kileo', designation: 'HR & Payroll Manager', username: 'nkileo', homeBranch: 'Dar es Salaam HQ', role: 'HR_PAYROLL_OFFICER' },
  { slug: 'frank-materu', fullName: 'Frank Materu', designation: 'Stores / Warehouse Supervisor', username: 'fmateru', homeBranch: 'Dar es Salaam HQ', role: 'STORES_SUPERVISOR' },
  { slug: 'editrude-mwakalukwa', fullName: 'Editrude Mwakalukwa', designation: 'Production Supervisor', username: 'emwakalukwa', homeBranch: 'Dar es Salaam HQ', role: 'PRODUCTION_OFFICER' },
  { slug: 'amina-mwanga', fullName: 'Amina Mwanga', designation: 'Accountant', username: 'amwanga', homeBranch: 'Dar es Salaam HQ', role: 'ACCOUNTANT' },
  { slug: 'john-komba', fullName: 'John Komba', designation: 'Cashier / Cash & Bank Officer', username: 'jkomba', homeBranch: 'Dar es Salaam HQ', role: 'CASHIER' },
  { slug: 'sabina-aloyce', fullName: 'Sabina Aloyce', designation: 'Salesperson', username: 'saloyce', homeBranch: 'Dar es Salaam HQ', role: 'SALES_OFFICER' },
  { slug: 'hamisi-ngassa', fullName: 'Hamisi Ngassa', designation: 'Field / Route Sales Agent', username: 'hngassa', homeBranch: 'Mwanza', role: 'FIELD_SALES_AGENT' },
  { slug: 'saidi-karume', fullName: 'Saidi Karume', designation: 'Storekeeper / Stock Controller', username: 'skarume', homeBranch: 'Dar es Salaam HQ', role: 'STOREKEEPER' },
  { slug: 'yusuf-mbwana', fullName: 'Yusuf Mbwana', designation: 'Procurement Officer', username: 'ymbwana', homeBranch: 'Dar es Salaam HQ', role: 'PROCUREMENT_OFFICER' },
];

const PRODUCTS_SOURCED = [
  'Cement (Portland 50 kg)', 'Corrugated iron sheet (gauge 28)', 'Steel reinforcement bar (12 mm)',
  'LED television 32"', 'Solar home lighting kit', 'Mobile phone (entry smartphone)',
  'Wheat flour (imported, 25 kg)', 'Sugar (50 kg)', 'Rice (imported, 25 kg)',
  'Cooking gas cylinder (6 kg LPG)', 'Soft drink crate (24 x 300 ml)', 'Laundry bucket (assorted plastics)',
];
const PRODUCTS_MANUFACTURED = [
  'Tembo Cooking Oil (1 L bottle)', 'Tembo Bar Soap (800 g)', 'Tembo Washing Powder (1 kg)',
  'Tembo Drinking Water (500 ml)', 'Tembo Maize Flour Sembe (25 kg)', 'Tembo Office Desk (1.2 m)',
];

const CUSTOMERS = [
  { name: 'Joseph Ulimboka', kind: 'CREDIT_ACCOUNT', region: 'Mwanza' },
  { name: 'Kariakoo Wholesale Mart', kind: 'CREDIT_ACCOUNT', region: 'Dar es Salaam' },
  { name: 'Mlimani Supermarket Ltd', kind: 'CREDIT_ACCOUNT', region: 'Dar es Salaam' },
  { name: 'Serengeti Lodges Ltd', kind: 'CREDIT_ACCOUNT', region: 'Arusha' },
  { name: 'Mbeya District Council', kind: 'CREDIT_ACCOUNT', region: 'Mbeya' },
  { name: 'Zanzibar Beach Resorts Ltd', kind: 'CREDIT_ACCOUNT', region: 'Zanzibar' },
  { name: 'Dodoma Cash & Carry', kind: 'CASH_WALK_IN', region: 'Dodoma' },
  { name: 'Tanga Fresh Distributors', kind: 'CREDIT_ACCOUNT', region: 'Tanga' },
  { name: 'Morogoro Mini-Markets Assoc.', kind: 'CREDIT_ACCOUNT', region: 'Morogoro' },
];
const SUPPLIERS = [
  { name: 'Mbasha Holdings Ltd', supplies: 'Raw materials + traded goods' },
  { name: 'Bidco Africa (TZ) Ltd', supplies: 'Crude palm oil' },
  { name: 'Twiga Cement PLC', supplies: 'Cement & building materials' },
  { name: 'Mwananchi Maize Traders', supplies: 'Maize grain' },
  { name: 'PET-Pak Tanzania Ltd', supplies: 'PET preforms, bottles, caps' },
  { name: 'Coastal Chemicals Ltd', supplies: 'Caustic soda, LABSA, fragrances' },
  { name: 'Sao Hill Timber Suppliers', supplies: 'Sawn timber, plywood' },
  { name: 'Shenzhen Electro Import Co.', supplies: 'Electronics (import)' },
];

module.exports = { BRANCHES, ROLES, STAFF, PRODUCTS_SOURCED, PRODUCTS_MANUFACTURED, CUSTOMERS, SUPPLIERS };
