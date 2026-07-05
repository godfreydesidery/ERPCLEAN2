/**
 * MassPriceChangeComponent spec.
 * Mirrors pricing-rules.component.spec.ts pattern (Vitest + vi.useFakeTimers).
 *
 * Covers:
 *  1. Startup: loads companies + active price lists.
 *  2. preview() validates required fields before calling the service.
 *  3. preview() calls the service with dryRun:true and populates `result`.
 *  4. canApply is false before any preview, true right after one.
 *  5. Editing a field after preview clears `result` (re-locks Apply).
 *  6. apply() is blocked without a prior preview (canApply=false).
 *  7. apply() re-confirms (window.confirm) and calls the service with dryRun:false.
 *  8. apply() does nothing when the user cancels the confirm dialog.
 *  9. 403 on preview() sets a friendly formError (no crash).
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
import { ProductService } from '../products/product.service';
import { MassPriceChangeService } from './mass-price-change.service';
import { MassPriceChangeComponent } from './mass-price-change.component';
import type { MassPriceChangeResult } from './mass-price-change.model';
import type { PriceListDto } from '../models/product.model';

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makePriceList(): PriceListDto {
  return {
    id: '30', uid: 'PL1', companyId: '10', code: 'RETAIL', name: 'Retail Price',
    priceIncludesVat: true, isDefault: true, status: 'ACTIVE', version: null,
    createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  };
}

function makeResult(overrides: Partial<MassPriceChangeResult> = {}): MassPriceChangeResult {
  return {
    priceListUid: 'PL1',
    priceListCode: 'RETAIL',
    priceListName: 'Retail Price',
    totalRows: 10,
    affected: 8,
    dryRun: true,
    samples: [
      { productCode: 'SKU-1', oldAmount: '100.00', newAmount: '110.00', currency: 'TZS' },
    ],
    ...overrides,
  };
}

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(opts: { applyImpl?: () => any } = {}) {
  const applyImpl = opts.applyImpl ?? (() => of(makeResult()));

  TestBed.configureTestingModule({
    imports: [MassPriceChangeComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: MassPriceChangeService,
        useValue: { apply: vi.fn(applyImpl) },
      },
      {
        provide: ProductService,
        useValue: { listPriceLists: vi.fn(() => of([makePriceList()])) },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSessionStore() },
    ],
  });
}

afterEach(() => TestBed.resetTestingModule());

// ── Startup ───────────────────────────────────────────────────────────────────

describe('MassPriceChangeComponent — startup', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('loads companies and active price lists', async () => {
    makeBed();
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.companies().length).toBe(1);
    expect(comp.selectedCompanyId()).toBe('10');
    expect(comp.priceLists().length).toBe(1);
    expect(comp.priceListOptions()[0].uid).toBe('PL1');
  });
});

// ── Preview validation ────────────────────────────────────────────────────────

describe('MassPriceChangeComponent — preview validation', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('requires a price list before calling the service', async () => {
    makeBed();
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    const svc = TestBed.inject(MassPriceChangeService) as any;
    await vi.runAllTimersAsync();

    comp.onValueChange('10');
    comp.preview();

    expect(comp.formError()).toBeTruthy();
    expect(svc.apply).not.toHaveBeenCalled();
  });

  it('requires a numeric value before calling the service', async () => {
    makeBed();
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    const svc = TestBed.inject(MassPriceChangeService) as any;
    await vi.runAllTimersAsync();

    comp.onPriceListChange('PL1');
    comp.onValueChange('not-a-number');
    comp.preview();

    expect(comp.formError()).toBeTruthy();
    expect(svc.apply).not.toHaveBeenCalled();
  });
});

// ── Preview ───────────────────────────────────────────────────────────────────

describe('MassPriceChangeComponent — preview', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('calls the service with dryRun:true and populates result', async () => {
    makeBed();
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    const svc = TestBed.inject(MassPriceChangeService) as any;
    await vi.runAllTimersAsync();

    comp.onPriceListChange('PL1');
    comp.onTypeChange('PERCENT');
    comp.onValueChange('10');
    comp.preview();
    await vi.runAllTimersAsync();

    expect(svc.apply).toHaveBeenCalledWith(
      expect.objectContaining({ priceListUid: 'PL1', type: 'PERCENT', value: 10, dryRun: true }),
    );
    expect(comp.result()?.affected).toBe(8);
  });

  it('canApply is false before any preview and true right after one', async () => {
    makeBed();
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.canApply()).toBe(false);

    comp.onPriceListChange('PL1');
    comp.onValueChange('10');
    comp.preview();
    await vi.runAllTimersAsync();

    expect(comp.canApply()).toBe(true);
  });

  it('editing a field after preview clears result and re-locks Apply', async () => {
    makeBed();
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.onPriceListChange('PL1');
    comp.onValueChange('10');
    comp.preview();
    await vi.runAllTimersAsync();
    expect(comp.canApply()).toBe(true);

    comp.onValueChange('15'); // edited after preview
    expect(comp.result()).toBeNull();
    expect(comp.canApply()).toBe(false);
  });

  it('sets a friendly formError on a 403', async () => {
    makeBed({
      applyImpl: () => throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.onPriceListChange('PL1');
    comp.onValueChange('10');
    comp.preview();
    await vi.runAllTimersAsync();

    expect(comp.formError()).toBeTruthy();
    expect(comp.result()).toBeNull();
  });
});

// ── Apply ─────────────────────────────────────────────────────────────────────

describe('MassPriceChangeComponent — apply', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it('does nothing without a prior preview', async () => {
    makeBed();
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    const svc = TestBed.inject(MassPriceChangeService) as any;
    await vi.runAllTimersAsync();

    comp.apply();

    expect(svc.apply).not.toHaveBeenCalled();
  });

  it('re-confirms and calls the service with dryRun:false when confirmed', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    makeBed();
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    const svc = TestBed.inject(MassPriceChangeService) as any;
    const alerts = TestBed.inject(AlertService) as any;
    await vi.runAllTimersAsync();

    comp.onPriceListChange('PL1');
    comp.onValueChange('10');
    comp.preview();
    await vi.runAllTimersAsync();

    comp.apply();
    await vi.runAllTimersAsync();

    expect(window.confirm).toHaveBeenCalledOnce();
    expect(svc.apply).toHaveBeenLastCalledWith(expect.objectContaining({ dryRun: false }));
    expect(alerts.success).toHaveBeenCalledOnce();
  });

  it('does nothing when the user cancels the confirm dialog', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    makeBed();
    const comp = TestBed.createComponent(MassPriceChangeComponent).componentInstance;
    const svc = TestBed.inject(MassPriceChangeService) as any;
    await vi.runAllTimersAsync();

    comp.onPriceListChange('PL1');
    comp.onValueChange('10');
    comp.preview();
    await vi.runAllTimersAsync();
    svc.apply.mockClear();

    comp.apply();

    expect(window.confirm).toHaveBeenCalledOnce();
    expect(svc.apply).not.toHaveBeenCalled();
  });
});
