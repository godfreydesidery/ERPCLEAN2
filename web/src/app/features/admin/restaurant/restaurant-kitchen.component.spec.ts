/**
 * RestaurantKitchenComponent specs (ADR-0066 D-6): tickets per station, bump and undo, axe.
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
import { KitchenTicketDto } from './models/restaurant.model';
import { RestaurantKitchenComponent } from './restaurant-kitchen.component';
import { RestaurantService } from './restaurant.service';

const ticket: KitchenTicketDto = {
  orderUid: 'O1', orderNumber: 'ORD-0007', orderType: 'DINE_IN', tableName: 'T4', guestCount: 2,
  round: 1, sentAt: new Date(Date.now() - 16 * 60_000).toISOString(), sentByName: 'Asha',
  items: [
    { lineUid: 'L1', productName: 'Tacos', quantity: 2, note: 'no onions', course: 1, voided: false,
      voidReason: null, addOns: [{ lineUid: 'L2', productName: 'Extra sauce', quantity: 2, voided: false }] },
    { lineUid: 'L3', productName: 'Burger', quantity: 1, note: null, course: 2, voided: true,
      voidReason: 'Wrong item', addOns: [] },
  ],
};

function makeBed() {
  const restaurant = {
    listStations: vi.fn(() => of([{ id: '1', uid: 'S1', branchId: '2', name: 'Kitchen', printerHost: null,
      printerPort: 9100, defaultStation: true, status: 'ACTIVE' as const }])),
    kitchenTickets: vi.fn(() => of([ticket])),
    kitchenReady: vi.fn(() => of(undefined)),
    kitchenRecall: vi.fn(() => of(undefined)),
  };
  TestBed.configureTestingModule({
    imports: [RestaurantKitchenComponent],
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

describe('RestaurantKitchenComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('loads the first station and its tickets, with the age in minutes', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantKitchenComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(restaurant.kitchenTickets).toHaveBeenCalledWith('S1');
    expect(c.stationName()).toBe('Kitchen');
    expect(c.where(ticket)).toBe('Table T4');
    expect(c.minutes(ticket)).toBeGreaterThanOrEqual(16);
  });

  it('Done bumps every item of the ticket, and Undo recalls them', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantKitchenComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.bumpTicket(ticket);
    expect(restaurant.kitchenReady).toHaveBeenCalledWith(['L1', 'L3']);
    c.undo();
    expect(restaurant.kitchenRecall).toHaveBeenCalledWith(['L1', 'L3']);
  });

  it('has no axe violations with tickets on screen', async () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantKitchenComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
