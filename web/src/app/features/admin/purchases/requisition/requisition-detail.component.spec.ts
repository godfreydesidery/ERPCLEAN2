/**
 * RequisitionDetailComponent — Convert (D-3) specs.
 *
 * Covers:
 *  1. Supplier options load for the requisition's company on init.
 *  2. Confirm Convert is disabled until a supplier is chosen (PURCHASE_ORDER branch).
 *  3. Confirm Convert is disabled until at least one supplier is invited (RFQ branch).
 *  4. confirmConvert() posts { targetType: 'PURCHASE_ORDER', supplierUid, currency }.
 *  5. confirmConvert() posts { targetType: 'RFQ', supplierUids } (no supplierUid/currency).
 *  6. A successful convert sets convertedUid and resets the form.
 *  7. The converted-to link renders (in the DOM) once the reloaded entity carries convertedToUid.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { SupplierService } from '../../parties/supplier.service';
import { ConvertRequisitionRequest, PurchaseRequisitionDto } from './purchase-requisition.model';
import { PurchaseRequisitionService } from './purchase-requisition.service';
import { RequisitionDetailComponent } from './requisition-detail.component';

// ── Stubs ──────────────────────────────────────────────────────────────────────

const STUB_REQ: PurchaseRequisitionDto = {
  uid: 'REQ1', id: '1', companyId: '10', branchId: '1',
  requisitionNumber: 'PR-0001', status: 'APPROVED',
  requiredByDate: null, costCentreCode: null,
  approvalRequestUid: null, approvalStatus: 'APPROVED',
  convertedToType: null, convertedToUid: null,
  notes: null, submittedAt: null, approvedAt: null,
  rejectedAt: null, convertedAt: null, cancelledAt: null,
  createdAt: null, lines: [],
};

const STUB_SUPPLIERS = [
  { uid: 'SUP1', id: '1', companyId: '10', code: 'SUP-A', displayName: 'Supplier A', status: 'ACTIVE' },
  { uid: 'SUP2', id: '2', companyId: '10', code: 'SUP-B', displayName: 'Supplier B', status: 'ACTIVE' },
];

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: {
  convertSpy?: ReturnType<typeof vi.fn>;
  entity?: PurchaseRequisitionDto;
  getByUidSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const entity = opts.entity ?? STUB_REQ;
  const getByUidSpy = opts.getByUidSpy ?? vi.fn(() => of(entity));
  const convertSpy = opts.convertSpy ?? vi.fn(() => of('CREATED-UID-1'));

  TestBed.configureTestingModule({
    imports: [RequisitionDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PurchaseRequisitionService,
        useValue: {
          getByUid: getByUidSpy,
          convert: convertSpy,
        },
      },
      {
        provide: SupplierService,
        useValue: {
          list: vi.fn(() => of({ rows: STUB_SUPPLIERS, meta: {} })),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });

  return { convertSpy, getByUidSpy };
}

describe('RequisitionDetailComponent — Convert', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Supplier options load ────────────────────────────────────────────────

  it('loads supplier options for the requisition company on init', async () => {
    makeBed();
    const fixture = TestBed.createComponent(RequisitionDetailComponent);
    fixture.componentRef.setInput('uid', 'REQ1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.supplierOptions()).toHaveLength(2);
    expect(comp.supplierOptions().map((o) => o.uid)).toEqual(['SUP1', 'SUP2']);
  });

  // ── 2/3. Confirm disabled until required supplier(s) chosen ────────────────

  it('canConfirmConvert is false for PURCHASE_ORDER until a supplier is picked', async () => {
    makeBed();
    const fixture = TestBed.createComponent(RequisitionDetailComponent);
    fixture.componentRef.setInput('uid', 'REQ1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.convertTargetType.set('PURCHASE_ORDER');
    expect(comp.canConfirmConvert()).toBe(false);

    comp.convertSupplierUid.set('SUP1');
    expect(comp.canConfirmConvert()).toBe(true);
  });

  it('canConfirmConvert is false for RFQ until at least one supplier is invited', async () => {
    makeBed();
    const fixture = TestBed.createComponent(RequisitionDetailComponent);
    fixture.componentRef.setInput('uid', 'REQ1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.convertTargetType.set('RFQ');
    expect(comp.canConfirmConvert()).toBe(false);

    comp.convertSupplierUids.set(['SUP1']);
    expect(comp.canConfirmConvert()).toBe(true);
  });

  // ── 4. PURCHASE_ORDER body ───────────────────────────────────────────────────

  it('confirmConvert() posts { targetType: PURCHASE_ORDER, supplierUid, currency }', async () => {
    const { convertSpy } = makeBed();
    const fixture = TestBed.createComponent(RequisitionDetailComponent);
    fixture.componentRef.setInput('uid', 'REQ1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.convertTargetType.set('PURCHASE_ORDER');
    comp.convertSupplierUid.set('SUP1');
    comp.convertCurrency.set('USD');
    comp.confirmConvert();

    expect(convertSpy).toHaveBeenCalledOnce();
    const req = convertSpy.mock.calls[0][1] as ConvertRequisitionRequest;
    expect(req).toEqual({ targetType: 'PURCHASE_ORDER', supplierUid: 'SUP1', currency: 'USD' });
  });

  // ── 5. RFQ body ────────────────────────────────────────────────────────────

  it('confirmConvert() posts { targetType: RFQ, supplierUids } with no supplierUid/currency', async () => {
    const { convertSpy } = makeBed();
    const fixture = TestBed.createComponent(RequisitionDetailComponent);
    fixture.componentRef.setInput('uid', 'REQ1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.convertTargetType.set('RFQ');
    comp.convertSupplierUids.set(['SUP1', 'SUP2']);
    comp.confirmConvert();

    expect(convertSpy).toHaveBeenCalledOnce();
    const req = convertSpy.mock.calls[0][1] as ConvertRequisitionRequest;
    expect(req).toEqual({ targetType: 'RFQ', supplierUids: ['SUP1', 'SUP2'] });
  });

  // ── 6. Success resets the form + sets convertedUid ──────────────────────────

  it('a successful convert sets convertedUid and resets the convert form', async () => {
    const converted: PurchaseRequisitionDto = {
      ...STUB_REQ, status: 'CONVERTED', convertedToType: 'PURCHASE_ORDER', convertedToUid: 'CREATED-UID-1',
    };
    // 1st call = init() (unconverted); 2nd call = the post-convert reload (converted).
    const getByUidSpy = vi.fn().mockReturnValueOnce(of(STUB_REQ)).mockReturnValue(of(converted));
    const { convertSpy } = makeBed({ getByUidSpy });
    const fixture = TestBed.createComponent(RequisitionDetailComponent);
    fixture.componentRef.setInput('uid', 'REQ1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.convertTargetType.set('PURCHASE_ORDER');
    comp.convertSupplierUid.set('SUP1');
    comp.confirmConvert();
    await vi.runAllTimersAsync();

    expect(convertSpy).toHaveBeenCalledOnce();
    expect(comp.convertedUid()).toBe('CREATED-UID-1');
    expect(comp.showConvertForm()).toBe(false);
    // Form reset back to defaults.
    expect(comp.convertSupplierUid()).toBe('');
    expect(comp.convertTargetType()).toBe('PURCHASE_ORDER');
    // Reloaded entity carries the convertedToUid — feeds the "View Purchase Order" link.
    expect(comp.entity()?.convertedToUid).toBe('CREATED-UID-1');
  });

  // ── 7. Converted-to link renders ─────────────────────────────────────────────

  it('renders the "View Purchase Order" link once the entity carries convertedToUid', async () => {
    const converted: PurchaseRequisitionDto = {
      ...STUB_REQ, status: 'CONVERTED', convertedToType: 'PURCHASE_ORDER', convertedToUid: 'PO-UID-9',
    };
    makeBed({ getByUidSpy: vi.fn(() => of(converted)) });
    const fixture = TestBed.createComponent(RequisitionDetailComponent);
    fixture.componentRef.setInput('uid', 'REQ1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const link = Array.from(el.querySelectorAll('a')).find((a) =>
      (a.textContent ?? '').includes('View Purchase Order'),
    );
    expect(link).toBeTruthy();
  });
});
