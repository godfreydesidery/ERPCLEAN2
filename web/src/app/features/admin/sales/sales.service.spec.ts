/**
 * SalesService — fiscal receipt unit specs (D-6: EFD, ADR-0049).
 *
 * getFiscalReceipt(): GET .../fiscal-receipt; a backend 404 (no receipt issued yet) maps to
 * `null`, not an error — any other error status rethrows.
 * issueFiscalReceipt(): POST .../fiscal-receipt with an empty body (issue/retry, idempotent).
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SalesService } from './sales.service';

const BASE = '/api/v1/sales-invoices';

const STUB_FISCAL_RECEIPT = {
  uid: 'FR-UID-1', invoiceUid: 'INV-UID-1', status: 'ISSUED',
  providerCode: 'SIMULATED', fiscalNumber: 'EFD-000123',
  verificationUrl: 'https://verify.example.tra/EFD-000123',
  deviceSerial: 'DEV-1', issuedAt: '2026-07-04T10:00:00Z',
  attemptCount: 1, errorDetail: null, version: '0', createdAt: '2026-07-04T10:00:00Z',
};

describe('SalesService — fiscal receipt', () => {
  let service: SalesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), SalesService],
    });
    service = TestBed.inject(SalesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { http.verify(); TestBed.resetTestingModule(); });

  it('getFiscalReceipt() GETs .../uid/{uid}/fiscal-receipt and returns the DTO on success', () => {
    let result: unknown;
    service.getFiscalReceipt('INV-UID-1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/INV-UID-1/fiscal-receipt`);
    expect(req.request.method).toBe('GET');
    req.flush(STUB_FISCAL_RECEIPT);

    expect(result).toEqual(STUB_FISCAL_RECEIPT);
  });

  it('getFiscalReceipt() maps a 404 (no receipt issued yet) to null', () => {
    let result: unknown = 'unset';
    let errored = false;
    service.getFiscalReceipt('INV-UID-1').subscribe({
      next: (r) => (result = r),
      error: () => (errored = true),
    });

    const req = http.expectOne(`${BASE}/uid/INV-UID-1/fiscal-receipt`);
    req.flush({ errors: ['Not found'] }, { status: 404, statusText: 'Not Found' });

    expect(errored).toBe(false);
    expect(result).toBeNull();
  });

  it('getFiscalReceipt() rethrows non-404 errors', () => {
    let error: unknown;
    service.getFiscalReceipt('INV-UID-1').subscribe({
      next: () => undefined,
      error: (err) => (error = err),
    });

    const req = http.expectOne(`${BASE}/uid/INV-UID-1/fiscal-receipt`);
    req.flush({ errors: ['Server error'] }, { status: 500, statusText: 'Internal Server Error' });

    expect(error).toBeTruthy();
  });

  it('issueFiscalReceipt() POSTs an empty body to .../uid/{uid}/fiscal-receipt', () => {
    let result: unknown;
    service.issueFiscalReceipt('INV-UID-1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/INV-UID-1/fiscal-receipt`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    req.flush(STUB_FISCAL_RECEIPT);

    expect(result).toEqual(STUB_FISCAL_RECEIPT);
  });
});

// ── Line price override (FR-SALES-08, BR-SALES-09, SALES.INVOICE.OVERRIDE) ─────

describe('SalesService — override line price', () => {
  let service: SalesService;
  let http: HttpTestingController;

  const STUB_LINE = {
    id: '1', uid: 'LN-UID-1', invoiceId: '1', companyId: '10', branchId: '1',
    productId: '7', productCode: 'P002', productName: 'Beverage',
    unitId: '1', unitName: 'Each',
    quantity: '10', qtyInBase: '10', requestedQuantity: 10,
    listPriceAmount: '100', unitPriceAmount: '90',
    priceOverridden: true, overriddenBy: '3',
    lineDiscountAmount: null, lineDiscountPercent: null,
    vatStatus: 'STANDARD', vatRate: '18',
    netAmount: '900', vatAmount: '162', grossAmount: '1062',
    priceInclusive: false, currency: 'TZS',
    createdAt: null, createdBy: null,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), SalesService],
    });
    service = TestBed.inject(SalesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { http.verify(); TestBed.resetTestingModule(); });

  it('POSTs to .../uid/{uid}/lines/uid/{lineUid}/override-price with the new unitPriceAmount', () => {
    let result: unknown;
    service.overrideLinePrice('INV-UID-1', 'LN-UID-1', 90).subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/uid/INV-UID-1/lines/uid/LN-UID-1/override-price`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ unitPriceAmount: 90 });
    req.flush(STUB_LINE);

    expect(result).toEqual(STUB_LINE);
  });

  it('propagates a server error (e.g. 409 on a non-DRAFT invoice) to the caller', () => {
    let error: unknown;
    service.overrideLinePrice('INV-UID-1', 'LN-UID-1', 90).subscribe({
      next: () => undefined,
      error: (err) => (error = err),
    });

    const req = http.expectOne(`${BASE}/uid/INV-UID-1/lines/uid/LN-UID-1/override-price`);
    req.flush({ errors: ['This invoice is no longer a draft.'] }, { status: 409, statusText: 'Conflict' });

    expect(error).toBeTruthy();
  });
});
