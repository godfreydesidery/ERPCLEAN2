/**
 * SalesSettingsComponent — unit specs (D-4: SO approval threshold).
 *
 * Covers:
 *  1. Renders and loads settings for the default company (GET via HttpTestingController).
 *  2. Toggling "enable" flips the form signal.
 *  3. save() PUTs via the real SalesSettingsService — HttpTestingController expects the PUT.
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { SalesSettingsComponent } from './sales-settings.component';

const BASE = '/api/v1/sales-settings';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
// soApprovalEnabled starts false so the initial render doesn't mount <app-currency-select>
// (which would fire its own HTTP GET against the currencies endpoint).
const STUB_SETTINGS = {
  id: '1', uid: 'S1', companyId: '10',
  soApprovalEnabled: false, soApprovalThresholdAmount: 5000, currency: 'TZS',
  allowNegativeStock: false,
};

function makeBed() {
  TestBed.configureTestingModule({
    imports: [SalesSettingsComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('SalesSettingsComponent', () => {
  let http: HttpTestingController;

  afterEach(() => { http.verify(); TestBed.resetTestingModule(); });

  it('renders and loads settings for the default company', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();

    const req = http.expectOne(`${BASE}/by-company/CO1`);
    expect(req.request.method).toBe('GET');
    req.flush(STUB_SETTINGS);
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.settings()).toEqual(STUB_SETTINGS);
    expect(comp.fSoApprovalEnabled()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Sales Settings');
    expect(fixture.nativeElement.textContent).toContain('Enable SO Approval Workflow');
  });

  it('toggling enable flips the form signal', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush(STUB_SETTINGS);
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.fSoApprovalEnabled()).toBe(false);

    comp.fSoApprovalEnabled.set(true);
    expect(comp.fSoApprovalEnabled()).toBe(true);
  });

  it('save() PUTs the settings via the service — HttpTestingController expects the PUT', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush(STUB_SETTINGS);
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.fSoApprovalEnabled.set(true);
    comp.fThresholdAmount.set('7500');
    comp.fCurrency.set('TZS');

    comp.save();

    const putReq = http.expectOne(BASE);
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body).toEqual({
      companyUid: 'CO1',
      soApprovalEnabled: true,
      soApprovalThresholdAmount: 7500,
      currency: 'TZS',
      allowNegativeStock: false,
    });

    putReq.flush({ ...STUB_SETTINGS, soApprovalEnabled: true, soApprovalThresholdAmount: 7500 });

    expect(comp.saving()).toBe(false);
    expect(comp.settings()?.soApprovalThresholdAmount).toBe(7500);
  });

  it('block-negative-stock switch reflects DTO polarity (checked = block = !allowNegativeStock) and saves the stored value', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    // Company ALLOWS backorder (allowNegativeStock = true) → the "Block…" switch must be OFF.
    http.expectOne(`${BASE}/by-company/CO1`).flush({ ...STUB_SETTINGS, allowNegativeStock: true });
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.fAllowNegativeStock()).toBe(true);
    const blockSwitch = fixture.nativeElement.querySelector('#blockNegativeStock') as HTMLInputElement;
    expect(blockSwitch.checked).toBe(false); // block OFF because backorder is allowed

    // Admin turns the block ON → the stored allowNegativeStock must flip to false.
    comp.fAllowNegativeStock.set(false);
    comp.save();

    const putReq = http.expectOne(BASE);
    expect(putReq.request.method).toBe('PUT');
    expect(putReq.request.body.allowNegativeStock).toBe(false);

    putReq.flush({ ...STUB_SETTINGS, allowNegativeStock: false });
    expect(comp.settings()?.allowNegativeStock).toBe(false);
  });
});
