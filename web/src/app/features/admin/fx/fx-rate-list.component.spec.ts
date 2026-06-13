import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { FxRateListComponent } from './fx-rate-list.component';
import { FxService } from './fx.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CurrencyRateDto } from './models/fx.model';
import { PageMeta } from '../../../core/api/api-response.model';

// ── Fixtures ──────────────────────────────────────────────────────────────────

const MOCK_ORG     = { uid: 'ORG1', id: '1', name: 'Acme Org' };
const MOCK_COMPANY = { uid: 'CO1', id: '10', name: 'Acme Ltd' };

const MOCK_META: PageMeta = { page: 0, size: 50, totalElements: 2, totalPages: 1, hasNext: false };

const MOCK_RATE_1: CurrencyRateDto = {
  id: '1', uid: 'RATE-001', companyId: '10',
  fromCurrency: 'USD', toCurrency: 'TZS',
  rate: '2350.000000',
  effectiveDate: '2026-06-01',
  rateType: 'SPOT',
  source: 'Central Bank',
  active: true,
};

const MOCK_RATE_2: CurrencyRateDto = {
  id: '2', uid: 'RATE-002', companyId: '10',
  fromCurrency: 'EUR', toCurrency: 'TZS',
  rate: '2520.000000',
  effectiveDate: '2026-06-01',
  rateType: 'OFFICIAL',
  source: null,
  active: true,
};

function makeSession(canManage = true) {
  return {
    hasPermission: vi.fn((perm: string) => {
      if (perm === 'CURRENCY.VIEW')   return true;
      if (perm === 'CURRENCY.MANAGE') return canManage;
      return false;
    }),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeFxService(rows = [MOCK_RATE_1, MOCK_RATE_2]) {
  return {
    listCurrencies: vi.fn(() => of([])),
    listRates: vi.fn(() => of({ rows, meta: MOCK_META })),
    addRate: vi.fn(() => of(MOCK_RATE_1)),
  };
}

function makeBed(fxService = makeFxService(), session = makeSession()) {
  TestBed.configureTestingModule({
    imports: [FxRateListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      { provide: FxService, useValue: fxService },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(MOCK_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([MOCK_COMPANY])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: session },
    ],
  });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('FxRateListComponent', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('renders without crashing', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(FxRateListComponent);
    expect(fixture).toBeTruthy();
  });

  it('starts in idle state with rows populated after companies resolve', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    // Immediately after construction via synchronous of() the constructor chain runs:
    expect(comp.rows()).toEqual([MOCK_RATE_1, MOCK_RATE_2]);
    expect(comp.state()).toBe('idle');
  });

  it('isEmpty is true when rows is empty', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    comp.rows.set([]);
    comp.state.set('idle');
    expect(comp.isEmpty()).toBe(true);

    comp.rows.set([MOCK_RATE_1]);
    expect(comp.isEmpty()).toBe(false);
  });

  it('isEmpty is false even with empty rows when state is not idle', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    comp.rows.set([]);
    comp.state.set('loading');
    expect(comp.isEmpty()).toBe(false);
  });

  it('fmtRate formats a 6-decimal rate string', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    expect(comp.fmtRate('2350.000000')).toBe('2350.000000');
    expect(comp.fmtRate('1.500000')).toBe('1.500000');
    expect(comp.fmtRate(null)).toBe('0.000000');
    expect(comp.fmtRate(undefined)).toBe('0.000000');
  });

  it('rateTypeBadgeClass returns correct badge classes', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    expect(comp.rateTypeBadgeClass('SPOT')).toBe('text-bg-success');
    expect(comp.rateTypeBadgeClass('FORWARD')).toBe('text-bg-info');
    expect(comp.rateTypeBadgeClass('OFFICIAL')).toBe('text-bg-primary');
    expect(comp.rateTypeBadgeClass('UNKNOWN')).toBe('text-bg-secondary');
    expect(comp.rateTypeBadgeClass(null)).toBe('text-bg-secondary');
  });

  it('submitCreate sets createError when fromCurrency is empty', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    comp.showCreateForm.set(true);
    comp.newFromCurrency.set('');
    comp.submitCreate();
    expect(comp.createError()).toBeTruthy();
  });

  it('submitCreate sets error when from === to', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    comp.showCreateForm.set(true);
    comp.newFromCurrency.set('USD');
    comp.newToCurrency.set('USD');
    comp.newRate.set('1.5');
    comp.newEffectiveDate.set('2026-06-01');
    comp.submitCreate();
    expect(comp.createError()).toContain('must differ');
  });

  it('submitCreate sets error when rate is zero', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    comp.showCreateForm.set(true);
    comp.newFromCurrency.set('USD');
    comp.newToCurrency.set('TZS');
    comp.newRate.set('0');
    comp.newEffectiveDate.set('2026-06-01');
    comp.submitCreate();
    expect(comp.createError()).toContain('positive');
  });

  it('state goes to forbidden on 403', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    fxSvc.listRates = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    );
    makeBed(fxSvc);
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    comp.load();
    expect(comp.state()).toBe('forbidden');
  });

  it('canManage is false when session lacks CURRENCY.MANAGE', () => {
    vi.useFakeTimers();
    makeBed(makeFxService(), makeSession(false));
    const comp = TestBed.createComponent(FxRateListComponent).componentInstance as any;
    expect(comp.canManage()).toBe(false);
  });
});
