import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import { TenantSummary } from './tenant.model';
import { TenantListComponent } from './tenant-list.component';

const base = `${environment.apiBaseUrl}/organisations`;

const tenant = (over: Partial<TenantSummary> = {}): TenantSummary => ({
  uid: 'T1',
  name: 'Acme Traders',
  alias: 'acme',
  status: 'ACTIVE',
  createdAt: '2026-10-01T08:00:00Z',
  companyCount: 2,
  userCount: 5,
  ...over,
});

describe('TenantListComponent', () => {
  let fixture: ComponentFixture<TenantListComponent>;
  let http: HttpTestingController;
  let comp: TenantListComponent;

  function setup(rows: TenantSummary[] = [tenant()], perms: string[] = ['ORG.CREATE', 'ORG.SUSPEND']): void {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const session = TestBed.inject(SessionStore);
    session.clear();
    session.setPermissions(perms);
    fixture = TestBed.createComponent(TenantListComponent);
    comp = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    http.expectOne(`${base}/tenants`).flush(rows);
    fixture.detectChanges();
  }

  function fillRequired(): void {
    comp.organisationName.set('Beta Ltd');
    comp.companyCode.set('BETA');
    comp.companyName.set('Beta Company');
    comp.branchCode.set('HQ');
    comp.branchName.set('Head Office');
    comp.adminUsername.set('owner');
    comp.adminPassword.set('a-long-password-1');
  }

  afterEach(() => {
    http.verify();
    TestBed.inject(SessionStore).clear();
    vi.useRealTimers();
  });

  it('renders each tenant with a text status badge', () => {
    setup([tenant(), tenant({ uid: 'T2', name: 'Beta', status: 'INACTIVE', alias: null })]);
    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Acme Traders');
    expect(rows[0].textContent).toContain('acme');
    expect(rows[0].textContent).toContain('Active');
    expect(rows[1].textContent).toContain('Suspended');
  });

  it('shows the empty state', () => {
    setup([]);
    expect(fixture.nativeElement.textContent).toContain('No tenants yet');
  });

  it('shows the error state when the list fails to load', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    fixture = TestBed.createComponent(TenantListComponent);
    http = TestBed.inject(HttpTestingController);
    http.expectOne(`${base}/tenants`).flush({ errors: ['x'] }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Could not load tenants');
  });

  it('suspends only after the confirm dialog names the tenant and is accepted', () => {
    setup();
    comp.askConfirm(tenant(), 'suspend');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alertdialog"]').textContent).toContain('Suspend Acme Traders?');
    http.expectNone(`${base}/uid/T1/suspend`);

    comp.confirm();
    const req = http.expectOne(`${base}/uid/T1/suspend`);
    expect(req.request.method).toBe('POST');
    req.flush({ uid: 'T1', status: 'INACTIVE' });
    http.expectOne(`${base}/tenants`).flush([tenant({ status: 'INACTIVE' })]);
    expect(comp.confirmTarget()).toBeNull();
  });

  it('reactivates via the resume endpoint', () => {
    setup([tenant({ status: 'INACTIVE' })]);
    comp.askConfirm(tenant({ status: 'INACTIVE' }), 'resume');
    comp.confirm();
    http.expectOne(`${base}/uid/T1/resume`).flush({ uid: 'T1', status: 'ACTIVE' });
    http.expectOne(`${base}/tenants`).flush([tenant()]);
  });

  it('cancelling the confirm makes no request', () => {
    setup();
    comp.askConfirm(tenant(), 'suspend');
    comp.cancelConfirm();
    expect(comp.confirmTarget()).toBeNull();
  });

  it('shows the server error when suspend is refused', () => {
    setup();
    comp.askConfirm(tenant(), 'suspend');
    comp.confirm();
    http
      .expectOne(`${base}/uid/T1/suspend`)
      .flush({ errors: ['You cannot suspend your own organisation.'] }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(comp.actionError()).toBe('You cannot suspend your own organisation.');
    expect(comp.confirmTarget()).not.toBeNull();
  });

  it('hides suspend actions without ORG.SUSPEND', () => {
    setup([tenant()], ['ORG.CREATE']);
    expect(fixture.nativeElement.querySelector('tbody button')).toBeNull();
  });

  it('maps the form to a request, omitting blank optional fields', () => {
    setup();
    fillRequired();
    comp.companyTaxId.set(' 123-456-789 ');
    comp.enabledCurrencies.set('tzs, kes');
    expect(comp.buildRequest()).toEqual({
      organisationName: 'Beta Ltd',
      timeZone: undefined,
      companyCode: 'BETA',
      companyName: 'Beta Company',
      companyLegalName: undefined,
      companyTaxId: '123-456-789',
      companyVrn: undefined,
      branchCode: 'HQ',
      branchName: 'Head Office',
      adminUsername: 'owner',
      adminPassword: 'a-long-password-1',
      adminDisplayName: undefined,
      baseCurrency: undefined,
      defaultCurrency: undefined,
      enabledCurrencies: ['TZS', 'KES'],
      priceListCode: undefined,
      priceListName: undefined,
      priceListIncludesVat: undefined,
      walkInCustomerName: undefined,
      posTillName: undefined,
    });
  });

  it('requires the VAT answer when a price list is named', () => {
    setup();
    fillRequired();
    comp.priceListCode.set('RETAIL');
    comp.priceListName.set('Retail');
    expect(comp.priceListVatError()).not.toBeNull();
    comp.create();
    http.expectNone(base);
    expect(comp.submitted()).toBe(true);

    comp.priceListIncludesVat.set('yes');
    expect(comp.priceListVatError()).toBeNull();
    expect(comp.buildRequest().priceListIncludesVat).toBe(true);
    comp.priceListIncludesVat.set('no');
    expect(comp.buildRequest().priceListIncludesVat).toBe(false);
  });

  it('requires price list code and name together', () => {
    setup();
    comp.priceListCode.set('RETAIL');
    expect(comp.priceListPairError()).not.toBeNull();
    comp.priceListName.set('Retail');
    expect(comp.priceListPairError()).toBeNull();
  });

  it('rejects @ in the username and short passwords client-side', () => {
    setup();
    fillRequired();
    comp.adminUsername.set('owner@acme');
    expect(comp.usernameError()).toContain("without '@'");
    comp.create();
    http.expectNone(base);

    comp.adminUsername.set('owner');
    comp.adminPassword.set('short');
    expect(comp.passwordError()).toContain('12 characters');
    comp.create();
    http.expectNone(base);
  });

  it('creates the tenant, shows <local>@<alias> and refreshes the list', () => {
    setup();
    comp.openForm();
    fillRequired();
    comp.create();
    const req = http.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.adminUsername).toBe('owner');
    req.flush({ uid: 'T9', name: 'Beta Ltd', alias: 'beta', status: 'ACTIVE' });
    http.expectOne(`${base}/tenants`).flush([tenant()]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="signin-name"]').textContent).toContain('owner@beta');
    expect(comp.showForm()).toBe(false);
    expect(comp.adminPassword()).toBe('');
  });

  it('shows the server message when creation is refused', () => {
    setup();
    comp.openForm();
    fillRequired();
    comp.create();
    http
      .expectOne(base)
      .flush({ errors: ['Fiscalisation is switched on.'] }, { status: 409, statusText: 'Conflict' });
    expect(comp.formError()).toBe('Fiscalisation is switched on.');
    expect(comp.saving()).toBe(false);
  });

  it('has no axe violations (list, form and confirm dialog open)', async () => {
    setup();
    comp.openForm();
    comp.priceListCode.set('R');
    comp.priceListName.set('Retail');
    comp.submitted.set(true);
    comp.askConfirm(tenant(), 'suspend');
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
