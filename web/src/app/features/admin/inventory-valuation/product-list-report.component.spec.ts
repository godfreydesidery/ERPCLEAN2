/**
 * ProductListReportComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads on init when permitted; never calls the API without INVENTORY.VALUATION.VIEW.
 *  2. Blank optional filters are sent as null, never as an empty string.
 *  3. A NULL price renders as an em dash — never 0.00 (an unpriced item is unknown, not free).
 *  4. The rows reach the DOM, and NO totals foot is rendered (this report has none, by design).
 *  5. The no-default-price-list warning appears only when priceListName is null.
 *  6. 403 handling — a refused BRANCH does not collapse a report the caller can run unfiltered.
 *  7. export() calls the export endpoint with the same filter and the requested format, and the
 *     buttons are hidden entirely from a user without REPORT.EXPORT.
 *  8. The branch picker offers only branches the caller may read (root still sees them all).
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierService } from '../parties/supplier.service';
import { ProductStockReportDto } from './models/product-stock-report.model';
import { ProductListReportComponent } from './product-list-report.component';
import { ProductStockReportService } from './product-stock-report.service';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const emptySupplierPage = () => ({
  rows: [],
  meta: { page: 0, size: 200, totalElements: 0, totalPages: 0, hasNext: false },
});

/** Every ACTIVE branch of the company — what BRANCH.VIEW returns, and what root may read. */
const ALL_BRANCHES = [
  { id: '1', uid: 'BR-HQ', companyId: '10', companyUid: 'CO1', code: 'HQ', name: 'Head Office', timeZone: 'Africa/Dar_es_Salaam', isDefault: true, status: 'ACTIVE' },
  { id: '2', uid: 'BR-ARU', companyId: '10', companyUid: 'CO1', code: 'ARU', name: 'Arusha', timeZone: 'Africa/Dar_es_Salaam', isDefault: false, status: 'ACTIVE' },
];
/** The caller's OWN assignments (GET /auth/my-branches) — HQ only, plus one in another company. */
const MY_BRANCHES = [
  { id: '11', uid: 'UB1', userUid: 'U1', branchUid: 'BR-HQ', branchCode: 'HQ', branchName: 'Head Office', companyUid: 'CO1', isDefault: true, assignedAt: '2026-01-01T00:00:00Z' },
  { id: '12', uid: 'UB2', userUid: 'U1', branchUid: 'BR-OTHER', branchCode: 'OTH', branchName: 'Other Co Branch', companyUid: 'CO2', isDefault: false, assignedAt: '2026-01-01T00:00:00Z' },
];

const MOCK_REPORT: ProductStockReportDto = {
  company: {
    name: 'Tembo Group Ltd', legalName: null,
    addressLine1: null, addressLine2: null, city: null, region: null, country: null,
    contactPhone: null, contactEmail: null, taxId: null, vrn: null,
  },
  branchName: null,
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
    // Never costed, never priced — the row that must NOT render as 0.00.
    {
      productCode: 'P002', productName: 'Imported gizmo', supplierName: null,
      quantityOnHand: 4, buyingPrice: null, costValue: null, sellingPrice: null, saleValue: null,
      discontinued: false,
    },
  ],
  totals: null,
  generatedAt: '2026-08-11T12:00:00Z',
};

/** `has` may be a flat answer or a per-code predicate (view granted, export denied). */
function makeSession(has: boolean | ((code: string) => boolean) = true, isRoot = false) {
  const answer = typeof has === 'function' ? has : () => has;
  return {
    hasPermission: vi.fn((code: string) => answer(code)),
    isAuthenticated: signal(true),
    user: signal(isRoot ? { isRoot: true } : null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(overrides: {
  reportSpy?: ReturnType<typeof vi.fn>;
  exportSpy?: ReturnType<typeof vi.fn>;
  session?: ReturnType<typeof makeSession>;
} = {}) {
  const reportSpy = overrides.reportSpy ?? vi.fn(() => of(MOCK_REPORT));
  const exportSpy = overrides.exportSpy ?? vi.fn(() => of(new Blob()));
  const branchListSpy = vi.fn(() => of(ALL_BRANCHES));
  const myBranchesSpy = vi.fn(() => of(MY_BRANCHES));

  TestBed.configureTestingModule({
    imports: [ProductListReportComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ProductStockReportService,
        useValue: { productList: reportSpy, exportProductList: exportSpy },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: BranchService, useValue: { list: branchListSpy } },
      { provide: AuthService, useValue: { myBranches: myBranchesSpy } },
      { provide: SupplierService, useValue: { list: vi.fn(() => of(emptySupplierPage())) } },
      { provide: SessionStore, useValue: overrides.session ?? makeSession() },
    ],
  });

  return { reportSpy, exportSpy, branchListSpy, myBranchesSpy };
}

describe('ProductListReportComponent', () => {
  afterEach(() => {
    vi.restoreAllMocks(); // undo the document.body/URL spies from the export() test
    TestBed.resetTestingModule();
  });

  it('loads the catalogue on init when permitted', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    expect(reportSpy).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.report()).toEqual(MOCK_REPORT);
    expect(fixture.componentInstance.state()).toBe('idle');
  });

  it('does not call the service when the user lacks INVENTORY.VALUATION.VIEW', () => {
    const { reportSpy } = makeBed({ session: makeSession(false) });
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    expect(reportSpy).not.toHaveBeenCalled();
    expect(fixture.componentInstance.canView()).toBe(false);
  });

  it('sends null (not an empty string) for blank optional filters', () => {
    const { reportSpy } = makeBed();
    const comp = TestBed.createComponent(ProductListReportComponent).componentInstance;
    comp.run();
    expect(reportSpy).toHaveBeenLastCalledWith({ branchUid: null, supplierUid: null });
  });

  it('passes the chosen branch and supplier through', () => {
    const { reportSpy } = makeBed();
    const comp = TestBed.createComponent(ProductListReportComponent).componentInstance;
    comp.branchUid.set('BR-1');
    comp.supplierUid.set('SUP-1');
    comp.run();
    expect(reportSpy).toHaveBeenLastCalledWith({ branchUid: 'BR-1', supplierUid: 'SUP-1' });
  });

  it('formats an unknown price as an em dash, never 0.00', () => {
    makeBed();
    const comp = TestBed.createComponent(ProductListReportComponent).componentInstance;
    expect(comp.fmtAmt(null)).toBe('—');
    expect(comp.fmtAmt(undefined)).toBe('—');
    expect(comp.fmtAmt(1500)).toMatch(/1,500\.00/);
    expect(comp.fmtAmt(0)).toMatch(/0\.00/); // a REAL zero still prints as zero
    expect(comp.fmtQty(10)).toBe('10');
  });

  it('renders the rows, showing a dash in the unpriced row instead of 0.00', () => {
    makeBed();
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    const bodyRows: HTMLTableRowElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('table.erp-table tbody tr'),
    );
    expect(bodyRows).toHaveLength(2);

    const unpriced = bodyRows[1].textContent ?? '';
    expect(unpriced).toContain('Imported gizmo');
    expect(unpriced).toContain('—');
    expect(unpriced).not.toContain('0.00');
  });

  it('renders NO totals foot — the Product List deliberately has none', () => {
    makeBed();
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('table.erp-table tfoot')).toBeNull();
  });

  it('names the VAT stance in the selling-price column head', () => {
    makeBed();
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.sellingHeader()).toBe('Selling Price (VAT incl.)');

    const heads: HTMLTableCellElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('table.erp-table thead th'),
    );
    expect(heads.map((h) => h.textContent?.trim())).toContain('Selling Price (VAT incl.)');
  });

  it('warns in plain words when the company has no default price list', () => {
    makeBed({ reportSpy: vi.fn(() => of({ ...MOCK_REPORT, priceListName: null })) });
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.noPriceList()).toBe(true);
    expect(fixture.nativeElement.querySelector('.alert-warning')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('default price list');
    // With no list the head cannot claim a VAT stance it does not know.
    expect(fixture.componentInstance.sellingHeader()).toBe('Selling Price');
  });

  it('points a user who can set the default at the screen that does it', () => {
    makeBed({ reportSpy: vi.fn(() => of({ ...MOCK_REPORT, priceListName: null })) });
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    const alert: HTMLElement = fixture.nativeElement.querySelector('.alert-warning');
    expect(fixture.componentInstance.canSetDefaultPriceList()).toBe(true);
    expect(alert.querySelector('a')?.getAttribute('href')).toBe('/admin/price-lists');
    expect(alert.textContent).toContain('Set default');
  });

  it('gives a user without PRICELIST.MANAGE the diagnosis without the dead-end link', () => {
    // The stock roles that read this report hold neither PRICELIST.VIEW nor .MANAGE: the Price
    // Lists route guard turns them away and the screen hides the button, so the link goes nowhere.
    makeBed({
      reportSpy: vi.fn(() => of({ ...MOCK_REPORT, priceListName: null })),
      session: makeSession((code) => code !== 'PRICELIST.MANAGE'),
    });
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    const alert: HTMLElement = fixture.nativeElement.querySelector('.alert-warning');
    expect(fixture.componentInstance.canSetDefaultPriceList()).toBe(false);
    // The load-bearing part still reaches them…
    expect(alert.textContent).toContain('default price list');
    expect(alert.textContent).toContain('cannot be shown');
    // …only the remedy forks.
    expect(alert.querySelector('a')).toBeNull();
    expect(alert.textContent).not.toContain('Set default');
    expect(alert.textContent).toContain('Ask whoever manages your price lists');
  });

  it('shows no price-list warning when a default price list exists', () => {
    makeBed();
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.noPriceList()).toBe(false);
    expect(fixture.nativeElement.querySelector('.alert-warning')).toBeNull();
  });

  it('offers only the branches the caller may read, never the whole company', () => {
    const { branchListSpy, myBranchesSpy } = makeBed();
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    // The whole-company list would offer Arusha, which the report refuses for this caller.
    expect(branchListSpy).not.toHaveBeenCalled();
    expect(myBranchesSpy).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.branchOptions()).toEqual([
      { uid: 'BR-HQ', label: 'Head Office', hint: 'HQ' },
    ]);
  });

  it('keeps root on the full company list — root reads branches it is not assigned to', () => {
    const { branchListSpy, myBranchesSpy } = makeBed({ session: makeSession(true, true) });
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    expect(myBranchesSpy).not.toHaveBeenCalled();
    expect(branchListSpy).toHaveBeenCalledWith('CO1');
    expect(fixture.componentInstance.branchOptions().map((o) => o.uid)).toEqual([
      'BR-HQ',
      'BR-ARU',
    ]);
  });

  it('sets state=forbidden on a 403 with no branch filter, and prints the server sentence', () => {
    const reportSpy = vi.fn(() =>
      throwError(
        () =>
          new HttpErrorResponse({
            status: 403,
            error: { errors: ['You are not assigned to that branch.'] },
          }),
      ),
    );
    makeBed({ reportSpy });
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('forbidden');
    expect(fixture.nativeElement.textContent).toContain('You are not assigned to that branch.');
  });

  it('treats a 403 on a BRANCH-filtered run as a filter refusal, keeping the listing on screen', () => {
    const reportSpy = vi
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
      );
    makeBed({ reportSpy });
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges(); // unfiltered run succeeds

    const comp = fixture.componentInstance;
    comp.branchUid.set('BR-ARU');
    comp.run();
    fixture.detectChanges();

    // The report was NOT refused — only the branch was.
    expect(comp.state()).toBe('idle');
    expect(comp.report()).toEqual(MOCK_REPORT);
    expect(comp.branchFilterError()).toBe('You are not assigned to that branch.');
    expect(fixture.nativeElement.textContent).toContain('Showing the previous results.');
    expect(fixture.nativeElement.textContent).not.toContain(
      "You don't have permission to view this report.",
    );
  });

  it('clears the branch-filter message on the next run', () => {
    const reportSpy = vi
      .fn()
      .mockReturnValueOnce(of(MOCK_REPORT))
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 403 })))
      .mockReturnValueOnce(of(MOCK_REPORT));
    makeBed({ reportSpy });
    const comp = TestBed.createComponent(ProductListReportComponent).componentInstance;
    comp.ngOnInit();
    comp.branchUid.set('BR-ARU');
    comp.run();
    expect(comp.branchFilterError()).not.toBeNull();

    comp.branchUid.set('');
    comp.run();
    expect(comp.branchFilterError()).toBeNull();
  });

  it('export() calls the export endpoint with the same filter and the requested format', () => {
    const { exportSpy } = makeBed();
    const comp = TestBed.createComponent(ProductListReportComponent).componentInstance;

    // Stub the browser download side-effect: jsdom chokes on anchor.click().
    vi.spyOn(document.body, 'appendChild').mockImplementation((n) => n as Node);
    vi.spyOn(document.body, 'removeChild').mockImplementation((n) => n as Node);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);

    comp.supplierUid.set('SUP-1');
    comp.export('XLSX');

    expect(exportSpy).toHaveBeenCalledWith({ branchUid: null, supplierUid: 'SUP-1' }, 'XLSX');
    expect(comp.exporting()).toBe(false);
  });

  it('hides the export buttons from a user who can view but not export', () => {
    makeBed({ session: makeSession((code) => code !== 'REPORT.EXPORT') });
    const fixture = TestBed.createComponent(ProductListReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.canView()).toBe(true);
    expect(fixture.componentInstance.canExport()).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Export PDF');
  });
});
