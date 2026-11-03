/**
 * Shared fixtures for the restaurant reports specs (J COB 2026-10-30).
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { ReportFilterOptionsService } from '../reporting/report-filter-options.service';
import {
  RestaurantBillsReportDto,
  RestaurantBillsSummaryDto,
  RestaurantDaypartDto,
  RestaurantPaymentsReportDto,
  RestaurantPendingBillsReportDto,
  RestaurantPendingSummaryDto,
  WaiterItemsReportDto,
  WaiterSalesReportDto,
} from './models/restaurant-report.model';
import { RestaurantReportService } from './restaurant-report.service';
import { RestaurantReportsComponent } from './restaurant-reports.component';

const COMPANY = {
  name: 'Tulia Ltd', legalName: null, addressLine1: 'Sokoine Rd', addressLine2: null,
  city: 'Arusha', region: null, country: 'Tanzania', contactPhone: '+255700000001',
  contactEmail: null, taxId: null, vrn: null,
};

export const SALES: WaiterSalesReportDto = {
  company: COMPANY, fromDate: '2026-10-01', toDate: '2026-10-30', waiterName: null, currency: 'TZS',
  rows: [
    { waiterUid: 'W1', waiterName: 'Baraka Juma', bills: 3, guests: 7, amount: 94000, averageBill: 31333.33 },
  ],
  totals: { waiterUid: null, waiterName: 'TOTAL', bills: 3, guests: 7, amount: 94000, averageBill: 31333.33 },
  generatedAt: '2026-10-30T12:00:00Z',
};

export const BILLS: RestaurantBillsReportDto = {
  company: COMPANY, fromDate: '2026-10-01', toDate: '2026-10-30', waiterName: 'Baraka Juma', currency: 'TZS',
  rows: [
    { orderUid: 'O1', orderNumber: 'ORD-0013', servedAt: 'T2', waiterName: 'Baraka Juma',
      cashierName: 'Sir Jacob', invoiceNumber: 'INV-0010', invoiceStatus: 'FINALISED', guests: 2,
      openedAt: '2026-10-30 18:10', paidAt: '2026-10-30 18:42', amount: 300, tenders: 'CASH' },
    { orderUid: 'O2', orderNumber: 'ORD-0014', servedAt: 'Takeaway', waiterName: 'Baraka Juma',
      cashierName: 'Sir Jacob', invoiceNumber: 'INV-0011', invoiceStatus: 'VOID', guests: null,
      openedAt: '2026-10-30 18:50', paidAt: '2026-10-30 18:55', amount: 500, tenders: 'MOBILE' },
  ],
  billCount: 1, totalAmount: 300, generatedAt: '2026-10-30T12:00:00Z',
};

export const PENDING: RestaurantPendingBillsReportDto = {
  company: COMPANY, waiterName: null, currency: 'TZS',
  rows: [
    { orderUid: 'O3', orderNumber: 'ORD-0015', servedAt: 'T4', waiterName: 'Neema Ali', status: 'BILLED',
      openedAt: '2026-10-30 19:00', billedAt: '2026-10-30 19:30', items: 3, billedAmount: 27000 },
    { orderUid: 'O4', orderNumber: 'ORD-0016', servedAt: 'T7', waiterName: 'Baraka Juma', status: 'OPEN',
      openedAt: '2026-10-30 19:20', billedAt: null, items: 2, billedAmount: null },
  ],
  openCount: 1, billedCount: 1, billedAmount: 27000, generatedAt: '2026-10-30T19:40:00Z',
};

export const WAITER_ITEMS: WaiterItemsReportDto = {
  company: COMPANY, fromDate: '2026-10-01', toDate: '2026-10-30', waiterName: null, currency: 'TZS',
  rows: [
    { waiterUid: 'W1', waiterName: 'Baraka Juma', code: '147', name: 'Tacos', unit: 'Pieces', quantity: 4, amount: 60000 },
    { waiterUid: 'W1', waiterName: 'Baraka Juma', code: '171', name: 'Latte', unit: 'Pieces', quantity: 2, amount: 34000 },
    { waiterUid: 'W2', waiterName: 'Neema Ali', code: '171', name: 'Latte', unit: 'Pieces', quantity: 1, amount: 17000 },
  ],
  waiters: [
    { waiterUid: 'W1', waiterName: 'Baraka Juma', bills: 3, amount: 94000 },
    { waiterUid: 'W2', waiterName: 'Neema Ali', bills: 1, amount: 17000 },
  ],
  totalAmount: 111000, generatedAt: '2026-10-30T12:00:00Z',
};

export const BILLS_SUMMARY: RestaurantBillsSummaryDto = {
  company: COMPANY, fromDate: '2026-10-01', toDate: '2026-10-30', waiterName: null, currency: 'TZS',
  tenderTypes: ['CASH', 'MOBILE_MONEY', 'COMPLIMENTARY'],
  rows: [
    { waiterUid: 'W1', waiterName: 'Baraka Juma', bills: 3, guests: 7, tenders: [50000, 40000, 4000], amount: 94000 },
  ],
  totals: { waiterUid: null, waiterName: 'TOTAL', bills: 3, guests: 7, tenders: [50000, 40000, 4000], amount: 94000 },
  generatedAt: '2026-10-30T12:00:00Z',
};

export const PENDING_SUMMARY: RestaurantPendingSummaryDto = {
  company: COMPANY, waiterName: null, currency: 'TZS',
  rows: [
    { waiterUid: 'W2', waiterName: 'Neema Ali', open: 0, billed: 1, items: 3, billedAmount: 27000, oldestOpenedAt: '2026-10-30 19:00' },
    { waiterUid: 'W1', waiterName: 'Baraka Juma', open: 1, billed: 0, items: 2, billedAmount: 0, oldestOpenedAt: '2026-10-30 19:20' },
  ],
  totals: { waiterUid: null, waiterName: 'TOTAL', open: 1, billed: 1, items: 5, billedAmount: 27000, oldestOpenedAt: '2026-10-30 19:00' },
  generatedAt: '2026-10-30T19:40:00Z',
};

export const DAYPART: RestaurantDaypartDto = {
  company: COMPANY, fromDate: '2026-10-01', toDate: '2026-10-30', branchName: null, currency: 'TZS',
  departments: ['Food', 'Drinks'],
  hours: [
    { label: '12:00-13:00', receipts: 4, sales: 80000, days: 30, averagePerDay: 2666.67, departmentSales: [60000, 20000] },
    { label: '19:00-20:00', receipts: 2, sales: 31000, days: 30, averagePerDay: 1033.33, departmentSales: [14000, 17000] },
  ],
  weekdays: [
    { label: 'Friday', receipts: 6, sales: 111000, days: 5, averagePerDay: 22200, departmentSales: [74000, 37000] },
  ],
  totals: { label: 'TOTAL', receipts: 6, sales: 111000, days: 30, averagePerDay: 3700, departmentSales: [74000, 37000] },
  generatedAt: '2026-10-30T12:00:00Z',
};

export const PAYMENTS: RestaurantPaymentsReportDto = {
  company: COMPANY, fromDate: '2026-10-01', toDate: '2026-10-30', branchName: null, currency: 'TZS',
  tenders: [
    { tenderType: 'CASH', payments: 4, amount: 70000, share: 63.06 },
    { tenderType: 'MOBILE_MONEY', payments: 2, amount: 41000, share: 36.94 },
  ],
  totalPaid: 111000,
  completion: { opened: 8, paid: 6, cancelled: 1, pending: 1, completionRate: 75 },
  refunds: [{ at: '2026-10-30 20:10', receiptNumber: 'INV-0010', reason: 'Cold food', by: 'Sir Jacob', amount: 5000 }],
  refundTotal: 5000,
  voidedReceipts: [{ soldAt: '2026-10-30 18:55', receiptNumber: 'INV-0011', by: 'Sir Jacob', amount: 500 }],
  voidedReceiptTotal: 500,
  cancelledOrders: [{ orderNumber: 'ORD-0017', servedAt: 'T3', waiterName: 'Neema Ali', openedAt: '2026-10-30 13:00', cancelledAt: '2026-10-30 13:05', reason: 'Customer left', items: 2 }],
  voidedItems: [{ voidedAt: '2026-10-30 19:10', orderNumber: 'ORD-0015', servedAt: 'T4', waiterName: 'Neema Ali', productName: 'Tacos', quantity: 1, reason: 'Wrong order' }],
  generatedAt: '2026-10-30T12:00:00Z',
};

/** The permissions a manager running these reports holds. */
export const REPORT_PERMS = ['SALES.INVOICE.VIEW', 'REPORT.EXPORT', 'RESTAURANT.ORDER.MANAGE'];

/** The report service's calls; specs that assert on them pass spies. */
export interface ReportServiceStub {
  waiterSales: (...a: unknown[]) => unknown;
  waiterItems: (...a: unknown[]) => unknown;
  bills: (...a: unknown[]) => unknown;
  billsSummary: (...a: unknown[]) => unknown;
  pendingBills: (...a: unknown[]) => unknown;
  pendingSummary: (...a: unknown[]) => unknown;
  daypart: (...a: unknown[]) => unknown;
  payments: (...a: unknown[]) => unknown;
  export: (...a: unknown[]) => unknown;
  waiters: (...a: unknown[]) => unknown;
}

/**
 * A service answering with the fixtures above. Plain functions: this file is compiled with the
 * app (only *.spec.ts is excluded), so it must not touch the test runner's globals.
 */
export function stubReportService(): ReportServiceStub {
  return {
    waiterSales: () => of(SALES),
    waiterItems: () => of(WAITER_ITEMS),
    bills: () => of(BILLS),
    billsSummary: () => of(BILLS_SUMMARY),
    pendingBills: () => of(PENDING),
    pendingSummary: () => of(PENDING_SUMMARY),
    daypart: () => of(DAYPART),
    payments: () => of(PAYMENTS),
    export: () => of(new Blob()),
    waiters: () => of([{ uid: 'W1', name: 'Baraka Juma', username: 'baraka' }]),
  };
}

export function reportsBed(perms: string[], svc: object): void {
  TestBed.configureTestingModule({
    imports: [RestaurantReportsComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: RestaurantReportService, useValue: svc },
      { provide: ReportFilterOptionsService, useValue: { branchOptions: () => of([]) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: (code: string) => perms.includes(code),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal(perms),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}
