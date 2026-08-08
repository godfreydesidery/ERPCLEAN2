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
  belowCostAction: 'OFF',
  discountApprovalAction: 'OFF',
  maxDiscountPercent: null,
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
      belowCostAction: 'OFF',
      // The discount pair always travels together — the server refuses half of it.
      discountApprovalAction: 'OFF',
      maxDiscountPercent: null,
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

  // ── Below-cost policy (V93) ────────────────────────────────────────────────

  it('below-cost control renders every policy choice, labelled and described in plain language', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush(STUB_SETTINGS);
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('#belowCostAction') as HTMLSelectElement;
    expect(select).toBeTruthy();
    expect([...select.options].map((o) => o.value)).toEqual(['OFF', 'WARN', 'APPROVE', 'BLOCK']);

    // Accessible name comes from a real <label for>, description from aria-describedby.
    const label = fixture.nativeElement.querySelector('label[for="belowCostAction"]');
    expect(label.textContent).toContain('Selling at or below cost');
    const describedBy = select.getAttribute('aria-describedby');
    expect(describedBy).toBe('belowCostHint');
    expect(fixture.nativeElement.querySelector(`#${describedBy}`)).toBeTruthy();

    // No jargon on screen — the admin sees consequences, not enum names or column names.
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Needs supervisor approval');
    expect(text).not.toContain('below_cost_action');
    expect(text).not.toContain('V93');
  });

  it('below-cost hint follows the selected policy', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush(STUB_SETTINGS);
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.belowCostHint()).toContain('Nothing is checked against cost');

    comp.fBelowCostAction.set('APPROVE');
    fixture.detectChanges();
    expect(comp.belowCostHint()).toContain('supervisor');
    expect(fixture.nativeElement.querySelector('#belowCostHint').textContent).toContain('supervisor');
  });

  it('loads the stored below-cost policy and saves the chosen one', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush({ ...STUB_SETTINGS, belowCostAction: 'APPROVE' });
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.fBelowCostAction()).toBe('APPROVE');

    comp.fBelowCostAction.set('BLOCK');
    comp.save();

    const putReq = http.expectOne(BASE);
    expect(putReq.request.body.belowCostAction).toBe('BLOCK');

    putReq.flush({ ...STUB_SETTINGS, belowCostAction: 'BLOCK' });
    expect(comp.settings()?.belowCostAction).toBe('BLOCK');
  });

  // ── Discount policy (K7, V95) ──────────────────────────────────────────────
  //
  // The server has enforced this policy since K7 with no way to configure it: the manager who owns
  // the rule could not turn it on, which made a shipped control undeliverable (UAT B3). These specs
  // pin the two controls, the pair-always-together wire contract, and the one sentence that must not
  // lie — an empty ceiling under an active stance allows NOTHING, it does not mean "no limit".

  it('discount stance control renders every choice, labelled and described in plain language', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush(STUB_SETTINGS);
    fixture.detectChanges();

    const select = fixture.nativeElement.querySelector('#discountApprovalAction') as HTMLSelectElement;
    expect(select).toBeTruthy();
    expect([...select.options].map((o) => o.value)).toEqual(['OFF', 'WARN', 'APPROVE', 'BLOCK']);

    // Accessible name from a real <label for>, description via aria-describedby.
    const label = fixture.nativeElement.querySelector('label[for="discountApprovalAction"]');
    expect(label.textContent).toContain('Discounts above a limit');
    expect(select.getAttribute('aria-describedby')).toBe('discountApprovalHint');
    expect(fixture.nativeElement.querySelector('#discountApprovalHint')).toBeTruthy();

    // Consequences, not enum names or column names.
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Needs supervisor approval');
    expect(text).not.toContain('discount_approval_action');
    expect(text).not.toContain('max_discount_percent');
  });

  it('the ceiling input appears only once a stance is chosen, and is a labelled 0–100 percent field', async () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush(STUB_SETTINGS);
    fixture.detectChanges();

    // OFF ignores the ceiling entirely — an input that does nothing must not be on screen.
    expect(fixture.nativeElement.querySelector('#maxDiscountPercent')).toBeNull();

    fixture.componentInstance.fDiscountApprovalAction.set('APPROVE');
    await fixture.whenStable();
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('#maxDiscountPercent') as HTMLInputElement;
    expect(input).toBeTruthy();
    expect(input.type).toBe('number');
    // Matches the server's NUMERIC(5,2) CHECK (0–100, two decimals).
    expect(input.min).toBe('0');
    expect(input.max).toBe('100');
    expect(input.step).toBe('0.01');

    const label = fixture.nativeElement.querySelector('label[for="maxDiscountPercent"]');
    expect(label.textContent).toContain('Discount a salesperson may give');
    expect(input.getAttribute('aria-describedby')).toBe('maxDiscountHint');
    expect(fixture.nativeElement.querySelector('#maxDiscountHint')).toBeTruthy();
  });

  it('says an empty ceiling allows nothing — never "no limit"', () => {
    // The negative-stock failure repeated would be a screen describing the opposite of what the
    // till enforces: the server reads an unset ceiling as ZERO once the policy is on.
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush(STUB_SETTINGS);
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.fDiscountApprovalAction.set('APPROVE');
    expect(comp.fMaxDiscountPercent()).toBe('');
    expect(comp.discountCeilingHint()).toContain('no discount at all is allowed');

    comp.fMaxDiscountPercent.set('10');
    expect(comp.discountCeilingHint()).toContain('most a salesperson may take off');
  });

  it('loads the stored policy and saves the pair together', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush({
      ...STUB_SETTINGS, discountApprovalAction: 'APPROVE', maxDiscountPercent: 7.5,
    });
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    expect(comp.fDiscountApprovalAction()).toBe('APPROVE');
    expect(comp.fMaxDiscountPercent()).toBe('7.5');

    comp.fDiscountApprovalAction.set('BLOCK');
    comp.fMaxDiscountPercent.set('12.25');
    comp.save();

    const putReq = http.expectOne(BASE);
    expect(putReq.request.body.discountApprovalAction).toBe('BLOCK');
    // A JSON number, not a string — the wire shape of a BigDecimal.
    expect(putReq.request.body.maxDiscountPercent).toBe(12.25);

    putReq.flush({ ...STUB_SETTINGS, discountApprovalAction: 'BLOCK', maxDiscountPercent: 12.25 });
    expect(comp.settings()?.discountApprovalAction).toBe('BLOCK');
  });

  it('sends an empty ceiling as null — a real instruction, alongside the stance', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush({
      ...STUB_SETTINGS, discountApprovalAction: 'APPROVE', maxDiscountPercent: 10,
    });
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.fMaxDiscountPercent.set('');
    comp.save();

    const putReq = http.expectOne(BASE);
    expect(putReq.request.body.maxDiscountPercent).toBeNull();
    // Never the ceiling alone — the server refuses that, and would be right to.
    expect(putReq.request.body.discountApprovalAction).toBe('APPROVE');
    putReq.flush({ ...STUB_SETTINGS, discountApprovalAction: 'APPROVE', maxDiscountPercent: null });
  });

  it('refuses an out-of-range or over-precise ceiling before sending anything', () => {
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    http.expectOne(`${BASE}/by-company/CO1`).flush(STUB_SETTINGS);
    fixture.detectChanges();

    const comp = fixture.componentInstance;
    comp.fDiscountApprovalAction.set('APPROVE');

    for (const bad of ['120', '-5', '7.555', 'abc']) {
      comp.fMaxDiscountPercent.set(bad);
      comp.save();
      expect(comp.saveError()).toContain('between 0 and 100');
      http.expectNone(BASE);
    }
  });

  it('a company saved before the policy existed shows OFF — the same value the till enforces', async () => {
    // The screen must never report a stance the backend would not apply. An API response with no
    // belowCostAction (pre-V93 row / older API) resolves to OFF on both sides.
    makeBed();
    http = TestBed.inject(HttpTestingController);

    const fixture = TestBed.createComponent(SalesSettingsComponent);
    fixture.detectChanges();
    const {
      belowCostAction: _omitted,
      discountApprovalAction: _alsoOmitted,
      maxDiscountPercent: _ceilingOmitted,
      ...legacySettings
    } = STUB_SETTINGS;
    http.expectOne(`${BASE}/by-company/CO1`).flush(legacySettings);
    fixture.detectChanges();

    expect(fixture.componentInstance.fBelowCostAction()).toBe('OFF');
    // Same rule for the discount policy: no value in the response = the server enforces OFF.
    expect(fixture.componentInstance.fDiscountApprovalAction()).toBe('OFF');
    expect(fixture.componentInstance.fMaxDiscountPercent()).toBe('');

    // ngModel writes the control value on a microtask — settle before reading the rendered <select>.
    await fixture.whenStable();
    fixture.detectChanges();
    const select = fixture.nativeElement.querySelector('#belowCostAction') as HTMLSelectElement;
    expect(select.value).toBe('OFF');
  });
});
