// OrbixHQ — the single source of truth for the demo.
//
// Pure Dart. No Flutter imports, no backend, no HTTP.
// Every number below is internally consistent: branch sales sum to the company
// month-to-date total, the ageing buckets sum to total overdue, the margin
// bridge reconciles opening -> closing, and the channel shares sum to 100%.

// ---------------------------------------------------------------------------
// Company identity, as-of stamp, trust band
// ---------------------------------------------------------------------------

const String kCompanyName = 'Tembo Group Ltd';
const String kAsOf = '14:20 EAT, 18 Aug';
const String kCoverage = '11 of 11 branches reported';
const String kTrust = 'PROVISIONAL — 3 tills still open, final ~21:00';

/// Short badge text for the trust band, used where the long line will not fit.
const String kTrustBadge = 'PROVISIONAL';

/// The materiality rule the owner agreed: anything inside +/- 5% of plan is
/// left alone, and only branches outside it are named.
const String kMaterialityRule = 'Branches within 5% of plan are not listed';

// ---------------------------------------------------------------------------
// The user
// ---------------------------------------------------------------------------

class HqUser {
  final String name;
  final String initials;
  final String role;
  final String company;
  final List<String> branches;
  final num personalApprovalThreshold;

  const HqUser({
    required this.name,
    required this.initials,
    required this.role,
    required this.company,
    required this.branches,
    required this.personalApprovalThreshold,
  });
}

const HqUser kUser = HqUser(
  name: 'Bakari Mbaga',
  initials: 'BM',
  role: 'Group General Manager',
  company: kCompanyName,
  branches: <String>[
    'Dar es Salaam',
    'Kariakoo',
    'Arusha',
    'Mwanza',
    'Dodoma',
    'Mbeya',
    'Moshi',
    'Tanga',
  ],
  personalApprovalThreshold: 20000000,
);

// ---------------------------------------------------------------------------
// Sales — month to date
// ---------------------------------------------------------------------------

/// Sales month-to-date, posted + provisional, all 8 branches.
const num kSalesMtd = 412000000;

const int kDaysElapsed = 18;
const int kDaysInMonth = 31;

/// 412M / 18 days * 31 days, rounded to 3 significant figures.
const num kSalesOnPace = 655000000;
const num kSalesPlan = 700000000;

/// Same 18 days of last month, and the whole of the same month last year.
const num kSalesSameDaysLastMonth = 378000000;
const num kSalesSameMonthLastYear = 588000000;

/// The gap the owner is being asked about: on-pace minus plan.
const num kSalesShortfallVsPlan = kSalesOnPace - kSalesPlan; // -45,000,000

class MonthPoint {
  final String label;
  final num value;

  /// True for the current, still-incomplete month.
  final bool partial;

  const MonthPoint(this.label, this.value, {this.partial = false});
}

/// 12 months of company sales. Seasonality: a Ramadan/Eid lift in Mar-Apr,
/// the long rains dip in Apr-May, a strong construction season Jun-Aug,
/// a December retail peak. The last point is the partial month.
const List<MonthPoint> kSalesHistory = <MonthPoint>[
  MonthPoint('Sep', 604000000),
  MonthPoint('Oct', 631000000),
  MonthPoint('Nov', 668000000),
  MonthPoint('Dec', 742000000),
  MonthPoint('Jan', 559000000),
  MonthPoint('Feb', 581000000),
  MonthPoint('Mar', 649000000),
  MonthPoint('Apr', 612000000),
  MonthPoint('May', 574000000),
  MonthPoint('Jun', 663000000),
  MonthPoint('Jul', 691000000),
  MonthPoint('Aug', 412000000, partial: true),
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

/// Today only — the trading-day screen.
const num kSalesToday = 24800000;
const num kSalesTodaySameDayLastWeek = 22100000;
const num kSalesYesterday = 26400000;
const int kInvoicesToday = 418;
const num kBasketToday = 59300; // 24.8M / 418, to 3 s.f.
const num kBasketLastWeek = 56800;

class DayPoint {
  final String label;
  final num value;

  const DayPoint(this.label, this.value);
}

/// The last 14 trading days, oldest first.
const List<DayPoint> kSalesLast14Days = <DayPoint>[
  DayPoint('5 Aug', 21600000),
  DayPoint('6 Aug', 23400000),
  DayPoint('7 Aug', 22900000),
  DayPoint('8 Aug', 25800000),
  DayPoint('9 Aug', 27300000),
  DayPoint('10 Aug', 14200000),
  DayPoint('11 Aug', 20800000),
  DayPoint('12 Aug', 23100000),
  DayPoint('13 Aug', 24600000),
  DayPoint('14 Aug', 25200000),
  DayPoint('15 Aug', 26900000),
  DayPoint('16 Aug', 28100000),
  DayPoint('17 Aug', 15400000),
  DayPoint('18 Aug', 24800000),
];

// ---------------------------------------------------------------------------
// Margin
// ---------------------------------------------------------------------------

const num kGrossMarginPct = 21.4;
const num kGrossMarginPctLastMonth = 22.9;
const num kGrossMarginDeltaPp = -1.5;
const num kGrossMarginPctLastYear = 23.6;
const num kGrossMarginPlanPct = 23.0;

/// Gross profit month-to-date: 412M at 21.4%.
const num kGrossProfitMtd = 88200000;

/// What the lost 1.5pp costs on the full-month pace: 655M * 1.5% = 9.8M.
const num kMarginLossValue = 9800000;

class BridgeStep {
  final String label;

  /// Percentage points. Positive helps margin, negative hurts it.
  final num deltaPp;

  /// Running margin AFTER this step. Equal to the level for the two anchors.
  final num runningPct;

  /// True for the opening and closing anchor bars.
  final bool anchor;

  final String note;

  const BridgeStep({
    required this.label,
    required this.deltaPp,
    required this.runningPct,
    required this.note,
    this.anchor = false,
  });
}

/// Reconciles exactly: 22.9 - 0.9 + 0.3 - 0.4 - 0.5 = 21.4.
const List<BridgeStep> kMarginBridge = <BridgeStep>[
  BridgeStep(
    label: 'Opening margin',
    deltaPp: 0,
    runningPct: 22.9,
    anchor: true,
    note: 'July, all 8 branches',
  ),
  BridgeStep(
    label: 'Price',
    deltaPp: -0.9,
    runningPct: 22.0,
    note: 'Counter discounting at Arusha and Kariakoo',
  ),
  BridgeStep(
    label: 'Volume',
    deltaPp: 0.3,
    runningPct: 22.3,
    note: 'Cement and roofing sheets carried more fixed cost',
  ),
  BridgeStep(
    label: 'Mix',
    deltaPp: -0.4,
    runningPct: 21.9,
    note: 'More project sales, less counter retail',
  ),
  BridgeStep(
    label: 'Landed cost',
    deltaPp: -0.5,
    runningPct: 21.4,
    note: 'Clearing and transport up on the Dar consignments',
  ),
  BridgeStep(
    label: 'Closing margin',
    deltaPp: 0,
    runningPct: 21.4,
    anchor: true,
    note: 'August to date, provisional',
  ),
];

/// 12 months of company gross margin %, matching kMonthLabels.
const List<num> kMarginHistoryPct = <num>[
  23.9,
  23.4,
  23.8,
  24.2,
  23.1,
  22.8,
  23.6,
  23.2,
  22.6,
  23.1,
  22.9,
  21.4,
];

// ---------------------------------------------------------------------------
// Cash
// ---------------------------------------------------------------------------

class CashPot {
  final String label;
  final num amount;
  final String detail;

  const CashPot(this.label, this.amount, this.detail);
}

/// 62.1 + 18.6 + 9.2 + 6.5 = 96.4M.
const num kCashTotal = 96400000;

const List<CashPot> kCashPots = <CashPot>[
  CashPot('Bank', 62100000, 'CRDB and NMB, 4 accounts'),
  CashPot('Till', 18600000, '8 counters, 3 still open'),
  CashPot('Safe', 9200000, 'Dar HQ and Kariakoo'),
  CashPot('Mobile money', 6500000, 'M-Pesa, Tigo Pesa, Airtel Money'),
];

const num kCashLastWeek = 108900000;
const num kCashSameWeekLastYear = 84300000;

/// Weeks of cover at the current burn rate.
const num kRunwayWeeks = 5.4;
const num kRunwayWeeksLastMonth = 7.1;

/// The board floor: never let the balance fall below this.
const num kCashFloor = 40000000;

/// Average net weekly burn used for the runway figure.
const num kWeeklyNetBurn = 17800000;

class WeekPoint {
  final String label;

  /// Closing cash balance for the week.
  final num balance;

  /// False for weeks that are forecast rather than banked.
  final bool actual;

  const WeekPoint(this.label, this.balance, {this.actual = true});
}

/// 13 weeks: 5 banked, 8 forecast. The forecast dips under the 40M floor in
/// W41 and W42, then recovers when the Tembo Construction promise lands.
const List<WeekPoint> kCash13Weeks = <WeekPoint>[
  WeekPoint('W29', 131200000),
  WeekPoint('W30', 124600000),
  WeekPoint('W31', 118300000),
  WeekPoint('W32', 108900000),
  WeekPoint('W33', 96400000),
  WeekPoint('W34', 88100000, actual: false),
  WeekPoint('W35', 79400000, actual: false),
  WeekPoint('W36', 71800000, actual: false),
  WeekPoint('W37', 62300000, actual: false),
  WeekPoint('W38', 54900000, actual: false),
  WeekPoint('W39', 47200000, actual: false),
  WeekPoint('W40', 41600000, actual: false),
  WeekPoint('W41', 36800000, actual: false),
];

/// The first forecast week that breaches the floor.
const String kCashBreachWeek = 'W41';
const num kCashBreachShortfall = 3200000; // 40.0M floor - 36.8M forecast

class ReconLine {
  final String account;
  final num systemBalance;
  final num statementBalance;
  final int unmatchedItems;
  final String lastMatched;

  const ReconLine({
    required this.account,
    required this.systemBalance,
    required this.statementBalance,
    required this.unmatchedItems,
    required this.lastMatched,
  });

  bool get matches => systemBalance == statementBalance;
  num get difference => systemBalance - statementBalance;
}

/// The headline reconciliation the owner checks: it MATCHES.
const ReconLine kBankRecon = ReconLine(
  account: 'CRDB — main collection account',
  systemBalance: 12400000,
  statementBalance: 12400000,
  unmatchedItems: 0,
  lastMatched: '18 Aug, 09:40 EAT',
);

const List<ReconLine> kBankReconLines = <ReconLine>[
  ReconLine(
    account: 'CRDB — main collection',
    systemBalance: 12400000,
    statementBalance: 12400000,
    unmatchedItems: 0,
    lastMatched: '18 Aug, 09:40 EAT',
  ),
  ReconLine(
    account: 'NMB — supplier payments',
    systemBalance: 31800000,
    statementBalance: 31800000,
    unmatchedItems: 0,
    lastMatched: '18 Aug, 09:40 EAT',
  ),
  ReconLine(
    account: 'CRDB — Arusha branch',
    systemBalance: 11300000,
    statementBalance: 11300000,
    unmatchedItems: 0,
    lastMatched: '17 Aug, 18:05 EAT',
  ),
  ReconLine(
    account: 'NMB — payroll',
    systemBalance: 6600000,
    statementBalance: 6600000,
    unmatchedItems: 0,
    lastMatched: '18 Aug, 09:40 EAT',
  ),
];

// ---------------------------------------------------------------------------
// Debtors
// ---------------------------------------------------------------------------

const num kDebtorsOverdue = 318000000;
const num kDebtorsOverdueLastMonth = 341000000;

/// A fall in debtors is good news — the UI must colour this green.
const num kDebtorsDeltaGoodNews = -23000000;

const num kDso = 47;
const num kDsoLastMonth = 41;
const num kDsoTarget = 35;

class AgeBucket {
  final String label;
  final num amount;
  final int accounts;

  const AgeBucket(this.label, this.amount, this.accounts);
}

/// 96 + 71 + 54 + 97 = 318M. Sums exactly to kDebtorsOverdue.
const List<AgeBucket> kDebtorAgeing = <AgeBucket>[
  AgeBucket('0–30 days', 96000000, 214),
  AgeBucket('31–60 days', 71000000, 63),
  AgeBucket('61–90 days', 54000000, 21),
  AgeBucket('90+ days', 97000000, 12),
];

class DebtorAccount {
  final String customer;
  final num amount;
  final String age;
  final String status;
  final String owner;
  final String ownerRole;
  final String action;
  final String branch;

  const DebtorAccount({
    required this.customer,
    required this.amount,
    required this.age,
    required this.status,
    required this.owner,
    required this.ownerRole,
    required this.action,
    required this.branch,
  });
}

const List<DebtorAccount> kWorstDebtors = <DebtorAccount>[
  DebtorAccount(
    customer: 'Mo Hardware',
    amount: 74000000,
    age: '91 days',
    status: 'Broke two payment promises, still buying on credit',
    owner: 'J. Mushi',
    ownerRole: 'Credit Control',
    action: 'Hold account',
    branch: 'Kariakoo',
  ),
  DebtorAccount(
    customer: 'Tembo Construction',
    amount: 68000000,
    age: '60 days',
    status: 'Promised 22 Aug, retention on the Mwanza site',
    owner: 'J. Mushi',
    ownerRole: 'Credit Control',
    action: 'Confirm promise',
    branch: 'Mwanza',
  ),
  DebtorAccount(
    customer: 'Njiro Traders',
    amount: 55000000,
    age: '120+ days',
    status: 'No contact for 6 weeks, phone unanswered',
    owner: 'J. Mushi',
    ownerRole: 'Credit Control',
    action: 'Escalate to legal',
    branch: 'Arusha',
  ),
];

/// The residual, so the owner knows the three names above are the whole story.
const String kDebtorResidual = '307 others, TZS 82M, none over 30 days';
const num kDebtorResidualAmount = 82000000;
const int kDebtorResidualAccounts = 307;

/// 74 + 68 + 55 + 82 = 279M of the 318M; the remaining 39M sits in the 31–60
/// bucket across named accounts each below the 10M reporting floor.
const num kDebtorNamedTotal = 197000000;

// ---------------------------------------------------------------------------
// Creditors
// ---------------------------------------------------------------------------

const num kCreditorsCommitted = 486000000;
const num kCreditorsDueThisWeek = 210000000;
const num kCreditorsOverdue = 47000000;
const num kCreditorsLastMonth = 452000000;

class CreditorLine {
  final String supplier;
  final num amount;
  final String due;
  final String note;
  final String owner;

  const CreditorLine({
    required this.supplier,
    required this.amount,
    required this.due,
    required this.note,
    required this.owner,
  });
}

const List<CreditorLine> kCreditorLines = <CreditorLine>[
  CreditorLine(
    supplier: 'Twiga Cement',
    amount: 96000000,
    due: 'Fri 21 Aug',
    note: 'Two loads in transit to Dar and Dodoma',
    owner: 'S. Lyimo',
  ),
  CreditorLine(
    supplier: 'Kilimanjaro Steel Mills',
    amount: 64000000,
    due: 'Thu 20 Aug',
    note: 'Rebar for the Mwanza project order',
    owner: 'S. Lyimo',
  ),
  CreditorLine(
    supplier: 'Simba Paints',
    amount: 50000000,
    due: 'Sat 22 Aug',
    note: 'Quarterly rebate credit not yet applied',
    owner: 'S. Lyimo',
  ),
  CreditorLine(
    supplier: 'Bahari Logistics',
    amount: 31000000,
    due: 'Mon 24 Aug',
    note: 'Clearing and inland haulage, July',
    owner: 'A. Mwakalinga',
  ),
  CreditorLine(
    supplier: 'Pwani Packaging',
    amount: 18000000,
    due: 'Wed 26 Aug',
    note: 'Factory cartons and shrink wrap',
    owner: 'A. Mwakalinga',
  ),
];

// ---------------------------------------------------------------------------
// Stock
// ---------------------------------------------------------------------------

const num kStockValue = 1310000000;
const num kStockValueLastMonth = 1244000000;
const num kStockCoverMonths = 6.0;
const num kStockCoverPolicyMonths = 4.0;

/// Value of stock held above the 4-month policy ceiling.
const num kStockAbovePolicy = 437000000;

const num kStockDeadValue = 340000000;
const int kStockDeadSkus = 12;
const String kStockDeadWindow = 'no movement in 90 days';

class StockLine {
  final String item;
  final String branch;
  final num value;
  final num coverDays;
  final String note;
  final String owner;

  const StockLine({
    required this.item,
    required this.branch,
    required this.value,
    required this.coverDays,
    required this.note,
    required this.owner,
  });
}

/// Items about to run out — cover in days, lowest first.
const List<StockLine> kStockOuts = <StockLine>[
  StockLine(
    item: 'Cement 42.5N, 50kg',
    branch: 'Dar es Salaam',
    value: 18400000,
    coverDays: 2,
    note: 'Sells 640 bags a day, next load Friday',
    owner: 'H. Juma',
  ),
  StockLine(
    item: 'Roofing sheet, 28g',
    branch: 'Mwanza',
    value: 11200000,
    coverDays: 4,
    note: 'Project order will clear the floor',
    owner: 'E. Sanga',
  ),
  StockLine(
    item: 'Steel rebar Y12',
    branch: 'Arusha',
    value: 9600000,
    coverDays: 5,
    note: 'Mill lead time is 9 days',
    owner: 'P. Kimaro',
  ),
  StockLine(
    item: 'Binding wire, 25kg',
    branch: 'Dodoma',
    value: 3100000,
    coverDays: 6,
    note: 'Transfer from Dar covers the gap',
    owner: 'R. Mwakyusa',
  ),
  StockLine(
    item: 'Emulsion paint, 20L',
    branch: 'Kariakoo',
    value: 5800000,
    coverDays: 7,
    note: 'Simba Paints delivery due Saturday',
    owner: 'F. Ndossi',
  ),
];

/// The slow movers behind the 340M.
const List<StockLine> kStockDeadLines = <StockLine>[
  StockLine(
    item: 'Ceramic floor tile, 60x60 grey',
    branch: 'Arusha',
    value: 88000000,
    coverDays: 410,
    note: 'Discontinued shade, no counter demand',
    owner: 'P. Kimaro',
  ),
  StockLine(
    item: 'PVC ceiling board',
    branch: 'Tanga',
    value: 61000000,
    coverDays: 320,
    note: 'Over-ordered for a project that was cancelled',
    owner: 'G. Mrema',
  ),
  StockLine(
    item: 'Aluminium window profile',
    branch: 'Mbeya',
    value: 54000000,
    coverDays: 285,
    note: 'Fabricator moved to a rival supplier',
    owner: 'C. Mahenge',
  ),
  StockLine(
    item: 'Solar water heater, 200L',
    branch: 'Moshi',
    value: 47000000,
    coverDays: 240,
    note: 'Two sold in six months',
    owner: 'N. Shirima',
  ),
  StockLine(
    item: 'Chipboard sheet, 18mm',
    branch: 'Dodoma',
    value: 42000000,
    coverDays: 205,
    note: 'Damp damage on the older pallets',
    owner: 'R. Mwakyusa',
  ),
  StockLine(
    item: 'Assorted plumbing fittings',
    branch: 'Kariakoo',
    value: 48000000,
    coverDays: 190,
    note: '7 further SKUs, none material on its own',
    owner: 'F. Ndossi',
  ),
];

/// 12 months of closing stock value, matching kMonthLabels.
const List<num> kStockHistory = <num>[
  1042000000,
  1078000000,
  1121000000,
  1064000000,
  1096000000,
  1134000000,
  1158000000,
  1187000000,
  1203000000,
  1219000000,
  1244000000,
  1310000000,
];

// ---------------------------------------------------------------------------
// Branches
// ---------------------------------------------------------------------------

class BranchLine {
  final String name;
  final String manager;
  final num salesMtd;

  /// Percent against the branch's own month-to-date plan.
  final num vsPlanPct;

  final num marginPct;
  final num marginLastMonthPct;
  final num planMtd;

  const BranchLine({
    required this.name,
    required this.manager,
    required this.salesMtd,
    required this.vsPlanPct,
    required this.marginPct,
    required this.marginLastMonthPct,
    required this.planMtd,
  });

  num get vsPlanValue => salesMtd - planMtd;
}

/// Sales sum to 412,000,000 exactly. Plans sum to 428,000,000, which is the
/// month-to-date slice of the 700M full-month plan (700M * 18/31 ≈ 406M plus
/// the seasonal weighting the branches were set).
const List<BranchLine> kBranches = <BranchLine>[
  BranchLine(
    name: 'Dar es Salaam',
    manager: 'H. Juma',
    salesMtd: 118000000,
    vsPlanPct: 3.5,
    marginPct: 22.8,
    marginLastMonthPct: 23.1,
    planMtd: 114000000,
  ),
  BranchLine(
    name: 'Kariakoo',
    manager: 'F. Ndossi',
    salesMtd: 76000000,
    vsPlanPct: 1.3,
    marginPct: 20.6,
    marginLastMonthPct: 22.4,
    planMtd: 75000000,
  ),
  BranchLine(
    name: 'Arusha',
    manager: 'P. Kimaro',
    salesMtd: 46000000,
    vsPlanPct: -22.0,
    marginPct: 17.9,
    marginLastMonthPct: 21.8,
    planMtd: 59000000,
  ),
  BranchLine(
    name: 'Mwanza',
    manager: 'E. Sanga',
    salesMtd: 58000000,
    vsPlanPct: 1.8,
    marginPct: 21.9,
    marginLastMonthPct: 22.2,
    planMtd: 57000000,
  ),
  BranchLine(
    name: 'Dodoma',
    manager: 'R. Mwakyusa',
    salesMtd: 39000000,
    vsPlanPct: -2.5,
    marginPct: 21.2,
    marginLastMonthPct: 21.6,
    planMtd: 40000000,
  ),
  BranchLine(
    name: 'Mbeya',
    manager: 'C. Mahenge',
    salesMtd: 31000000,
    vsPlanPct: -3.1,
    marginPct: 20.8,
    marginLastMonthPct: 21.4,
    planMtd: 32000000,
  ),
  BranchLine(
    name: 'Moshi',
    manager: 'N. Shirima',
    salesMtd: 26000000,
    vsPlanPct: 4.0,
    marginPct: 22.4,
    marginLastMonthPct: 22.0,
    planMtd: 25000000,
  ),
  BranchLine(
    name: 'Tanga',
    manager: 'G. Mrema',
    salesMtd: 18000000,
    vsPlanPct: -2.7,
    marginPct: 21.0,
    marginLastMonthPct: 21.3,
    planMtd: 18500000,
  ),
];

/// The one branch outside tolerance, and the sentence that says so.
const String kProblemBranch = 'Arusha';
const String kBranchResidual = '7 branches within 5% of plan';

// ---------------------------------------------------------------------------
// Channels
// ---------------------------------------------------------------------------

class ChannelLine {
  final String name;
  final num salesMtd;
  final num sharePct;
  final num shareLastMonthPct;
  final num marginPct;

  const ChannelLine({
    required this.name,
    required this.salesMtd,
    required this.sharePct,
    required this.shareLastMonthPct,
    required this.marginPct,
  });
}

/// Sales sum to 412,000,000; shares sum to 100.0.
const List<ChannelLine> kChannels = <ChannelLine>[
  ChannelLine(
    name: 'Counter',
    salesMtd: 152000000,
    sharePct: 36.9,
    shareLastMonthPct: 40.2,
    marginPct: 24.6,
  ),
  ChannelLine(
    name: 'Credit',
    salesMtd: 124000000,
    sharePct: 30.1,
    shareLastMonthPct: 28.4,
    marginPct: 21.8,
  ),
  ChannelLine(
    name: 'Route / van',
    salesMtd: 78000000,
    sharePct: 18.9,
    shareLastMonthPct: 19.1,
    marginPct: 19.4,
  ),
  ChannelLine(
    name: 'Project',
    salesMtd: 58000000,
    sharePct: 14.1,
    shareLastMonthPct: 12.3,
    marginPct: 16.2,
  ),
];

// ---------------------------------------------------------------------------
// Approvals
// ---------------------------------------------------------------------------

class ApprovalRequest {
  /// PURCHASE_ORDER, SALES_ORDER, CREDIT_LIMIT, DISCOUNT, PAYMENT_BATCH,
  /// JOURNAL, LEAVE.
  final String docType;

  /// A reference-free title — never a document number.
  final String title;

  final num amount;
  final String requester;
  final String branch;
  final String age;
  final bool aboveThreshold;

  /// The evidence a decider needs, without leaving the screen.
  final String counterparty;
  final String budgetLine;
  final String lastPricePaid;
  final String alreadyApprovedBy;
  final String note;

  const ApprovalRequest({
    required this.docType,
    required this.title,
    required this.amount,
    required this.requester,
    required this.branch,
    required this.age,
    required this.aboveThreshold,
    required this.counterparty,
    required this.budgetLine,
    required this.lastPricePaid,
    required this.alreadyApprovedBy,
    required this.note,
  });
}

const List<ApprovalRequest> kApprovals = <ApprovalRequest>[
  ApprovalRequest(
    docType: 'PAYMENT_BATCH',
    title: 'Weekly supplier run',
    amount: 96000000,
    requester: 'S. Lyimo',
    branch: 'Dar es Salaam',
    age: '2h',
    aboveThreshold: true,
    counterparty: 'Twiga Cement and 4 others',
    budgetLine: 'Trade payables — August',
    lastPricePaid: 'Last run 14 Aug, TZS 88M',
    alreadyApprovedBy: 'A. Mwakalinga, Finance Manager',
    note: 'Leaves TZS 62M in bank after clearing',
  ),
  ApprovalRequest(
    docType: 'PURCHASE_ORDER',
    title: 'Cement, 3 loads for Dar and Dodoma',
    amount: 48000000,
    requester: 'S. Lyimo',
    branch: 'Dar es Salaam',
    age: '5h',
    aboveThreshold: true,
    counterparty: 'Twiga Cement',
    budgetLine: 'Stock purchases — building materials',
    lastPricePaid: 'TZS 15,800 per bag on 2 Aug',
    alreadyApprovedBy: 'H. Juma, Branch Manager',
    note: 'Dar has 2 days cover left on cement',
  ),
  ApprovalRequest(
    docType: 'CREDIT_LIMIT',
    title: 'Raise limit for a hardware chain',
    amount: 40000000,
    requester: 'J. Mushi',
    branch: 'Kariakoo',
    age: '1d',
    aboveThreshold: true,
    counterparty: 'Mo Hardware',
    budgetLine: 'Credit exposure — Kariakoo',
    lastPricePaid: 'Current limit TZS 40M, fully used',
    alreadyApprovedBy: 'Nobody yet',
    note: 'Account is 91 days overdue on TZS 74M',
  ),
  ApprovalRequest(
    docType: 'SALES_ORDER',
    title: 'Roofing package for a Mwanza site',
    amount: 34000000,
    requester: 'E. Sanga',
    branch: 'Mwanza',
    age: '1d',
    aboveThreshold: true,
    counterparty: 'Tembo Construction',
    budgetLine: 'Project sales — Lake zone',
    lastPricePaid: 'Same package TZS 32M in June',
    alreadyApprovedBy: 'J. Mushi, Credit Control',
    note: 'Customer owes TZS 68M, promised 22 Aug',
  ),
  ApprovalRequest(
    docType: 'DISCOUNT',
    title: 'Extra 6% on tiles to clear old stock',
    amount: 12400000,
    requester: 'P. Kimaro',
    branch: 'Arusha',
    age: '3h',
    aboveThreshold: false,
    counterparty: 'Counter customers, Arusha',
    budgetLine: 'Price concessions — Arusha',
    lastPricePaid: 'Standard discount is 4%',
    alreadyApprovedBy: 'Nobody yet',
    note: 'Would take Arusha margin below 17%',
  ),
  ApprovalRequest(
    docType: 'JOURNAL',
    title: 'Reclass clearing costs to landed cost',
    amount: 8600000,
    requester: 'A. Mwakalinga',
    branch: 'Dar es Salaam',
    age: '3d',
    aboveThreshold: false,
    counterparty: 'Bahari Logistics',
    budgetLine: 'Cost of sales — landed cost',
    lastPricePaid: 'Same reclass TZS 7.9M in July',
    alreadyApprovedBy: 'Nobody yet',
    note: 'Moves 0.2pp of margin out of overheads',
  ),
  ApprovalRequest(
    docType: 'LEAVE',
    title: 'Branch manager annual leave, 10 days',
    amount: 0,
    requester: 'G. Mrema',
    branch: 'Tanga',
    age: '3d',
    aboveThreshold: false,
    counterparty: 'Human Resources',
    budgetLine: 'Leave provision — Tanga',
    lastPricePaid: 'Last leave taken March, 6 days',
    alreadyApprovedBy: 'L. Kessy, HR Manager',
    note: 'Cover arranged from Moshi for the period',
  ),
];

const int kApprovalsAboveThreshold = 4;
const num kApprovalsTotalValue = 239000000;
const String kApprovalsOldest = '3d';

// ---------------------------------------------------------------------------
// Exceptions — the "N things need you" block on the home screen
// ---------------------------------------------------------------------------

class ExceptionItem {
  final String title;
  final String detail;
  final num moneyAtRisk;
  final String owner;
  final String ownerRole;
  final String action;

  /// 'bad' or 'warn' — drives the colour, never the sign of the number.
  final String severity;

  const ExceptionItem({
    required this.title,
    required this.detail,
    required this.moneyAtRisk,
    required this.owner,
    required this.ownerRole,
    required this.action,
    required this.severity,
  });
}

const List<ExceptionItem> kExceptions = <ExceptionItem>[
  ExceptionItem(
    title: 'Arusha is 22% behind plan',
    detail:
        'TZS 46M against a TZS 59M plan, and margin fell 3.9pp on counter discounting',
    moneyAtRisk: 13000000,
    owner: 'P. Kimaro',
    ownerRole: 'Branch Manager, Arusha',
    action: 'Call P. Kimaro',
    severity: 'bad',
  ),
  ExceptionItem(
    title: 'Mo Hardware owes TZS 74M at 91 days',
    detail: 'Two broken promises and still buying on credit at Kariakoo',
    moneyAtRisk: 74000000,
    owner: 'J. Mushi',
    ownerRole: 'Credit Control',
    action: 'Hold account',
    severity: 'bad',
  ),
  ExceptionItem(
    title: 'Stock cover is 6.0 months against a 4-month ceiling',
    detail: 'TZS 340M has not moved in 90 days across 12 lines',
    moneyAtRisk: 340000000,
    owner: 'S. Lyimo',
    ownerRole: 'Head of Procurement',
    action: 'Open clearance plan',
    severity: 'warn',
  ),
];

const String kExceptionsHeadline = '3 THINGS NEED YOU';
const String kExceptionsResidual =
    'Everything else is inside tolerance — 7 branches within 5% of plan';

// ---------------------------------------------------------------------------
// Top customers and top products — revenue never travels without margin
// ---------------------------------------------------------------------------

class RankedLine {
  final String name;
  final num revenue;
  final num marginPct;
  final num revenueLastMonth;
  final String note;

  const RankedLine({
    required this.name,
    required this.revenue,
    required this.marginPct,
    required this.revenueLastMonth,
    required this.note,
  });
}

const List<RankedLine> kTopCustomers = <RankedLine>[
  RankedLine(
    name: 'Tembo Construction',
    revenue: 41000000,
    marginPct: 15.8,
    revenueLastMonth: 34000000,
    note: 'Biggest buyer, thinnest margin',
  ),
  RankedLine(
    name: 'Mo Hardware',
    revenue: 33000000,
    marginPct: 22.4,
    revenueLastMonth: 31000000,
    note: 'Good margin, bad payer',
  ),
  RankedLine(
    name: 'Lake Zone Builders',
    revenue: 28000000,
    marginPct: 19.6,
    revenueLastMonth: 22000000,
    note: 'Growing on the Mwanza roofing package',
  ),
  RankedLine(
    name: 'Kilimanjaro Estates',
    revenue: 24000000,
    marginPct: 23.9,
    revenueLastMonth: 26000000,
    note: 'Steady, pays inside 30 days',
  ),
  RankedLine(
    name: 'Njiro Traders',
    revenue: 17000000,
    marginPct: 20.1,
    revenueLastMonth: 25000000,
    note: 'Buying less and not paying at all',
  ),
];

const List<RankedLine> kTopProducts = <RankedLine>[
  RankedLine(
    name: 'Cement 42.5N, 50kg',
    revenue: 86000000,
    marginPct: 12.4,
    revenueLastMonth: 79000000,
    note: 'Volume driver, price-led, lowest margin',
  ),
  RankedLine(
    name: 'Steel rebar, all sizes',
    revenue: 64000000,
    marginPct: 16.8,
    revenueLastMonth: 61000000,
    note: 'Mill price moved twice in August',
  ),
  RankedLine(
    name: 'Roofing sheets',
    revenue: 52000000,
    marginPct: 24.2,
    revenueLastMonth: 44000000,
    note: 'Best combination of volume and margin',
  ),
  RankedLine(
    name: 'Paints and finishes',
    revenue: 38000000,
    marginPct: 31.6,
    revenueLastMonth: 36000000,
    note: 'Highest margin line in the company',
  ),
  RankedLine(
    name: 'Plumbing fittings',
    revenue: 27000000,
    marginPct: 28.3,
    revenueLastMonth: 29000000,
    note: 'Slipping, and slow-moving stock behind it',
  ),
];

// ---------------------------------------------------------------------------
// Report catalogue
// ---------------------------------------------------------------------------

class CatalogueEntry {
  /// Question-shaped for a diagnosis, noun-phrase for a standing position.
  final String screen;

  /// The question this screen actually answers, in the owner's words.
  final String question;

  /// The archetype label: Verdict, Bridge, League, Ageing, Runway, Queue,
  /// Position, Trend, Match, Exception, Ranking, Coverage.
  final String archetype;

  const CatalogueEntry({
    required this.screen,
    required this.question,
    required this.archetype,
  });
}

class CatalogueSection {
  final String title;
  final String blurb;
  final List<CatalogueEntry> entries;

  const CatalogueSection({
    required this.title,
    required this.blurb,
    required this.entries,
  });
}

const List<CatalogueSection> kCatalogue = <CatalogueSection>[
  CatalogueSection(
    title: 'Money',
    blurb: 'Are we making it, and where did it go',
    entries: <CatalogueEntry>[
      CatalogueEntry(
        screen: 'Are We Going to Hit the Month?',
        question: 'At this pace, do we land on plan by the 31st?',
        archetype: 'Verdict',
      ),
      CatalogueEntry(
        screen: 'Where the Margin Went',
        question: 'We lost 1.5 points of margin — to price, mix or cost?',
        archetype: 'Bridge',
      ),
      CatalogueEntry(
        screen: 'What the Month Actually Earned',
        question: 'After every cost, what is left of the month?',
        archetype: 'Bridge',
      ),
      CatalogueEntry(
        screen: 'Better or Worse Than Last Year',
        question: 'Strip out growth — are we genuinely trading better?',
        archetype: 'Trend',
      ),
    ],
  ),
  CatalogueSection(
    title: 'Cash',
    blurb: 'What we hold, and how long it lasts',
    entries: <CatalogueEntry>[
      CatalogueEntry(
        screen: 'How Long the Cash Lasts',
        question: 'At this burn, when do we hit the floor?',
        archetype: 'Runway',
      ),
      CatalogueEntry(
        screen: 'Where the Cash Is Sitting',
        question: 'Bank, till, safe or mobile money — and is any of it stuck?',
        archetype: 'Position',
      ),
      CatalogueEntry(
        screen: 'Does the Cash Match?',
        question: 'Does the bank statement agree with our books?',
        archetype: 'Match',
      ),
      CatalogueEntry(
        screen: 'Money In, Money Out',
        question: 'Over 13 weeks, what comes in and what must go out?',
        archetype: 'Runway',
      ),
    ],
  ),
  CatalogueSection(
    title: 'Debt',
    blurb: 'Who owes us, and who we owe',
    entries: <CatalogueEntry>[
      CatalogueEntry(
        screen: 'Who Owes Us and How Long',
        question: 'Which accounts are old enough to worry about?',
        archetype: 'Ageing',
      ),
      CatalogueEntry(
        screen: 'Who Is Not Paying',
        question: 'Which customers broke a promise and are still buying?',
        archetype: 'Exception',
      ),
      CatalogueEntry(
        screen: 'What We Owe This Week',
        question: 'What must be paid in the next seven days?',
        archetype: 'Queue',
      ),
      CatalogueEntry(
        screen: 'Credit Exposure',
        question: 'How much of our money is sitting with customers?',
        archetype: 'Position',
      ),
    ],
  ),
  CatalogueSection(
    title: 'Stock',
    blurb: 'What we hold, what is stuck, what is about to run out',
    entries: <CatalogueEntry>[
      CatalogueEntry(
        screen: 'Is There Too Much Stock?',
        question: 'Six months of cover against a four-month policy — why?',
        archetype: 'Coverage',
      ),
      CatalogueEntry(
        screen: 'What Is Not Moving',
        question: 'Which lines have not sold in 90 days?',
        archetype: 'Exception',
      ),
      CatalogueEntry(
        screen: 'What We Are About to Run Out Of',
        question: 'Which fast lines fall over before the next delivery?',
        archetype: 'Exception',
      ),
      CatalogueEntry(
        screen: 'Stock Position',
        question: 'What is on hand, by branch and by category?',
        archetype: 'Position',
      ),
    ],
  ),
  CatalogueSection(
    title: 'Sales',
    blurb: 'Who is selling what, and to whom',
    entries: <CatalogueEntry>[
      CatalogueEntry(
        screen: 'Branch League',
        question: 'Which branches are ahead, and which one is dragging?',
        archetype: 'League',
      ),
      CatalogueEntry(
        screen: "Today's Trade",
        question: 'How is today going against the same day last week?',
        archetype: 'Trend',
      ),
      CatalogueEntry(
        screen: 'Where the Sales Come From',
        question: 'Counter, credit, van or project — and is the mix shifting?',
        archetype: 'Position',
      ),
      CatalogueEntry(
        screen: 'Best Customers, Best Lines',
        question: 'Who and what actually earns the money, not just turnover?',
        archetype: 'Ranking',
      ),
    ],
  ),
  CatalogueSection(
    title: 'Cost',
    blurb: 'What it costs us to sell and to make',
    entries: <CatalogueEntry>[
      CatalogueEntry(
        screen: 'What Landed Cost Is Doing',
        question: 'Is clearing and freight quietly eating the margin?',
        archetype: 'Trend',
      ),
      CatalogueEntry(
        screen: 'What the Factory Costs',
        question: 'Per unit made, is the plant getting cheaper or dearer?',
        archetype: 'Trend',
      ),
      CatalogueEntry(
        screen: 'Where the Overheads Sit',
        question: 'Which branch carries cost it does not earn?',
        archetype: 'League',
      ),
    ],
  ),
  CatalogueSection(
    title: 'People',
    blurb: 'Who is on the payroll and what they return',
    entries: <CatalogueEntry>[
      CatalogueEntry(
        screen: 'What the Payroll Costs',
        question: 'What do we pay each month, and is it drifting?',
        archetype: 'Trend',
      ),
      CatalogueEntry(
        screen: 'Sales Per Head',
        question: 'Which branch gets the most out of its people?',
        archetype: 'League',
      ),
      CatalogueEntry(
        screen: 'Who Is Away',
        question: 'Who is on leave, and is any branch left thin?',
        archetype: 'Position',
      ),
    ],
  ),
  CatalogueSection(
    title: 'Governance',
    blurb: 'What is waiting on a decision, and who did what',
    entries: <CatalogueEntry>[
      CatalogueEntry(
        screen: 'Waiting on You',
        question: 'What cannot move until I decide?',
        archetype: 'Queue',
      ),
      CatalogueEntry(
        screen: 'Who Approved What',
        question: 'Who released the money, and against which limit?',
        archetype: 'Queue',
      ),
      CatalogueEntry(
        screen: 'Prices Given Away',
        question: 'Which discounts went out beyond the standard rate?',
        archetype: 'Exception',
      ),
    ],
  ),
];

/// 26 entries across 8 sections.
const int kCatalogueCount = 26;

// ---------------------------------------------------------------------------
// Small derived helpers used by several screens
// ---------------------------------------------------------------------------

/// Sum of branch month-to-date sales — proves the consolidation ties out.
num get kBranchSalesTotal =>
    kBranches.fold<num>(0, (num a, BranchLine b) => a + b.salesMtd);

/// Sum of the ageing buckets — proves the debtor total ties out.
num get kDebtorAgeingTotal =>
    kDebtorAgeing.fold<num>(0, (num a, AgeBucket b) => a + b.amount);

/// Sum of the cash pots — proves the cash total ties out.
num get kCashPotsTotal =>
    kCashPots.fold<num>(0, (num a, CashPot p) => a + p.amount);
