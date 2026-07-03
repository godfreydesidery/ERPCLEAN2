/**
 * SalesOrdersService specs (Vitest) — submitForApproval().
 *
 * Covers:
 *  1. submitForApproval() calls PUT /sales-orders/uid/:uid/submit-for-approval with an empty body.
 *  2. The unwrapped SalesOrderDto response (interceptor already strips the ApiResponse envelope
 *     in production; this test flushes the raw DTO directly, matching how HttpClient sees it
 *     once unwrapped) is passed through to the caller.
 */
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SalesOrdersService } from './sales-orders.service';
import { SalesOrderDto } from '../models/sales-orders.model';

const BASE = '/api/v1/sales-orders';

const stubOrder: SalesOrderDto = {
  uid: 'SO1', id: '1', companyId: '10', branchId: '1',
  branchName: 'Head Office', branchCode: 'BR-01',
  orderNumber: 'SO-0001', status: 'DRAFT' as const,
  customerId: '5', customerName: 'Acme Traders', customerCode: 'ACME',
  agentId: null, agentName: null, currency: 'TZS', orderDate: '2025-01-01',
  sourceQuotationUid: null,
  docDiscountAmount: null, docDiscountPercent: null,
  netTotalAmount: '1000', vatTotalAmount: '180', grossTotalAmount: '1180',
  confirmedAt: null, cancelledAt: null, cancelReason: null, notes: null, lines: [],
  approvalStatus: 'PENDING',
};

describe('SalesOrdersService — submitForApproval', () => {
  let service: SalesOrdersService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [SalesOrdersService],
    });
    service = TestBed.inject(SalesOrdersService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('calls PUT /sales-orders/uid/SO1/submit-for-approval with an empty body', () => {
    let result: SalesOrderDto | undefined;
    service.submitForApproval('SO1').subscribe((r) => { result = r; });

    const req = httpMock.expectOne(`${BASE}/uid/SO1/submit-for-approval`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({});

    req.flush(stubOrder);

    expect(result?.uid).toBe('SO1');
    expect(result?.approvalStatus).toBe('PENDING');
  });
});
