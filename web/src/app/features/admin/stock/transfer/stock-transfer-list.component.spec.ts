/**
 * StockTransferListComponent specs.
 *
 * Covers:
 *  1. Fires one load on startup; rows and state=idle.
 *  2. isEmpty is true when no rows returned.
 *  3. Status pill: each StockTransferStatus renders the correct status-tag--* class.
 *  4. 403 response sets state = 'forbidden'.
 *  5. goToPage triggers a reload with the given page.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../../core/auth/session.store';
import { StockLocationService } from '../locations/stock-location.service';
import { StockTransferService } from './stock-transfer.service';
import type { StockTransferPage } from './stock-transfer.service';
import { StockTransferListComponent } from './stock-transfer-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const STUB_TRANSFER = {
  uid: 'TRF1', id: '1', companyId: '10',
  transferNumber: 'TRF-0001',
  status: 'DRAFT' as const,
  transferMode: 'IN_TRANSIT',
  sourceBranchId: '1', sourceLocationId: '2',
  destBranchId: '3', destLocationId: '4',
  transferDate: '2025-01-15',
  dispatchedAt: null, receivedAt: null,
  notes: null, lines: [],
};

const emptyPage = (): StockTransferPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const onePage = (): StockTransferPage => ({
  rows: [STUB_TRANSFER],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(opts: { listImpl?: () => any; canView?: boolean } = {}) {
  const listSpy = vi.fn(opts.listImpl ?? (() => of(onePage())));

  TestBed.configureTestingModule({
    imports: [StockTransferListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: StockTransferService,
        useValue: { list: listSpy },
      },
      {
        provide: StockLocationService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false } })),
        },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => opts.canView ?? true),
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

// ── Init ───────────────────────────────────────────────────────────────────────

describe('StockTransferListComponent — init', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('fires one load on startup and sets state=idle', async () => {
    const { listSpy } = makeBed();
    const comp = TestBed.createComponent(StockTransferListComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalledOnce();
    expect(listSpy).toHaveBeenCalledWith(0, 20);
    expect(comp.state()).toBe('idle');
    expect(comp.rows()).toHaveLength(1);
  });

  it('isEmpty is true when no rows returned', async () => {
    makeBed({ listImpl: () => of(emptyPage()) });
    const comp = TestBed.createComponent(StockTransferListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.isEmpty()).toBe(true);
  });
});

// ── Status pill rendering ─────────────────────────────────────────────────────

describe('StockTransferListComponent — status pill', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it.each([
    ['DRAFT',      'status-tag--warn'],
    ['DISPATCHED', 'status-tag--info'],
    ['RECEIVED',   'status-tag--info'],
    ['COMPLETED',  'status-tag--ok'],
    ['CANCELLED',  'status-tag--danger'],
  ] as const)('status %s renders %s on the pill element', async (status, expectedClass) => {
    const page = (): StockTransferPage => ({
      rows: [{ ...STUB_TRANSFER, status }],
      meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
    });
    makeBed({ listImpl: () => of(page()) });
    const fixture = TestBed.createComponent(StockTransferListComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const pill: HTMLElement | null = fixture.nativeElement.querySelector('.status-tag');
    expect(pill).not.toBeNull();
    expect(pill!.classList).toContain(expectedClass);
  });
});

// ── 403 forbidden ─────────────────────────────────────────────────────────────

describe('StockTransferListComponent — 403 forbidden', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sets state=forbidden on 403 response', async () => {
    makeBed({
      listImpl: () =>
        throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });
    const comp = TestBed.createComponent(StockTransferListComponent).componentInstance;
    await vi.runAllTimersAsync();
    expect(comp.state()).toBe('forbidden');
  });
});

// ── goToPage ──────────────────────────────────────────────────────────────────

describe('StockTransferListComponent — goToPage', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls list() with the given page on goToPage', async () => {
    const { listSpy } = makeBed();
    const comp = TestBed.createComponent(StockTransferListComponent).componentInstance;
    await vi.runAllTimersAsync();

    listSpy.mockReturnValue(of(emptyPage()));
    comp.goToPage(2);
    await vi.runAllTimersAsync();

    expect(listSpy).toHaveBeenCalledWith(2, 20);
  });
});
