/**
 * Accessibility gate — ProductListReportComponent.
 *
 * Covers the filter bar (both uid-pickers must be labelled through their inner <select>), the
 * company header block, the populated table, and the no-default-price-list warning banner.
 *
 * The CI-starvation guard lives inside assertA11y() (it renders but skips the scan when
 * process.env.CI is set), so specs only supply the wider per-test timeout.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierService } from '../parties/supplier.service';
import { ProductStockReportDto } from './models/product-stock-report.model';
import { ProductListReportComponent } from './product-list-report.component';
import { ProductStockReportService } from './product-stock-report.service';

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
  supplierName: null,
  currency: 'TZS',
  priceListName: 'Retail 2026',
  priceIncludesVat: true,
  rows: [
    {
      productCode: 'P001', productName: 'Widget', supplierName: 'Kilimanjaro Supplies',
      quantityOnHand: 10, buyingPrice: 1000, costValue: 10000, sellingPrice: 1500, saleValue: 15000,
      discontinued: false,
    },
    {
      productCode: 'P002', productName: 'Imported gizmo', supplierName: null,
      quantityOnHand: 4, buyingPrice: null, costValue: null, sellingPrice: null, saleValue: null,
      discontinued: false,
    },
  ],
  totals: null,
  generatedAt: '2026-08-11T12:00:00Z',
};

function makeBed(reportSpy: () => unknown = vi.fn(() => of(MOCK_REPORT))) {
  TestBed.configureTestingModule({
    imports: [ProductListReportComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ProductStockReportService,
        useValue: { productList: reportSpy, exportProductList: vi.fn(() => of(new Blob())) },
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

describe('ProductListReportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations with a populated report', async () => {
    makeBed();
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when the company has no default price list', async () => {
    makeBed(vi.fn(() => of({ ...MOCK_REPORT, priceListName: null })));
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when no product matches the filters', async () => {
    makeBed(vi.fn(() => of({ ...MOCK_REPORT, rows: [] })));
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when the branch filter is refused beside the picker', async () => {
    makeBed(
      vi
        .fn()
        .mockReturnValueOnce(of(MOCK_REPORT))
        .mockReturnValueOnce(
          throwError(
            () =>
              new HttpErrorResponse({
                status: 403,
                error: { errors: ['You are not assigned to that branch.'] },
              }),
          ),
        ),
    );
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();
    fixture.componentInstance.branchUid.set('BR-ARU');
    fixture.componentInstance.run();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
