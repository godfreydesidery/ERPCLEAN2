/**
 * StockBatchListComponent — key behaviour specs.
 *
 * Covers:
 *  1. Does NOT auto-load without location+product selected (location mode).
 *  2. Loads when location and product are both set.
 *  3. 403 forbidden sets state = 'forbidden'.
 *  4. Switches to expiring mode and calls listExpiring.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { StockLocationService } from '../locations/stock-location.service';
import { StockBatchService } from './stock-batch.service';
import { StockBatchListComponent } from './stock-batch-list.component';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_LOCATION = {
  uid: 'LOC1', id: '100', companyId: '10', branchId: '5',
  code: 'WH-01', name: 'Main Warehouse', locationType: 'WAREHOUSE' as const,
  isDefault: true, status: 'ACTIVE' as const,
};
const STUB_PRODUCT = {
  uid: 'PROD1', id: '55', code: 'P001', name: 'Product A',
  status: 'ACTIVE', companyId: '10',
};
const STUB_BATCH = {
  uid: 'BATCH1', id: '200', companyId: '10', branchId: '5',
  locationId: '100', productId: '55',
  lotNumber: 'LOT-001', manufactureDate: '2024-01-01', expiryDate: '2026-01-01',
  qtyOnHand: '50', expired: false,
};

const emptyPage = () => ({
  rows: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const batchPage = () => ({
  rows: [STUB_BATCH],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(overrides: {
  listAtLocationSpy?: ReturnType<typeof vi.fn>;
  listExpiringSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const listAtLocationSpy = overrides.listAtLocationSpy ?? vi.fn(() => of(batchPage()));
  const listExpiringSpy = overrides.listExpiringSpy ?? vi.fn(() => of(batchPage()));

  TestBed.configureTestingModule({
    imports: [StockBatchListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: StockBatchService,
        useValue: {
          listAtLocation: listAtLocationSpy,
          listExpiring: listExpiringSpy,
          getByUid: vi.fn(() => of(STUB_BATCH)),
        },
      },
      {
        provide: StockLocationService,
        useValue: {
          list: vi.fn(() => of({ rows: [STUB_LOCATION], meta: { page: 0, size: 200, totalElements: 1, totalPages: 1, hasNext: false } })),
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of(STUB_ORG)) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([STUB_COMPANY])) },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [STUB_PRODUCT], meta: { page: 0, size: 200, totalElements: 1, totalPages: 1, hasNext: false } })),
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

  return { listAtLocationSpy, listExpiringSpy };
}

describe('StockBatchListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Does not load without filters ──────────────────────────────────────

  it('does not call listAtLocation when location or product is missing', async () => {
    const { listAtLocationSpy } = makeBed();
    TestBed.createComponent(StockBatchListComponent);
    await vi.runAllTimersAsync();

    expect(listAtLocationSpy).not.toHaveBeenCalled();
  });

  // ── 2. Loads when filters are set ─────────────────────────────────────────

  it('loads batches when both location and product are selected', async () => {
    const { listAtLocationSpy } = makeBed();
    const fixture = TestBed.createComponent(StockBatchListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    // Simulate picker selections
    comp.onLocationChange(STUB_LOCATION.uid);
    comp.onProductChange(STUB_PRODUCT.uid);
    await vi.runAllTimersAsync();

    expect(listAtLocationSpy).toHaveBeenCalled();
    expect(comp.rows()).toHaveLength(1);
    expect(comp.state()).toBe('idle');
  });

  // ── 3. 403 forbidden ──────────────────────────────────────────────────────

  it('sets state=forbidden on 403', async () => {
    const listAtLocationSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
    );
    makeBed({ listAtLocationSpy });
    const fixture = TestBed.createComponent(StockBatchListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.onLocationChange(STUB_LOCATION.uid);
    comp.onProductChange(STUB_PRODUCT.uid);
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });

  // ── 4. Expiring mode ──────────────────────────────────────────────────────

  it('calls listExpiring when switching to expiring mode', async () => {
    const { listExpiringSpy } = makeBed();
    const fixture = TestBed.createComponent(StockBatchListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.switchToExpiring();
    await vi.runAllTimersAsync();

    expect(listExpiringSpy).toHaveBeenCalled();
  });
});
