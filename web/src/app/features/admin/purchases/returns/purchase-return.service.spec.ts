/**
 * PurchaseReturnService — unit specs.
 * Verifies list() returns rows+meta, getByUid() passes uid, create() posts body, confirm() posts to /confirm.
 */
import { HttpContext, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { PurchaseReturnService } from './purchase-return.service';
import { SKIP_UNWRAP } from '../../../../core/api/http-context.tokens';

const BASE = '/api/v1/purchase-returns';

const STUB_RETURN = {
  uid: 'RET1', id: '1', companyId: '10', branchId: '1',
  returnNumber: 'PR-0001', status: 'DRAFT',
  goodsReceiptId: '5', goodsReceiptUid: 'GR1',
  supplierId: '2', supplierCode: 'SUP001', supplierName: 'Supplier A',
  reason: 'Damaged', netAmount: '1000', vatAmount: '0', grossAmount: '1000',
  currency: 'TZS', debitNoteUid: null, glEntryUid: null,
  confirmedAt: null, createdAt: null, lines: null,
};

describe('PurchaseReturnService', () => {
  let service: PurchaseReturnService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), PurchaseReturnService],
    });
    service = TestBed.inject(PurchaseReturnService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { http.verify(); TestBed.resetTestingModule(); });

  it('list() calls GET with companyId param and maps data+meta', () => {
    let result: { rows: unknown[]; meta: unknown } | undefined;
    service.list('10', 0, 20).subscribe((r) => (result = r));

    const req = http.expectOne((r) => r.url === BASE && r.params.get('companyId') === '10');
    expect(req.request.method).toBe('GET');
    req.flush({
      data: [STUB_RETURN],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });

    expect(result?.rows).toHaveLength(1);
  });

  it('getByUid() calls GET /uid/{uid}', () => {
    let result: unknown;
    service.getByUid('RET1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/RET1`);
    expect(req.request.method).toBe('GET');
    req.flush(STUB_RETURN);

    expect(result).toEqual(STUB_RETURN);
  });

  it('create() POSTs to base URL', () => {
    const body = {
      companyUid: 'CO1', goodsReceiptUid: 'GR1', reason: 'Damaged',
      lines: [{ goodsReceiptLineUid: 'GRL1', returnedQty: '2' }],
    };
    let result: unknown;
    service.create(body).subscribe((r) => (result = r));

    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('POST');
    req.flush(STUB_RETURN);

    expect(result).toEqual(STUB_RETURN);
  });

  it('confirm() POSTs to /uid/{uid}/confirm', () => {
    let result: unknown;
    service.confirm('RET1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/RET1/confirm`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...STUB_RETURN, status: 'CONFIRMED' });

    expect((result as { status: string }).status).toBe('CONFIRMED');
  });
});
