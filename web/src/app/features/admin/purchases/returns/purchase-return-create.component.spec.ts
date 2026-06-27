/**
 * PurchaseReturnCreateComponent — number-input coercion regression specs.
 *
 * Covers:
 *  1. updateLineQty with a NUMBER coerces to a string.
 *  2. submit() does not throw when returnedQty was set as a number.
 *  3. submit() sends the correct stringified qty in the payload.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PurchasesService } from '../purchases.service';
import { PurchaseReturnService } from './purchase-return.service';
import { PurchaseReturnCreateComponent } from './purchase-return-create.component';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const STUB_GR_LINE = {
  uid: 'GL1', id: '11', goodsReceiptId: '21', lineNo: 1,
  productId: '5', productCode: 'P001', productName: 'Widget',
  unitId: '1', unitName: 'Each',
  qtyInBase: '60', lotNumber: null, expiryDate: null,
  manufactureDate: null, serialNumbers: null,
};

const STUB_GR = {
  uid: 'GR1', id: '21', companyId: '10', branchId: '1',
  purchaseOrderId: '1', receiptNumber: 'GR-0001', status: 'RECEIVED',
  supplierId: '2', receivedAt: null, voidedAt: null, voidReason: null,
  notes: null, createdAt: null, lines: [STUB_GR_LINE],
};

const STUB_RETURN = {
  uid: 'RET1', id: '31', returnNumber: 'RET-0001', status: 'DRAFT',
};

function makeBed(createSpy = vi.fn(() => of(STUB_RETURN))) {
  TestBed.configureTestingModule({
    imports: [PurchaseReturnCreateComponent],
    providers: [
      provideRouter([{ path: 'admin/purchase-returns/uid/:uid', component: PurchaseReturnCreateComponent }]),
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      {
        provide: PurchasesService,
        useValue: {
          listReceipts: vi.fn(() => of({ rows: [{ uid: 'GR1', receiptNumber: 'GR-0001', status: 'RECEIVED' }], meta: {} })),
          getReceiptByUid: vi.fn(() => of(STUB_GR)),
        },
      },
      { provide: PurchaseReturnService, useValue: { create: createSpy } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return { createSpy };
}

describe('PurchaseReturnCreateComponent — number-input coercion', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. updateLineQty coerces number to string ─────────────────────────────

  it('updateLineQty coerces a numeric value to a string', async () => {
    makeBed();
    const comp = TestBed.createComponent(PurchaseReturnCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    // Seed one return line manually (as if a GR was loaded).
    comp.returnLines.set([{ line: STUB_GR_LINE as any, returnedQty: '', include: true }]);

    // Simulate ngModelChange emitting a number.
    comp.updateLineQty(0, 5 as unknown as string);

    expect(typeof comp.returnLines()[0].returnedQty).toBe('string');
    expect(comp.returnLines()[0].returnedQty).toBe('5');
  });

  // ── 2. submit() does not throw when returnedQty set as a number ───────────

  it('submit() does not throw when returnedQty was set as a number', async () => {
    const { createSpy } = makeBed();
    const comp = TestBed.createComponent(PurchaseReturnCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.selectedGrUid.set('GR1');
    comp.reason.set('Damaged goods');
    comp.returnLines.set([{ line: STUB_GR_LINE as any, returnedQty: '', include: true }]);
    comp.updateLineQty(0, 15 as unknown as string);

    expect(() => comp.submit()).not.toThrow();
    await vi.runAllTimersAsync();
  });

  // ── 3. Payload carries the stringified qty ────────────────────────────────

  it('submit() posts the stringified qty when returnedQty arrived as a number', async () => {
    const { createSpy } = makeBed();
    const comp = TestBed.createComponent(PurchaseReturnCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.selectedGrUid.set('GR1');
    comp.reason.set('Damaged goods');
    comp.returnLines.set([{ line: STUB_GR_LINE as any, returnedQty: '', include: true }]);
    comp.updateLineQty(0, 15 as unknown as string);

    comp.submit();
    await vi.runAllTimersAsync();

    expect(createSpy).toHaveBeenCalledOnce();
    const payload = (createSpy.mock.calls as any[][])[0][0];
    expect(payload.lines[0].returnedQty).toBe('15');
  });
});
