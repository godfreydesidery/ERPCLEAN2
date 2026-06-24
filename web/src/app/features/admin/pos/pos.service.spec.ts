/**
 * PosService specs.
 *
 * Covers:
 *  1. listTills: builds correct query params.
 *  2. listSessions: uses SKIP_UNWRAP and maps rows + meta.
 *  3. openSession: POSTs to /pos/sessions.
 *  4. closeSession: POSTs to /pos/sessions/uid/:uid/close.
 *  5. xRead: GETs /pos/sessions/uid/:uid/x-read.
 *  6. reconcileSession: POSTs to /pos/sessions/uid/:uid/reconcile.
 *  7. processSale: POSTs to /pos/sales.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PosService } from './pos.service';
import type { OpenSessionRequest, PosPayoutRequest, CloseSessionRequest, PosSaleRequest } from './models/pos.model';

const API = '/api/v1';

function makeBed() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), PosService],
  });
}

const stubTill = { id: '1', uid: 'TILL1', companyId: '10', branchId: '2', name: 'Counter 1', status: 'ACTIVE' as const };
const stubSession = {
  id: '5', uid: 'SESS1', companyId: '10', branchId: '2', posTillId: '1', cashierId: '99',
  status: 'OPEN' as const, openedAt: '2025-01-01T08:00:00', closedAt: null, reconciledAt: null,
  openingFloatAmount: '100.00', countedCashAmount: null, expectedCashAmount: null, varianceAmount: null,
  varianceJournalId: null, notes: null,
};

// ── listTills ─────────────────────────────────────────────────────────────────

describe('PosService — listTills', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('GETs /pos/tills with companyId param', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    svc.listTills('10').subscribe();

    const req = http.expectOne((r) => r.url === `${API}/pos/tills` && r.params.get('companyId') === '10');
    expect(req.request.method).toBe('GET');
    req.flush([stubTill]);
  });

  it('includes branchId param when supplied', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    svc.listTills('10', '2').subscribe();

    const req = http.expectOne((r) => r.params.get('branchId') === '2');
    req.flush([stubTill]);
  });
});

// ── listSessions ──────────────────────────────────────────────────────────────

describe('PosService — listSessions', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('maps envelope to {rows, meta}', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    let result: any;
    svc.listSessions('10', 0, 20).subscribe((r) => (result = r));

    const req = http.expectOne((r) => r.url === `${API}/pos/sessions` && r.params.get('companyId') === '10');
    req.flush({
      data: [stubSession],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });

    expect(result.rows).toHaveLength(1);
    expect(result.rows[0].uid).toBe('SESS1');
    expect(result.meta.totalElements).toBe(1);
  });

  it('falls back gracefully when meta is absent', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    let result: any;
    svc.listSessions('10').subscribe((r) => (result = r));

    const req = http.expectOne((r) => r.url === `${API}/pos/sessions`);
    req.flush({ data: [stubSession] });

    expect(result.meta.totalPages).toBe(1);
  });

  it('appends status=OPEN when a status filter is given (POS checkout)', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    svc.listSessions('10', 0, 50, 'OPEN').subscribe();

    const req = http.expectOne(
      (r) => r.url === `${API}/pos/sessions` && r.params.get('status') === 'OPEN',
    );
    expect(req.request.params.get('companyId')).toBe('10');
    req.flush({ data: [stubSession], meta: { page: 0, size: 50, totalElements: 1, totalPages: 1, hasNext: false } });
  });
});

// ── openSession ───────────────────────────────────────────────────────────────

describe('PosService — openSession', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('POSTs to /pos/sessions with request body', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    const body: OpenSessionRequest = { tillUid: 'TILL1', openingFloatAmount: '100.00' };
    svc.openSession(body).subscribe();

    const req = http.expectOne(`${API}/pos/sessions`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(stubSession);
  });
});

// ── closeSession ──────────────────────────────────────────────────────────────

describe('PosService — closeSession', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('POSTs to /pos/sessions/uid/:uid/close', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    const body: CloseSessionRequest = { countedCashAmount: '250.00', notes: 'End of day' };
    svc.closeSession('SESS1', body).subscribe();

    const req = http.expectOne(`${API}/pos/sessions/uid/SESS1/close`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.countedCashAmount).toBe('250.00');
    req.flush({ ...stubSession, status: 'CLOSED' });
  });
});

// ── xRead ─────────────────────────────────────────────────────────────────────

describe('PosService — xRead', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('GETs /pos/sessions/uid/:uid/x-read', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    svc.xRead('SESS1').subscribe();

    const req = http.expectOne(`${API}/pos/sessions/uid/SESS1/x-read`);
    expect(req.request.method).toBe('GET');
    req.flush({ sessionUid: 'SESS1', posTillId: '1', cashierId: '99', openedAt: null, openingFloatAmount: '100.00', totalSalesAmount: '500.00', totalPayoutsNetAmount: '0.00', expectedCashAmount: '600.00', invoiceCount: 2 });
  });
});

// ── reconcileSession ──────────────────────────────────────────────────────────

describe('PosService — reconcileSession', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('POSTs to /pos/sessions/uid/:uid/reconcile', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    svc.reconcileSession('SESS1', { notes: 'OK' }).subscribe();

    const req = http.expectOne(`${API}/pos/sessions/uid/SESS1/reconcile`);
    expect(req.request.method).toBe('POST');
    req.flush({});
  });
});

// ── processSale ───────────────────────────────────────────────────────────────

describe('PosService — processSale', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('POSTs to /pos/sales with request body', () => {
    const svc = TestBed.inject(PosService);
    const http = TestBed.inject(HttpTestingController);

    const body: PosSaleRequest = {
      sessionUid: 'SESS1',
      customerId: '5',
      currency: 'TZS',
      lines: [{ productId: '10', unitId: '1', quantity: '2', unitPrice: '500.00' }],
      tenderedAmount: '1000.00',
    };
    svc.processSale(body).subscribe();

    const req = http.expectOne(`${API}/pos/sales`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.sessionUid).toBe('SESS1');
    req.flush({ id: '99', uid: 'INV1', invoiceNumber: 'INV-0001', status: 'FINALISED', grossTotalAmount: '1000.00', currency: 'TZS' });
  });
});
