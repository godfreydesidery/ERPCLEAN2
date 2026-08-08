/**
 * SalesSettingsService — unit specs (D-4: SO approval threshold).
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SalesSettingsService } from './sales-settings.service';

const BASE = '/api/v1/sales-settings';

const STUB_SETTINGS = {
  id: '1', uid: 'S1', companyId: '10',
  soApprovalEnabled: true, soApprovalThresholdAmount: 5000, currency: 'TZS',
};

describe('SalesSettingsService', () => {
  let service: SalesSettingsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), SalesSettingsService],
    });
    service = TestBed.inject(SalesSettingsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => { http.verify(); TestBed.resetTestingModule(); });

  it('getByCompany() calls GET /by-company/{companyUid}', () => {
    let result: unknown;
    service.getByCompany('CO1').subscribe((r) => (result = r));

    const req = http.expectOne(`${BASE}/by-company/CO1`);
    expect(req.request.method).toBe('GET');
    req.flush(STUB_SETTINGS);

    expect(result).toEqual(STUB_SETTINGS);
  });

  it('update() PUTs the settings body to the base URL', () => {
    const body = {
      companyUid: 'CO1',
      soApprovalEnabled: true,
      soApprovalThresholdAmount: 7500,
      currency: 'TZS',
      allowNegativeStock: false,
      belowCostAction: 'OFF' as const,
      // K7: the stance and its ceiling are one policy — the request type requires both, because the
      // server refuses a ceiling sent on its own.
      discountApprovalAction: 'OFF' as const,
      maxDiscountPercent: null,
    };
    let result: unknown;
    service.update(body).subscribe((r) => (result = r));

    const req = http.expectOne(BASE);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(body);
    req.flush({ ...STUB_SETTINGS, soApprovalThresholdAmount: 7500 });

    expect(result).toEqual({ ...STUB_SETTINGS, soApprovalThresholdAmount: 7500 });
  });
});
