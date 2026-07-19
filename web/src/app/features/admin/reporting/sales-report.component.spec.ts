/**
 * SalesReportComponent — key behaviour specs (SAM Electronix go-live).
 *
 * Covers:
 *  1. Renders without crashing.
 *  2. fmtMoney / fmtQty numeric-money guard (BigDecimal arrives as JSON number).
 *  3. run() calls reportingService.salesReport with the filter built from the form signals.
 *  4. Blank optional filters are omitted (sent as null, not empty string).
 *  5. 403 sets state = 'forbidden'.
 *  6. export() calls reportingService.exportSalesReport with the same filter + format.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AgentService } from '../parties/agent.service';
import { SupplierService } from '../parties/supplier.service';
import { RoutesService } from '../routes/routes.service';
import { ReportingService } from './reporting.service';
import { SalesReportComponent } from './sales-report.component';
import { SalesReportDto } from './models/sales-report.model';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const emptyListPage = () => ({
  rows: [],
  meta: { page: 0, size: 200, totalElements: 0, totalPages: 0, hasNext: false },
});

const MOCK_REPORT: SalesReportDto = {
  company: {
    name: 'SAM Electronix Ltd', legalName: 'SAM Electronix Company Limited',
    addressLine1: 'Plot 12', addressLine2: 'Nyerere Road', city: 'Dar es Salaam', region: 'Dar',
    country: 'Tanzania', contactPhone: '+255700000000', contactEmail: 'info@sam.co.tz',
    taxId: 'TIN-999', vrn: 'VRN-888',
  },
  fromDate: '2026-07-01', toDate: '2026-07-19',
  supplierName: null, agentName: null, routeName: null,
  currency: 'TZS',
  rows: [
    { productCode: 'P001', productName: 'Widget', currentStock: 10, qtySold: 5, discount: 100, vat: 50, margin: 20, amount: 500 },
  ],
  totals: { qtySold: 5, discount: 100, vat: 50, margin: 20, amount: 500 },
  generatedAt: '2026-07-19T12:00:00Z',
};

function makeSession() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(overrides: {
  salesReportSpy?: ReturnType<typeof vi.fn>;
  exportSalesReportSpy?: ReturnType<typeof vi.fn>;
  session?: ReturnType<typeof makeSession>;
} = {}) {
  const salesReportSpy = overrides.salesReportSpy ?? vi.fn(() => of(MOCK_REPORT));
  const exportSalesReportSpy = overrides.exportSalesReportSpy ?? vi.fn(() => of(new Blob()));

  TestBed.configureTestingModule({
    imports: [SalesReportComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ReportingService,
        useValue: { salesReport: salesReportSpy, exportSalesReport: exportSalesReportSpy },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      { provide: AgentService, useValue: { list: vi.fn(() => of(emptyListPage())) } },
      { provide: RoutesService, useValue: { list: vi.fn(() => of(emptyListPage())) } },
      { provide: SupplierService, useValue: { list: vi.fn(() => of(emptyListPage())) } },
      { provide: SessionStore, useValue: overrides.session ?? makeSession() },
    ],
  });

  return { salesReportSpy, exportSalesReportSpy };
}

describe('SalesReportComponent', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks(); // undo the document.body/URL spies from the export() test
    TestBed.resetTestingModule();
  });

  it('renders without crashing', () => {
    makeBed();
    const fixture = TestBed.createComponent(SalesReportComponent);
    expect(fixture).toBeTruthy();
  });

  it('fmtMoney / fmtQty coerce numeric wire values (numeric-money guard)', () => {
    makeBed();
    const comp = TestBed.createComponent(SalesReportComponent).componentInstance;
    expect(comp.fmtMoney(500)).toMatch(/500\.00/);
    expect(comp.fmtMoney(null)).toMatch(/0\.00/);
    expect(comp.fmtMoney(undefined)).toMatch(/0\.00/);
    expect(() => comp.fmtMoney(500)).not.toThrow();
    expect(comp.fmtQty(5)).toBe('5');
    expect(comp.fmtQty('5')).toBe('5');
    expect(comp.fmtQty(null)).toBe('0');
  });

  it('run() calls salesReport with the filled filter form', () => {
    const { salesReportSpy } = makeBed();
    const comp = TestBed.createComponent(SalesReportComponent).componentInstance;

    comp.fromDate.set('2026-07-01');
    comp.toDate.set('2026-07-19');
    comp.agentUid.set('AGENT-1');
    comp.routeUid.set('ROUTE-1');
    comp.supplierUid.set('SUPP-1');
    comp.run();

    expect(salesReportSpy).toHaveBeenCalledOnce();
    expect(salesReportSpy).toHaveBeenCalledWith({
      fromDate: '2026-07-01',
      toDate: '2026-07-19',
      agentUid: 'AGENT-1',
      routeUid: 'ROUTE-1',
      supplierUid: 'SUPP-1',
    });
    expect(comp.report()).toEqual(MOCK_REPORT);
    expect(comp.state()).toBe('idle');
  });

  it('run() sends null (not empty string) for blank optional filters', () => {
    const { salesReportSpy } = makeBed();
    const comp = TestBed.createComponent(SalesReportComponent).componentInstance;

    comp.fromDate.set('2026-07-01');
    comp.toDate.set('2026-07-19');
    comp.run();

    const req = salesReportSpy.mock.calls[0][0];
    expect(req.agentUid).toBeNull();
    expect(req.routeUid).toBeNull();
    expect(req.supplierUid).toBeNull();
  });

  it('does not call the service when From/To date is blank', () => {
    const { salesReportSpy } = makeBed();
    const comp = TestBed.createComponent(SalesReportComponent).componentInstance;

    comp.fromDate.set('');
    comp.toDate.set('2026-07-19');
    comp.run();

    expect(salesReportSpy).not.toHaveBeenCalled();
  });

  it('sets state=forbidden on a 403 response', () => {
    const salesReportSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
    );
    makeBed({ salesReportSpy });
    const comp = TestBed.createComponent(SalesReportComponent).componentInstance;

    comp.fromDate.set('2026-07-01');
    comp.toDate.set('2026-07-19');
    comp.run();

    expect(comp.state()).toBe('forbidden');
  });

  it('export() calls exportSalesReport with the same filter and the requested format', () => {
    const { exportSalesReportSpy } = makeBed();
    const comp = TestBed.createComponent(SalesReportComponent).componentInstance;

    // Stub the browser download side-effect so jsdom doesn't choke on anchor.click().
    vi.spyOn(document.body, 'appendChild').mockImplementation((n) => n as Node);
    vi.spyOn(document.body, 'removeChild').mockImplementation((n) => n as Node);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);

    comp.fromDate.set('2026-07-01');
    comp.toDate.set('2026-07-19');
    comp.export('PDF');

    expect(exportSalesReportSpy).toHaveBeenCalledOnce();
    const [filter, format] = exportSalesReportSpy.mock.calls[0];
    expect(filter.fromDate).toBe('2026-07-01');
    expect(filter.toDate).toBe('2026-07-19');
    expect(format).toBe('PDF');
    expect(comp.exporting()).toBe(false);
  });

  it('hides the screen behind a permission notice when SALES.INVOICE.VIEW is absent', () => {
    const session = makeSession();
    session.hasPermission = vi.fn(() => false);
    makeBed({ session });
    const comp = TestBed.createComponent(SalesReportComponent).componentInstance;
    expect(comp.canView()).toBe(false);
  });
});
