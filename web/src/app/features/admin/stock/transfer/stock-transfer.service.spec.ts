/**
 * StockTransferService specs.
 *
 * Covers:
 *  1. list() uses SKIP_UNWRAP and maps envelope to {rows, meta}.
 *  2. create() POSTs to /api/v1/stock-transfers.
 *  3. dispatch() PATCHes /uid/{uid}/dispatch.
 *  4. receive() PATCHes /uid/{uid}/receive.
 *  5. completeInstant() PATCHes /uid/{uid}/complete-instant.
 *  6. cancel() DELETEs /uid/{uid}.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { StockTransferService } from './stock-transfer.service';
import type { CreateStockTransferRequest } from './stock-transfer.model';

const BASE = '/api/v1/stock-transfers';

const STUB_TRANSFER = {
  uid: 'TRF1', id: '1', companyId: '10',
  transferNumber: 'TRF-0001',
  status: 'DRAFT',
  transferMode: 'IN_TRANSIT',
  sourceBranchId: '1', sourceLocationId: '2',
  destBranchId: '3', destLocationId: '4',
  transferDate: '2025-01-15',
  dispatchedAt: null, receivedAt: null,
  notes: null, lines: [],
};

const ENV_PAGE = {
  data: [STUB_TRANSFER],
  errors: [],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 2, hasNext: true },
};

function setup() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
  const service = TestBed.inject(StockTransferService);
  const http = TestBed.inject(HttpTestingController);
  return { service, http };
}

// ── 1. list() ─────────────────────────────────────────────────────────────────

describe('StockTransferService — list()', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('maps envelope to {rows, meta}', () => {
    const { service, http } = setup();
    let result: any;

    service.list(0, 20).subscribe((r) => { result = r; });

    const req = http.expectOne((r) => r.url === BASE);
    expect(req.request.method).toBe('GET');
    req.flush(ENV_PAGE);

    expect(result.rows).toHaveLength(1);
    expect(result.rows[0].uid).toBe('TRF1');
    expect(result.meta.totalPages).toBe(2);
    expect(result.meta.hasNext).toBe(true);

    http.verify();
  });
});

// ── 2. create() ──────────────────────────────────────────────────────────────

describe('StockTransferService — create()', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('POSTs to /api/v1/stock-transfers', () => {
    const { service, http } = setup();
    const req: CreateStockTransferRequest = {
      sourceLocationUid: 'LOC-SRC',
      destLocationUid: 'LOC-DST',
      transferDate: '2025-01-15',
      transferMode: 'IN_TRANSIT',
      lines: [{ productUid: 'PROD1', qty: '10' }],
    };

    let result: any;
    service.create(req).subscribe((r) => { result = r; });

    const httpReq = http.expectOne({ url: BASE, method: 'POST' });
    expect(httpReq.request.body).toEqual(req);
    httpReq.flush(STUB_TRANSFER);

    expect(result.uid).toBe('TRF1');
    http.verify();
  });
});

// ── 3. dispatch() ─────────────────────────────────────────────────────────────

describe('StockTransferService — dispatch()', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('PATCHes /uid/{uid}/dispatch', () => {
    const { service, http } = setup();
    let result: any;

    service.dispatch('TRF1').subscribe((r) => { result = r; });

    const httpReq = http.expectOne({ url: `${BASE}/uid/TRF1/dispatch`, method: 'PATCH' });
    httpReq.flush({ ...STUB_TRANSFER, status: 'DISPATCHED' });

    expect(result.status).toBe('DISPATCHED');
    http.verify();
  });
});

// ── 4. receive() ─────────────────────────────────────────────────────────────

describe('StockTransferService — receive()', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('PATCHes /uid/{uid}/receive', () => {
    const { service, http } = setup();
    let result: any;

    service.receive('TRF1').subscribe((r) => { result = r; });

    const httpReq = http.expectOne({ url: `${BASE}/uid/TRF1/receive`, method: 'PATCH' });
    httpReq.flush({ ...STUB_TRANSFER, status: 'RECEIVED' });

    expect(result.status).toBe('RECEIVED');
    http.verify();
  });
});

// ── 5. completeInstant() ─────────────────────────────────────────────────────

describe('StockTransferService — completeInstant()', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('PATCHes /uid/{uid}/complete-instant', () => {
    const { service, http } = setup();
    let result: any;

    service.completeInstant('TRF1').subscribe((r) => { result = r; });

    const httpReq = http.expectOne({ url: `${BASE}/uid/TRF1/complete-instant`, method: 'PATCH' });
    httpReq.flush({ ...STUB_TRANSFER, status: 'COMPLETED' });

    expect(result.status).toBe('COMPLETED');
    http.verify();
  });
});

// ── 6. cancel() ──────────────────────────────────────────────────────────────

describe('StockTransferService — cancel()', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('DELETEs /uid/{uid}', () => {
    const { service, http } = setup();
    let called = false;

    service.cancel('TRF1').subscribe(() => { called = true; });

    const httpReq = http.expectOne({ url: `${BASE}/uid/TRF1`, method: 'DELETE' });
    httpReq.flush(null, { status: 204, statusText: 'No Content' });

    expect(called).toBe(true);
    http.verify();
  });
});
