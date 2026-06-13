/**
 * LandedCostService — unit specs.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { LandedCostService } from './landed-cost.service';

const BASE = '/api/v1/landed-costs';

const STUB_LC = {
  uid: 'LC1', id: '1', companyId: '10', branchId: '1',
  landedCostNumber: 'LC-0001', status: 'DRAFT',
  basis: 'BY_VALUE', totalChargeAmount: '5000',
  currency: 'TZS', glEntryUid: null, notes: null,
  confirmedAt: null, createdAt: null,
  receiptUids: ['GR1'], charges: [],
};

describe('LandedCostService', () => {
  let service: LandedCostService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), LandedCostService],
    });
    service = TestBed.inject(LandedCostService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { http.verify(); TestBed.resetTestingModule(); });

  it('list() calls GET with companyId param and maps data+meta', () => {
    let result: { rows: unknown[]; meta: unknown } | undefined;
    service.list('10', 0, 20).subscribe((r) => (result = r));

    const req = http.expectOne((r) => r.url === BASE && r.params.get('companyId') === '10');
    expect(req.request.method).toBe('GET');
    req.flush({
      data: [STUB_LC],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });

    expect(result?.rows).toHaveLength(1);
  });

  it('getByUid() calls GET /uid/{uid}', () => {
    let result: unknown;
    service.getByUid('LC1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/LC1`);
    expect(req.request.method).toBe('GET');
    req.flush(STUB_LC);

    expect(result).toEqual(STUB_LC);
  });

  it('create() POSTs to base URL', () => {
    const body = {
      companyUid: 'CO1', basis: 'BY_VALUE' as const,
      receiptUids: ['GR1'],
      charges: [{ chargeType: 'FREIGHT' as const, amount: '500' }],
    };
    let result: unknown;
    service.create(body).subscribe((r) => (result = r));

    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('POST');
    req.flush(STUB_LC);

    expect(result).toEqual(STUB_LC);
  });

  it('confirm() POSTs to /uid/{uid}/confirm', () => {
    let result: unknown;
    service.confirm('LC1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/LC1/confirm`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...STUB_LC, status: 'CONFIRMED' });

    expect((result as { status: string }).status).toBe('CONFIRMED');
  });
});
