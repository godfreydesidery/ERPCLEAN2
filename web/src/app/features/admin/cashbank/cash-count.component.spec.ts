/**
 * CashCountComponent specs (ADR-0050 D-7 PR-A).
 *
 * Covers:
 *  1. New-mode: openDisabled until till + business date + company are set.
 *  2. New-mode: openCount() posts the correct OpenCashCountRequest and loads the returned entity.
 *  3. Denomination grid: countedAmount is the live Σ(denomination × quantity); varianceAmount = counted - expected.
 *  4. varianceTone: over(ok) / short(danger) / balanced(neutral).
 *  5. editable() is false once RECONCILED, and false without CASH.COUNT.MANAGE.
 *  6. Detail-mode: loads an existing count by uid and seeds quantities from its denominations.
 *  7. saveCount() sends the full 8-line denomination ladder (including zero-quantity rows).
 *  8. reconcileCount() only calls the service when status is COUNTED.
 *  9. Reconcile button renders in the DOM only when status is COUNTED and the user can manage.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Route, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { CashCountComponent } from './cash-count.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import type { CashCountDto } from './models/cashbank.model';

@Component({ template: '', standalone: true })
class StubRouteComponent {}
const STUB_ROUTES: Route[] = [{ path: '**', component: StubRouteComponent }];

function makeSession(canManage = true) {
  return {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

const MOCK_TILLS = [
  { uid: 'TILL1', id: '1', companyId: '10', code: 'CASH-001', name: 'Main Till', accountType: 'CASH', active: true, isDefault: true, currency: 'TZS', glAccountId: '50', bankName: null, bankAccountNo: null, bankBranch: null, branchId: null },
  { uid: 'BANK1', id: '2', companyId: '10', code: 'BANK-001', name: 'CRDB Bank', accountType: 'BANK', active: true, isDefault: false, currency: 'TZS', glAccountId: '51', bankName: 'CRDB', bankAccountNo: '0001', bankBranch: 'HQ', branchId: null },
];

function makeCount(overrides: Partial<CashCountDto> = {}): CashCountDto {
  return {
    id: '1',
    uid: 'CC1',
    companyId: '10',
    cashBankAccountUid: 'TILL1',
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
    ...overrides,
  };
}

function makeBed(opts: { canManage?: boolean; cashbankOverrides?: Record<string, unknown> } = {}) {
  const { canManage = true, cashbankOverrides = {} } = opts;

  TestBed.configureTestingModule({
    imports: [CashCountComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter(STUB_ROUTES),
      {
        provide: CashbankService,
        useValue: {
          listAllAccounts: vi.fn(() => of(MOCK_TILLS)),
          openCashCount: vi.fn(() => of(makeCount())),
          recordDenominations: vi.fn(() => of(makeCount({ status: 'COUNTED' }))),
          reconcileCashCount: vi.fn(() => of(makeCount({ status: 'RECONCILED', journalEntryRef: 'JE1' }))),
          getCashCount: vi.fn(() => of(makeCount())),
          ...cashbankOverrides,
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSession(canManage) },
    ],
  });
}

// ── New-mode: open form ────────────────────────────────────────────────────

describe('CashCountComponent — new mode (open form)', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('openDisabled true initially (no till selected)', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CashCountComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isNewMode()).toBe(true);
    expect(comp.openDisabled()).toBe(true);
  });

  it('till options are filtered to CASH-type accounts only', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CashCountComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.tills().map((t) => t.uid)).toEqual(['TILL1']);
  });

  it('openCount() posts the correct request and loads the returned entity', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CashCountComponent);
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CashbankService) as any;
    const router = TestBed.inject(Router);
    const navSpy = vi.spyOn(router, 'navigate');
    await vi.runAllTimersAsync();

    comp.selectedTillUid.set('TILL1');
    comp.businessDate.set('2026-07-04');
    expect(comp.openDisabled()).toBe(false);

    comp.openCount();
    await vi.runAllTimersAsync();

    expect(svc.openCashCount).toHaveBeenCalledWith({
      companyUid: 'CO1',
      cashBankAccountUid: 'TILL1',
      businessDate: '2026-07-04',
    });
    expect(comp.entity()?.uid).toBe('CC1');
    expect(navSpy).toHaveBeenCalledWith(['/admin/cash/counts/uid', 'CC1'], { replaceUrl: true });
  });

  it('openCount() surfaces a friendly error on failure', async () => {
    vi.useFakeTimers();
    makeBed({
      cashbankOverrides: {
        openCashCount: vi.fn(() =>
          throwError(() => new HttpErrorResponse({ status: 422, error: { errors: ['Till already has an open count today.'] } })),
        ),
      },
    });
    const fixture = TestBed.createComponent(CashCountComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectedTillUid.set('TILL1');
    comp.businessDate.set('2026-07-04');
    comp.openCount();
    await vi.runAllTimersAsync();

    expect(comp.openError()).toBe('Till already has an open count today.');
    expect(comp.entity()).toBeNull();
  });
});

// ── Denomination grid: counted + variance ──────────────────────────────────

describe('CashCountComponent — denomination grid computes counted + variance', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('countedAmount sums denomination × quantity live', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.entity()?.expectedAmount).toBe(1000);
    expect(comp.countedAmount()).toBe(0);

    comp.setQuantity(10000, '1'); // 1 × 10,000
    comp.setQuantity(500, '4');   // 4 × 500 = 2,000
    expect(comp.countedAmount()).toBe(12000);
  });

  it('varianceAmount = counted - expected (over is positive)', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // expected = 1000
    comp.setQuantity(1000, '2'); // counted = 2000
    expect(comp.countedAmount()).toBe(2000);
    expect(comp.varianceAmount()).toBe(1000); // over by 1000
    expect(comp.varianceTone()).toBe('ok');
  });

  it('varianceTone is danger when counted < expected (short)', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // expected = 1000, counted = 500 → short by 500
    comp.setQuantity(500, '1');
    expect(comp.varianceAmount()).toBe(-500);
    expect(comp.varianceTone()).toBe('danger');
  });

  it('varianceTone is neutral when counted exactly matches expected', async () => {
    vi.useFakeTimers();
    makeBed({
      cashbankOverrides: {
        getCashCount: vi.fn(() => of(makeCount({ expectedAmount: 500 }))),
      },
    });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.setQuantity(500, '1'); // counted = 500 = expected
    expect(comp.varianceAmount()).toBe(0);
    expect(comp.varianceTone()).toBe('neutral');
  });
});

// ── editable() gating ───────────────────────────────────────────────────────

describe('CashCountComponent — editable() gating', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('editable is true for an OPEN count when the user can manage', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: true });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.editable()).toBe(true);
  });

  it('editable is false once the count is RECONCILED', async () => {
    vi.useFakeTimers();
    makeBed({
      canManage: true,
      cashbankOverrides: { getCashCount: vi.fn(() => of(makeCount({ status: 'RECONCILED' }))) },
    });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.editable()).toBe(false);
  });

  it('editable is false without CASH.COUNT.MANAGE, even for an OPEN count', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: false });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.canManage()).toBe(false);
    expect(comp.editable()).toBe(false);
  });
});

// ── Detail-mode load + seeding ──────────────────────────────────────────────

describe('CashCountComponent — detail mode load', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('loads an existing count by uid and seeds quantities from its denominations', async () => {
    vi.useFakeTimers();
    makeBed({
      cashbankOverrides: {
        getCashCount: vi.fn(() =>
          of(makeCount({
            status: 'COUNTED',
            countedAmount: 12000,
            varianceAmount: 11000,
            denominations: [
              { denomination: 10000, quantity: 1, lineAmount: 10000 },
              { denomination: 500, quantity: 4, lineAmount: 2000 },
            ],
          })),
        ),
      },
    });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('idle');
    expect(comp.quantityOf(10000)).toBe('1');
    expect(comp.quantityOf(500)).toBe('4');
    expect(comp.quantityOf(5000)).toBe(''); // never counted — blank, not '0'
    expect(comp.countedAmount()).toBe(12000);
  });

  it('sets forbidden state on a 403', async () => {
    vi.useFakeTimers();
    makeBed({
      cashbankOverrides: {
        getCashCount: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
      },
    });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});

// ── saveCount() ──────────────────────────────────────────────────────────────

describe('CashCountComponent — saveCount()', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sends the full 8-line denomination ladder, including zero-quantity rows', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CashbankService) as any;
    await vi.runAllTimersAsync();

    comp.setQuantity(10000, '2');
    comp.setQuantity(50, '3');
    comp.saveCount();
    await vi.runAllTimersAsync();

    expect(svc.recordDenominations).toHaveBeenCalledWith('CC1', {
      lines: [
        { denomination: 10000, quantity: 2 },
        { denomination: 5000, quantity: 0 },
        { denomination: 2000, quantity: 0 },
        { denomination: 1000, quantity: 0 },
        { denomination: 500, quantity: 0 },
        { denomination: 200, quantity: 0 },
        { denomination: 100, quantity: 0 },
        { denomination: 50, quantity: 3 },
      ],
    });
    expect(comp.entity()?.status).toBe('COUNTED');
  });

  it('does not call the service when not editable (already RECONCILED)', async () => {
    vi.useFakeTimers();
    makeBed({ cashbankOverrides: { getCashCount: vi.fn(() => of(makeCount({ status: 'RECONCILED' }))) } });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CashbankService) as any;
    await vi.runAllTimersAsync();

    comp.saveCount();
    expect(svc.recordDenominations).not.toHaveBeenCalled();
  });
});

// ── reconcileCount() ─────────────────────────────────────────────────────────

describe('CashCountComponent — reconcileCount()', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('does nothing when status is not COUNTED', async () => {
    vi.useFakeTimers();
    makeBed(); // default entity status OPEN
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CashbankService) as any;
    await vi.runAllTimersAsync();

    comp.reconcileCount();
    expect(svc.reconcileCashCount).not.toHaveBeenCalled();
  });

  it('posts the reconcile action and updates the entity when status is COUNTED', async () => {
    vi.useFakeTimers();
    makeBed({ cashbankOverrides: { getCashCount: vi.fn(() => of(makeCount({ status: 'COUNTED' }))) } });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    const comp = fixture.componentInstance;
    const svc = TestBed.inject(CashbankService) as any;
    await vi.runAllTimersAsync();

    comp.reconcileCount();
    await vi.runAllTimersAsync();

    expect(svc.reconcileCashCount).toHaveBeenCalledWith('CC1');
    expect(comp.entity()?.status).toBe('RECONCILED');
    expect(comp.entity()?.journalEntryRef).toBe('JE1');
  });
});

// ── DOM: Reconcile button visibility ────────────────────────────────────────

describe('CashCountComponent — Reconcile button DOM visibility', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('does not render a Reconcile button while status is OPEN', async () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some((b) => b.textContent?.includes('Reconcile'))).toBe(false);
  });

  it('renders a Reconcile button once status is COUNTED and the user can manage', async () => {
    vi.useFakeTimers();
    makeBed({ cashbankOverrides: { getCashCount: vi.fn(() => of(makeCount({ status: 'COUNTED' }))) } });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some((b) => b.textContent?.includes('Reconcile'))).toBe(true);
  });

  it('does not render a Reconcile button when the user lacks CASH.COUNT.MANAGE', async () => {
    vi.useFakeTimers();
    makeBed({ canManage: false, cashbankOverrides: { getCashCount: vi.fn(() => of(makeCount({ status: 'COUNTED' }))) } });
    const fixture = TestBed.createComponent(CashCountComponent);
    fixture.componentRef.setInput('uid', 'CC1');
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const buttons = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
    expect(buttons.some((b) => b.textContent?.includes('Reconcile'))).toBe(false);
  });
});
