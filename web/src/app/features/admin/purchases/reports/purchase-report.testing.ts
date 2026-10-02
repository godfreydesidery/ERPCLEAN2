/**
 * Test-only fixtures for the purchase report specs (imported by *.spec.ts only, never by app code).
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider, signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { SessionStore } from '../../../../core/auth/session.store';
import { ReportCompanyHeaderDto } from '../../models/report-company-header.model';
import {
  GoodsReceivedRegisterDto,
  OpenPurchaseOrdersDto,
  PurchasePriceVarianceDto,
  PurchasesBySupplierDto,
} from './models/purchase-report.model';
import { PurchaseReportPickersService } from './purchase-report-pickers.service';
import { PurchaseReportService } from './purchase-report.service';

export const COMPANY: ReportCompanyHeaderDto = {
  name: 'Kilimanjaro Star Liquor Store', legalName: null,
  addressLine1: 'Plot 4', addressLine2: null, city: 'Moshi', region: null, country: 'Tanzania',
  contactPhone: '+255700000000', contactEmail: null, taxId: 'TIN-1', vrn: null,
};

export type ReportServiceMock = { [K in keyof PurchaseReportService]: ReturnType<typeof vi.fn> };

/** Every API method stubbed; each spec overrides the one it exercises. */
export function reportServiceMock(overrides: Partial<ReportServiceMock> = {}): ReportServiceMock {
  return {
    goodsReceived: vi.fn(),
    exportGoodsReceived: vi.fn(() => of(new Blob())),
    bySupplier: vi.fn(),
    exportBySupplier: vi.fn(() => of(new Blob())),
    openOrders: vi.fn(),
    exportOpenOrders: vi.fn(() => of(new Blob())),
    priceVariance: vi.fn(),
    exportPriceVariance: vi.fn(() => of(new Blob())),
    ...overrides,
  } as ReportServiceMock;
}

export function pickersMock() {
  return {
    branchOptions: vi.fn(() => of([{ uid: 'BR-A', label: 'Branch A', hint: 'A' }])),
    supplierSeed: vi.fn(() => of([{ uid: 'SUP-1', label: 'Alpha Traders', hint: 'S1' }])),
    productSeed: vi.fn(() => of([{ uid: 'PRD-1', label: 'Konyagi', hint: 'K1' }])),
    searchSuppliers: vi.fn(() => of([])),
    searchProducts: vi.fn(() => of([])),
  };
}

/** A non-root session holding exactly `permissions`. */
export function sessionWith(permissions: readonly string[]) {
  return {
    hasPermission: vi.fn((code: string) => permissions.includes(code)),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([...permissions]),
    activeBranchUid: signal(null),
  };
}

export function providersFor(
  service: ReportServiceMock,
  permissions: readonly string[],
  pickers = pickersMock(),
): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter([]),
    { provide: PurchaseReportService, useValue: service },
    { provide: PurchaseReportPickersService, useValue: pickers },
    { provide: SessionStore, useValue: sessionWith(permissions) },
  ];
}

export function registerDto(overrides: Partial<GoodsReceivedRegisterDto> = {}): GoodsReceivedRegisterDto {
  return {
    company: COMPANY,
    fromDate: '2026-03-01',
    toDate: '2026-03-31',
    branchName: null,
    supplierName: null,
    productName: null,
    currency: 'TZS',
    rows: [
      {
        entryType: 'RECEIPT', entryAt: '2026-03-05T12:00:00+03:00', receiptNumber: 'GRN-0001',
        receiptUid: 'GR1', orderNumber: 'PO-0001', direct: false, supplierCode: 'S1',
        supplierName: 'Alpha Traders', branchName: 'Branch A', productCode: 'X', productName: 'Item X',
        unitName: 'PCS', quantity: 10, unitCost: 100, value: 1000, currency: 'TZS',
      },
      {
        entryType: 'RECEIPT', entryAt: '2026-03-15T12:00:00+03:00', receiptNumber: 'GRN-0003',
        receiptUid: 'GR3', orderNumber: 'PO-0002', direct: true, supplierCode: 'S2',
        supplierName: 'Beta Wholesale', branchName: 'Branch A', productCode: 'X', productName: 'Item X',
        unitName: 'PCS', quantity: 4, unitCost: 55, value: 220, currency: 'TZS',
      },
      {
        entryType: 'VOID', entryAt: '2026-03-20T12:00:00+03:00', receiptNumber: 'GRN-0002',
        receiptUid: 'GR2', orderNumber: 'PO-0001', direct: false, supplierCode: 'S1',
        supplierName: 'Alpha Traders', branchName: 'Branch A', productCode: 'Y', productName: 'Item Y',
        unitName: 'PCS', quantity: -5, unitCost: 40, value: -200, currency: 'TZS',
      },
    ],
    totals: { receipts: 2, voids: 1, lines: 3, value: 1020, rowsInOtherCurrency: 0 },
    page: 0,
    size: 50,
    totalElements: 3,
    totalPages: 1,
    generatedAt: '2026-04-01T08:00:00Z',
    ...overrides,
  };
}


export function bySupplierDto(overrides: Partial<PurchasesBySupplierDto> = {}): PurchasesBySupplierDto {
  return {
    company: COMPANY,
    fromDate: '2026-03-01',
    toDate: '2026-03-31',
    branchName: null,
    currency: 'TZS',
    returnsShown: true,
    billsShown: true,
    rows: [
      {
        supplierCode: 'S1', supplierName: 'Alpha Traders', currency: 'TZS', receipts: 2,
        receivedValue: 1200, returnsValue: 300, netPurchases: 900, billedAmount: 1050, unpaidAmount: 500,
      },
      {
        supplierCode: 'S2', supplierName: 'Beta Wholesale', currency: 'TZS', receipts: 1,
        receivedValue: 100, returnsValue: 0, netPurchases: 100, billedAmount: 0, unpaidAmount: 0,
      },
    ],
    totals: {
      receipts: 3, receivedValue: 1300, returnsValue: 300, netPurchases: 1000,
      billedAmount: 1050, unpaidAmount: 500, rowsInOtherCurrency: 0,
    },
    generatedAt: '2026-04-01T08:00:00Z',
    ...overrides,
  };
}

export function openOrdersDto(overrides: Partial<OpenPurchaseOrdersDto> = {}): OpenPurchaseOrdersDto {
  return {
    company: COMPANY,
    asOfDate: '2026-03-10',
    branchName: null,
    supplierName: null,
    currency: 'TZS',
    rows: [
      {
        orderNumber: 'PO-0001', orderUid: 'PO1', orderDate: '2026-03-01', expectedDate: '2026-03-08',
        supplierCode: 'S1', supplierName: 'Alpha Traders', branchName: 'Branch A', productCode: 'X',
        productName: 'Item X', unitName: 'CTN', orderedQty: 10, receivedQty: 6, outstandingQty: 4,
        unitCost: 100, outstandingValue: 400, currency: 'TZS', ageDays: 9, overdue: true,
      },
      {
        orderNumber: 'PO-0002', orderUid: 'PO2', orderDate: '2026-03-02', expectedDate: null,
        supplierCode: 'S2', supplierName: 'Beta Wholesale', branchName: 'Branch B', productCode: 'X',
        productName: 'Item X', unitName: 'PCS', orderedQty: 3, receivedQty: 0, outstandingQty: 3,
        unitCost: 50, outstandingValue: 150, currency: 'TZS', ageDays: 8, overdue: false,
      },
    ],
    totals: { orders: 2, lines: 2, outstandingValue: 550, rowsInOtherCurrency: 0 },
    generatedAt: '2026-03-10T08:00:00Z',
    ...overrides,
  };
}

export function varianceDto(overrides: Partial<PurchasePriceVarianceDto> = {}): PurchasePriceVarianceDto {
  return {
    company: COMPANY,
    fromDate: '2026-03-01',
    toDate: '2026-03-31',
    branchName: null,
    supplierName: null,
    currency: 'TZS',
    billsShown: true,
    rows: [
      {
        receivedAt: '2026-03-05T12:00:00+03:00', receiptNumber: 'GRN-0001', receiptUid: 'GR1',
        orderNumber: 'PO-0001', supplierCode: 'S1', supplierName: 'Alpha Traders', productCode: 'X',
        productName: 'Item X', unitName: 'PCS', receivedQty: 10, poPrice: 100, receiptCost: 100,
        receiptVariancePerUnit: 0, receiptVarianceTotal: 0, receiptVariancePct: 0,
        billedQty: 10, billPrice: 102, billVariancePerUnit: 2, billVarianceTotal: 20,
        billVariancePct: 2, currency: 'TZS',
      },
      {
        receivedAt: '2026-03-07T12:00:00+03:00', receiptNumber: 'GRN-0003', receiptUid: 'GR3',
        orderNumber: 'PO-0003', supplierCode: 'S1', supplierName: 'Alpha Traders', productCode: 'Y',
        productName: 'Item Y', unitName: 'PCS', receivedQty: 4, poPrice: 50, receiptCost: 52,
        receiptVariancePerUnit: 2, receiptVarianceTotal: 8, receiptVariancePct: 4,
        billedQty: null, billPrice: null, billVariancePerUnit: null, billVarianceTotal: null,
        billVariancePct: null, currency: 'TZS',
      },
    ],
    totals: { lines: 2, receiptVarianceTotal: 8, billVarianceTotal: 20, rowsInOtherCurrency: 0 },
    generatedAt: '2026-04-01T08:00:00Z',
    ...overrides,
  };
}
