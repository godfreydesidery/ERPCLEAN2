/**
 * StockCountListComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads list on construction.
 *  2. Emits isEmpty=true when no rows returned.
 *  3. Sets state=forbidden on 403 response.
 *  4. goToPage triggers re-load with correct page.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../../core/auth/session.store';
import { StockCountDto } from './stock-count.model';
import { StockCountService } from './stock-count.service';
import { StockCountListComponent } from './stock-count-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const STUB_COUNT: StockCountDto = {
  uid: 'CNT1', id: '1',
  companyId: '10', branchId: '20',
  countNumber: 'SC-0001',
  status: 'COUNTING',
  countType: 'FULL',
  locationId: '5',
  countDate: '2025-06-01',
  frozenAt: null, postedAt: null, cancelledAt: null,
  varianceGlEntryUid: null, notes: null,
  lines: [],
};

const emptyPage = () => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const onePage = () => ({
  rows: [STUB_COUNT],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(overrides: { listSpy?: ReturnType<typeof vi.fn> } = {}) {
  const listSpy = overrides.listSpy ?? vi.fn(() => of(onePage()));

  TestBed.configureTestingModule({
    imports: [StockCountListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: StockCountService,
        useValue: {
          list: listSpy,
          getByUid: vi.fn(() => of(STUB_COUNT)),
          create: vi.fn(() => of(STUB_COUNT)),
          enterCount: vi.fn(() => of(STUB_COUNT)),
          post: vi.fn(() => of(STUB_COUNT)),
          cancel: vi.fn(() => of(undefined)),
        },
      },
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

  return { listSpy };
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('StockCountListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Loads list on construction ─────────────────────────────────────────

  it('loads stock counts on construction', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(StockCountListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalledWith(0, 20);
    expect(comp.rows()).toHaveLength(1);
    expect(comp.state()).toBe('idle');
  });

  // ── 2. isEmpty when no rows ───────────────────────────────────────────────

  it('isEmpty is true when no rows returned', async () => {
    makeBed({ listSpy: vi.fn(() => of(emptyPage())) });
    const fixture = TestBed.createComponent(StockCountListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.isEmpty()).toBe(true);
    expect(comp.rows()).toHaveLength(0);
  });

  // ── 3. 403 → forbidden state ──────────────────────────────────────────────

  it('sets state=forbidden on 403 response', async () => {
    makeBed({
      listSpy: vi.fn(() =>
        throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
      ),
    });
    const fixture = TestBed.createComponent(StockCountListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });

  // ── 4. goToPage triggers reload ───────────────────────────────────────────

  it('goToPage calls list with the correct page number', async () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(StockCountListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    listSpy.mockClear();
    comp.goToPage(2);
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalledWith(2, 20);
  });
});
