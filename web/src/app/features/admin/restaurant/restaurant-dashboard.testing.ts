/**
 * Shared fixtures for the restaurant dashboard specs (2026-11-03).
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { ReportFilterOptionsService } from '../reporting/report-filter-options.service';
import { DashboardKpisDto, RestaurantDashboardDto } from './models/restaurant-report.model';
import { RestaurantDashboardComponent } from './restaurant-dashboard.component';
import { RestaurantReportService } from './restaurant-report.service';

const KPIS: DashboardKpisDto = {
  sales: 120000, receipts: 8, averageReceipt: 15000, orders: 6, guests: 12, spendPerGuest: 8500,
  discounts: 3000, complimentary: 7000, refunds: 0, voidedReceipts: 1, cancelledOrders: 1,
  voidedItems: 2,
};

export const DASHBOARD: RestaurantDashboardDto = {
  company: {
    name: 'Tulia Ltd', legalName: null, addressLine1: null, addressLine2: null, city: 'Arusha',
    region: null, country: 'Tanzania', contactPhone: null, contactEmail: null, taxId: null, vrn: null,
  },
  fromDate: '2026-11-01', toDate: '2026-11-03', branchName: null, currency: 'TZS',
  current: KPIS,
  previous: { ...KPIS, sales: 100000, refunds: 0, discounts: 6000, voidedItems: 0 },
  previousFromDate: '2026-10-29', previousToDate: '2026-10-31',
  live: { openOrders: 3, billedOrders: 2, billedAmount: 41000 },
  days: [
    { date: '2026-11-01', receipts: 3, sales: 40000 },
    { date: '2026-11-02', receipts: 2, sales: 30000 },
    { date: '2026-11-03', receipts: 3, sales: 50000 },
  ],
  hours: [
    { hour: 7, receipts: 0, sales: 0 },
    { hour: 13, receipts: 5, sales: 80000 },
    { hour: 19, receipts: 3, sales: 40000 },
  ],
  weekdays: [
    { day: 1, label: 'Monday', days: 1, receipts: 3, sales: 50000, averagePerDay: 50000 },
    { day: 6, label: 'Saturday', days: 1, receipts: 3, sales: 40000, averagePerDay: 40000 },
  ],
  departments: [
    { name: 'Food', quantity: 10, sales: 80000 },
    { name: 'Drinks', quantity: 14, sales: 40000 },
  ],
  topItems: [{ code: '147', name: 'Tacos', unit: 'Pieces', quantity: 5, sales: 75000 }],
  waiters: [
    { waiterUid: 'W1', waiterName: 'Baraka Juma', bills: 6, guests: 12, amount: 100000, averageBill: 16666.67 },
    { waiterUid: null, waiterName: '', bills: 2, guests: 0, amount: 20000, averageBill: 10000 },
  ],
  tenders: [
    { tenderType: 'CASH', payments: 5, amount: 73000 },
    { tenderType: 'MOBILE_MONEY', payments: 2, amount: 40000 },
    { tenderType: 'COMPLIMENTARY', payments: 1, amount: 7000 },
  ],
  complimentary: [{ receiptNumber: 'INV-0042', at: '2026-11-02 13:40', givenTo: 'Owner guest', amount: 7000 }],
  generatedAt: '2026-11-03T12:00:00Z',
};

export const DASHBOARD_PERMS = ['SALES.INVOICE.VIEW', 'REPORT.EXPORT'];

export interface DashboardServiceStub {
  dashboard: (...a: unknown[]) => unknown;
  exportFlashReport: (...a: unknown[]) => unknown;
}

/** Plain functions: this file is compiled with the app, so no test-runner globals here. */
export function stubDashboardService(): DashboardServiceStub {
  return {
    dashboard: () => of(DASHBOARD),
    exportFlashReport: () => of(new Blob()),
  };
}

export function dashboardBed(perms: string[], svc: object): void {
  TestBed.configureTestingModule({
    imports: [RestaurantDashboardComponent],
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
