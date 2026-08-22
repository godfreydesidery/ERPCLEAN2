/// Demo data for the OrbixHQ mockup.
///
/// Mockup only — nothing here talks to the ERP. Figures are internally
/// consistent (branch lines sum to the company total, valuation rows sum to
/// the stock value) so the numbers hold up while the client taps through.
library;

// ---------------------------------------------------------------------------
// Who and where
// ---------------------------------------------------------------------------

const String kCompanyName = 'Tembo Group Ltd';
const String kUserName = 'Bakari Mbaga';
const String kUserRole = 'Branch Manager';
const String kUserInitials = 'BM';
const String kBranchName = 'Dar es Salaam';
const String kServerHost = 'tembo.orbixerp.com';

const List<String> kBranches = <String>[
  'Dar es Salaam',
  'Kariakoo',
  'Arusha',
  'Mwanza',
  'Dodoma',
  'Mbeya',
];

const String kAsOf = '14:20, 19 Aug 2026';
const String kToday = 'Tuesday, 19 August 2026';

// ---------------------------------------------------------------------------
// Dashboard — today's sales
// ---------------------------------------------------------------------------

const num kSalesToday = 8_420_000;
const num kSalesYesterday = 7_780_000;
const num kSalesMonthToDate = 148_600_000;
const int kInvoicesToday = 63;
const num kAverageSale = 133_650;
const num kCashCollectedToday = 5_120_000;
const num kCreditSalesToday = 3_300_000;

/// Last 7 days of sales, oldest first. The final value is today (part day).
const List<double> kSales7Days = <double>[
  6_950_000,
  7_240_000,
  8_100_000,
  6_480_000,
  7_920_000,
  7_780_000,
  8_420_000,
];

const List<String> kDay7Labels = <String>[
  'Wed',
  'Thu',
  'Fri',
  'Sat',
  'Sun',
  'Mon',
  'Tue',
];

/// Sales by channel today.
const List<({String label, num value})> kSalesByChannel =
    <({String label, num value})>[
  (label: 'Counter', value: 4_180_000),
  (label: 'Credit', value: 3_300_000),
  (label: 'Route van', value: 940_000),
];

// ---------------------------------------------------------------------------
// Dashboard — stock
// ---------------------------------------------------------------------------

const num kStockValue = 412_800_000;
const int kSkuCount = 1_284;
const int kLowStockCount = 17;
const int kOutOfStockCount = 4;

/// Items that need attention on the dashboard.
const List<({String name, String detail, int qty, String unit, bool critical})>
    kLowStock =
    <({String name, String detail, int qty, String unit, bool critical})>[
  (
    name: 'Cement 50kg — Twiga',
    detail: 'about 2 days of cover',
    qty: 34,
    unit: 'bags',
    critical: true
  ),
  (
    name: 'Cooking Oil 20L — Tembo',
    detail: 'about 4 days of cover',
    qty: 61,
    unit: 'ctn',
    critical: true
  ),
  (
    name: 'Bar Soap 800g',
    detail: 'below reorder level',
    qty: 118,
    unit: 'ctn',
    critical: false
  ),
  (
    name: 'Maize Flour 25kg',
    detail: 'below reorder level',
    qty: 92,
    unit: 'bags',
    critical: false
  ),
];

// ---------------------------------------------------------------------------
// Products — used by adjustments, receiving and lookups
// ---------------------------------------------------------------------------

class Product {
  const Product({
    required this.code,
    required this.name,
    required this.unit,
    required this.onHand,
    required this.cost,
    required this.price,
    required this.category,
  });

  final String code;
  final String name;
  final String unit;
  final int onHand;
  final num cost;
  final num price;
  final String category;
}

const List<Product> kProducts = <Product>[
  Product(
      code: 'CEM-050',
      name: 'Cement 50kg — Twiga',
      unit: 'bag',
      onHand: 34,
      cost: 17_200,
      price: 19_500,
      category: 'Building'),
  Product(
      code: 'OIL-20L',
      name: 'Cooking Oil 20L — Tembo',
      unit: 'ctn',
      onHand: 61,
      cost: 82_000,
      price: 94_000,
      category: 'Food'),
  Product(
      code: 'SOAP-800',
      name: 'Bar Soap 800g',
      unit: 'ctn',
      onHand: 118,
      cost: 26_400,
      price: 31_000,
      category: 'Household'),
  Product(
      code: 'FLR-025',
      name: 'Maize Flour 25kg',
      unit: 'bag',
      onHand: 92,
      cost: 41_000,
      price: 47_500,
      category: 'Food'),
  Product(
      code: 'WTR-500',
      name: 'Drinking Water 500ml x24',
      unit: 'ctn',
      onHand: 240,
      cost: 6_800,
      price: 8_500,
      category: 'Beverage'),
  Product(
      code: 'PWD-001',
      name: 'Washing Powder 1kg',
      unit: 'ctn',
      onHand: 76,
      cost: 22_500,
      price: 27_000,
      category: 'Household'),
  Product(
      code: 'SGR-050',
      name: 'Sugar 50kg',
      unit: 'bag',
      onHand: 48,
      cost: 118_000,
      price: 129_000,
      category: 'Food'),
  Product(
      code: 'RCE-025',
      name: 'Rice 25kg — Mbeya',
      unit: 'bag',
      onHand: 55,
      cost: 78_000,
      price: 88_000,
      category: 'Food'),
];

const List<String> kCategories = <String>[
  'Building',
  'Food',
  'Household',
  'Beverage',
  'Stationery',
];

const List<String> kUnits = <String>[
  'piece',
  'bag',
  'ctn',
  'kg',
  'litre',
  'dozen',
];

// ---------------------------------------------------------------------------
// Suppliers
// ---------------------------------------------------------------------------

class Supplier {
  const Supplier({
    required this.name,
    required this.phone,
    required this.location,
  });

  final String name;
  final String phone;
  final String location;
}

const List<Supplier> kSuppliers = <Supplier>[
  Supplier(
      name: 'Mbasha Holdings Ltd',
      phone: '+255 754 118 220',
      location: 'Dar es Salaam'),
  Supplier(
      name: 'Twiga Cement Distributors',
      phone: '+255 715 904 771',
      location: 'Dar es Salaam'),
  Supplier(
      name: 'Njombe Agro Supplies', phone: '+255 767 341 902', location: 'Njombe'),
  Supplier(
      name: 'Kilimanjaro Traders', phone: '+255 784 220 118', location: 'Moshi'),
];

// ---------------------------------------------------------------------------
// Sales report
// ---------------------------------------------------------------------------

class SalesRow {
  const SalesRow({
    required this.label,
    required this.qty,
    required this.amount,
    required this.share,
  });

  final String label;
  final int qty;
  final num amount;
  final double share;
}

const num kSalesReportTotal = 148_600_000;
const int kSalesReportInvoices = 1_147;

const List<SalesRow> kSalesByProduct = <SalesRow>[
  SalesRow(label: 'Cement 50kg — Twiga', qty: 1_820, amount: 35_490_000, share: 0.239),
  SalesRow(label: 'Cooking Oil 20L — Tembo', qty: 296, amount: 27_824_000, share: 0.187),
  SalesRow(label: 'Sugar 50kg', qty: 168, amount: 21_672_000, share: 0.146),
  SalesRow(label: 'Rice 25kg — Mbeya', qty: 214, amount: 18_832_000, share: 0.127),
  SalesRow(label: 'Maize Flour 25kg', qty: 302, amount: 14_345_000, share: 0.097),
  SalesRow(label: 'Bar Soap 800g', qty: 388, amount: 12_028_000, share: 0.081),
  SalesRow(label: 'Washing Powder 1kg', qty: 341, amount: 9_207_000, share: 0.062),
  SalesRow(label: 'Drinking Water 500ml x24', qty: 1_084, amount: 9_202_000, share: 0.061),
];

const List<SalesRow> kSalesByBranch = <SalesRow>[
  SalesRow(label: 'Dar es Salaam', qty: 402, amount: 52_010_000, share: 0.350),
  SalesRow(label: 'Kariakoo', qty: 288, amount: 34_178_000, share: 0.230),
  SalesRow(label: 'Mwanza', qty: 171, amount: 22_290_000, share: 0.150),
  SalesRow(label: 'Arusha', qty: 132, amount: 17_832_000, share: 0.120),
  SalesRow(label: 'Dodoma', qty: 94, amount: 13_374_000, share: 0.090),
  SalesRow(label: 'Mbeya', qty: 60, amount: 8_916_000, share: 0.060),
];

/// Twelve months of sales, oldest first; the last entry is the part month.
const List<double> kSales12Months = <double>[
  121_000_000,
  134_000_000,
  128_000_000,
  142_000_000,
  151_000_000,
  147_000_000,
  139_000_000,
  158_000_000,
  164_000_000,
  152_000_000,
  171_000_000,
  148_600_000,
];

const List<String> kMonthLabels = <String>[
  'Sep',
  'Oct',
  'Nov',
  'Dec',
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
];

// ---------------------------------------------------------------------------
// Stock report + valuation
// ---------------------------------------------------------------------------

class ValuationRow {
  const ValuationRow({
    required this.category,
    required this.skus,
    required this.qty,
    required this.value,
  });

  final String category;
  final int skus;
  final int qty;
  final num value;
}

const List<ValuationRow> kValuation = <ValuationRow>[
  ValuationRow(category: 'Building', skus: 214, qty: 8_420, value: 148_120_000),
  ValuationRow(category: 'Food', skus: 386, qty: 6_180, value: 121_440_000),
  ValuationRow(category: 'Household', skus: 291, qty: 4_950, value: 74_600_000),
  ValuationRow(category: 'Beverage', skus: 208, qty: 9_310, value: 48_320_000),
  ValuationRow(category: 'Stationery', skus: 185, qty: 3_240, value: 20_320_000),
];

/// Stock movement summary for the stock report.
const int kReceiptsThisMonth = 84;
const int kIssuesThisMonth = 1_147;
const int kAdjustmentsThisMonth = 12;
const num kShrinkageValue = 1_840_000;

// ---------------------------------------------------------------------------
// Till sessions
// ---------------------------------------------------------------------------

class TillSession {
  const TillSession({
    required this.till,
    required this.cashier,
    required this.openedAt,
    required this.sales,
    required this.cash,
    required this.transactions,
  });

  final String till;
  final String cashier;
  final String openedAt;
  final num sales;
  final num cash;
  final int transactions;
}

const List<TillSession> kOpenSessions = <TillSession>[
  TillSession(
      till: 'Till 1',
      cashier: 'Sabina Aloyce',
      openedAt: '07:58',
      sales: 3_180_000,
      cash: 2_240_000,
      transactions: 28),
  TillSession(
      till: 'Till 2',
      cashier: 'John Komba',
      openedAt: '08:05',
      sales: 2_640_000,
      cash: 1_910_000,
      transactions: 22),
  TillSession(
      till: 'Counter 3',
      cashier: 'Hamisi Ngassa',
      openedAt: '09:12',
      sales: 1_120_000,
      cash: 970_000,
      transactions: 13),
];

// ---------------------------------------------------------------------------
// Recent activity — shown on the dashboard
// ---------------------------------------------------------------------------

const List<({String title, String detail, String time, String kind})>
    kRecentActivity =
    <({String title, String detail, String time, String kind})>[
  (
    title: 'Goods received',
    detail: 'Mbasha Holdings · 40 bags cement',
    time: '11:42',
    kind: 'receive'
  ),
  (
    title: 'Stock adjusted',
    detail: 'Bar Soap 800g · −6 ctn · damaged',
    time: '10:15',
    kind: 'adjust'
  ),
  (
    title: 'Item created',
    detail: 'Rice 25kg — Mbeya',
    time: '09:30',
    kind: 'item'
  ),
  (
    title: 'Session closed',
    detail: 'Till 4 · Amina Mwanga · balanced',
    time: '08:02',
    kind: 'session'
  ),
];

// ---------------------------------------------------------------------------
// Reasons for a stock adjustment
// ---------------------------------------------------------------------------

const List<String> kAdjustmentReasons = <String>[
  'Damaged',
  'Expired',
  'Stock count correction',
  'Theft or loss',
  'Sample or donation',
  'Opening balance',
];

// ---------------------------------------------------------------------------
// Till reports — X (read, session stays open) and Z (end of day, closes it)
// ---------------------------------------------------------------------------

class TenderLine {
  const TenderLine(this.label, this.amount, this.count);

  final String label;
  final num amount;
  final int count;
}

/// Tender split for a till read. Sums to the session's sales.
const List<TenderLine> kTenderSplit = <TenderLine>[
  TenderLine('Cash', 2_240_000, 19),
  TenderLine('Mobile money', 690_000, 6),
  TenderLine('Card', 180_000, 2),
  TenderLine('Cheque', 70_000, 1),
];

/// Figures a till read carries beyond the tender split.
const num kOpeningFloat = 100_000;
const num kCashIn = 0;
const num kCashOut = 60_000;
const int kVoidCount = 2;
const num kVoidValue = 84_000;
const int kRefundCount = 1;
const num kRefundValue = 31_000;
const num kDiscountValue = 47_500;
const num kVatCollected = 485_085;

/// The last Z number issued on this till, and how many X reads since.
const int kLastZNumber = 418;
const String kLastZAt = '18 Aug 2026, 20:41';
const int kXReadsToday = 2;
