/**
 * ChartOfAccountsListComponent spec.
 * Mirrors product-list.component.spec.ts conventions (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Startup: loads companies then accounts on init.
 *  2. isEmpty is true when no accounts returned.
 *  3. create() validation: requires code and name.
 *  4. create() calls glService.createAccount with correct payload.
 *  5. 403 response sets state to 'forbidden'.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from './gl.service';
import type { AccountPage } from './gl.service';
import { ChartOfAccountsListComponent } from './chart-of-accounts-list.component';

// ── Helpers ────────────────────────────────────────────────────────────────────

const emptyPage = (): AccountPage => ({
  rows: [],
  meta: { page: 0, size: 50, totalElements: 0, totalPages: 0, hasNext: false },
});

const sampleAccount = () => ({
  id: '1', uid: 'ACC1', companyId: '10',
  accountCode: '1001', name: 'Cash', accountType: 'ASSET' as const,
  normalBalance: 'DEBIT' as const, active: true, status: 'ACTIVE',
});

function makeSessionStore(canManage = false) {
  return {
    hasPermission: vi.fn(() => canManage),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { listImpl?: () => any; canManage?: boolean } = {}) {
  const { listImpl, canManage = false } = opts;
  const sessionStore = makeSessionStore(canManage);

  TestBed.configureTestingModule({
    imports: [ChartOfAccountsListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: GlService,
        useValue: {
          listAccounts: vi.fn(listImpl ?? (() => of(emptyPage()))),
          createAccount: vi.fn(() => of(sampleAccount())),
          updateAccount: vi.fn(() => of(sampleAccount())),
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SessionStore, useValue: sessionStore },
    ],
  });
}

// ── Init ───────────────────────────────────────────────────────────────────────

describe('ChartOfAccountsListComponent — init', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fires exactly one load on startup after company resolves', async () => {
    TestBed.createComponent(ChartOfAccountsListComponent);
    const svc = TestBed.inject(GlService) as any;
    await vi.runAllTimersAsync();

    expect(svc.listAccounts).toHaveBeenCalledTimes(1);
    expect(svc.listAccounts).toHaveBeenCalledWith('10', undefined, 0, 50);
  });

  it('isEmpty is true when no accounts returned', async () => {
    const comp = TestBed.createComponent(ChartOfAccountsListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Create form ────────────────────────────────────────────────────────────────

describe('ChartOfAccountsListComponent — create form', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed({ canManage: true }); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('create() validation: requires code', async () => {
    const comp = TestBed.createComponent(ChartOfAccountsListComponent).componentInstance;
    const svc = TestBed.inject(GlService) as any;
    await vi.runAllTimersAsync();

    comp.newCode.set('');
    comp.newName.set('Cash');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createAccount).not.toHaveBeenCalled();
  });

  it('create() validation: requires name', async () => {
    const comp = TestBed.createComponent(ChartOfAccountsListComponent).componentInstance;
    const svc = TestBed.inject(GlService) as any;
    await vi.runAllTimersAsync();

    comp.newCode.set('1001');
    comp.newName.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(svc.createAccount).not.toHaveBeenCalled();
  });

  it('create() calls glService.createAccount with correct payload', async () => {
    const comp = TestBed.createComponent(ChartOfAccountsListComponent).componentInstance;
    const svc = TestBed.inject(GlService) as any;
    await vi.runAllTimersAsync();

    comp.newCode.set('1001');
    comp.newName.set('Cash at Bank');
    comp.newAccountType.set('ASSET');
    comp.create();

    expect(svc.createAccount).toHaveBeenCalledOnce();
    const req = svc.createAccount.mock.calls[0][0];
    expect(req.companyUid).toBe('CO1');
    expect(req.accountCode).toBe('1001');
    expect(req.name).toBe('Cash at Bank');
    expect(req.accountType).toBe('ASSET');
  });
});

// ── 403 forbidden ──────────────────────────────────────────────────────────────

describe('ChartOfAccountsListComponent — 403 forbidden', () => {
  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets state to forbidden when list returns 403', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });

    const comp = TestBed.createComponent(ChartOfAccountsListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
