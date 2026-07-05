/**
 * Accessibility gate — ProductMasterComponent.
 *
 * Covers: General tab (create mode), all five tabs navigated, Branch-availability
 * tab with branch rows rendered. Each scenario is scanned with axe-core against
 * WCAG 2.1 AA (color-contrast + scrollable-region-focusable disabled under jsdom).
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { BranchService } from '../branch/branch.service';
import { SupplierService } from '../parties/supplier.service';
import { StockService } from '../stock/stock.service';
import { ProductService } from './product.service';
import { ProductMasterComponent } from './product-master.component';
import { CurrencyService } from '../../../shared/currency-select/currency.service';
import { assertA11y } from '../../../../testing/a11y.helper';
import type { PriceListDto, UnitOfMeasureDto } from '../models/product.model';
import type { Branch } from '../models/branch.model';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const CO  = { uid: 'CO1', id: '10', name: 'Main Co' };

function makePriceList(uid: string, code: string): PriceListDto {
  return {
    id: uid, uid, companyId: '10', code, name: code, priceIncludesVat: true, isDefault: false,
    status: 'ACTIVE', version: null, createdAt: null, createdBy: null,
    updatedAt: null, updatedBy: null,
  };
}

function makeUnit(uid: string, code: string): UnitOfMeasureDto {
  return {
    id: uid, uid, companyId: '10', code, name: code, status: 'ACTIVE',
    version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  };
}

function makeBranch(uid: string, code: string): Branch {
  return {
    id: uid, uid, companyId: '10', companyUid: 'CO1', code, name: code,
    timeZone: 'Africa/Dar_es_Salaam', isDefault: false, status: 'ACTIVE',
  };
}

const BRANCHES = [makeBranch('B1', 'HQ'), makeBranch('B2', 'BRANCH-2')];
const UNITS    = [makeUnit('U1', 'PCS')];
const LISTS    = [makePriceList('PL1', 'DEFAULT')];

function makeBed() {
  TestBed.configureTestingModule({
    imports: [ProductMasterComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ProductService,
        useValue: {
          create:         vi.fn(() => of({})),
          update:         vi.fn(() => of({})),
          getByUid:       vi.fn(() => of({})),
          listUnits:      vi.fn(() => of({ rows: UNITS, meta: { page: 0, size: 200, totalElements: 1, totalPages: 1, hasNext: false } })),
          listPriceLists: vi.fn(() => of(LISTS)),
          listBarcodes:   vi.fn(() => of([])),
          listBulkPacks:  vi.fn(() => of([])),
          listPrices:     vi.fn(() => of([])),
          listBranches:   vi.fn(() => of([])),
          setPrice:       vi.fn(() => of({})),
          addBarcode:     vi.fn(() => of({})),
          addBulkPack:    vi.fn(() => of({})),
          assignBranch:   vi.fn(() => of({})),
        },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of(ORG)) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([CO])) },
      },
      {
        provide: BranchService,
        useValue: { list: vi.fn(() => of(BRANCHES)) },
      },
      {
        provide: SupplierService,
        useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) },
      },
      {
        provide: StockService,
        useValue: { openingBalance: vi.fn(() => of({})) },
      },
      {
        // CurrencySelectComponent (imported by ProductMasterComponent) calls this.
        provide: CurrencyService,
        useValue: {
          getEnabledOptions: vi.fn(() => of({ options: [{ code: 'TZS', name: 'Tanzanian Shilling', symbol: 'TSh' }], resolvedDefault: 'TZS' })),
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
          activeBranchUid: signal<string | null>('B1'),
        },
      },
    ],
  });
}

describe('ProductMasterComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('General tab (create mode) has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(ProductMasterComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('Pricing tab has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    comp.setTab('pricing');
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('Supplier & UoM tab has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    comp.setTab('supplier');
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('Stock & Barcodes tab has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    comp.setTab('stock');
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('Branch-availability tab with branch rows rendered has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(ProductMasterComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    // Branches are populated by loadBranches() which ran during ngOnInit.
    expect(comp.branchRows().length).toBe(2);
    comp.setTab('branches');
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
