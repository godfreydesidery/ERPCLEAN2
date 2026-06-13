/**
 * BlanketOrderService specs (Vitest).
 *
 * Covers:
 *  1. list() passes companyId + page + size as query params and returns {rows, meta}.
 *  2. getByUid() calls GET /blanket-orders/uid/:uid.
 *  3. create() calls POST /blanket-orders with the request body.
 *  4. cancel() calls DELETE /blanket-orders/uid/:uid.
 *  5. draw() calls POST /blanket-orders/uid/:uid/draw with the request body.
 */
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BlanketOrderService } from './blanket-order.service';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';
import { HTTP_INTERCEPTORS } from '@angular/common/http';

// We test the service in isolation without the real interceptor;
// SKIP_UNWRAP means the envelope is NOT unwrapped by the test harness.

const BASE = '/api/v1/blanket-orders';

const stubBlanket = {
  id: '1', uid: 'B1', companyId: '10', branchId: '2',
  orderNumber: 'BLK-001', customerId: '5', currency: 'TZS',
  validFrom: '2025-01-01', validTo: '2025-12-31',
  totalCommittedAmount: '100000', totalDrawnAmount: '0',
  status: 'ACTIVE' as const, notes: null, lines: [],
};

const stubSalesOrder = {
  id: '99', uid: 'SO1', companyId: '10', branchId: '2',
  orderNumber: 'SO-0001', status: 'DRAFT' as const,
  customerId: '5', agentId: '3', currency: 'TZS',
  orderDate: '2025-06-01', sourceQuotationUid: null,
  docDiscountAmount: null, docDiscountPercent: null,
  netTotalAmount: '0', vatTotalAmount: '0', grossTotalAmount: '0',
  confirmedAt: null, cancelledAt: null, cancelReason: null,
  notes: null, lines: [],
};

describe('BlanketOrderService', () => {
  let service: BlanketOrderService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [BlanketOrderService],
    });
    service = TestBed.inject(BlanketOrderService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('list() returns {rows, meta} from the envelope', () => {
    let result: any;
    service.list('10', 0, 20).subscribe((r) => { result = r; });

    const req = httpMock.expectOne((r) =>
      r.url === BASE && r.params.get('companyId') === '10',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ data: [stubBlanket], errors: [], meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false } });

    expect(result.rows).toHaveLength(1);
    expect(result.rows[0].uid).toBe('B1');
    expect(result.meta.totalElements).toBe(1);
  });

  it('getByUid() calls GET /blanket-orders/uid/B1', () => {
    let result: any;
    service.getByUid('B1').subscribe((r) => { result = r; });

    const req = httpMock.expectOne(`${BASE}/uid/B1`);
    expect(req.request.method).toBe('GET');
    req.flush(stubBlanket);

    expect(result.uid).toBe('B1');
  });

  it('create() calls POST /blanket-orders', () => {
    const body = {
      companyUid: 'CO1', branchId: '2', customerId: '5',
      currency: 'TZS', validFrom: '2025-01-01', validTo: '2025-12-31',
      lines: [{ productId: '1', unitId: '1', committedQtyBase: '100', unitPriceAmount: '50' }],
    };
    let result: any;
    service.create(body).subscribe((r) => { result = r; });

    const req = httpMock.expectOne(BASE);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.companyUid).toBe('CO1');
    req.flush(stubBlanket);

    expect(result.uid).toBe('B1');
  });

  it('cancel() calls DELETE /blanket-orders/uid/B1', () => {
    service.cancel('B1').subscribe();
    const req = httpMock.expectOne(`${BASE}/uid/B1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('draw() calls POST /blanket-orders/uid/B1/draw', () => {
    const body = {
      blanketUid: 'B1', branchId: '2', agentId: '3',
      lines: [{ blanketLineUid: 'BL1', qtyBase: '10' }],
    };
    let result: any;
    service.draw('B1', body).subscribe((r) => { result = r; });

    const req = httpMock.expectOne(`${BASE}/uid/B1/draw`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.blanketUid).toBe('B1');
    req.flush(stubSalesOrder);

    expect(result.uid).toBe('SO1');
  });
});
