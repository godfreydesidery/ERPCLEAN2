/**
 * Accessibility gate — StockValueReportComponent.
 *
 * Covers the filter bar (both uid-pickers must be labelled through their inner <select>), the
 * company header block, the table WITH its grand-totals <tfoot>, and the "excluded from totals"
 * warning banner — the one piece of markup on this screen axe has not already seen elsewhere.
 *
 * The CI-starvation guard lives inside assertA11y() (it renders but skips the scan when
 * process.env.CI is set), so specs only supply the wider per-test timeout.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierService } from '../parties/supplier.service';
import { ProductStockReportDto } from './models/product-stock-report.model';
import { ProductStockReportService } from './product-stock-report.service';
import { StockValueReportComponent } from './stock-value-report.component';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_BRANCHES = [
  { id: '1', uid: 'BR1', companyId: '10', companyUid: 'CO1', code: 'HQ', name: 'Head Office', timeZone: 'Africa/Dar_es_Salaam', isDefault: true, status: 'ACTIVE' },
];
/** A non-root caller fills the picker from their OWN assignments, not the company branch list. */
const MY_BRANCHES = [
  { id: '11', uid: 'UB1', userUid: 'U1', branchUid: 'BR1', branchCode: 'HQ', branchName: 'Head Office', companyUid: 'CO1', isDefault: true, assignedAt: '2026-01-01T00:00:00Z' },
];
const supplierPage = () => ({
  rows: [
    {
      id: '5', uid: 'SUP1', companyId: '10', code: 'S-001', partyType: 'COMPANY',
      displayName: 'Kilimanjaro Supplies', legalName: null, tin: null, vatRegistered: false,
      vrn: null, businessRegNo: null, mobileMoneyNo: null, phone: null, email: null,
      physicalAddress: null, postalAddress: null, region: null, district: null,
      supplierKind: 'GOODS', status: 'ACTIVE', version: null,
      createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
    },
  ],
  meta: { page: 0, size: 200, totalElements: 1, totalPages: 1, hasNext: false },
});

const MOCK_REPORT: ProductStockReportDto = {
  company: {
    name: 'Tembo Group Ltd', legalName: 'Tembo Group Company Limited',
    addressLine1: 'Plot 12', addressLine2: null, city: 'Dar es Salaam', region: null,
    country: 'Tanzania', contactPhone: '+255700000000', contactEmail: 'info@tembo.co.tz',
    taxId: 'TIN-999', vrn: 'VRN-888',
  },
  branchName: 'Head Office',
  supplierName: 'Kilimanjaro Supplies',
  currency: 'TZS',
  priceListName: 'Retail 2026',
  priceIncludesVat: false,
  rows: [
    {
      productCode: 'P001', productName: 'Widget', supplierName: 'Kilimanjaro Supplies',
      quantityOnHand: 10, buyingPrice: 1000, costValue: 10000, sellingPrice: 1500, saleValue: 15000,
      discontinued: false,
    },
    {
      productCode: 'P002', productName: 'Imported gizmo', supplierName: 'Kilimanjaro Supplies',
      quantityOnHand: 4, buyingPrice: null, costValue: null, sellingPrice: null, saleValue: null,
      discontinued: false,
    },
  ],
  totals: {
    totalQuantity: 14,
    totalCostValue: 10000,
    totalSaleValue: 15000,
    potentialMargin: 5000,
    unvaluedItems: 1,
    unpricedItems: 1,
    discontinuedItems: 0,
  },
  generatedAt: '2026-08-11T12:00:00Z',
};

/** The widened row set: off-catalogue stock, marked in the table and counted in a note. */
const REPORT_WITH_DISCONTINUED: ProductStockReportDto = {
  ...MOCK_REPORT,
  rows: [
    ...MOCK_REPORT.rows,
    {
      productCode: 'P003', productName: 'Old model kettle', supplierName: 'Kilimanjaro Supplies',
      quantityOnHand: 2, buyingPrice: 500, costValue: 1000, sellingPrice: 800, saleValue: 1600,
      discontinued: true,
    },
  ],
  totals: { ...MOCK_REPORT.totals!, discontinuedItems: 1 },
};

function makeBed(reportSpy: () => unknown = vi.fn(() => of(MOCK_REPORT))) {
  TestBed.configureTestingModule({
    imports: [StockValueReportComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ProductStockReportService,
        useValue: { stockValue: reportSpy, exportStockValue: vi.fn(() => of(new Blob())) },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of(STUB_BRANCHES)) } },
      { provide: AuthService, useValue: { myBranches: vi.fn(() => of(MY_BRANCHES)) } },
      { provide: SupplierService, useValue: { list: vi.fn(() => of(supplierPage())) } },
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
}

describe('StockValueReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with a populated report, totals foot and exclusion banner', async () => {
    makeBed();
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when nothing is excluded from the totals', async () => {
    const clean = {
      ...MOCK_REPORT,
      totals: { ...MOCK_REPORT.totals!, unvaluedItems: 0, unpricedItems: 0 },
    };
    makeBed(vi.fn(() => of(clean)));
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when the company has no default price list', async () => {
    makeBed(vi.fn(() => of({ ...MOCK_REPORT, priceListName: null })));
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations with the discontinued mark and its note', async () => {
    makeBed(vi.fn(() => of(REPORT_WITH_DISCONTINUED)));
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
