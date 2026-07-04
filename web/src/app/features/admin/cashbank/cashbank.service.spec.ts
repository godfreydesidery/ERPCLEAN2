/**
 * CashbankService specs — Cash Count endpoints (ADR-0050 D-7 PR-A).
 *
 * Covers:
 *  1. openCashCount: POSTs to /cash/counts with the request body.
 *  2. recordDenominations: PUTs to /cash/counts/uid/:uid/denominations with the lines body.
 *  3. reconcileCashCount: POSTs to /cash/counts/uid/:uid/reconcile with no body.
 *  4. getCashCount: GETs /cash/counts/uid/:uid.
 *  5. listCashCounts: GETs /cash/counts with BOTH companyId + accountId (no pagination —
 *     the backend's listByAccount is till-scoped, unlike the other cashbank lists).
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CashbankService } from './cashbank.service';
import type { CashCountDto, OpenCashCountRequest, RecordDenominationsRequest } from './models/cashbank.model';

const API = '/api/v1/cash';

function makeBed() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), CashbankService],
  });
}

const stubCount: CashCountDto = {
  id: '1',
  uid: 'CC1',
  companyId: '10',
  cashBankAccountUid: 'ACC1',
  cashBankAccountName: 'CASH-001 — Main Till',
  countNumber: 'CC-0001',
  businessDate: '2026-07-04',
  expectedAmount: 1000,
  countedAmount: 0,
  varianceAmount: -1000,
  currency: 'TZS',
  status: 'OPEN',
  journalEntryRef: null,
  denominations: [],
  countedAt: null,
  reconciledAt: null,
};

describe('CashbankService — openCashCount', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('POSTs to /cash/counts with the request body', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    const body: OpenCashCountRequest = {
      companyUid: 'CO1',
      cashBankAccountUid: 'ACC1',
      businessDate: '2026-07-04',
    };
    svc.openCashCount(body).subscribe();

    const req = http.expectOne(`${API}/counts`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(stubCount);
  });
});

describe('CashbankService — recordDenominations', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('PUTs to /cash/counts/uid/:uid/denominations with the lines body', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    const body: RecordDenominationsRequest = {
      lines: [
        { denomination: 10000, quantity: 3 },
        { denomination: 5000, quantity: 0 },
      ],
    };
    svc.recordDenominations('CC1', body).subscribe();

    const req = http.expectOne(`${API}/counts/uid/CC1/denominations`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush({ ...stubCount, status: 'COUNTED', countedAmount: 30000, varianceAmount: 29000 });
  });
});

describe('CashbankService — reconcileCashCount', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('POSTs to /cash/counts/uid/:uid/reconcile with no body required', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    svc.reconcileCashCount('CC1').subscribe();

    const req = http.expectOne(`${API}/counts/uid/CC1/reconcile`);
    expect(req.request.method).toBe('POST');
    req.flush({ ...stubCount, status: 'RECONCILED', journalEntryRef: 'JE1' });
  });
});

describe('CashbankService — getCashCount', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('GETs /cash/counts/uid/:uid', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    let result: CashCountDto | undefined;
    svc.getCashCount('CC1').subscribe((r) => (result = r));

    const req = http.expectOne(`${API}/counts/uid/CC1`);
    expect(req.request.method).toBe('GET');
    req.flush(stubCount);

    expect(result?.uid).toBe('CC1');
  });
});

describe('CashbankService — listCashCounts', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('GETs /cash/counts with BOTH companyId and accountId params (till-scoped, no pagination)', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    let result: CashCountDto[] | undefined;
    svc.listCashCounts('10', '1').subscribe((r) => (result = r));

    const req = http.expectOne(
      (r) => r.url === `${API}/counts` && r.params.get('companyId') === '10' && r.params.get('accountId') === '1',
    );
    expect(req.request.method).toBe('GET');
    req.flush([stubCount]);

    expect(result).toHaveLength(1);
    expect(result?.[0].uid).toBe('CC1');
  });
});
