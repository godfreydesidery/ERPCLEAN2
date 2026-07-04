/**
 * VanReconciliationService — unit specs.
 *
 * Covers:
 *  1. list() GETs the base URL with page/size params and maps rows + meta (SKIP_UNWRAP; the
 *     backend endpoint is a plain Spring `Pageable` list scoped implicitly via RequestContext —
 *     no companyId/branchId query params).
 *  2. get() GETs /uid/{uid}.
 *  3. create() POSTs to base URL with the create request body.
 *  4. enterLines() POSTs to /uid/{uid}/lines with the lines body.
 *  5. reconcile() POSTs to /uid/{uid}/reconcile.
 *  6. cancel() POSTs to /uid/{uid}/cancel.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { environment } from '../../../../../environments/environment';
import { VanReconciliationDto } from './van-reconciliation.model';
import { VanReconciliationService } from './van-reconciliation.service';

const BASE = `${environment.apiBaseUrl}/van-reconciliations`;

const STUB_RECON: VanReconciliationDto = {
  id: '1',
  uid: 'VR1',
  companyId: '10',
  branchId: '20',
  vanLocationUid: 'LOC-VAN-1',
  vanLocationName: 'Van 01',
  agentUid: 'AGT1',
  agentName: 'Hamisi',
  reconNumber: 'VR-0001',
  businessDate: '2026-07-04',
  status: 'OPEN',
  notes: null,
  lines: [],
  reconciledAt: null,
};

describe('VanReconciliationService', () => {
  let service: VanReconciliationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [VanReconciliationService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(VanReconciliationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { http.verify(); TestBed.resetTestingModule(); });

  // ── 1. list() ─────────────────────────────────────────────────────────────

  it('list() GETs the base URL with page/size params and maps rows + meta', () => {
    let result: { rows: VanReconciliationDto[]; meta: unknown } | undefined;
    service.list(0, 20).subscribe((r) => (result = r));

    const req = http.expectOne((r) => r.url === BASE && r.params.get('page') === '0' && r.params.get('size') === '20');
    expect(req.request.method).toBe('GET');
    req.flush({
      data: [STUB_RECON],
      errors: [],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });

    expect(result?.rows).toHaveLength(1);
    expect(result?.rows[0].uid).toBe('VR1');
    expect((result?.meta as { totalElements: number }).totalElements).toBe(1);
  });

  // ── 2. get() ──────────────────────────────────────────────────────────────

  it('get() GETs /uid/{uid}', () => {
    let result: VanReconciliationDto | undefined;
    service.get('VR1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/VR1`);
    expect(req.request.method).toBe('GET');
    req.flush(STUB_RECON);

    expect(result?.uid).toBe('VR1');
  });

  // ── 3. create() ───────────────────────────────────────────────────────────

  it('create() POSTs to the base URL with the request body', () => {
    let created: VanReconciliationDto | undefined;
    service
      .create({ vanLocationUid: 'LOC-VAN-1', businessDate: '2026-07-04' })
      .subscribe((r) => (created = r));

    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ vanLocationUid: 'LOC-VAN-1', businessDate: '2026-07-04' });
    req.flush(STUB_RECON);

    expect(created?.uid).toBe('VR1');
  });

  // ── 4. enterLines() ───────────────────────────────────────────────────────

  it('enterLines() POSTs to /uid/{uid}/lines with the lines body', () => {
    let result: VanReconciliationDto | undefined;
    const body = { lines: [{ productUid: 'PROD1', soldQty: 5, physicalQty: 10, loadedQty: 20, returnedQty: 5 }] };
    service.enterLines('VR1', body).subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/VR1/lines`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(STUB_RECON);

    expect(result?.uid).toBe('VR1');
  });

  // ── 5. reconcile() ────────────────────────────────────────────────────────

  it('reconcile() POSTs to /uid/{uid}/reconcile', () => {
    let result: VanReconciliationDto | undefined;
    service.reconcile('VR1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/VR1/reconcile`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...STUB_RECON, status: 'RECONCILED' });

    expect(result?.status).toBe('RECONCILED');
  });

  // ── 6. cancel() ───────────────────────────────────────────────────────────

  it('cancel() POSTs to /uid/{uid}/cancel', () => {
    let result: VanReconciliationDto | undefined;
    service.cancel('VR1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/VR1/cancel`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...STUB_RECON, status: 'CANCELLED' });

    expect(result?.status).toBe('CANCELLED');
  });
});
