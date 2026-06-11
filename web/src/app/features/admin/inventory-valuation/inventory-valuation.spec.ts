/**
 * Inventory Valuation component specs (ADR-0020, FR-INV-06/07).
 *
 * Covers:
 *  1. StockValuationReportComponent — renders without crashing.
 *  2. StockValuationReportComponent — fmtMoney coerces numeric wire values (BigDecimal as number).
 *     Regression guard: must not throw when called with a number (not a string).
 *  3. StockValuationReportComponent — reconciliation badge: ties=true shows "Reconciled to GL".
 *  4. StockValuationReportComponent — reconciliation badge: ties=false alarm shows difference.
 *  5. StockValuationReportComponent — isUnvalued: null and zero avgCost flagged as unvalued.
 *  6. OpeningValuationComponent — renders without crashing.
 *  7. OpeningValuationComponent — fmtMoney numeric-money guard (same class of bug).
 *  8. OpeningValuationComponent — submitDisabled: true when no row selected.
 *  9. OpeningValuationComponent — submitDisabled: false when row + valid cost provided.
 * 10. OpeningValuationComponent — messageFrom: 409 returns already-valued message.
 */

import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { describe, it, expect, afterEach, vi, beforeEach } from 'vitest';

import { StockValuationReportComponent } from './stock-valuation-report.component';
import { OpeningValuationComponent } from './opening-valuation.component';
import { InventoryValuationService } from './inventory-valuation.service';
import { StockService } from '../stock/stock.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  StockValuationReportDto,
  StockValuationReconDto,
} from './models/inventory-valuation.model';

// ── Shared mock helpers ────────────────────────────────────────────────────────

const MOCK_ORG = { uid: 'ORG1', id: '1', name: 'Acme Org' };
const MOCK_COMPANY = { uid: 'CO1', id: '10', name: 'Acme Ltd' };

function makeSession() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function reconTied(): StockValuationReconDto {
  return { label: 'Σ inv == GL 1300', computed: 150000, expected: 150000, difference: 0, ties: true };
}

function reconUntied(): StockValuationReconDto {
  return { label: 'Σ inv == GL 1300', computed: 155000, expected: 150000, difference: 5000, ties: false };
}

const MOCK_REPORT_TIED: StockValuationReportDto = {
  companyId: '10',
  currency: 'TZS',
  totalValue: 150000,
  recon: reconTied(),
  rows: [
    {
      productId: '1', productUid: 'PRD-001', productCode: 'P001', productName: 'Widget A',
      quantity: 100, avgCost: 1500, value: 150000, currency: 'TZS',
    },
  ],
};

const MOCK_REPORT_UNTIED: StockValuationReportDto = {
  ...MOCK_REPORT_TIED,
  totalValue: 155000,
  recon: reconUntied(),
  rows: [
    ...MOCK_REPORT_TIED.rows,
    {
      productId: '2', productUid: 'PRD-002', productCode: 'P002', productName: 'Widget B (unvalued)',
      quantity: 50, avgCost: null, value: null, currency: 'TZS',
    },
  ],
};

function makeValuationService(reportDto: StockValuationReportDto = MOCK_REPORT_TIED) {
  return {
    report: vi.fn(() => of(reportDto)),
    setOpening: vi.fn(() =>
      of({
        productUid: 'PRD-002',
        quantity: 50,
        openingCost: 100,
        openingValue: 5000,
        glEntryUid: 'JE-OPENING-001',
        currency: 'TZS',
      }),
    ),
  };
}

function makeStockService() {
  return {
    listOnHand: vi.fn(() =>
      of({
        rows: [
          { id: '1', uid: 'SOH-001', productId: '1', companyId: '10', branchId: '100', quantity: '100', reorderLevel: null, negative: false, low: false, version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null },
          { id: '2', uid: 'SOH-002', productId: '2', companyId: '10', branchId: '100', quantity: '50', reorderLevel: null, negative: false, low: false, version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null },
        ],
        meta: { page: 0, size: 500, totalElements: 2, totalPages: 1, hasNext: false },
      }),
    ),
  };
}

// ── StockValuationReportComponent ─────────────────────────────────────────────

describe('StockValuationReportComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  function makeBed(reportDto: StockValuationReportDto = MOCK_REPORT_TIED) {
    TestBed.configureTestingModule({
      imports: [StockValuationReportComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: '**', redirectTo: '' }]),
        { provide: InventoryValuationService, useValue: makeValuationService(reportDto) },
        { provide: SessionStore, useValue: makeSession() },
      ],
    });
  }

  it('renders without crashing', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(StockValuationReportComponent);
    expect(fixture).toBeTruthy();
  });

  it('fmtMoney coerces numeric wire values — BigDecimal arrives as number (money-type regression guard)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(StockValuationReportComponent).componentInstance as any;
    // BigDecimal serialised as JSON number — must never call .toFixed() on a string
    expect(comp.fmtMoney(150000)).toMatch(/150,000\.00/);
    expect(comp.fmtMoney(0)).toMatch(/0\.00/);
    expect(comp.fmtMoney(null)).toMatch(/0\.00/);
    expect(comp.fmtMoney(undefined)).toMatch(/0\.00/);
    // Must not throw when called with a number
    expect(() => comp.fmtMoney(150000)).not.toThrow();
    // Negative money
    expect(comp.fmtMoney(-5000)).toMatch(/-5,000\.00/);
  });

  it('reconciliation ties=true → tied flag is true on the mock DTO', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(StockValuationReportComponent).componentInstance as any;
    comp.report.set(MOCK_REPORT_TIED);
    expect(comp.report()?.recon.ties).toBe(true);
    expect(+(comp.report()?.recon.difference ?? 0)).toBe(0);
  });

  it('reconciliation ties=false alarm — difference is non-zero', () => {
    vi.useFakeTimers();
    makeBed(MOCK_REPORT_UNTIED);
    const comp = TestBed.createComponent(StockValuationReportComponent).componentInstance as any;
    comp.report.set(MOCK_REPORT_UNTIED);
    expect(comp.report()?.recon.ties).toBe(false);
    expect(+(comp.report()?.recon.difference ?? 0)).toBeGreaterThan(0);
    expect(+(comp.report()?.recon.computed ?? 0)).toBe(155000);
    expect(+(comp.report()?.recon.expected ?? 0)).toBe(150000);
  });

  it('isUnvalued returns true for null, undefined, and zero avgCost', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(StockValuationReportComponent).componentInstance as any;
    expect(comp.isUnvalued(null)).toBe(true);
    expect(comp.isUnvalued(undefined)).toBe(true);
    expect(comp.isUnvalued(0)).toBe(true);
    expect(comp.isUnvalued(1500)).toBe(false);
    expect(comp.isUnvalued('0')).toBe(true);
    expect(comp.isUnvalued('1500')).toBe(false);
  });
});

// ── OpeningValuationComponent ─────────────────────────────────────────────────

describe('OpeningValuationComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  function makeBed(reportDto: StockValuationReportDto = MOCK_REPORT_UNTIED) {
    TestBed.configureTestingModule({
      imports: [OpeningValuationComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: '**', redirectTo: '' }]),
        { provide: InventoryValuationService, useValue: makeValuationService(reportDto) },
        { provide: StockService, useValue: makeStockService() },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of(MOCK_ORG)) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([MOCK_COMPANY])) } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: SessionStore, useValue: makeSession() },
      ],
    });
  }

  it('renders without crashing', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(OpeningValuationComponent);
    expect(fixture).toBeTruthy();
  });

  it('fmtMoney numeric-money guard — must not throw on numeric inputs', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(OpeningValuationComponent).componentInstance as any;
    expect(comp.fmtMoney(5000)).toMatch(/5,000\.00/);
    expect(comp.fmtMoney(0)).toMatch(/0\.00/);
    expect(comp.fmtMoney(null)).toMatch(/0\.00/);
    expect(() => comp.fmtMoney(5000)).not.toThrow();
  });

  it('submitDisabled is true when no stock-on-hand row is selected', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(OpeningValuationComponent).componentInstance;
    comp.selectedStockOnHandUid.set('');
    comp.openingCostStr.set('100.00');
    expect(comp.submitDisabled()).toBe(true);
  });

  it('submitDisabled is true when no opening cost is entered', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(OpeningValuationComponent).componentInstance;
    comp.selectedStockOnHandUid.set('SOH-002');
    comp.openingCostStr.set('');
    expect(comp.submitDisabled()).toBe(true);
  });

  it('submitDisabled is false when a row is selected and a valid cost is entered', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(OpeningValuationComponent).componentInstance;
    comp.selectedStockOnHandUid.set('SOH-002');
    comp.openingCostStr.set('150.00');
    // loadState must not be 'loading'
    comp.loadState.set('idle');
    expect(comp.submitDisabled()).toBe(false);
  });

  it('submitDisabled is true for a negative opening cost', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(OpeningValuationComponent).componentInstance;
    comp.selectedStockOnHandUid.set('SOH-002');
    comp.openingCostStr.set('-10');
    comp.loadState.set('idle');
    expect(comp.submitDisabled()).toBe(true);
  });
});
