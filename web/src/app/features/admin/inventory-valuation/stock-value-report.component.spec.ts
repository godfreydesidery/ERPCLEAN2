/**
 * StockValueReportComponent — key behaviour specs.
 *
 * Covers:
 *  1. Loads on init when permitted; never calls the API without INVENTORY.VALUATION.VIEW.
 *  2. The supplier filter (the "supplier-wise" ask) reaches the API, and blanks are sent as null.
 *  3. A NULL cost/sale value renders as an em dash — never 0.00.
 *  4. The grand-totals foot IS rendered here (unlike the Product List) with cost, sale and margin.
 *  5. The "excluded from totals" caveat appears exactly when items were left out.
 *  6. The no-default-price-list warning appears when priceListName is null.
 *  7. 403 handling — a refused BRANCH does not collapse a report the caller can run unfiltered;
 *     export() calls the stock-value export with the same filter.
 *  8. Discontinued rows (in the valuation, absent from the Product List) are marked and counted.
 *  9. The branch picker offers only branches the caller may read (root still sees them all).
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
import { ProductStockReportService } from './product-stock-report.service';
import { StockValueReportComponent } from './stock-value-report.component';

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
    // Holds stock, never costed and never priced — excluded from BOTH totals.
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

/**
 * The widened row set: an off-catalogue product that still holds stock. It IS counted in the
 * totals (that is what ties this valuation to the books) and is absent from the Product List.
 */
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
    imports: [StockValueReportComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ProductStockReportService,
        useValue: { stockValue: reportSpy, exportStockValue: exportSpy },
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

describe('StockValueReportComponent', () => {
  afterEach(() => {
    vi.restoreAllMocks(); // undo the document.body/URL spies from the export() test
    TestBed.resetTestingModule();
  });

  it('loads the valuation on init when permitted', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    expect(reportSpy).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.report()).toEqual(MOCK_REPORT);
    expect(fixture.componentInstance.state()).toBe('idle');
  });

  it('does not call the service when the user lacks INVENTORY.VALUATION.VIEW', () => {
    const { reportSpy } = makeBed({ session: makeSession(false) });
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    expect(reportSpy).not.toHaveBeenCalled();
    expect(fixture.componentInstance.canView()).toBe(false);
  });

  it('sends null (not an empty string) for blank optional filters', () => {
    const { reportSpy } = makeBed();
    const comp = TestBed.createComponent(StockValueReportComponent).componentInstance;
    comp.run();
    expect(reportSpy).toHaveBeenLastCalledWith({ branchUid: null, supplierUid: null });
  });

  it('runs supplier-wise when a supplier is chosen', () => {
    const { reportSpy } = makeBed();
    const comp = TestBed.createComponent(StockValueReportComponent).componentInstance;
    comp.supplierUid.set('SUP-1');
    comp.run();
    expect(reportSpy).toHaveBeenLastCalledWith({ branchUid: null, supplierUid: 'SUP-1' });
  });

  it('formats an unknown value as an em dash, never 0.00', () => {
    makeBed();
    const comp = TestBed.createComponent(StockValueReportComponent).componentInstance;
    expect(comp.fmtAmt(null)).toBe('—');
    expect(comp.fmtAmt(undefined)).toBe('—');
    expect(comp.fmtAmt(0)).toMatch(/0\.00/); // a REAL zero still prints as zero
    expect(comp.fmtAmt(15000)).toMatch(/15,000\.00/);
  });

  it('renders the unvalued row with dashes rather than zeros', () => {
    makeBed();
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    const bodyRows: HTMLTableRowElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('table.erp-table tbody tr'),
    );
    expect(bodyRows).toHaveLength(2);
    const unvalued = bodyRows[1].textContent ?? '';
    expect(unvalued).toContain('Imported gizmo');
    expect(unvalued).toContain('—');
    expect(unvalued).not.toContain('0.00');
  });

  it('renders the grand-totals foot with total cost, total sale and the potential margin', () => {
    makeBed();
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    const foot: HTMLTableSectionElement | null =
      fixture.nativeElement.querySelector('table.erp-table tfoot');
    expect(foot).not.toBeNull();

    const text = foot?.textContent ?? '';
    expect(text).toContain('TOTAL');
    expect(text).toContain('14'); // total quantity
    expect(text).toContain('10,000.00'); // total cost value
    expect(text).toContain('15,000.00'); // total sale value
    expect(text).toContain('5,000.00'); // potential margin
  });

  it('states how many items were left out of the totals', () => {
    makeBed();
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.excludedFromTotals()).toEqual({ unvalued: 1, unpriced: 1 });
    expect(fixture.nativeElement.textContent).toContain('lower than the real value');
  });

  it('stays quiet about exclusions when nothing was left out', () => {
    const clean = {
      ...MOCK_REPORT,
      totals: { ...MOCK_REPORT.totals!, unvaluedItems: 0, unpricedItems: 0 },
    };
    makeBed({ reportSpy: vi.fn(() => of(clean)) });
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.excludedFromTotals()).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('lower than the real value');
  });

  it('names the VAT stance in the selling-price column head', () => {
    makeBed();
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.sellingHeader()).toBe('Selling Price (excl. VAT)');
  });

  it('warns in plain words when the company has no default price list', () => {
    makeBed({ reportSpy: vi.fn(() => of({ ...MOCK_REPORT, priceListName: null })) });
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.noPriceList()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('default price list');
    expect(fixture.componentInstance.sellingHeader()).toBe('Selling Price');
  });

  it('points a user who can set the default at the screen that does it', () => {
    makeBed({ reportSpy: vi.fn(() => of({ ...MOCK_REPORT, priceListName: null })) });
    const fixture = TestBed.createComponent(StockValueReportComponent);
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
    const fixture = TestBed.createComponent(StockValueReportComponent);
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

  it('marks and counts items that left the catalogue but still hold stock', () => {
    makeBed({ reportSpy: vi.fn(() => of(REPORT_WITH_DISCONTINUED)) });
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.discontinuedCount()).toBe(1);

    const bodyRows: HTMLTableRowElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('table.erp-table tbody tr'),
    );
    expect(bodyRows).toHaveLength(3);
    // The mark is real text, not an icon — it has to survive a screen reader and a printout.
    expect(bodyRows[2].textContent).toContain('Discontinued');
    expect(bodyRows[0].textContent).not.toContain('Discontinued');
    // ...and the count is stated, mirroring the export's header line.
    expect(fixture.nativeElement.textContent).toContain('still hold stock');
    expect(fixture.nativeElement.textContent).toContain("You won't find them on the Product List");
  });

  it('says nothing about discontinued items when there are none', () => {
    makeBed();
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.discontinuedCount()).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Discontinued');
    expect(fixture.nativeElement.textContent).not.toContain('still hold stock');
  });

  it('offers only the branches the caller may read, never the whole company', () => {
    const { branchListSpy, myBranchesSpy } = makeBed();
    const fixture = TestBed.createComponent(StockValueReportComponent);
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
    const fixture = TestBed.createComponent(StockValueReportComponent);
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
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.state()).toBe('forbidden');
    expect(fixture.nativeElement.textContent).toContain('You are not assigned to that branch.');
  });

  it('treats a 403 on a BRANCH-filtered run as a filter refusal, keeping the figures on screen', () => {
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
    const fixture = TestBed.createComponent(StockValueReportComponent);
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

  it('export() calls the stock-value export with the same filter and the requested format', () => {
    const { exportSpy } = makeBed();
    const comp = TestBed.createComponent(StockValueReportComponent).componentInstance;

    // Stub the browser download side-effect: jsdom chokes on anchor.click().
    vi.spyOn(document.body, 'appendChild').mockImplementation((n) => n as Node);
    vi.spyOn(document.body, 'removeChild').mockImplementation((n) => n as Node);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);

    comp.branchUid.set('BR-1');
    comp.export('PDF');

    expect(exportSpy).toHaveBeenCalledWith({ branchUid: 'BR-1', supplierUid: null }, 'PDF');
    expect(comp.exporting()).toBe(false);
  });

  it('hides the export buttons from a user who can view but not export', () => {
    makeBed({ session: makeSession((code) => code !== 'REPORT.EXPORT') });
    const fixture = TestBed.createComponent(StockValueReportComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.canView()).toBe(true);
    expect(fixture.componentInstance.canExport()).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Export PDF');
  });
});
