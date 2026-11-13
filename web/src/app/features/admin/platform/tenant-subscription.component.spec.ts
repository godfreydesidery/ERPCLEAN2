import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { environment } from '../../../../environments/environment';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import {
  PlanCatalogue,
  TenantSubscription,
  addCycle,
  addMonths,
  daysBetween,
} from './subscription.model';
import { TenantSubscriptionComponent } from './tenant-subscription.component';

const api = environment.apiBaseUrl;
const subUrl = `${api}/organisations/uid/T1/subscription`;

const catalogue: PlanCatalogue = {
  plans: [
    { code: 'BASIC', name: 'Basic', rank: 1, maxUsers: 5, maxBranches: 1, maxTills: 3, maxCompanies: 1, modules: [] },
    { code: 'ENTERPRISE', name: 'Enterprise', rank: 4, maxUsers: null, maxBranches: null, maxTills: null, maxCompanies: null, modules: [] },
  ],
  addOns: [
    { code: 'RESTAURANT', name: 'Restaurant' },
    { code: 'ORBIXHQ', name: 'OrbixHQ app' },
  ],
};

const unassigned = (): TenantSubscription => ({
  organisationUid: 'T1',
  organisationName: 'Acme Traders',
  assigned: false,
  planCode: null,
  planName: null,
  addOnRestaurant: false,
  addOnOrbixHq: false,
  billingCycle: null,
  trialEndsOn: null,
  paidUntil: null,
  graceDays: 7,
  readOnlyDays: 7,
  notes: null,
  stage: 'UNRESTRICTED',
  dueDate: null,
  readOnlyFrom: null,
  suspendedFrom: null,
  caps: [],
  modules: [],
  payments: [],
});

const assigned = (over: Partial<TenantSubscription> = {}): TenantSubscription => ({
  ...unassigned(),
  assigned: true,
  planCode: 'BASIC',
  planName: 'Basic',
  billingCycle: 'MONTHLY',
  paidUntil: '2026-11-30',
  stage: 'ACTIVE',
  dueDate: '2026-11-30',
  readOnlyFrom: '2026-12-08',
  suspendedFrom: '2026-12-15',
  caps: [
    { resource: 'USERS', planLimit: 5, override: 8, effectiveLimit: 8, inUse: 3 },
    { resource: 'BRANCHES', planLimit: 1, override: null, effectiveLimit: 1, inUse: 1 },
    { resource: 'TILLS', planLimit: 3, override: null, effectiveLimit: 3, inUse: 2 },
    { resource: 'COMPANIES', planLimit: 1, override: null, effectiveLimit: 1, inUse: 1 },
  ],
  modules: [{ code: 'POS', name: 'Point of sale' }],
  payments: [
    {
      uid: 'P1', amount: 150000, currencyCode: 'TZS', paidOn: '2026-10-30', paidUntil: '2026-11-30',
      reference: 'MPESA-1', note: null, recordedAt: '2026-10-30T08:00:00Z', recordedBy: 'rootadmin',
    },
  ],
  ...over,
});

describe('TenantSubscriptionComponent', () => {
  let fixture: ComponentFixture<TenantSubscriptionComponent>;
  let http: HttpTestingController;
  let comp: TenantSubscriptionComponent;

  function setup(sub: TenantSubscription, perms: string[] = ['ORG.CREATE', 'ORG.SUSPEND']): void {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const session = TestBed.inject(SessionStore);
    session.clear();
    session.setPermissions(perms);
    fixture = TestBed.createComponent(TenantSubscriptionComponent);
    fixture.componentRef.setInput('uid', 'T1');
    comp = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne(subUrl).flush(sub);
    http.expectOne(`${api}/subscription-plans`).flush(catalogue);
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify({ ignoreCancelled: true });
    TestBed.inject(SessionStore).clear();
    vi.useRealTimers();
  });

  const text = (): string => fixture.nativeElement.textContent as string;

  it('shows the error state when loading fails, with Retry', () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(TenantSubscriptionComponent);
    fixture.componentRef.setInput('uid', 'T1');
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    http.expectOne(subUrl).flush({ errors: ['x'] }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(text()).toContain('Could not load the subscription');
  });

  it('with no plan: says unrestricted, offers Assign plan, defaults 7/7, disables payment', () => {
    setup(unassigned());
    expect(fixture.nativeElement.querySelector('[data-testid="sub-unassigned"]')).not.toBeNull();
    expect(text()).toContain('Assign plan');
    expect(comp.graceDays()).toBe(7);
    expect(comp.readOnlyDays()).toBe(7);
    expect(fixture.nativeElement.querySelector('[data-testid="pay-disabled-note"]')).not.toBeNull();
    expect((fixture.nativeElement.querySelector('#payAmount') as HTMLInputElement).matches(':disabled')).toBe(true);
    const payBtn = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>)
      .find((b) => b.textContent!.includes('Record payment'))!;
    expect(payBtn.disabled).toBe(true);
    comp.recordPayment();
    http.expectNone(`${subUrl}/payments`);
  });

  it('shows plan, stage, due date, read-only and suspended dates, modules and payment history', () => {
    setup(assigned());
    const cur = fixture.nativeElement.querySelector('[data-testid="sub-current"]').textContent as string;
    expect(cur).toContain('Basic');
    expect(cur).toContain('Active');
    expect(cur).toContain('Nov 30, 2026');
    expect(cur).toContain('Dec 8, 2026');
    expect(cur).toContain('Dec 15, 2026');
    expect(fixture.nativeElement.querySelector('[data-testid="modules"]').textContent).toContain('Point of sale');
    expect(fixture.nativeElement.querySelector('[data-testid="payments"]').textContent).toContain('MPESA-1');
  });

  it('shows plan limits beside overrides and in-use counts; null limit reads Unlimited', async () => {
    setup(assigned());
    await fixture.whenStable();
    fixture.detectChanges();
    const limits = () =>
      Array.from(fixture.nativeElement.querySelectorAll('[data-testid="plan-limit"]') as NodeListOf<HTMLElement>)
        .map((c) => c.textContent!.trim());
    expect(limits()).toEqual(['5', '1', '3', '1']);
    expect((fixture.nativeElement.querySelector('#subOverrideUSERS') as HTMLInputElement).value).toBe('8');
    comp.planCode.set('ENTERPRISE');
    fixture.detectChanges();
    expect(limits()).toEqual(['Unlimited', 'Unlimited', 'Unlimited', 'Unlimited']);
  });

  it('maps the form to the PUT body (blank override = null)', () => {
    setup(unassigned());
    comp.planCode.set('BASIC');
    comp.addOnRestaurant.set(true);
    comp.billingCycle.set('QUARTERLY');
    comp.trialEndsOn.set('2026-11-27');
    comp.graceDays.set(10);
    comp.overrides.TILLS.set(12);
    comp.notes.set(' contract ');
    comp.save();
    const req = http.expectOne(subUrl);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({
      planCode: 'BASIC',
      addOnRestaurant: true,
      addOnOrbixHq: false,
      billingCycle: 'QUARTERLY',
      trialEndsOn: '2026-11-27',
      paidUntil: null,
      graceDays: 10,
      readOnlyDays: 7,
      maxUsersOverride: null,
      maxBranchesOverride: null,
      maxTillsOverride: 12,
      maxCompaniesOverride: null,
      notes: 'contract',
    });
    req.flush(assigned({ billingCycle: 'QUARTERLY' }));
    fixture.detectChanges();
    expect(comp.saveOk()).toBe(true);
    expect(comp.subscription()?.assigned).toBe(true);
  });

  it('refuses to save without a plan or with days out of range', () => {
    setup(unassigned());
    comp.save();
    http.expectNone(subUrl);
    expect(comp.planErrors()).toContain('Choose a plan.');
    comp.planCode.set('BASIC');
    comp.graceDays.set(400);
    comp.save();
    http.expectNone(subUrl);
    expect(comp.planErrors().join(' ')).toContain('Grace days');
  });

  it('shows the server message when save is refused', () => {
    setup(unassigned());
    comp.planCode.set('BASIC');
    comp.save();
    http.expectOne(subUrl).flush({ errors: ['Billing cycle is required.'] }, { status: 400, statusText: 'Bad Request' });
    expect(comp.saveError()).toBe('Billing cycle is required.');
    expect(comp.saving()).toBe(false);
  });

  it('pre-fills the payment: paid on today, new paid-until = current paid-until + one cycle', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 10, 13)); // 13 Nov 2026, local
    setup(assigned({ paidUntil: '2026-11-30', billingCycle: 'MONTHLY' }));
    expect(comp.payPaidOn()).toBe('2026-11-13');
    expect(comp.payPaidUntil()).toBe('2026-12-30');
    expect(comp.payCurrency()).toBe('TZS');
  });

  it('pre-fills from today when nothing has been paid, and not at all for CUSTOM', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 10, 13));
    setup(assigned({ paidUntil: null, billingCycle: 'ANNUAL' }));
    expect(comp.payPaidUntil()).toBe('2027-11-13');
    comp.subscription.set(assigned({ paidUntil: '2026-11-30', billingCycle: 'CUSTOM' }));
    comp.prefillPayment();
    expect(comp.payPaidUntil()).toBe('');
  });

  it('records a payment (POST body), then shows the refreshed subscription', () => {
    setup(assigned());
    comp.payAmount.set(150000);
    comp.payPaidOn.set('2026-11-13');
    comp.payPaidUntil.set('2026-12-30');
    comp.payReference.set(' MPESA-2 ');
    comp.recordPayment();
    const req = http.expectOne(`${subUrl}/payments`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      amount: 150000,
      currencyCode: 'TZS',
      paidOn: '2026-11-13',
      paidUntil: '2026-12-30',
      reference: 'MPESA-2',
      note: null,
    });
    req.flush(assigned({ paidUntil: '2026-12-30', dueDate: '2026-12-30' }));
    expect(comp.payOk()).toBe(true);
    expect(comp.subscription()?.paidUntil).toBe('2026-12-30');
  });

  it('requires the amount and the new paid-until; shows the server error on refusal', () => {
    setup(assigned());
    comp.payPaidUntil.set('');
    comp.recordPayment();
    http.expectNone(`${subUrl}/payments`);
    expect(comp.payErrors().length).toBe(2);

    comp.payAmount.set(10);
    comp.payPaidUntil.set('2026-12-30');
    comp.recordPayment();
    http.expectOne(`${subUrl}/payments`).flush({ errors: ['Assign a plan first.'] }, { status: 409, statusText: 'Conflict' });
    expect(comp.payError()).toBe('Assign a plan first.');
  });

  it('without ORG.SUSPEND the forms are read-only and have no submit buttons', () => {
    setup(assigned(), ['ORG.CREATE']);
    expect((fixture.nativeElement.querySelector('#subPlan') as HTMLSelectElement).matches(':disabled')).toBe(true);
    expect(fixture.nativeElement.querySelector('button[type="submit"]')).toBeNull();
  });

  it('has no axe violations (assigned, with history)', async () => {
    setup(assigned());
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations (no plan yet)', async () => {
    setup(unassigned());
    await assertA11y(fixture);
  }, 20_000);
});

describe('subscription date helpers', () => {
  it('adds months, clamping to the month end', () => {
    expect(addMonths('2026-01-31', 1)).toBe('2026-02-28');
    expect(addMonths('2026-11-30', 3)).toBe('2027-02-28');
    expect(addMonths('2026-11-13', 12)).toBe('2027-11-13');
  });
  it('adds one billing cycle, nothing for CUSTOM', () => {
    expect(addCycle('2026-11-13', 'HALF_YEARLY')).toBe('2027-05-13');
    expect(addCycle('2026-11-13', 'CUSTOM')).toBeNull();
    expect(addCycle('2026-11-13', null)).toBeNull();
  });
  it('counts whole days across a month boundary', () => {
    expect(daysBetween('2026-11-28', '2026-12-05')).toBe(7);
    expect(daysBetween('2026-12-05', '2026-11-28')).toBe(-7);
  });
});
