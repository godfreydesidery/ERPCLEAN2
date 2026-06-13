/**
 * StockCountService — unit specs.
 *
 * Covers:
 *  1. list() calls the correct URL with page/size params and maps rows + meta.
 *  2. create() posts to base URL.
 *  3. enterCount() PATCHes to /uid/{uid}/enter.
 *  4. post() PATCHes to /uid/{uid}/post with postingDate query param.
 *  5. cancel() DELETEs /uid/{uid}.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { StockCountDto } from './stock-count.model';
import { StockCountService } from './stock-count.service';
import { environment } from '../../../../../environments/environment';

const BASE = `${environment.apiBaseUrl}/stock-counts`;

const STUB_COUNT: StockCountDto = {
  uid: 'CNT1', id: '1',
  companyId: '10', branchId: '20',
  countNumber: 'SC-0001',
  status: 'COUNTING',
  countType: 'FULL',
  locationId: '5',
  countDate: '2025-06-01',
  frozenAt: null, postedAt: null, cancelledAt: null,
  varianceGlEntryUid: null, notes: null,
  lines: [],
};

describe('StockCountService', () => {
  let service: StockCountService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [StockCountService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(StockCountService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { http.verify(); TestBed.resetTestingModule(); });

  // ── 1. list() ─────────────────────────────────────────────────────────────

  it('list() calls correct URL and maps rows + meta', () => {
    let result: { rows: StockCountDto[]; meta: unknown } | undefined;
    service.list(0, 20).subscribe((r) => (result = r));

    const req = http.expectOne((r) => r.url === BASE && r.params.get('page') === '0');
    expect(req.request.method).toBe('GET');
    req.flush({
      data: [STUB_COUNT],
      errors: [],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });

    expect(result?.rows).toHaveLength(1);
    expect(result?.rows[0].uid).toBe('CNT1');
    expect((result?.meta as { totalElements: number }).totalElements).toBe(1);
  });

  // ── 2. create() ───────────────────────────────────────────────────────────

  it('create() POSTs to base URL', () => {
    const req$ = service.create({
      locationUid: 'LOC1', countDate: '2025-06-01', countType: 'FULL',
    });
    let created: StockCountDto | undefined;
    req$.subscribe((r) => (created = r));

    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('POST');
    req.flush(STUB_COUNT);

    expect(created?.uid).toBe('CNT1');
  });

  // ── 3. enterCount() ───────────────────────────────────────────────────────

  it('enterCount() PATCHes to /uid/{uid}/enter', () => {
    let result: StockCountDto | undefined;
    service.enterCount('CNT1', { lines: [{ lineId: '1', countedQty: '10' }] })
      .subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/CNT1/enter`);
    expect(req.request.method).toBe('PATCH');
    req.flush(STUB_COUNT);

    expect(result?.uid).toBe('CNT1');
  });

  // ── 4. post() ─────────────────────────────────────────────────────────────

  it('post() PATCHes to /uid/{uid}/post with postingDate param', () => {
    let result: StockCountDto | undefined;
    service.post('CNT1', '2025-06-15').subscribe((r) => (result = r));

    const req = http.expectOne(
      (r) => r.url === `${BASE}/uid/CNT1/post` && r.params.get('postingDate') === '2025-06-15',
    );
    expect(req.request.method).toBe('PATCH');
    req.flush({ ...STUB_COUNT, status: 'POSTED' });

    expect(result?.status).toBe('POSTED');
  });

  // ── 5. cancel() ───────────────────────────────────────────────────────────

  it('cancel() DELETEs /uid/{uid}', () => {
    let completed = false;
    service.cancel('CNT1').subscribe({ complete: () => (completed = true) });

    const req = http.expectOne(`${BASE}/uid/CNT1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    expect(completed).toBe(true);
  });
});
