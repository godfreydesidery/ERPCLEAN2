/**
 * DeliveryCreateComponent — regression specs for numeric-input string-op crash class.
 *
 * updateQty() is called with a JS number from type="number" ngModelChange.
 * After the fix it coerces to string so qtyInput is always a string when posted.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { SalesOrdersService } from './sales-orders.service';
import { DeliveryCreateComponent } from './delivery-create.component';

const stubLine = {
  uid: 'LINE1', id: '1', lineNo: 1,
  productId: '20', productCode: 'P001', productName: 'Widget',
  unitId: '1', unitName: 'pcs',
  qty: '10', qtyBase: '10', openQtyBase: '10',
  deliveredQtyBase: '0', invoicedQtyBase: '0',
};

const stubDelivery = { uid: 'DEL1', id: '1', deliveryNumber: 'DN-0001', status: 'CONFIRMED' };

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed() {
  TestBed.configureTestingModule({
    imports: [DeliveryCreateComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: 'admin/deliveries/uid/:uid', component: DeliveryCreateComponent }]),
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: { get: () => 'SO1' } } },
      },
      {
        provide: SalesOrdersService,
        useValue: {
          getOrderByUid: vi.fn(() => of({ uid: 'SO1', id: '1', orderNumber: 'SO-0001', status: 'CONFIRMED', companyId: '10' })),
          listOrderLines: vi.fn(() => of([stubLine])),
          createDelivery: vi.fn(() => of(stubDelivery)),
          listDeliveriesForOrder: vi.fn(() => of([])),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
}

// ── updateQty numeric coercion ────────────────────────────────────────────────

describe('DeliveryCreateComponent — numeric field coercion', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('updateQty stores qtyInput as a string when passed a number', async () => {
    const comp = TestBed.createComponent(DeliveryCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    // Simulate NumberValueAccessor emitting a JS number from type="number".
    comp.updateQty(0, 7 as unknown as number);

    expect(typeof comp.lineEntries()[0].qtyInput).toBe('string');
    expect(comp.lineEntries()[0].qtyInput).toBe('7');
  });

  it('submit() does not throw and posts stringified qty when input holds a number', async () => {
    const comp = TestBed.createComponent(DeliveryCreateComponent).componentInstance;
    const svc = TestBed.inject(SalesOrdersService) as any;
    await vi.runAllTimersAsync();

    comp.deliveryDate.set('2025-06-01');
    // Force a numeric qtyInput to simulate unedited type="number" field.
    comp.lineEntries.update((entries) =>
      entries.map((e) => ({ ...e, qtyInput: 5 as unknown as string, include: true })),
    );

    expect(() => comp.submit()).not.toThrow();
    expect(svc.createDelivery).toHaveBeenCalledOnce();
    const body = svc.createDelivery.mock.calls[0][0];
    // Wire payload must be the numeric string, not a raw number.
    expect(body.lines[0].qtyDelivered).toBe('5');
  });
});
