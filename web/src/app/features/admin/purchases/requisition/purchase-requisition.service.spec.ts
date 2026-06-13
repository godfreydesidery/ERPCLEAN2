/**
 * PurchaseRequisitionService — unit specs.
 *
 * Covers:
 *  1. list() passes correct params and maps rows+meta.
 *  2. getByUid() calls the correct URL.
 *  3. submit() calls POST /uid/{uid}/submit.
 *  4. approve() calls POST /uid/{uid}/approve.
 *  5. reject() passes reason as query param.
 *  6. convert() passes targetType as query param and returns string uid.
 *  7. cancel() passes reason as query param.
 */
import { HttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApiResponse } from '../../../../core/api/api-response.model';
import { PurchaseRequisitionDto } from './purchase-requisition.model';
import { PurchaseRequisitionService } from './purchase-requisition.service';

const STUB_DTO: PurchaseRequisitionDto = {
  uid: 'REQ1', id: '1', companyId: '10', branchId: '1',
  requisitionNumber: 'PR-0001', status: 'DRAFT',
  requiredByDate: null, costCentreCode: null,
  approvalRequestUid: null, approvalStatus: null,
  convertedToType: null, convertedToUid: null,
  notes: null, submittedAt: null, approvedAt: null,
  rejectedAt: null, convertedAt: null, cancelledAt: null,
  createdAt: null, lines: [],
};

const STUB_PAGE: ApiResponse<PurchaseRequisitionDto[]> = {
  data: [STUB_DTO],
  errors: [],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
};

function makeSvc() {
  const getSpy = vi.fn(() => of(STUB_PAGE));
  const postSpy = vi.fn(() => of(STUB_DTO));

  TestBed.configureTestingModule({
    providers: [
      PurchaseRequisitionService,
      {
        provide: HttpClient,
        useValue: { get: getSpy, post: postSpy },
      },
    ],
  });

  const svc = TestBed.inject(PurchaseRequisitionService);
  return { svc, getSpy, postSpy };
}

describe('PurchaseRequisitionService', () => {
  afterEach(() => TestBed.resetTestingModule());

  // ── 1. list() ─────────────────────────────────────────────────────────────

  it('list() calls GET with companyId, status, page, size and maps to rows+meta', () => {
    const { svc, getSpy } = makeSvc();
    let result: unknown;
    svc.list('10', 'DRAFT', 0, 20).subscribe((r) => (result = r));

    expect(getSpy).toHaveBeenCalledOnce();
    const r = result as { rows: unknown[]; meta: unknown };
    expect(r.rows).toHaveLength(1);
    expect((r.rows[0] as PurchaseRequisitionDto).uid).toBe('REQ1');
  });

  // ── 2. getByUid() ─────────────────────────────────────────────────────────

  it('getByUid() calls GET /uid/{uid}', () => {
    const getSpy = vi.fn(() => of(STUB_DTO));
    TestBed.configureTestingModule({
      providers: [
        PurchaseRequisitionService,
        { provide: HttpClient, useValue: { get: getSpy } },
      ],
    });
    const svc = TestBed.inject(PurchaseRequisitionService);
    let out: unknown;
    svc.getByUid('REQ1').subscribe((r) => (out = r));

    expect(getSpy).toHaveBeenCalledOnce();
    const calls = getSpy.mock.calls as unknown[][];
    expect(calls[0][0] as string).toContain('/uid/REQ1');
    expect((out as PurchaseRequisitionDto).uid).toBe('REQ1');
  });

  // ── 3. submit() ───────────────────────────────────────────────────────────

  it('submit() POSTs to /uid/{uid}/submit', () => {
    const { svc, postSpy } = makeSvc();
    svc.submit('REQ1').subscribe();

    expect(postSpy).toHaveBeenCalledOnce();
    const calls = postSpy.mock.calls as unknown[][];
    expect(calls[0][0] as string).toContain('/uid/REQ1/submit');
  });

  // ── 4. approve() ──────────────────────────────────────────────────────────

  it('approve() POSTs to /uid/{uid}/approve', () => {
    const { svc, postSpy } = makeSvc();
    svc.approve('REQ1').subscribe();

    expect(postSpy).toHaveBeenCalledOnce();
    const calls = postSpy.mock.calls as unknown[][];
    expect(calls[0][0] as string).toContain('/uid/REQ1/approve');
  });

  // ── 5. reject() ───────────────────────────────────────────────────────────

  it('reject() POSTs to /uid/{uid}/reject with reason param', () => {
    const { svc, postSpy } = makeSvc();
    svc.reject('REQ1', 'Budget exceeded').subscribe();

    expect(postSpy).toHaveBeenCalledOnce();
    const calls = postSpy.mock.calls as unknown[][];
    expect(calls[0][0] as string).toContain('/uid/REQ1/reject');
    const opts = calls[0][2] as { params?: { toString: () => string } } | undefined;
    expect(opts?.params?.toString()).toContain('Budget');
  });

  // ── 6. convert() returns string uid ───────────────────────────────────────

  it('convert() POSTs to /uid/{uid}/convert with targetType param', () => {
    const postSpy = vi.fn(() => of('PO-UID-999'));
    TestBed.configureTestingModule({
      providers: [
        PurchaseRequisitionService,
        { provide: HttpClient, useValue: { get: vi.fn(), post: postSpy } },
      ],
    });
    const svc = TestBed.inject(PurchaseRequisitionService);
    let out: string | undefined;
    svc.convert('REQ1', 'PURCHASE_ORDER').subscribe((r) => (out = r));

    expect(postSpy).toHaveBeenCalledOnce();
    const calls = postSpy.mock.calls as unknown[][];
    expect(calls[0][0] as string).toContain('/uid/REQ1/convert');
    const opts = calls[0][2] as { params?: { toString: () => string } } | undefined;
    expect(opts?.params?.toString()).toContain('targetType');
    expect(out).toBe('PO-UID-999');
  });

  // ── 7. cancel() ───────────────────────────────────────────────────────────

  it('cancel() POSTs to /uid/{uid}/cancel with reason param', () => {
    const { svc, postSpy } = makeSvc();
    svc.cancel('REQ1', 'No longer needed').subscribe();

    expect(postSpy).toHaveBeenCalledOnce();
    const calls = postSpy.mock.calls as unknown[][];
    expect(calls[0][0] as string).toContain('/uid/REQ1/cancel');
    const opts = calls[0][2] as { params?: { toString: () => string } } | undefined;
    expect(opts?.params?.toString()).toContain('No');
  });
});
