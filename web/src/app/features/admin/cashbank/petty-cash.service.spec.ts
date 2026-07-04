/**
 * CashbankService specs — Petty Cash endpoints (ADR-0050 D-7 PR-B).
 *
 * Petty cash is its own top-level resource (`/api/v1/petty-cash`), not nested under `/cash`.
 *
 * Covers:
 *  1. listPettyCashFunds: GETs /petty-cash/funds with companyId (plain unwrapped array).
 *  2. getPettyCashFund: GETs /petty-cash/funds/uid/:uid.
 *  3. createPettyCashFund: POSTs to /petty-cash/funds with the request body.
 *  4. updatePettyCashFund: PUTs to /petty-cash/funds/uid/:uid with the request body.
 *  5. listPettyCashTransactions: GETs /petty-cash/funds/uid/:uid/transactions.
 *  6. recordPettyCashTransaction: POSTs to /petty-cash/funds/uid/:uid/transactions with the request body.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { CashbankService } from './cashbank.service';
import type {
  CreatePettyCashFundRequest,
  PettyCashFundDto,
  PettyCashTransactionDto,
  RecordPettyCashTransactionRequest,
  UpdatePettyCashFundRequest,
} from './models/cashbank.model';

const API = '/api/v1/petty-cash';

function makeBed() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), CashbankService],
  });
}

const stubFund: PettyCashFundDto = {
  uid: 'PCF1',
  companyId: '10',
  branchId: '1',
  code: 'PCF-001',
  name: 'Front Office Petty Cash',
  custodianUid: null,
  custodianName: null,
  floatAmount: 100000,
  balanceAmount: 100000,
  currency: 'TZS',
  status: 'ACTIVE',
  version: '0',
  createdAt: '2026-07-04T08:00:00Z',
};

const stubTxn: PettyCashTransactionDto = {
  uid: 'PCT1',
  fundUid: 'PCF1',
  txnNumber: 'PC-0001',
  txnType: 'DISBURSEMENT',
  txnDate: '2026-07-04',
  amount: 5000,
  balanceAfter: 95000,
  glAccountUid: null,
  reference: null,
  description: null,
  createdAt: '2026-07-04T09:00:00Z',
};

describe('CashbankService — listPettyCashFunds', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('GETs /petty-cash/funds with companyId (plain unwrapped array)', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    let result: PettyCashFundDto[] | undefined;
    svc.listPettyCashFunds('10').subscribe((r) => (result = r));

    const req = http.expectOne((r) => r.url === `${API}/funds` && r.params.get('companyId') === '10');
    expect(req.request.method).toBe('GET');
    req.flush([stubFund]);

    expect(result).toHaveLength(1);
    expect(result?.[0].uid).toBe('PCF1');
  });
});

describe('CashbankService — getPettyCashFund', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('GETs /petty-cash/funds/uid/:uid', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    let result: PettyCashFundDto | undefined;
    svc.getPettyCashFund('PCF1').subscribe((r) => (result = r));

    const req = http.expectOne(`${API}/funds/uid/PCF1`);
    expect(req.request.method).toBe('GET');
    req.flush(stubFund);

    expect(result?.uid).toBe('PCF1');
  });
});

describe('CashbankService — createPettyCashFund', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('POSTs to /petty-cash/funds with the request body', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    const body: CreatePettyCashFundRequest = {
      companyUid: 'CO1',
      code: 'PCF-001',
      name: 'Front Office Petty Cash',
      floatAmount: 100000,
      currency: 'TZS',
    };
    svc.createPettyCashFund(body).subscribe();

    const req = http.expectOne(`${API}/funds`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(stubFund);
  });
});

describe('CashbankService — updatePettyCashFund', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('PUTs to /petty-cash/funds/uid/:uid with the request body', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    const body: UpdatePettyCashFundRequest = { name: 'Renamed Fund' };
    svc.updatePettyCashFund('PCF1', body).subscribe();

    const req = http.expectOne(`${API}/funds/uid/PCF1`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush({ ...stubFund, name: 'Renamed Fund' });
  });
});

describe('CashbankService — listPettyCashTransactions', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('GETs /petty-cash/funds/uid/:uid/transactions', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    let result: PettyCashTransactionDto[] | undefined;
    svc.listPettyCashTransactions('PCF1').subscribe((r) => (result = r));

    const req = http.expectOne(`${API}/funds/uid/PCF1/transactions`);
    expect(req.request.method).toBe('GET');
    req.flush([stubTxn]);

    expect(result).toHaveLength(1);
    expect(result?.[0].uid).toBe('PCT1');
  });
});

describe('CashbankService — recordPettyCashTransaction', () => {
  beforeEach(() => makeBed());
  afterEach(() => { TestBed.inject(HttpTestingController).verify(); TestBed.resetTestingModule(); });

  it('POSTs to /petty-cash/funds/uid/:uid/transactions with the request body', () => {
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);

    const body: RecordPettyCashTransactionRequest = {
      type: 'DISBURSEMENT',
      amount: 5000,
      txnDate: '2026-07-04',
    };
    svc.recordPettyCashTransaction('PCF1', body).subscribe();

    const req = http.expectOne(`${API}/funds/uid/PCF1/transactions`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush(stubTxn);
  });
});
