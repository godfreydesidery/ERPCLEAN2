import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { OfflineReviewComponent } from './offline-review.component';

const META = { page: 0, size: 20, totalElements: 2, totalPages: 1, hasNext: false };
const HELD_JSON = '{"lines":[{"sku":"A1","qty":2}],"total":"10.00"}';

const ITEMS = [
  { uid: 'R1', kind: 'PRICE_DIFFERENCE', status: 'OPEN', offlineNumber: 'OFF-1', detail: 'Price moved', tillName: 'Till 1',
    invoiceUid: 'INV1', invoiceNumber: 'SI-1', heldRequest: null, createdAt: '2026-11-01T10:00:00Z',
    acknowledgedByName: null, acknowledgedAt: null, acknowledgeNote: null },
  { uid: 'R2', kind: 'HELD', status: 'OPEN', offlineNumber: 'OFF-2', detail: 'Held', tillName: 'Till 1',
    invoiceUid: null, invoiceNumber: null, heldRequest: HELD_JSON, createdAt: '2026-11-01T11:00:00Z',
    acknowledgedByName: null, acknowledgedAt: null, acknowledgeNote: null },
  { uid: 'R3', kind: 'BELOW_COST', status: 'OPEN', offlineNumber: 'OFF-3', detail: 'Below cost', tillName: 'Till 1',
    invoiceUid: null, invoiceNumber: null, heldRequest: null, createdAt: '2026-11-01T12:00:00Z',
    acknowledgedByName: null, acknowledgedAt: null, acknowledgeNote: null },
];
const PERIODS = [
  { uid: 'P1', tillName: 'Till 1', reason: 'UNREACHABLE', switchedByName: null, startedAt: '2026-11-01T09:00:00Z', endedAt: null, salesCount: 3 },
];

function setup(allowed = true) {
  TestBed.configureTestingModule({
    imports: [OfflineReviewComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: { hasPermission: vi.fn(() => allowed), isAuthenticated: signal(true), user: signal(null), permissions: signal([]), activeBranchUid: signal(null) } },
    ],
  });
  const http = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(OfflineReviewComponent);
  fixture.detectChanges();
  return { http, fixture, el: fixture.nativeElement as HTMLElement };
}

function flushLists(http: HttpTestingController) {
  http.expectOne((r) => r.url.endsWith('/pos/offline-reviews') && r.params.get('status') === 'OPEN')
    .flush({ data: ITEMS, errors: [], meta: META });
  http.expectOne((r) => r.url.endsWith('/pos/offline-periods')).flush({ data: PERIODS, errors: [], meta: META });
}

describe('OfflineReviewComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('lists items and periods with plain labels', () => {
    const { http, fixture, el } = setup();
    flushLists(http);
    fixture.detectChanges();
    expect(el.textContent).toContain('Price differed');
    expect(el.textContent).toContain('Sold below cost');
    expect(el.textContent).toContain('Held — not posted');
    expect(el.querySelector('a[href="/admin/sales-invoices/uid/INV1"]')).toBeTruthy();
    expect(el.textContent).toContain('No connection');
    expect(el.textContent).toContain('Still offline');
    http.verify();
  });

  it('renders HELD json pretty-printed on expand', () => {
    const { http, fixture, el } = setup();
    flushLists(http);
    fixture.detectChanges();
    const btn = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('View sale as rung'))!;
    btn.click();
    fixture.detectChanges();
    const pre = el.querySelector('pre')!;
    expect(pre.textContent).toContain('"sku": "A1"');
    expect(pre.textContent).toContain('\n');
    http.verify();
  });

  it('acknowledge POSTs the note and refreshes', () => {
    const { http, fixture, el } = setup();
    flushLists(http);
    fixture.detectChanges();
    fixture.componentInstance.startAck('R1');
    fixture.componentInstance.ackNote.set(' checked ');
    fixture.componentInstance.confirmAck();
    const post = http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/pos/offline-reviews/uid/R1/acknowledge'));
    expect(post.request.body).toEqual({ note: 'checked' });
    post.flush({ ...ITEMS[0], status: 'ACKNOWLEDGED' });
    http.expectOne((r) => r.url.endsWith('/pos/offline-reviews') && r.params.get('status') === 'OPEN')
      .flush({ data: [ITEMS[1], ITEMS[2]], errors: [], meta: META });
    fixture.detectChanges();
    expect(el.textContent).not.toContain('Price differed');
    http.verify();
  });

  it('shows the friendly server error on 409', () => {
    const { http, fixture } = setup();
    flushLists(http);
    fixture.componentInstance.startAck('R1');
    fixture.componentInstance.confirmAck();
    http.expectOne((r) => r.method === 'POST').flush({ errors: ['Already acknowledged.'] }, { status: 409, statusText: 'Conflict' });
    expect(fixture.componentInstance.ackError()).toBe('Already acknowledged.');
  });

  it('shows empty states', () => {
    const { http, fixture, el } = setup();
    http.expectOne((r) => r.url.endsWith('/pos/offline-reviews')).flush({ data: [], errors: [], meta: META });
    http.expectOne((r) => r.url.endsWith('/pos/offline-periods')).flush({ data: [], errors: [], meta: META });
    fixture.detectChanges();
    expect(el.textContent).toContain('Nothing to review.');
    expect(el.textContent).toContain('No till has gone offline.');
  });

  it('without permission renders no data and makes no calls', () => {
    const { http, el } = setup(false);
    expect(el.textContent).toContain('permission');
    http.verify();
  });
});
