import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { EnterBillComponent } from './enter-bill.component';
import { ApService } from './ap.service';
import { PurchasesService } from '../purchases/purchases.service';
import { SupplierService } from '../parties/supplier.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

// Enter Bill spec:
//  1. submitDisabled when no supplier / missing required fields.
//  2. lineNetAmount computed correctly (qty * cost as numbers).
//  3. Match result: matchLineBadgeClass returns correct classes; isHeld identifies HELD lines.
//  4. addLine / removeLine mutations.

const MOCK_BILL = {
  uid: 'BILL-UID-1',
  billNumber: 'AP-BILL-0001',
  supplierInvoiceNo: 'INV-100',
  billDate: '2026-06-01',
  dueDate: '2026-07-01',
  netAmount: 1000,
  vatAmount: 180,
  grossAmount: 1180,
  outstandingAmount: 1180,
  currency: 'TZS',
  status: 'DRAFT' as const,
  source: 'PURCHASE_ORDER' as const,
  purchaseOrderUid: null,
  postedGlEntryUid: null,
  supplierId: 'SUP1',
  companyId: '10',
  branchId: 'BR1',
  lines: [],
};

const MOCK_MATCH_RESULT = {
  billUid: 'BILL-UID-1',
  billStatus: 'HELD' as const,
  lineResults: [
    {
      billLineId: '1',
      billLineUid: 'LINE-1',
      matchStatus: 'HELD_PRICE_VARIANCE' as const,
      priceVarianceAmount: 50,
      priceVariancePct: 5,
      qtyVariance: 0,
      poUnitCostAmount: 950,
      grReceivedQty: 10,
      billedQty: 10,
      matchedAt: null,
    },
    {
      billLineId: '2',
      billLineUid: 'LINE-2',
      matchStatus: 'MATCHED' as const,
      priceVarianceAmount: 0,
      priceVariancePct: 0,
      qtyVariance: 0,
      poUnitCostAmount: 200,
      grReceivedQty: 5,
      billedQty: 5,
      matchedAt: '2026-06-09T10:00:00',
    },
  ],
};

function makeSession(canEnter = true) {
  return {
    hasPermission: vi.fn(() => canEnter),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed() {
  TestBed.configureTestingModule({
    imports: [EnterBillComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: ApService,
        useValue: {
          enterBill: vi.fn(() => of(MOCK_BILL)),
          runMatch: vi.fn(() => of(MOCK_MATCH_RESULT)),
          acceptVariance: vi.fn(() => of(MOCK_MATCH_RESULT)),
        },
      },
      { provide: SupplierService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      // PurchasesService added by uid-picker sweep (loadPoOptions)
      { provide: PurchasesService, useValue: { listOrders: vi.fn(() => of({ rows: [], meta: {} })), listOrderLines: vi.fn(() => of([])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSession() },
    ],
  });
}

describe('EnterBillComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('submitDisabled true initially', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    expect(comp.submitDisabled()).toBe(true);
  });

  it('submitDisabled false when all required fields set', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    comp.selectedCompanyId.set('10');
    comp.selectedSupplier.set({ uid: 'SUP1', label: 'Supplier A' });
    comp.supplierInvoiceNo.set('INV-100');
    comp.billDate.set('2026-06-01');
    comp.dueDate.set('2026-07-01');
    expect(comp.submitDisabled()).toBe(false);
  });

  it('lineNetAmount multiplies qty * cost correctly (numbers)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    const line = { description: 'Widget', billedQty: '5', unitCostAmount: '200', poLineUid: '' };
    expect(comp.lineNetAmount(line)).toBeCloseTo(1000, 5);
  });

  it('lineNetAmount returns 0 for empty/invalid inputs', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    expect(comp.lineNetAmount({ description: '', billedQty: '', unitCostAmount: '', poLineUid: '' })).toBe(0);
  });

  it('addLine appends an empty row', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    const before = comp.lines().length;
    comp.addLine();
    expect(comp.lines().length).toBe(before + 1);
  });

  it('removeLine removes the correct index', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    comp.addLine(); // 2 lines
    comp.updateLine(0, 'description', 'Line A');
    comp.updateLine(1, 'description', 'Line B');
    comp.removeLine(0);
    expect(comp.lines().length).toBe(1);
    expect(comp.lines()[0].description).toBe('Line B');
  });

  it('matchLineBadgeClass returns correct classes for each status', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    expect(comp.matchLineBadgeClass({ matchStatus: 'MATCHED' })).toBe('text-bg-success');
    expect(comp.matchLineBadgeClass({ matchStatus: 'HELD_PRICE_VARIANCE' })).toBe('text-bg-danger');
    expect(comp.matchLineBadgeClass({ matchStatus: 'HELD_QTY_VARIANCE' })).toBe('text-bg-warning');
    expect(comp.matchLineBadgeClass({ matchStatus: 'VARIANCE_ACCEPTED' })).toBe('text-bg-info');
  });

  it('isHeld returns true only for HELD statuses', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    expect(comp.isHeld({ matchStatus: 'HELD_PRICE_VARIANCE' })).toBe(true);
    expect(comp.isHeld({ matchStatus: 'HELD_QTY_VARIANCE' })).toBe(true);
    expect(comp.isHeld({ matchStatus: 'MATCHED' })).toBe(false);
    expect(comp.isHeld({ matchStatus: 'VARIANCE_ACCEPTED' })).toBe(false);
  });

  it('after submit + runMatch, matchResult and matchState are populated', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    comp.selectedCompanyId.set('10');
    comp.selectedSupplier.set({ uid: 'SUP1', label: 'Supplier A' });
    comp.supplierInvoiceNo.set('INV-100');
    comp.billDate.set('2026-06-01');
    comp.dueDate.set('2026-07-01');
    comp.lines.set([{ description: 'Widget', billedQty: '5', unitCostAmount: '200', poLineUid: '' }]);

    comp.submit();

    // enterBill and runMatch both return synchronously via of()
    expect(comp.savedBill()).not.toBeNull();
    expect(comp.matchState()).toBe('done');
    expect(comp.matchResult()).not.toBeNull();
    expect(comp.matchResult().lineResults.length).toBe(2);
  });

  // FOLLOW-002 C1 regression: PO option label must never be the raw uid
  it('PO option label falls back to (draft PO) when orderNumber is null — never the uid', () => {
    vi.useFakeTimers();
    const draftPo = {
      uid: '01KV2E27BG084Q4831F8Q8Q8F1',
      id: '99',
      orderNumber: null,
      supplierName: 'Acme Supplies',
      status: 'DRAFT',
    };
    TestBed.configureTestingModule({
      imports: [EnterBillComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', redirectTo: '' }]),
        { provide: ApService, useValue: { enterBill: vi.fn(), runMatch: vi.fn(), acceptVariance: vi.fn() } },
        { provide: SupplierService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
        {
          provide: PurchasesService,
          useValue: {
            listOrders: vi.fn(() => of({ rows: [draftPo], meta: {} })),
            listOrderLines: vi.fn(() => of([])),
          },
        },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: SessionStore, useValue: makeSession() },
      ],
    });
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    const options: { uid: string; label: string }[] = comp.poOptions();
    expect(options.length).toBe(1);
    // Label must NOT be the uid
    expect(options[0].label).not.toBe(draftPo.uid);
    // Label must be the human placeholder
    expect(options[0].label).toBe('(draft PO)');
  });
});
