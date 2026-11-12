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
    // setup() defaults to ONE organisation, where creating another needs its exact name typed.
    comp.sharedConfirm.set(tenant().name);
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

  it('on a one-organisation server, refuses to create until the current name is typed exactly', () => {
    setup([tenant()]);
    comp.openForm();
    fillRequired();
    comp.sharedConfirm.set('');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('This server holds one organisation');
    comp.create();
    http.expectNone(base);
    expect(comp.formError()).toBe('Please correct the fields marked below.');

    comp.sharedConfirm.set(tenant().name.toLowerCase() + ' ');
    comp.create();
    http.expectNone(base);

    comp.sharedConfirm.set(tenant().name);
    comp.create();
    http.expectOne(base).flush({ uid: 'T9', name: 'Beta Ltd', alias: 'beta', status: 'ACTIVE' });
    http.expectOne(`${base}/tenants`).flush([tenant()]);
  });

  it('with two or more organisations, asks for no confirmation name', () => {
    setup([tenant(), tenant({ uid: 'T2', name: 'Second Customer' })]);
    comp.openForm();
    fillRequired();
    comp.sharedConfirm.set('');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#sharedConfirm')).toBeNull();
    comp.create();
    http.expectOne(base).flush({ uid: 'T9', name: 'Beta Ltd', alias: 'beta', status: 'ACTIVE' });
    http.expectOne(`${base}/tenants`).flush([tenant()]);
  });

  it('refuses to create while the tenant list has not loaded (fail closed)', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const session = TestBed.inject(SessionStore);
    session.clear();
    session.setPermissions(['ORG.CREATE', 'ORG.SUSPEND']);
    fixture = TestBed.createComponent(TenantListComponent);
    comp = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    http.expectOne(`${base}/tenants`).flush({ errors: ['x'] }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    comp.openForm();
    fillRequired();
    comp.create();
    http.expectNone(base);
    expect(comp.sharedConfirmError()).toContain('must load');
  });

  it('root sees Enter on other tenants, nothing on the current row; non-root sees no Enter', () => {
    setup([tenant({ uid: 'T0', name: 'Home Org', current: true }), tenant()]);
    expect(fixture.nativeElement.querySelector('[aria-label="Enter Acme Traders"]')).toBeNull();
    TestBed.inject(SessionStore).setSession('a', 'r', {
      uid: 'U-ROOT', username: 'rootadmin', displayName: 'Root', isRoot: true,
      activeCompanyUid: 'C', activeBranchUid: 'B', hasBranch: true,
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[aria-label="Enter Acme Traders"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[aria-label="Enter Home Org"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[aria-label="Suspend Home Org"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('You are here');
  });

  it('Enter switches to the tenant branch, remembers the tenant, then reloads', () => {
    setup([tenant()]);
    const s = TestBed.inject(SessionStore);
    s.setSession('a', 'r', {
      uid: 'U-ROOT', username: 'rootadmin', displayName: 'Root', isRoot: true,
      activeCompanyUid: 'C', activeBranchUid: 'B', hasBranch: true,
    });
    const nav = vi.spyOn(comp as unknown as { navigate: (u: string) => void }, 'navigate')
      .mockImplementation(() => undefined);
    comp.enter(tenant());
    http.expectOne(`${base}/uid/T1/entry`).flush({
      organisationUid: 'T1', organisationName: 'Acme Traders', companyName: 'Acme',
      branchUid: 'BR-ACME', branchName: 'Acme HQ', branchCode: 'AHQ',
    });
    expect(s.activeBranchUid()).toBe('BR-ACME');
    expect(s.platformTenant()?.organisationName).toBe('Acme Traders');
    expect(nav).toHaveBeenCalledWith('/admin/home');
  });

  function asRoot(): void {
    TestBed.inject(SessionStore).setSession('a', 'r', {
      uid: 'U-ROOT', username: 'rootadmin', displayName: 'Root', isRoot: true,
      activeCompanyUid: 'C', activeBranchUid: 'B', hasBranch: true,
    });
  }

  it('offers Create platform organisation to root at home when none exists', () => {
    setup([tenant({ uid: 'H', name: 'Home Customer', current: true })]);
    asRoot();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="platform-offer"]')).not.toBeNull();
  });

  it('does not offer it once a platform organisation exists, and the Platform row has no actions', () => {
    setup([tenant({ uid: 'P', name: 'Platform', alias: 'platform', current: true }), tenant()]);
    asRoot();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="platform-offer"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[aria-label="Suspend Platform"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[aria-label="Enter Platform"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Platform');
  });

  it('a non-root user is never offered it', () => {
    setup([tenant({ uid: 'H', name: 'Home Customer', current: true })]);
    expect(fixture.nativeElement.querySelector('[data-testid="platform-offer"]')).toBeNull();
  });

  it('creates it only after the exact name is typed, then signs out', () => {
    setup([tenant({ uid: 'H', name: 'Home Customer', current: true })]);
    asRoot();
    const nav = vi.spyOn(comp as unknown as { navigate: (u: string) => void }, 'navigate')
      .mockImplementation(() => undefined);
    comp.openPlatformDialog();
    comp.platformConfirm.set('home customer');
    comp.createPlatform();
    http.expectNone(`${base}/platform`);
    comp.platformConfirm.set('Home Customer');
    comp.createPlatform();
    const req = http.expectOne(`${base}/platform`);
    expect(req.request.body).toEqual({ confirmName: 'Home Customer' });
    req.flush({ uid: 'P', name: 'Platform', alias: 'platform', status: 'ACTIVE' });
    http.expectOne((r) => r.url.endsWith('/auth/logout')).flush(null);
    expect(nav).toHaveBeenCalledWith('/login');
  });

  it('shows the server refusal (e.g. no administrator of their own)', () => {
    setup([tenant({ uid: 'H', name: 'Home Customer', current: true })]);
    asRoot();
    comp.openPlatformDialog();
    comp.platformConfirm.set('Home Customer');
    comp.createPlatform();
    http.expectOne(`${base}/platform`).flush(
      { errors: ["Give at least one of Home Customer's staff the Organisation administrator role first."] },
      { status: 409, statusText: 'Conflict' });
    expect(comp.platformError()).toContain('Organisation administrator role');
    expect(comp.platformSaving()).toBe(false);
  });

  describe('filter', () => {
    const rows = () => [
      tenant({ uid: 'A', name: 'Acme Traders', alias: 'acme' }),
      tenant({ uid: 'B', name: 'Beta Foods', alias: 'beta', status: 'INACTIVE' }),
      tenant({ uid: 'C', name: 'Cafe Tulia', alias: 'tulia' }),
    ];
    const names = () =>
      Array.from(fixture.nativeElement.querySelectorAll('tbody tr td:first-child') as NodeListOf<HTMLElement>)
        .map((td) => td.textContent!.trim());

    it('matches name or alias, case-insensitively, and counts', () => {
      setup(rows());
      comp.searchText.set('TULIA');
      fixture.detectChanges();
      expect(names()).toEqual(['Cafe Tulia']);
      comp.searchText.set('bet');
      fixture.detectChanges();
      expect(names()).toEqual(['Beta Foods']);
      expect(fixture.nativeElement.querySelector('[data-testid="tenant-count"]').textContent)
        .toContain('Showing 1 of 3');
    });

    it('filters by status', () => {
      setup(rows());
      comp.statusFilter.set('INACTIVE');
      fixture.detectChanges();
      expect(names()).toEqual(['Beta Foods']);
      comp.statusFilter.set('ACTIVE');
      fixture.detectChanges();
      expect(names()).toEqual(['Acme Traders', 'Cafe Tulia']);
    });

    it('says so when nothing matches, and Clear restores the list', () => {
      setup(rows());
      comp.searchText.set('zzz');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[data-testid="tenant-no-match"]')).not.toBeNull();
      comp.clearFilter();
      fixture.detectChanges();
      expect(names().length).toBe(3);
    });
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
