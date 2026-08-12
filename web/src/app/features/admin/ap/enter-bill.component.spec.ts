import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
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
//  3. Match result: status-tag pills rendered for match statuses; isHeld identifies HELD lines.
//  4. addLine / removeLine mutations.
//  5. UAT 2026-08-12 — the fail-closed hold must be ACTIONABLE and honest:
//     a null variance never renders as "0.00", a genuine 0.00 still does, the human note replaces
//     the raw enum, and the goods-receipt picker exists and reaches the submitted payload.

const MOCK_GOODS_RECEIPTS = [
  {
    uid: 'GR-UID-1',
    receiptNumber: 'GRN-000123',
    status: 'RECEIVED' as const,
    supplierId: 'SUP-ID-1',
    purchaseOrderUid: 'PO-UID-1',
    lines: [
      {
        uid: 'GRL-UID-1',
        productCode: 'WID-1',
        productName: 'Widget',
        unitName: 'PCS',
        receivedQty: 10,
        qtyInBase: 10,
      },
    ],
  },
  // Another supplier's receipt — must never appear in this supplier's picker.
  {
    uid: 'GR-UID-2',
    receiptNumber: 'GRN-000999',
    status: 'RECEIVED' as const,
    supplierId: 'SUP-ID-OTHER',
    purchaseOrderUid: null,
    lines: [
      { uid: 'GRL-UID-OTHER', productCode: 'X', productName: 'Other', unitName: 'PCS', receivedQty: 1, qtyInBase: 1 },
    ],
  },
  // Voided receipt for the right supplier — nothing was really received, so it must not be offered.
  {
    uid: 'GR-UID-3',
    receiptNumber: 'GRN-000124',
    status: 'VOID' as const,
    supplierId: 'SUP-ID-1',
    purchaseOrderUid: 'PO-UID-1',
    lines: [
      { uid: 'GRL-UID-VOID', productCode: 'V', productName: 'Voided', unitName: 'PCS', receivedQty: 5, qtyInBase: 5 },
    ],
  },
];

/** The shape the fail-closed backend sends when the goods receipt could not be found. */
const NO_RECEIPT_LINE = {
  billLineId: '1',
  billLineUid: 'LINE-NO-GR',
  matchStatus: 'HELD_PRICE_VARIANCE' as const,
  priceVarianceAmount: null,
  priceVariancePct: null,
  qtyVariance: null,
  poUnitCostAmount: null,
  grReceivedQty: null,
  billedQty: 15,
  matchedAt: null,
  comparisonPerformed: false,
  matchNote:
    'The goods receipt for this line could not be found, so the quantity billed could not be '
    + 'checked against what was received. Attach the goods receipt line to this bill line, then '
    + 'run the match again.',
};

/** A line the control DID run on and found equal — a real, meaningful 0.00. */
const CLEAN_ZERO_LINE = {
  billLineId: '2',
  billLineUid: 'LINE-CLEAN',
  matchStatus: 'MATCHED' as const,
  priceVarianceAmount: 0,
  priceVariancePct: 0,
  qtyVariance: 0,
  poUnitCostAmount: 4500,
  grReceivedQty: 20,
  billedQty: 20,
  matchedAt: '2026-08-12T10:00:00Z',
  comparisonPerformed: true,
  matchNote: null,
};

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
      // PurchasesService added by uid-picker sweep (loadPoOptions); listReceipts feeds the
      // goods-receipt line picker (UAT 2026-08-12).
      {
        provide: PurchasesService,
        useValue: {
          listOrders: vi.fn(() => of({ rows: [], meta: {} })),
          listOrderLines: vi.fn(() => of([])),
          listReceipts: vi.fn(() => of({ rows: MOCK_GOODS_RECEIPTS, meta: {} })),
        },
      },
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
    comp.selectedSupplier.set({ id: 'SUP-ID-1', uid: 'SUP1', label: 'Supplier A' });
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

  it('match result pills: status-tag--ok for MATCHED, --danger for HELD_PRICE_VARIANCE, --warn for HELD_QTY_VARIANCE, --info for VARIANCE_ACCEPTED', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(EnterBillComponent);
    const comp = fixture.componentInstance as any;
    await vi.runAllTimersAsync();

    comp.matchResult.set({
      billStatus: 'HELD',
      lineResults: [
        { billLineUid: 'L1', matchStatus: 'MATCHED',              poUnitCostAmount: 100, priceVarianceAmount: 0, priceVariancePct: 0, grReceivedQty: 1, billedQty: 1, qtyVariance: 0 },
        { billLineUid: 'L2', matchStatus: 'HELD_PRICE_VARIANCE',  poUnitCostAmount: 100, priceVarianceAmount: 5, priceVariancePct: 5, grReceivedQty: 1, billedQty: 1, qtyVariance: 0 },
        { billLineUid: 'L3', matchStatus: 'HELD_QTY_VARIANCE',    poUnitCostAmount: 100, priceVarianceAmount: 0, priceVariancePct: 0, grReceivedQty: 2, billedQty: 3, qtyVariance: 1 },
        { billLineUid: 'L4', matchStatus: 'VARIANCE_ACCEPTED',    poUnitCostAmount: 100, priceVarianceAmount: 0, priceVariancePct: 0, grReceivedQty: 1, billedQty: 1, qtyVariance: 0 },
      ],
    });
    comp.matchState.set('done');
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.status-tag.status-tag--ok')).not.toBeNull();
    expect(el.querySelector('.status-tag.status-tag--danger')).not.toBeNull();
    expect(el.querySelector('.status-tag.status-tag--warn')).not.toBeNull();
    expect(el.querySelector('.status-tag.status-tag--info')).not.toBeNull();
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
    comp.selectedSupplier.set({ id: 'SUP-ID-1', uid: 'SUP1', label: 'Supplier A' });
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
            listReceipts: vi.fn(() => of({ rows: [], meta: {} })),
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

  // ───────────────────────────────────────────────────────────────────────────
  // UAT 2026-08-12 — the fail-closed hold must be readable and escapable.
  // ───────────────────────────────────────────────────────────────────────────

  it('a null variance renders as an em dash, never as 0.00', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    expect(comp.fmtMoney(null)).toBe('—');
    expect(comp.fmtMoney(undefined)).toBe('—');
    expect(comp.fmtPct(null)).toBe('—');
    expect(comp.isNotChecked(null)).toBe(true);
  });

  it('a genuine 0 variance still renders as 0.00 — "checked and equal" is a real result', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    expect(comp.fmtMoney(0)).toBe('0.00');
    expect(comp.fmtPct(0)).toBe('0.00%');
    expect(comp.isNotChecked(0)).toBe(false);
  });

  it('an unchecked line shows no 0.00 in the match table, while a checked 0.00 line does', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(EnterBillComponent);
    const comp = fixture.componentInstance as any;
    await vi.runAllTimersAsync();

    comp.matchResult.set({ billUid: 'B1', billStatus: 'HELD', lineResults: [NO_RECEIPT_LINE] });
    comp.matchState.set('done');
    fixture.detectChanges();
    let text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('0.00');
    expect(text).toContain('—');
    // The one figure that IS known (what the supplier billed) is still shown.
    expect(text).toContain('15.00');

    comp.matchResult.set({ billUid: 'B1', billStatus: 'MATCHED', lineResults: [CLEAN_ZERO_LINE] });
    fixture.detectChanges();
    text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('0.00');
  });

  it('shows the plain-English note and a human status — never the raw enum', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(EnterBillComponent);
    const comp = fixture.componentInstance as any;
    await vi.runAllTimersAsync();

    comp.matchResult.set({ billUid: 'B1', billStatus: 'HELD', lineResults: [NO_RECEIPT_LINE] });
    comp.matchState.set('done');
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('HELD_PRICE_VARIANCE');
    expect(text).toContain('Attach the goods receipt line to this bill line');
    expect(comp.statusLabel(NO_RECEIPT_LINE)).toBe('On hold — not checked');
  });

  it('a line held for a missing receipt is not labelled a price variance', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    // Same enum, opposite meanings — the label must follow comparisonPerformed, not the enum.
    expect(comp.statusLabel(NO_RECEIPT_LINE)).not.toContain('price');
    expect(
      comp.statusLabel({ matchStatus: 'HELD_PRICE_VARIANCE', comparisonPerformed: true }),
    ).toBe('On hold — price differs');
  });

  it('the override button says what it really does on an unchecked line', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    expect(comp.acceptLabel(NO_RECEIPT_LINE)).toBe('Post without checking');
    expect(comp.acceptLabel({ matchStatus: 'HELD_QTY_VARIANCE', comparisonPerformed: true }))
      .toBe('Accept variance');
  });

  it('picking a supplier loads that supplier\'s received goods receipt lines, labelled by GRN number', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    comp.selectedCompanyId.set('10');
    comp.selectSupplier({ id: 'SUP-ID-1', uid: 'SUP1', code: 'S001', displayName: 'Acme Supplies' });

    const options: { uid: string; label: string; hint?: string }[] = comp.grLineOptions();
    expect(options.length).toBe(1);
    expect(options[0].uid).toBe('GRL-UID-1');
    // The label carries what is printed on the paper in the accountant's hand — never a raw uid.
    expect(options[0].label).toContain('GRN-000123');
    expect(options[0].label).toContain('Widget');
    expect(options[0].label).not.toContain('GRL-UID-1');
    expect(options[0].hint).toContain('received 10 PCS');
    // Another supplier's receipt, and a voided one, are both excluded.
    expect(options.some((o) => o.uid === 'GRL-UID-OTHER')).toBe(false);
    expect(options.some((o) => o.uid === 'GRL-UID-VOID')).toBe(false);
  });

  it('renders the goods receipt picker in the line editor once receipts are loaded', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(EnterBillComponent);
    const comp = fixture.componentInstance as any;
    await vi.runAllTimersAsync();

    comp.selectedCompanyId.set('10');
    comp.selectSupplier({ id: 'SUP-ID-1', uid: 'SUP1', code: 'S001', displayName: 'Acme Supplies' });
    fixture.detectChanges();

    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('Goods Receipt Line');
    // The picker is a real labelled <select>, not a uid text box.
    const picker = el.querySelector<HTMLSelectElement>('select[aria-labelledby="grLineLabel_0"]');
    expect(picker).not.toBeNull();
    const optionText = Array.from(picker!.options).map((o) => o.textContent ?? '').join('|');
    expect(optionText).toContain('GRN-000123');
    expect(optionText).toContain('received 10 PCS');
  });

  it('the selected goods receipt line reaches the submitted payload', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    const apService = TestBed.inject(ApService) as any;

    comp.selectedCompanyId.set('10');
    comp.selectedSupplier.set({ id: 'SUP-ID-1', uid: 'SUP1', label: 'Supplier A' });
    comp.supplierInvoiceNo.set('INV-100');
    comp.billDate.set('2026-08-12');
    comp.dueDate.set('2026-09-12');
    comp.lines.set([
      { description: 'Widget', billedQty: '10', unitCostAmount: '4500', poLineUid: 'POL-1', grLineUid: 'GRL-UID-1' },
    ]);

    comp.submit();

    expect(apService.enterBill).toHaveBeenCalled();
    const request = apService.enterBill.mock.calls[0][0];
    expect(request.lines[0].grLineUid).toBe('GRL-UID-1');
    expect(request.lines[0].poLineUid).toBe('POL-1');
  });

  it('sends null (not an empty string) when no goods receipt line was attached', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    const apService = TestBed.inject(ApService) as any;

    comp.selectedCompanyId.set('10');
    comp.selectedSupplier.set({ id: 'SUP-ID-1', uid: 'SUP1', label: 'Supplier A' });
    comp.supplierInvoiceNo.set('INV-101');
    comp.billDate.set('2026-08-12');
    comp.dueDate.set('2026-09-12');
    comp.lines.set([
      { description: 'Consultancy', billedQty: '1', unitCostAmount: '500', poLineUid: '', grLineUid: '' },
    ]);

    comp.submit();

    expect(apService.enterBill.mock.calls[0][0].lines[0].grLineUid).toBeNull();
  });

  it('warns before submit when a purchase-linked line has no receipt attached, and not for a service charge', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;

    // Service charge: no order anywhere → legitimately unmatched, so no warning.
    comp.lines.set([{ description: 'Consultancy', billedQty: '1', unitCostAmount: '500', poLineUid: '', grLineUid: '' }]);
    expect(comp.linesMissingReceipt()).toBe(0);

    // Same line under a purchase order → this one WILL be held; say so while it is still fixable.
    comp.purchaseOrderUid.set('PO-UID-1');
    expect(comp.linesMissingReceipt()).toBe(1);

    comp.updateLine(0, 'grLineUid', 'GRL-UID-1');
    expect(comp.linesMissingReceipt()).toBe(0);
  });

  it('drops a stale receipt link when the supplier changes', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    comp.selectedCompanyId.set('10');
    comp.lines.set([
      { description: 'Widget', billedQty: '1', unitCostAmount: '1', poLineUid: '', grLineUid: 'GRL-UID-1' },
    ]);

    comp.selectSupplier({ id: 'SUP-ID-OTHER', uid: 'SUP2', code: 'S002', displayName: 'Other Supplies' });

    expect(comp.lines()[0].grLineUid).toBe('');
  });

  it('a failed receipt lookup leaves the form usable and says so calmly', () => {
    vi.useFakeTimers();
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
            listOrders: vi.fn(() => of({ rows: [], meta: {} })),
            listOrderLines: vi.fn(() => of([])),
            listReceipts: vi.fn(() => throwError(() => new Error('boom'))),
          },
        },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: SessionStore, useValue: makeSession() },
      ],
    });
    const comp = TestBed.createComponent(EnterBillComponent).componentInstance as any;
    comp.selectedCompanyId.set('10');
    comp.selectSupplier({ id: 'SUP-ID-1', uid: 'SUP1', code: 'S001', displayName: 'Acme Supplies' });

    expect(comp.grLookupState()).toBe('unavailable');
    expect(comp.grLineOptions()).toEqual([]);
  });
});
