/**
 * GoodsReceiptCreateComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads PO lines from query param poUid on init.
 *  2. Outstanding qty defaulted to receivedQty for each line.
 *  3. Submit builds correct CreateGoodsReceiptRequest (included lines only).
 *  4. 409 over-receipt error surfaced in formError.
 *  5. Zero qty → per-line validation error, createReceipt NOT called.
 *  6. Excluding all lines → formError.
 *  7. Batch/serial fields included in payload when provided.
 *  8. Empty batch/serial fields are omitted from the payload.
 *  9. Over-receipt tolerance banner shows when the company setting has a tolerance configured.
 * 10. Over-receipt tolerance banner is absent when the setting is null/0 (strict).
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Route } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PurchasesService } from './purchases.service';
import { PurchaseSettingsService } from './settings/purchase-settings.service';
import { GoodsReceiptCreateComponent } from './goods-receipt-create.component';

@Component({ template: '', standalone: true })
class StubRouteComponent {}

const STUB_ROUTES: Route[] = [
  { path: '**', component: StubRouteComponent },
];

// ── Stubs ─────────────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const STUB_PO = {
  uid: 'PO-UID-1', id: '1', companyId: '10', branchId: '1',
  orderNumber: 'PO-0001', status: 'ORDERED', supplierId: '2',
  supplierCode: 'SUP001', supplierName: 'Supplier A',
  currency: 'TZS', orderTotalAmount: '50000',
  expectedDate: null, notes: null, orderedAt: null,
  voidedAt: null, voidReason: null, closedAt: null,
  createdAt: null, lines: null,
};

const STUB_LINE = {
  uid: 'LINE-UID-1', id: '11', purchaseOrderId: '1', lineNo: 1,
  productId: '5', productCode: 'P001', productName: 'Widget',
  unitId: '1', unitName: 'Each',
  orderedQty: '100', orderedQtyInBase: '100',
  receivedQtyInBase: '40', outstandingQtyInBase: '60',
  fullyReceived: false,
  unitCostAmount: '500', lineTotalAmount: '50000', currency: 'TZS',
};

const STUB_GR = {
  uid: 'GR-UID-1', id: '21', companyId: '10', branchId: '1',
  purchaseOrderId: '1', receiptNumber: 'GR-0001', status: 'RECEIVED',
  supplierId: '2', receivedAt: null, voidedAt: null, voidReason: null,
  notes: null, createdAt: null, lines: null,
};

const STUB_SETTINGS_NO_TOLERANCE = {
  id: '1', uid: 'PS-1', companyId: '10',
  poApprovalEnabled: false, poApprovalThresholdAmount: '0', currency: 'TZS',
  receiptTolerancePct: null as number | null,
};

const STUB_SETTINGS_WITH_TOLERANCE = {
  ...STUB_SETTINGS_NO_TOLERANCE,
  receiptTolerancePct: 5,
};

function makeBed(overrides: {
  poUid?: string;
  getOrderByUidSpy?: ReturnType<typeof vi.fn>;
  listOrderLinesSpy?: ReturnType<typeof vi.fn>;
  createReceiptSpy?: ReturnType<typeof vi.fn>;
  settingsResponse?: object;
} = {}) {
  const poUid = overrides.poUid ?? 'PO-UID-1';
  const getOrderByUidSpy = overrides.getOrderByUidSpy ?? vi.fn(() => of(STUB_PO));
  const listOrderLinesSpy = overrides.listOrderLinesSpy ?? vi.fn(() => of([STUB_LINE]));
  const createReceiptSpy = overrides.createReceiptSpy ?? vi.fn(() => of(STUB_GR));
  const settingsSpy = vi.fn(() => of(overrides.settingsResponse ?? STUB_SETTINGS_NO_TOLERANCE));

  TestBed.configureTestingModule({
    imports: [GoodsReceiptCreateComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter(STUB_ROUTES),
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            queryParamMap: {
              get: (key: string) => (key === 'poUid' ? poUid : null),
            },
          },
        },
      },
      {
        provide: PurchasesService,
        useValue: {
          getOrderByUid: getOrderByUidSpy,
          listOrderLines: listOrderLinesSpy,
          listOrders: vi.fn(() => of({ rows: [], meta: { page: 0, size: 10, totalElements: 0, totalPages: 0, hasNext: false } })),
          createReceipt: createReceiptSpy,
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of(STUB_ORG)) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([STUB_COMPANY])) },
      },
      {
        provide: PurchaseSettingsService,
        useValue: { getByCompany: settingsSpy },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal({ activeCompanyUid: STUB_COMPANY.uid, isRoot: false }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { getOrderByUidSpy, listOrderLinesSpy, createReceiptSpy, settingsSpy };
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('GoodsReceiptCreateComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Loads PO and lines from query param ─────────────────────────────────

  it('loads the PO and its outstanding lines from the poUid query param', async () => {
    const { getOrderByUidSpy, listOrderLinesSpy } = makeBed();
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(getOrderByUidSpy).toHaveBeenCalledWith('PO-UID-1');
    expect(listOrderLinesSpy).toHaveBeenCalledWith('PO-UID-1');
    expect(comp.po()?.uid).toBe('PO-UID-1');
    // Only non-fully-received lines included
    expect(comp.receiveLines()).toHaveLength(1);
  });

  // ── 2. Outstanding qty defaulted ──────────────────────────────────────────

  it('defaults receivedQty to outstandingQtyInBase for each line', async () => {
    makeBed();
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    const entry = comp.receiveLines()[0];
    expect(entry.receivedQty).toBe(STUB_LINE.outstandingQtyInBase);
    expect(entry.include).toBe(true);
  });

  // ── 3. Submit builds correct request ──────────────────────────────────────

  it('calls createReceipt with correct payload for included lines', async () => {
    const { createReceiptSpy } = makeBed();
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.notes.set('Test delivery');
    comp.submit();
    await vi.runAllTimersAsync();

    expect(createReceiptSpy).toHaveBeenCalledOnce();
    const req = createReceiptSpy.mock.calls[0][0];
    expect(req.purchaseOrderUid).toBe('PO-UID-1');
    expect(req.notes).toBe('Test delivery');
    expect(req.lines).toHaveLength(1);
    expect(req.lines[0].purchaseOrderLineUid).toBe(STUB_LINE.uid);
    expect(req.lines[0].receivedQty).toBe(STUB_LINE.outstandingQtyInBase);
  });

  // ── 3b. Receive-remaining regression: numeric outstandingQtyInBase on the wire ──
  // BigDecimal serialises as a JSON *number*, so outstandingQtyInBase arrives numeric. The
  // prefilled receivedQty must still be coerced to a string, else submit's receivedQty.trim()
  // throws "trim is not a function" when the user accepts the remaining without editing.
  it('submits the prefilled remaining when outstandingQtyInBase arrives as a number', async () => {
    const numericLine = { ...STUB_LINE, outstandingQtyInBase: 60 as unknown as string };
    const { createReceiptSpy } = makeBed({ listOrderLinesSpy: vi.fn(() => of([numericLine])) });
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // receivedQty must be a string even though the wire value was numeric.
    expect(comp.receiveLines()[0].receivedQty).toBe('60');

    expect(() => comp.submit()).not.toThrow();
    await vi.runAllTimersAsync();

    expect(createReceiptSpy).toHaveBeenCalledOnce();
    expect(createReceiptSpy.mock.calls[0][0].lines[0].receivedQty).toBe('60');
  });

  // ── 4. 409 over-receipt surfaces in formError ──────────────────────────────

  it('surfaces the server 409 message (not a canned string) as formError', async () => {
    // The screen now shows the server's actual reason, which distinguishes over-receipt from an
    // already-received/closed PO — rather than always saying "reduce the quantities".
    const serverMsg =
      'Over-receipt rejected for Widget: the quantity received exceeds the outstanding amount on this line. Reduce it and try again.';
    const createReceiptSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 409, error: { errors: [serverMsg] } })),
    );
    makeBed({ createReceiptSpy });
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.submit();
    await vi.runAllTimersAsync();

    expect(comp.formError()).toBe(serverMsg);
  });

  // ── 5. Zero qty validation ─────────────────────────────────────────────────

  it('does NOT call createReceipt when receivedQty is zero', async () => {
    const { createReceiptSpy } = makeBed();
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Set qty to 0
    comp.updateLineQty(0, '0');
    comp.submit();

    expect(comp.lineErrors()[STUB_LINE.uid]).toBeTruthy();
    expect(createReceiptSpy).not.toHaveBeenCalled();
  });

  // ── 6. Excluding all lines → formError ────────────────────────────────────

  it('sets formError when all lines are excluded', async () => {
    const { createReceiptSpy } = makeBed();
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.toggleLineInclude(0, false);
    comp.submit();

    expect(comp.formError()).toContain('at least one line');
    expect(createReceiptSpy).not.toHaveBeenCalled();
  });

  // ── 7. Batch/serial fields included in payload when provided ──────────────

  it('includes lotNumber, dates and serialNumbers in the line payload when provided', async () => {
    const { createReceiptSpy } = makeBed();
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Simulate user entering batch detail for line 0
    comp.updateLineBatchField(0, 'lotNumber', 'LOT-2024-001');
    comp.updateLineBatchField(0, 'expiryDate', '2025-12-31');
    comp.updateLineBatchField(0, 'manufactureDate', '2024-01-15');
    comp.updateLineBatchField(0, 'serialNumbersRaw', 'SN-001\nSN-002\n  \nSN-003');

    comp.submit();
    await vi.runAllTimersAsync();

    expect(createReceiptSpy).toHaveBeenCalledOnce();
    const line = createReceiptSpy.mock.calls[0][0].lines[0];
    expect(line.lotNumber).toBe('LOT-2024-001');
    expect(line.expiryDate).toBe('2025-12-31');
    expect(line.manufactureDate).toBe('2024-01-15');
    // Blank line should be dropped; trimmed serials preserved
    expect(line.serialNumbers).toEqual(['SN-001', 'SN-002', 'SN-003']);
  });

  // ── 8. Empty batch/serial fields are omitted from the payload ─────────────

  it('omits lotNumber, dates and serialNumbers when left blank', async () => {
    const { createReceiptSpy } = makeBed();
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Leave all batch fields at their default empty values
    comp.submit();
    await vi.runAllTimersAsync();

    expect(createReceiptSpy).toHaveBeenCalledOnce();
    const line = createReceiptSpy.mock.calls[0][0].lines[0];
    expect(line.lotNumber).toBeUndefined();
    expect(line.expiryDate).toBeUndefined();
    expect(line.manufactureDate).toBeUndefined();
    expect(line.serialNumbers).toBeUndefined();
  });

  // ── 9. Tolerance banner shows when configured ─────────────────────────────

  it('shows the over-receipt tolerance banner when the company setting has a tolerance', async () => {
    const { settingsSpy } = makeBed({ settingsResponse: STUB_SETTINGS_WITH_TOLERANCE });
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(settingsSpy).toHaveBeenCalledWith(STUB_COMPANY.uid);
    expect(comp.hasTolerance()).toBe(true);
    const banner = fixture.nativeElement.querySelector('.alert-info');
    expect(banner?.textContent).toContain('5%');
  });

  // ── 10. Tolerance banner absent when null/0 (strict) ──────────────────────

  it('does not show the tolerance banner when the company setting is null (strict)', async () => {
    makeBed({ settingsResponse: STUB_SETTINGS_NO_TOLERANCE });
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(comp.hasTolerance()).toBe(false);
    expect(fixture.nativeElement.querySelector('.alert-info')).toBeNull();
  });

  it('does not show the tolerance banner when the company setting is 0 (strict)', async () => {
    makeBed({ settingsResponse: { ...STUB_SETTINGS_NO_TOLERANCE, receiptTolerancePct: 0 } });
    const fixture = TestBed.createComponent(GoodsReceiptCreateComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    expect(comp.hasTolerance()).toBe(false);
    expect(fixture.nativeElement.querySelector('.alert-info')).toBeNull();
  });
});
