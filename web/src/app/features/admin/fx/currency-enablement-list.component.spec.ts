import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { CurrencyEnablementListComponent } from './currency-enablement-list.component';
import { FxService } from './fx.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyCurrencyDto, CurrencyDto } from './models/fx.model';

// ── Fixtures ──────────────────────────────────────────────────────────────────

const MOCK_ORG     = { uid: 'ORG1', id: '1', name: 'Acme Org' };
const MOCK_COMPANY = { uid: 'CO1', id: '10', name: 'Acme Ltd', organisationId: '1', code: 'ACM', legalName: null, taxId: null, timeZone: 'UTC', status: 'ACTIVE' };

const MOCK_CURRENCIES: CurrencyDto[] = [
  { id: '1', uid: 'CUR-001', code: 'USD', name: 'US Dollar',        symbol: '$',   minorUnits: 2, numericCode: '840', active: true, status: 'ACTIVE' },
  { id: '2', uid: 'CUR-002', code: 'TZS', name: 'Tanzanian Shilling', symbol: 'TSh', minorUnits: 0, numericCode: '834', active: true, status: 'ACTIVE' },
  { id: '3', uid: 'CUR-003', code: 'EUR', name: 'Euro',              symbol: '€',   minorUnits: 2, numericCode: '978', active: true, status: 'ACTIVE' },
];

const MOCK_ENABLED: CompanyCurrencyDto[] = [
  { id: '100', uid: 'CC-001', companyId: '10', currencyCode: 'TZS', isDefault: true,  active: true },
  { id: '101', uid: 'CC-002', companyId: '10', currencyCode: 'USD', isDefault: false, active: true },
];

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

function makeFxService(
  currencies = MOCK_CURRENCIES,
  enabled = MOCK_ENABLED,
) {
  return {
    listCurrencies:        vi.fn(() => of(currencies)),
    listEnabledForCompany: vi.fn(() => of(enabled)),
    enableForCompany:      vi.fn(() => of({ ...MOCK_ENABLED[1], currencyCode: 'EUR', id: '102', uid: 'CC-003' } satisfies CompanyCurrencyDto)),
    deactivateForCompany:  vi.fn(() => of({ ...MOCK_ENABLED[1], active: false } satisfies CompanyCurrencyDto)),
    setDefaultForCompany:  vi.fn(() => of({ ...MOCK_ENABLED[1], isDefault: true } satisfies CompanyCurrencyDto)),
  };
}

function makeBed(fxService = makeFxService(), session = makeSession()) {
  TestBed.configureTestingModule({
    imports: [CurrencyEnablementListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      { provide: FxService, useValue: fxService },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(MOCK_ORG)) } },
      { provide: CompanyService,       useValue: { list: vi.fn(() => of([MOCK_COMPANY])) } },
      { provide: AlertService,         useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore,         useValue: session },
    ],
  });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('CurrencyEnablementListComponent', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('renders without crashing', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(CurrencyEnablementListComponent);
    expect(fixture).toBeTruthy();
  });

  it('populates allCurrencies and enabledRows after construction', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    expect(comp.allCurrencies()).toHaveLength(3);
    expect(comp.enabledRows()).toHaveLength(2);
    expect(comp.state()).toBe('idle');
  });

  it('isEnabled returns true only for active enabled rows', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    expect(comp.isEnabled('TZS')).toBe(true);
    expect(comp.isEnabled('USD')).toBe(true);
    expect(comp.isEnabled('EUR')).toBe(false);
  });

  it('isDefault returns true only for the default currency', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    expect(comp.isDefault('TZS')).toBe(true);
    expect(comp.isDefault('USD')).toBe(false);
    expect(comp.isDefault('EUR')).toBe(false);
  });

  it('enabledMap is keyed by currencyCode', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    const map: Map<string, CompanyCurrencyDto> = comp.enabledMap();
    expect(map.has('TZS')).toBe(true);
    expect(map.has('USD')).toBe(true);
    expect(map.has('EUR')).toBe(false);
  });

  it('enable() calls fxService.enableForCompany with correct args', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    makeBed(fxSvc);
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    comp.enable('EUR');
    expect(fxSvc.enableForCompany).toHaveBeenCalledWith('CO1', { currencyCode: 'EUR', setAsDefault: false });
  });

  it('enable() adds the returned row to enabledRows', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    makeBed(fxSvc);
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    const initialLen = comp.enabledRows().length;
    comp.enable('EUR');
    expect(comp.enabledRows().length).toBe(initialLen + 1);
    expect(comp.enabledRows().some((r: CompanyCurrencyDto) => r.currencyCode === 'EUR')).toBe(true);
  });

  it('disable() calls fxService.deactivateForCompany', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    makeBed(fxSvc);
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    comp.disable('USD');
    expect(fxSvc.deactivateForCompany).toHaveBeenCalledWith('CO1', 'USD');
  });

  it('setDefault() calls fxService.setDefaultForCompany and reloads enabled list', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    makeBed(fxSvc);
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    comp.setDefault('USD');
    expect(fxSvc.setDefaultForCompany).toHaveBeenCalledWith('CO1', 'USD');
    // After setDefault the service reloads the enabled list
    expect(fxSvc.listEnabledForCompany).toHaveBeenCalledTimes(2); // once on init, once after setDefault
  });

  it('canManage is false when session lacks CURRENCY.MANAGE', () => {
    vi.useFakeTimers();
    makeBed(makeFxService(), makeSession(false));
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    expect(comp.canManage()).toBe(false);
  });

  it('enable() is a no-op when canManage is false', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    makeBed(fxSvc, makeSession(false));
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    comp.enable('EUR');
    expect(fxSvc.enableForCompany).not.toHaveBeenCalled();
  });

  it('state goes to forbidden on 403 from listCurrencies', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    fxSvc.listCurrencies = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    );
    makeBed(fxSvc);
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    expect(comp.state()).toBe('forbidden');
  });

  it('state goes to error on 500 from listCurrencies', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    fxSvc.listCurrencies = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
    );
    makeBed(fxSvc);
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    expect(comp.state()).toBe('error');
  });

  it('actionError is set from errors[] on enable failure', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    fxSvc.enableForCompany = vi.fn(() =>
      throwError(
        () =>
          new HttpErrorResponse({
            status: 422,
            error: { errors: ['Currency is already enabled'] },
          }),
      ),
    );
    makeBed(fxSvc);
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    comp.enable('EUR');
    expect(comp.actionError()).toBe('Currency is already enabled');
  });

  it('actingCode is cleared after a successful action', () => {
    vi.useFakeTimers();
    const fxSvc = makeFxService();
    makeBed(fxSvc);
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    comp.enable('EUR');
    expect(comp.actingCode()).toBeNull();
  });

  it('isEmpty is true only when state is idle and allCurrencies is empty', () => {
    vi.useFakeTimers();
    makeBed(makeFxService([], []));
    const comp = TestBed.createComponent(CurrencyEnablementListComponent).componentInstance as any;
    expect(comp.isEmpty()).toBe(true);
  });
});
