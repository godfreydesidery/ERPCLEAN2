/**
 * StockSerialListComponent — key behaviour specs.
 *
 * Covers:
 *  1. Does NOT auto-load without location+product (location mode).
 *  2. Loads when both location and product are set.
 *  3. 403 forbidden sets state = 'forbidden'.
 *  4. Product history mode calls listByProduct.
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
import { AlertService } from '../../../../core/feedback/alert.service';
import { StockLocationService } from '../locations/stock-location.service';
import { StockSerialService } from './stock-serial.service';
import { StockSerialListComponent } from './stock-serial-list.component';

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
const STUB_SERIAL = {
  uid: 'SER1', id: '300', companyId: '10', branchId: '5',
  locationId: '100', productId: '55',
  serialNumber: 'SN-00001', serialStatus: 'IN_STOCK' as const,
  receivedDocumentUid: 'GR-001', issuedDocumentUid: null, createdAt: '2025-01-01T00:00:00Z',
};

const emptyPage = () => ({
  rows: [], meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const serialPage = () => ({
  rows: [STUB_SERIAL],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(overrides: {
  listAtLocationSpy?: ReturnType<typeof vi.fn>;
  listByProductSpy?: ReturnType<typeof vi.fn>;
} = {}) {
  const listAtLocationSpy = overrides.listAtLocationSpy ?? vi.fn(() => of(serialPage()));
  const listByProductSpy = overrides.listByProductSpy ?? vi.fn(() => of(serialPage()));

  TestBed.configureTestingModule({
    imports: [StockSerialListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: StockSerialService,
        useValue: {
          listAtLocation: listAtLocationSpy,
          listByProduct: listByProductSpy,
          lookup: vi.fn(() => of(STUB_SERIAL)),
          getByUid: vi.fn(() => of(STUB_SERIAL)),
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
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
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

  return { listAtLocationSpy, listByProductSpy };
}

describe('StockSerialListComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. No auto-load without filters ───────────────────────────────────────

  it('does not call listAtLocation when filters not set', async () => {
    const { listAtLocationSpy } = makeBed();
    TestBed.createComponent(StockSerialListComponent);
    await vi.runAllTimersAsync();

    expect(listAtLocationSpy).not.toHaveBeenCalled();
  });

  // ── 2. Loads when both location and product selected ──────────────────────

  it('loads serials when location and product are set', async () => {
    const { listAtLocationSpy } = makeBed();
    const fixture = TestBed.createComponent(StockSerialListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectedLocationUid.set(STUB_LOCATION.uid);
    comp.selectedProductUid.set(STUB_PRODUCT.uid);
    comp.onFilterChange();
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
    const fixture = TestBed.createComponent(StockSerialListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectedLocationUid.set(STUB_LOCATION.uid);
    comp.selectedProductUid.set(STUB_PRODUCT.uid);
    comp.onFilterChange();
    await vi.runAllTimersAsync();

    expect(comp.state()).toBe('forbidden');
  });

  // ── 4. Product history mode ───────────────────────────────────────────────

  it('calls listByProduct when in product mode and product is selected', async () => {
    const { listByProductSpy } = makeBed();
    const fixture = TestBed.createComponent(StockSerialListComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.switchToProduct();
    comp.selectedProductUid.set(STUB_PRODUCT.uid);
    comp.onFilterChange();
    await vi.runAllTimersAsync();

    expect(listByProductSpy).toHaveBeenCalled();
  });
});
