/**
 * ProductListComponent — live-search debounce + race-safety spec.
 *
 * Mirrors customer-list.component.spec.ts exactly. Uses Vitest fake timers (vi.useFakeTimers).
 *
 * Covers:
 *  1. Typing triggers a load after 300 ms debounce (not before).
 *  2. Typing the same value twice does not re-fire (distinctUntilChanged).
 *  3. applySearch (button/Enter) fires immediately, no debounce wait.
 *  4. clearSearch resets the query and fires immediately.
 *  5. Race-safety: stale response discarded; only latest query result lands.
 *  6. BR-PROD-01: SERVICE type sets stockableDisabled, create sends stockable=false.
 *  7. 403 response sets state to 'forbidden'.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from './product.service';
import type { ProductPage } from './product.service';
import { ProductListComponent } from './product-list.component';
import { HttpErrorResponse } from '@angular/common/http';

// ── Helpers ────────────────────────────────────────────────────────────────────

const emptyPage = (): ProductPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => false),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(listImpl?: () => any, canManage = false) {
  const sessionStore = makeSessionStore();
  if (canManage) {
    sessionStore.hasPermission = vi.fn(() => true);
  }
  TestBed.configureTestingModule({
    imports: [ProductListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(listImpl ?? (() => of(emptyPage()))),
          create: vi.fn(() => of({ uid: 'P1', name: 'Widget', code: 'W001', type: 'GOODS', sellable: true, stockable: true, status: 'ACTIVE', baseUnit: 'PCS' })),
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

// ── BR-PROD-01 guard ──────────────────────────────────────────────────────────

describe('ProductListComponent — BR-PROD-01 SERVICE stockable guard', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    makeBed(undefined, true);
  });

  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('stockableDisabled is false when type is GOODS', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newType.set('GOODS');
    expect(comp.stockableDisabled()).toBe(false);
  });

  it('onNewTypeChange SERVICE sets stockableDisabled=true and newStockable=false', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.newStockable.set(true);
    comp.onNewTypeChange('SERVICE');

    expect(comp.stockableDisabled()).toBe(true);
    expect(comp.newStockable()).toBe(false);
  });

  it('create sends stockable=false when type=SERVICE regardless of checkbox (BR-PROD-01)', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    const productSvc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    comp.newName.set('Consulting');
    comp.newType.set('SERVICE');
    comp.newStockable.set(true); // would be overridden
    comp.newBaseUnit.set('HR');

    comp.create();

    expect(productSvc.create).toHaveBeenCalledOnce();
    const req = productSvc.create.mock.calls[0][0];
    expect(req.stockable).toBe(false);
    expect(req.type).toBe('SERVICE');
  });

  it('create validation: requires name', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    const productSvc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    comp.newName.set('');
    comp.newBaseUnit.set('PCS');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(productSvc.create).not.toHaveBeenCalled();
  });

  it('create validation: requires base unit', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    const productSvc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    comp.newName.set('Widget');
    comp.newBaseUnit.set('');
    comp.create();

    expect(comp.formError()).toBeTruthy();
    expect(productSvc.create).not.toHaveBeenCalled();
  });
});

// ── Live search ───────────────────────────────────────────────────────────────

describe('ProductListComponent — live search', () => {
  let listSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    listSpy = vi.fn(() => of(emptyPage()));

    TestBed.configureTestingModule({
      imports: [ProductListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ProductService,
          useValue: {
            list: listSpy,
            create: vi.fn(() => of({})),
          },
        },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: SessionStore, useValue: makeSessionStore() },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  // ── 1. Startup load ──────────────────────────────────────────────────────────

  it('fires exactly one load on startup (immediate trigger from company resolve)', async () => {
    TestBed.createComponent(ProductListComponent);
    await vi.runAllTimersAsync();
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', undefined, 0, 20);
  });

  // ── 2. Debounce: does not fire before 300 ms ─────────────────────────────────

  it('does NOT fire before 300 ms when the user types', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('wi');
    await vi.advanceTimersByTimeAsync(200);
    expect(listSpy).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(100);
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', 'wi', 0, 20);
  });

  // ── 3. Typed query resets to page 0 ─────────────────────────────────────────

  it('resets to page 0 when a new query is typed', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('xyz');
    await vi.advanceTimersByTimeAsync(300);
    expect(listSpy).toHaveBeenCalledWith('10', 'xyz', 0, 20);
  });

  // ── 4. distinctUntilChanged ───────────────────────────────────────────────────

  it('does not re-fire when the same query is typed twice (distinctUntilChanged)', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    expect(listSpy).toHaveBeenCalledTimes(1);
    listSpy.mockClear();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    expect(listSpy).not.toHaveBeenCalled();
  });

  // ── 5. applySearch is immediate ───────────────────────────────────────────────

  it('applySearch fires immediately via immediateTrigger$ without waiting for debounce', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    listSpy.mockClear();

    comp.searchQ.set('fast');
    comp.applySearch();
    await vi.advanceTimersByTimeAsync(0);

    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', 'fast', 0, 20);
  });

  // ── 6. clearSearch ───────────────────────────────────────────────────────────

  it('clearSearch resets the query and fires an immediate load', async () => {
    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.searchQ.set('abc');
    await vi.advanceTimersByTimeAsync(300);
    listSpy.mockClear();

    comp.clearSearch();
    await vi.advanceTimersByTimeAsync(0);

    expect(comp.searchQ()).toBe('');
    expect(listSpy).toHaveBeenCalledTimes(1);
    expect(listSpy).toHaveBeenCalledWith('10', undefined, 0, 20);
  });

  // ── 7. Race-safety via switchMap ─────────────────────────────────────────────

  it('race-safety: only the latest (fresh) response lands; stale response is discarded', async () => {
    const stale$ = new Subject<ProductPage>();
    const fresh$ = new Subject<ProductPage>();
    let callCount = 0;

    const raceSpy = vi.fn(() => {
      callCount++;
      if (callCount === 2) return stale$.asObservable();
      if (callCount === 3) return fresh$.asObservable();
      return of(emptyPage());
    });

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ProductListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ProductService,
          useValue: { list: raceSpy, create: vi.fn(() => of({})) },
        },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: SessionStore, useValue: makeSessionStore() },
      ],
    });

    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    expect(raceSpy).toHaveBeenCalledTimes(1);

    comp.searchQ.set('slow');
    comp.applySearch();
    await vi.advanceTimersByTimeAsync(0);
    expect(raceSpy).toHaveBeenCalledTimes(2);

    comp.searchQ.set('fast');
    comp.applySearch();
    await vi.advanceTimersByTimeAsync(0);
    expect(raceSpy).toHaveBeenCalledTimes(3);

    stale$.next({
      rows: [{ uid: 'STALE' } as any],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });
    expect(comp.rows()).toHaveLength(0);

    fresh$.next({
      rows: [{ uid: 'FRESH' } as any],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });
    expect(comp.rows()).toHaveLength(1);
    expect(comp.rows()[0].uid).toBe('FRESH');
  });

  // ── 8. 403 → forbidden state ─────────────────────────────────────────────────

  it('sets state to forbidden when list returns 403', async () => {
    const forbiddenSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    );

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ProductListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: ProductService,
          useValue: { list: forbiddenSpy, create: vi.fn(() => of({})) },
        },
        { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
        { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: SessionStore, useValue: makeSessionStore() },
      ],
    });

    const fixture = TestBed.createComponent(ProductListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });
});
