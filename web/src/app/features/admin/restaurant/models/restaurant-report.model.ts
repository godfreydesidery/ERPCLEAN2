import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Restaurant reports (J COB 2026-10-30; dashboard and summaries 2026-11-03). Money (`BigDecimal`)
 * arrives as a JSON number; counts as numbers. The waiter is whoever holds the order.
 */

export interface WaiterSalesRowDto {
  waiterUid: string | null;
  waiterName: string;
  bills: number;
  guests: number;
  amount: number;
  averageBill: number;
}

export interface WaiterSalesReportDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  waiterName: string | null;
  currency: string;
  rows: WaiterSalesRowDto[];
  totals: WaiterSalesRowDto;
  generatedAt: string;
}

export interface RestaurantBillRowDto {
  orderUid: string;
  orderNumber: string;
  servedAt: string | null;
  waiterName: string;
  cashierName: string | null;
  invoiceNumber: string | null;
  /** FINALISED, or VOID when the receipt was voided afterwards (listed, never totalled). */
  invoiceStatus: string;
  guests: number | null;
  openedAt: string | null;
  paidAt: string | null;
  amount: number;
  tenders: string | null;
}

export interface RestaurantBillsReportDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  waiterName: string | null;
  currency: string;
  rows: RestaurantBillRowDto[];
  billCount: number;
  totalAmount: number;
  generatedAt: string;
}

export interface RestaurantPendingBillRowDto {
  orderUid: string;
  orderNumber: string;
  servedAt: string | null;
  waiterName: string;
  /** OPEN, or BILLED (bill printed, not yet paid). */
  status: string;
  openedAt: string | null;
  billedAt: string | null;
  items: number;
  billedAmount: number | null;
}

export interface RestaurantPendingBillsReportDto {
  company: ReportCompanyHeaderDto;
  waiterName: string | null;
  currency: string;
  rows: RestaurantPendingBillRowDto[];
  openCount: number;
  billedCount: number;
  billedAmount: number;
  generatedAt: string;
}

/** A waiter of the branch (GET /restaurant/waiters). */
export interface RestaurantWaiterDto {
  uid: string;
  name: string;
  username: string;
}

// ── Sales by waiter, detail (GET /waiter-items) ─────────────────────────────

export interface WaiterItemRowDto {
  waiterUid: string | null;
  waiterName: string;
  code: string | null;
  name: string;
  unit: string | null;
  quantity: number;
  amount: number;
}

export interface WaiterItemSubtotalDto {
  waiterUid: string | null;
  waiterName: string;
  bills: number;
  amount: number;
}

export interface WaiterItemsReportDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  waiterName: string | null;
  currency: string;
  rows: WaiterItemRowDto[];
  waiters: WaiterItemSubtotalDto[];
  totalAmount: number;
  generatedAt: string;
}

// ── Bills paid, summary (GET /bills-summary) ────────────────────────────────

export interface RestaurantBillsSummaryRowDto {
  waiterUid: string | null;
  waiterName: string;
  bills: number;
  guests: number;
  /** Lines up with `tenderTypes`. */
  tenders: number[];
  amount: number;
}

export interface RestaurantBillsSummaryDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  waiterName: string | null;
  currency: string;
  tenderTypes: string[];
  rows: RestaurantBillsSummaryRowDto[];
  totals: RestaurantBillsSummaryRowDto;
  generatedAt: string;
}

// ── Pending bills, summary (GET /pending-summary) ───────────────────────────

export interface RestaurantPendingSummaryRowDto {
  waiterUid: string | null;
  waiterName: string;
  open: number;
  billed: number;
  items: number;
  billedAmount: number;
  oldestOpenedAt: string | null;
}

export interface RestaurantPendingSummaryDto {
  company: ReportCompanyHeaderDto;
  waiterName: string | null;
  currency: string;
  rows: RestaurantPendingSummaryRowDto[];
  totals: RestaurantPendingSummaryRowDto;
  generatedAt: string;
}

// ── Sales by hour and day of the week (GET /daypart) ────────────────────────

export interface DaypartSlotDto {
  /** "07:00-08:00", or "Monday". */
  label: string;
  receipts: number;
  sales: number;
  days: number;
  averagePerDay: number;
  /** Lines up with the report's `departments`. */
  departmentSales: number[];
}

export interface RestaurantDaypartDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  branchName: string | null;
  currency: string;
  departments: string[];
  hours: DaypartSlotDto[];
  weekdays: DaypartSlotDto[];
  totals: DaypartSlotDto;
  generatedAt: string;
}

// ── Payments & completion (GET /payments) ───────────────────────────────────

export interface PaymentsTenderDto {
  tenderType: string;
  payments: number;
  amount: number;
  /** Percentage of `totalPaid`. */
  share: number;
}

export interface PaymentsCompletionDto {
  opened: number;
  paid: number;
  cancelled: number;
  pending: number;
  /** Paid / opened as a percentage; null when nothing was opened. */
  completionRate: number | null;
}

export interface PaymentsRefundDto {
  at: string | null;
  receiptNumber: string | null;
  reason: string | null;
  by: string | null;
  amount: number;
}

export interface PaymentsVoidedReceiptDto {
  soldAt: string | null;
  receiptNumber: string | null;
  by: string | null;
  amount: number;
}

export interface PaymentsCancelledOrderDto {
  orderNumber: string;
  servedAt: string | null;
  waiterName: string | null;
  openedAt: string | null;
  cancelledAt: string | null;
  reason: string | null;
  items: number;
}

export interface PaymentsVoidedItemDto {
  voidedAt: string | null;
  orderNumber: string;
  servedAt: string | null;
  waiterName: string | null;
  productName: string;
  quantity: number;
  reason: string | null;
}

export interface RestaurantPaymentsReportDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  branchName: string | null;
  currency: string;
  tenders: PaymentsTenderDto[];
  totalPaid: number;
  completion: PaymentsCompletionDto;
  refunds: PaymentsRefundDto[];
  refundTotal: number;
  voidedReceipts: PaymentsVoidedReceiptDto[];
  voidedReceiptTotal: number;
  cancelledOrders: PaymentsCancelledOrderDto[];
  voidedItems: PaymentsVoidedItemDto[];
  generatedAt: string;
}

// ── Dashboard / Flash Report (GET /dashboard) ───────────────────────────────

export interface DashboardKpisDto {
  sales: number;
  receipts: number;
  averageReceipt: number;
  orders: number;
  guests: number;
  spendPerGuest: number;
  discounts: number;
  complimentary: number;
  refunds: number;
  voidedReceipts: number;
  cancelledOrders: number;
  voidedItems: number;
}

export interface DashboardLiveDto {
  openOrders: number;
  billedOrders: number;
  billedAmount: number;
}

export interface DashboardDayDto {
  date: string;
  receipts: number;
  sales: number;
}

export interface DashboardHourDto {
  hour: number;
  receipts: number;
  sales: number;
}

export interface DashboardWeekdayDto {
  /** 1 = Monday. */
  day: number;
  label: string;
  days: number;
  receipts: number;
  sales: number;
  averagePerDay: number;
}

export interface DashboardDepartmentDto {
  name: string;
  quantity: number;
  sales: number;
}

export interface DashboardItemDto {
  code: string | null;
  name: string;
  unit: string | null;
  quantity: number;
  sales: number;
}

export interface DashboardTenderDto {
  tenderType: string;
  payments: number;
  amount: number;
}

export interface DashboardCompDto {
  receiptNumber: string | null;
  at: string | null;
  givenTo: string | null;
  amount: number;
}

export interface RestaurantDashboardDto {
  company: ReportCompanyHeaderDto;
  fromDate: string;
  toDate: string;
  branchName: string | null;
  currency: string;
  current: DashboardKpisDto;
  previous: DashboardKpisDto;
  previousFromDate: string;
  previousToDate: string;
  live: DashboardLiveDto;
  days: DashboardDayDto[];
  hours: DashboardHourDto[];
  weekdays: DashboardWeekdayDto[];
  departments: DashboardDepartmentDto[];
  topItems: DashboardItemDto[];
  /** Per waiter; the row with `waiterUid === null` is sales made without a table order. */
  waiters: WaiterSalesRowDto[];
  tenders: DashboardTenderDto[];
  complimentary: DashboardCompDto[];
  generatedAt: string;
}

export interface RestaurantDashboardFilter {
  fromDate: string;
  toDate: string;
  branchUid: string | null;
}

/** Each kind is also its endpoint's path segment under /reports/restaurant. */
export type RestaurantReportKind =
  | 'waiter-sales'
  | 'waiter-items'
  | 'bills'
  | 'bills-summary'
  | 'pending-bills'
  | 'pending-summary'
  | 'daypart'
  | 'payments';

/** Payment types in plain words. */
export function tenderLabel(tender: string): string {
  switch (tender) {
    case 'CASH':
      return 'Cash';
    case 'MOBILE_MONEY':
      return 'Mobile money';
    case 'CARD':
      return 'Card';
    case 'CHEQUE':
      return 'Cheque';
    case 'COMPLIMENTARY':
      return 'Complimentary';
    default:
      return tender
        .toLowerCase()
        .replace(/_/g, ' ')
        .replace(/^./, (c) => c.toUpperCase());
  }
}

export interface RestaurantReportFilter {
  /** Ignored by pending bills, which is "now". */
  fromDate: string;
  toDate: string;
  waiterUid: string | null;
  branchUid: string | null;
}
