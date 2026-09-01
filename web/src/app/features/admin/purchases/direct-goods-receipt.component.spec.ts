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

/** A cost the system already holds for the item — what K-2026-08-30 #4 asked to stop retyping. */
const STUB_SUGGESTION = {
  amount: 4500,
  currency: 'TZS',
  source: 'LAST_PURCHASE' as const,
  asOf: '2026-08-14',
};

function makeBed(overrides: {
  receiveDirectSpy?: ReturnType<typeof vi.fn>;
  directCostSuggestionSpy?: ReturnType<typeof vi.fn>;
  hasPermission?: (code: string) => boolean;
} = {}) {
  const receiveDirectSpy = overrides.receiveDirectSpy ?? vi.fn(() => of(STUB_GR));
  // Default: nothing known. The screen must behave exactly as it did before the suggestion existed.
  const directCostSuggestionSpy = overrides.directCostSuggestionSpy ?? vi.fn(() => of(null));

  TestBed.configureTestingModule({
    imports: [DirectGoodsReceiptComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter(STUB_ROUTES),
      {
        provide: PurchasesService,
        useValue: {
          receiveDirect: receiveDirectSpy,
          directCostSuggestion: directCostSuggestionSpy,
        },
      },
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

  return { receiveDirectSpy, directCostSuggestionSpy };
}

/**
 * Drives the screen to a submittable state: supplier picked, one line staged.
 *
 * <p>Qty/cost are set as NUMBERS on purpose. Angular's NumberValueAccessor claims
 * `input[type=number][ngModel]` and parseFloats the DOM value, so at runtime these signals hold
 * numbers even though they are declared `signal('')`. Setting strings here is what let a
 * `.trim() is not a function` crash ship green — see the DOM-driven spec below.
 */
async function stageOneLine(
  comp: DirectGoodsReceiptComponent,
  cost: number | string = 100,
): Promise<void> {
  comp.selectSupplier(STUB_SUPPLIER as never);
  comp.selectProduct(STUB_PRODUCT as never);
  await vi.runAllTimersAsync();
  comp.newLineQty.set(20 as unknown as string);
  comp.newLineCost.set(cost as unknown as string);
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

  // K-2026-08-30 #4: "have items pick cost price already existing in the system, not having to
  // input the cost price all the time."
  describe('unit-cost suggestion', () => {
    it('fills the cost box from the stored price and says where it came from', async () => {
      const { directCostSuggestionSpy } = makeBed({
        directCostSuggestionSpy: vi.fn(() => of(STUB_SUGGESTION)),
      });
      const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
      const comp = fixture.componentInstance;
      await vi.runAllTimersAsync();

      comp.selectSupplier(STUB_SUPPLIER as never);
      comp.selectProduct(STUB_PRODUCT as never);
      await vi.runAllTimersAsync();

      expect(directCostSuggestionSpy).toHaveBeenCalledWith(
        'CO1', 'SUP-UID-1', 'PRD-UID-1', 'UOM-UID-1');
      expect(comp.newLineCost()).toBe('4500');
      expect(comp.costSuggestion()).toEqual(STUB_SUGGESTION);
    });

    // The supplier narrows the answer but must not gate it: the storekeeper often picks the items
    // first, and the product master's cost still answers without one.
    it('asks without a supplier when none is picked yet', async () => {
      const { directCostSuggestionSpy } = makeBed({
        directCostSuggestionSpy: vi.fn(() => of(STUB_SUGGESTION)),
      });
      const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
      const comp = fixture.componentInstance;
      await vi.runAllTimersAsync();

      comp.selectProduct(STUB_PRODUCT as never);
      await vi.runAllTimersAsync();

      expect(directCostSuggestionSpy).toHaveBeenCalledWith('CO1', '', 'PRD-UID-1', 'UOM-UID-1');
    });

    // A defaulted cost feeds the moving average. A figure the storekeeper actually typed is the
    // one they checked, so it must survive.
    it('never overwrites a cost the storekeeper typed', async () => {
      makeBed({ directCostSuggestionSpy: vi.fn(() => of(STUB_SUGGESTION)) });
      const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
      const comp = fixture.componentInstance;
      await vi.runAllTimersAsync();

      comp.onUnitCostChange(7250);
      comp.selectProduct(STUB_PRODUCT as never);
      await vi.runAllTimersAsync();

      expect(comp.newLineCost()).toBe('7250');
    });

    // Regression guard: our own prefill must not linger onto the next item, or the line would be
    // staged at the previous product's price.
    it('drops a figure it filled in when the item changes', async () => {
      makeBed({ directCostSuggestionSpy: vi.fn(() => of(STUB_SUGGESTION)) });
      const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
      const comp = fixture.componentInstance;
      await vi.runAllTimersAsync();

      comp.selectProduct(STUB_PRODUCT as never);
      await vi.runAllTimersAsync();
      expect(comp.newLineCost()).toBe('4500');

      comp.onProductSearchChange('something else');
      expect(comp.newLineCost()).toBe('');
      expect(comp.costSuggestion()).toBeNull();
    });

    // The suggestion is a convenience. A lookup that fails must leave the storekeeper typing, not
    // block the delivery.
    it('leaves the box blank and silent when nothing is known', async () => {
      makeBed();
      const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
      const comp = fixture.componentInstance;
      await vi.runAllTimersAsync();

      comp.selectProduct(STUB_PRODUCT as never);
      await vi.runAllTimersAsync();

      expect(comp.newLineCost()).toBe('');
      expect(comp.costSuggestion()).toBeNull();
    });
  });

  it('refuses a zero unit cost with no note (mirrors the backend rule)', async () => {
    makeBed();
    const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    await stageOneLine(comp, 0);

    expect(comp.stagedLines()).toHaveLength(0);
    expect(comp.lineFormError()).toContain('note');
  });

  // Regression: the screen shipped with `.trim()` called on signals that hold NUMBERS at runtime,
  // so "+ Add item" threw a TypeError before any validation message could be set and the button
  // appeared completely inert. Every existing spec missed it by poking the signals with strings —
  // the one path Angular's NumberValueAccessor never takes. This one drives the real DOM.
  it('stages a line when qty and cost are typed into the number inputs', async () => {
    makeBed();
    const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectSupplier(STUB_SUPPLIER as never);
    comp.selectProduct(STUB_PRODUCT as never);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const qty = el.querySelector<HTMLInputElement>('input#drQty')!;
    const cost = el.querySelector<HTMLInputElement>('input#drCost')!;
    expect(qty).toBeTruthy();
    expect(cost).toBeTruthy();

    qty.value = '50';
    qty.dispatchEvent(new Event('input'));
    cost.value = '5000';
    cost.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await vi.runAllTimersAsync();

    // What the real screen delivers: numbers, not strings.
    expect(typeof comp.newLineQty()).toBe('number');

    comp.addLine();

    expect(comp.lineFormError()).toBeNull();
    expect(comp.stagedLines()).toHaveLength(1);
    expect(comp.stagedLines()[0]).toMatchObject({ qty: '50', unitCost: '5000' });
  });

  it('keeps "Receive into stock" clickable with no lines so the guard can explain why', async () => {
    makeBed();
    const fixture = TestBed.createComponent(DirectGoodsReceiptComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectSupplier(STUB_SUPPLIER as never);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    const submitBtn = Array.from(el.querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.includes('Receive into stock'));

    expect(submitBtn).toBeTruthy();
    // A disabled button fires no click at all, so the user would get silence instead of a reason.
    expect(submitBtn!.disabled).toBe(false);
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
