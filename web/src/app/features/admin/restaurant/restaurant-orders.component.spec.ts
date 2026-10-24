/**
 * RestaurantOrdersComponent specs (ADR-0065): live orders, status wording, cancel rules, axe.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { assertA11y } from '../../../../testing/a11y.helper';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { RestaurantOrderDto } from './models/restaurant.model';
import { RestaurantService } from './restaurant.service';
import { RestaurantOrdersComponent } from './restaurant-orders.component';

const order: RestaurantOrderDto = {
  id: '1', uid: 'O1', branchId: '2', orderNumber: 'ORD-0001', orderType: 'DINE_IN', status: 'OPEN',
  tableUid: 'T1', tableName: 'T1', guestCount: 2, customerId: null, openedBy: '5',
  openedByName: 'Asha', openedAt: '2026-10-24T10:00:00Z', billedAt: null, billedTotalAmount: null,
  billPrintCount: 0, paidAt: null, salesInvoiceUid: null, cancelledAt: null, cancelReason: null,
  notes: null, pendingLineCount: 1,
  lines: [
    { id: '1', uid: 'L1', lineNo: 1, productId: '7', productUid: 'P7', productCode: '147',
      productName: 'Tacos', unitId: '1', unitName: 'Pieces', quantity: 1, note: null, status: 'SENT',
      fireRound: 1, prepStationUid: 'S1', prepStationName: 'Kitchen', sentAt: '2026-10-24T10:01:00Z',
      voidedAt: null, voidReason: null, parentLineUid: null, course: 1, held: false, readyAt: null },
    { id: '2', uid: 'L2', lineNo: 2, productId: '8', productUid: 'P8', productCode: '171',
      productName: 'Latte', unitId: '1', unitName: 'Pieces', quantity: 2, note: 'no sugar',
      status: 'PENDING', fireRound: null, prepStationUid: null, prepStationName: null, sentAt: null,
      voidedAt: null, voidReason: null, parentLineUid: null, course: 1, held: false, readyAt: null },
  ],
};

function makeBed() {
  const restaurant = {
    listOrders: vi.fn(() => of([order])),
    cancelOrder: vi.fn(() => of({ order: { ...order, status: 'CANCELLED' }, slips: [] })),
  };
  TestBed.configureTestingModule({
    imports: [RestaurantOrdersComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: RestaurantService, useValue: restaurant },
      { provide: AuthService, useValue: { myBranches: vi.fn(() => of([])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true), isAuthenticated: signal(true), user: signal(null),
          permissions: signal([]), activeBranchUid: signal(null),
        },
      },
    ],
  });
  return restaurant;
}

describe('RestaurantOrdersComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('lists live orders and says what is not sent yet', () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantOrdersComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.orders()).toHaveLength(1);
    expect(c.where(order)).toBe('Table T1');
    expect(c.statusLabel(order)).toBe('Open · 1 not sent');
    expect(c.itemCount(order)).toBe(2);
  });

  it('asks for a reason before cancelling an order with sent items', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantOrdersComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.startCancel(order);
    c.confirmCancel(order);
    expect(c.cancelError()).toContain('reason');
    expect(restaurant.cancelOrder).not.toHaveBeenCalled();

    c.cancelReason.set('Customer left');
    c.confirmCancel(order);
    expect(restaurant.cancelOrder).toHaveBeenCalledWith('O1', { reason: 'Customer left' });
  });

  it('has no axe violations, with an order expanded and the cancel form open', async () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantOrdersComponent);
    fixture.detectChanges();
    fixture.componentInstance.toggle('O1');
    fixture.componentInstance.startCancel(order);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
