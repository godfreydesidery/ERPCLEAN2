/**
 * DirectGoodsReceiptComponent — key behaviour specs (K3).
 *
 * Covers:
 *  1. Submit builds the DirectGoodsReceiptRequest the backend expects.
 *  2. Submitting with no supplier does not call the API.
 *  3. Submitting with no lines does not call the API.
 *  4. A zero unit cost without a note is refused (mirrors the backend rule).
 *  5. The server's user-safe message is surfaced inline on failure.
 *  6. The screen is gated on PURCHASE.RECEIVE.DIRECT — the same code as route guard and endpoint.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Route } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { SupplierService } from '../parties/supplier.service';
import { PurchasesService } from './purchases.service';
import { DirectGoodsReceiptComponent } from './direct-goods-receipt.component';

@Component({ template: '', standalone: true })
class StubRouteComponent {}

const STUB_ROUTES: Route[] = [{ path: '**', component: StubRouteComponent }];

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_SUPPLIER = { uid: 'SUP-UID-1', id: '2', code: 'SUP001', displayName: 'Walk-in Traders', status: 'ACTIVE' };
const STUB_PRODUCT = { uid: 'PRD-UID-1', id: '5', code: 'P001', name: 'Sugar 1kg', status: 'ACTIVE' };
const STUB_UNIT = { uid: 'UOM-UID-1', id: '1', companyId: '10', code: 'PCS', name: 'Piece', status: 'ACTIVE' };

const STUB_GR = {
  uid: 'GR-UID-1', id: '21', companyId: '10', branchId: '1',
  purchaseOrderId: '1', receiptNumber: 'GRN-0349', status: 'RECEIVED',
  supplierId: '2', receivedAt: null, voidedAt: null, voidReason: null,
  notes: null, createdAt: null, lines: null,
};

function makeBed(overrides: {
  receiveDirectSpy?: ReturnType<typeof vi.fn>;
  hasPermission?: (code: string) => boolean;
} = {}) {
  const receiveDirectSpy = overrides.receiveDirectSpy ?? vi.fn(() => of(STUB_GR));

  TestBed.configureTestingModule({
    imports: [DirectGoodsReceiptComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter(STUB_ROUTES),
      { provide: PurchasesService, useValue: { receiveDirect: receiveDirectSpy } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      {
        provide: SupplierService,
        useValue: {
          list: vi.fn(() => of({
            rows: [STUB_SUPPLIER],
            meta: { page: 0, size: 10, totalElements: 1, totalPages: 1, hasNext: false },
          })),
        },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({
            rows: [STUB_PRODUCT],
            meta: { page: 0, size: 10, totalElements: 1, totalPages: 1, hasNext: false },
          })),
          listProductUnits: vi.fn(() => of([STUB_UNIT])),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(overrides.hasPermission ?? (() => true)),
          isAuthenticated: signal(true),
          user: signal({ activeCompanyUid: STUB_COMPANY.uid, isRoot: false }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { receiveDirectSpy };
}

/** Drives the screen to a submittable state: supplier picked, one line staged. */
async function stageOneLine(comp: DirectGoodsReceiptComponent, cost = '100'): Promise<void> {
  comp.selectSupplier(STUB_SUPPLIER as never);
  comp.selectProduct(STUB_PRODUCT as never);
  await vi.runAllTimersAsync();
  comp.newLineQty.set('20');
  comp.newLineCost.set(cost);
  comp.addLine();
}

describe('DirectGoodsReceiptComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('submits the supplier, currency and lines the backend expects', async () => {
    const { receiveDirectSpy } = makeBed();
    const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.notes.set('supplier DN 4471');
    await stageOneLine(comp);
    expect(comp.stagedLines()).toHaveLength(1);

    comp.submit();
    await vi.runAllTimersAsync();

    expect(receiveDirectSpy).toHaveBeenCalledTimes(1);
    expect(receiveDirectSpy).toHaveBeenCalledWith({
      companyUid: 'CO1',
      supplierUid: 'SUP-UID-1',
      currency: 'TZS',
      notes: 'supplier DN 4471',
      lines: [{
        productUid: 'PRD-UID-1',
        unitUid: 'UOM-UID-1',
        receivedQty: '20',
        unitCostAmount: '100',
      }],
    });
  });

  it('does not call the API when no supplier is selected', async () => {
    const { receiveDirectSpy } = makeBed();
    const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as never);
    await vi.runAllTimersAsync();
    comp.newLineQty.set('20');
    comp.newLineCost.set('100');
    comp.addLine();

    comp.submit();
    await vi.runAllTimersAsync();

    expect(receiveDirectSpy).not.toHaveBeenCalled();
    expect(comp.formError()).toContain('supplier');
  });

  it('does not call the API when no lines were added', async () => {
    const { receiveDirectSpy } = makeBed();
    const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectSupplier(STUB_SUPPLIER as never);
    comp.submit();
    await vi.runAllTimersAsync();

    expect(receiveDirectSpy).not.toHaveBeenCalled();
    expect(comp.formError()).toContain('at least one item');
  });

  it('refuses a zero unit cost with no note (mirrors the backend rule)', async () => {
    makeBed();
    const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    await stageOneLine(comp, '0');

    expect(comp.stagedLines()).toHaveLength(0);
    expect(comp.lineFormError()).toContain('note');
  });

  it('surfaces the server message inline when the receipt is rejected', async () => {
    const err = new HttpErrorResponse({
      status: 409,
      error: { data: null, errors: ['This delivery could not be recorded.'] },
    });
    const { receiveDirectSpy } = makeBed({ receiveDirectSpy: vi.fn(() => throwError(() => err)) });
    const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    await stageOneLine(comp);
    comp.submit();
    await vi.runAllTimersAsync();

    expect(receiveDirectSpy).toHaveBeenCalled();
    expect(comp.formError()).toBe('This delivery could not be recorded.');
    expect(comp.submitting()).toBe(false);
  });

  it('is gated on PURCHASE.RECEIVE.DIRECT — the same code as the route guard and the endpoint', async () => {
    makeBed({ hasPermission: (code) => code === 'PURCHASE.RECEIVE.DIRECT' });
    const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.canReceiveDirect()).toBe(true);
  });
});
