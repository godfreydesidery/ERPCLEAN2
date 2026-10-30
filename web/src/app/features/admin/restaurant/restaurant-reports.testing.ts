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
  RestaurantPendingBillsReportDto,
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

/** The permissions a manager running these reports holds. */
export const REPORT_PERMS = ['SALES.INVOICE.VIEW', 'REPORT.EXPORT', 'RESTAURANT.ORDER.MANAGE'];

/** The report service's calls; specs that assert on them pass spies. */
export interface ReportServiceStub {
  waiterSales: (...a: unknown[]) => unknown;
  bills: (...a: unknown[]) => unknown;
  pendingBills: (...a: unknown[]) => unknown;
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
    bills: () => of(BILLS),
    pendingBills: () => of(PENDING),
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
