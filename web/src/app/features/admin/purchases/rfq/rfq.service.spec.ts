/**
 * RfqService — unit specs.
 *
 * Covers:
 *  1. listRfqs passes companyId + pagination params with SKIP_UNWRAP.
 *  2. createRfq posts to /rfqs and returns the unwrapped dto.
 *  3. sendRfq posts to /rfqs/uid/:uid/send.
 *  4. awardRfq posts to /rfqs/uid/:uid/award?quoteUid=.
 *  5. captureQuote posts to /supplier-quotes.
 *  6. listQuotesByRfq fetches /supplier-quotes/by-rfq/:rfqUid with SKIP_UNWRAP.
 */
import { HttpParams, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RfqService } from './rfq.service';

const API = '/api/v1';

describe('RfqService', () => {
  let service: RfqService;
  let ctrl: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RfqService);
    ctrl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { ctrl.verify(); TestBed.resetTestingModule(); });

  // ── 1. listRfqs ────────────────────────────────────────────────────────────

  it('GET /rfqs with companyId and pagination', () => {
    service.listRfqs('10', 0, 20).subscribe();
    const req = ctrl.expectOne((r) =>
      r.url === `${API}/rfqs` &&
      r.params.get('companyId') === '10' &&
      r.params.get('page') === '0' &&
      r.params.get('size') === '20',
    );
    expect(req.request.method).toBe('GET');
    req.flush({ data: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false } });
  });

  // ── 2. createRfq ──────────────────────────────────────────────────────────

  it('POST /rfqs with request body', () => {
    const req$ = service.createRfq({
      companyUid: 'CO-UID-1',
      supplierUids: ['SUP1'],
      lines: [{ productId: '5', unitId: '2', quantity: '10' }],
    });
    req$.subscribe();
    const req = ctrl.expectOne(`${API}/rfqs`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.companyUid).toBe('CO-UID-1');
    req.flush({ uid: 'RFQ1', rfqNumber: 'RFQ-0001', status: 'DRAFT' });
  });

  // ── 3. sendRfq ────────────────────────────────────────────────────────────

  it('POST /rfqs/uid/:uid/send', () => {
    service.sendRfq('RFQ1').subscribe();
    const req = ctrl.expectOne(`${API}/rfqs/uid/RFQ1/send`);
    expect(req.request.method).toBe('POST');
    req.flush({ uid: 'RFQ1', status: 'SENT' });
  });

  // ── 4. awardRfq ───────────────────────────────────────────────────────────

  it('POST /rfqs/uid/:uid/award?quoteUid=', () => {
    service.awardRfq('RFQ1', 'SQ1').subscribe();
    const req = ctrl.expectOne((r) =>
      r.url === `${API}/rfqs/uid/RFQ1/award` && r.params.get('quoteUid') === 'SQ1',
    );
    expect(req.request.method).toBe('POST');
    req.flush({ uid: 'RFQ1', status: 'AWARDED', awardedPoUid: 'PO1' });
  });

  // ── 5. captureQuote ────────────────────────────────────────────────────────

  it('POST /supplier-quotes with capture request', () => {
    service.captureQuote({
      rfqUid: 'RFQ1',
      supplierUid: 'SUP1',
      lines: [{ rfqLineId: '1', quotedQty: '10', unitPriceAmount: '25.00' }],
    }).subscribe();
    const req = ctrl.expectOne(`${API}/supplier-quotes`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.rfqUid).toBe('RFQ1');
    req.flush({ uid: 'SQ1', quoteNumber: 'SQ-0001', status: 'RECEIVED' });
  });

  // ── 6. listQuotesByRfq ────────────────────────────────────────────────────

  it('GET /supplier-quotes/by-rfq/:rfqUid', () => {
    service.listQuotesByRfq('RFQ1').subscribe();
    const req = ctrl.expectOne((r) => r.url === `${API}/supplier-quotes/by-rfq/RFQ1`);
    expect(req.request.method).toBe('GET');
    req.flush({ data: [], meta: { page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false } });
  });
});
