import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';

/**
 * Restaurant reports per waiter (J COB 2026-10-30). Money (`BigDecimal`) arrives as a JSON number;
 * counts as numbers. The waiter is whoever opened the order.
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

export type RestaurantReportKind = 'waiter-sales' | 'bills' | 'pending-bills';

export interface RestaurantReportFilter {
  /** Ignored by pending bills, which is "now". */
  fromDate: string;
  toDate: string;
  waiterUid: string | null;
  branchUid: string | null;
}
