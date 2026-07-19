/**
 * StockReportComponent — key behaviour specs (SAM Electronix go-live).
 *
 * Covers:
 *  1. Renders without crashing; loads on init when permitted.
 *  2. fmtMoney / fmtQty numeric-money guard.
 *  3. 403 sets state = 'forbidden'.
 *  4. export() calls stockReportService.export with the requested format.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { StockReportService } from './stock-report.service';
import { StockReportComponent } from './stock-report.component';
import { StockReportDto } from './models/stock-report.model';

const MOCK_REPORT: StockReportDto = {
  company: {
    name: 'SAM Electronix Ltd', legalName: null,
    addressLine1: null, addressLine2: null, city: null, region: null, country: null,
    contactPhone: null, contactEmail: null, taxId: null, vrn: null,
  },
  currency: 'TZS',
  rows: [
    { productCode: 'P001', productName: 'Widget', quantityOnHand: 10, buyingPrice: 1000, sellingPrice: 1500, value: 10000 },
  ],
  totalValue: 10000,
  generatedAt: '2026-07-19T12:00:00Z',
};

function makeSession(hasPermission = true) {
  return {
    hasPermission: vi.fn(() => hasPermission),
    isAuthenticated: signal(true),
    user: signal(null),
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

  TestBed.configureTestingModule({
    imports: [StockReportComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      { provide: StockReportService, useValue: { report: reportSpy, export: exportSpy } },
      { provide: SessionStore, useValue: overrides.session ?? makeSession() },
    ],
  });

  return { reportSpy, exportSpy };
}

describe('StockReportComponent', () => {
  afterEach(() => {
    vi.restoreAllMocks(); // undo the document.body/URL spies from the export() test
    TestBed.resetTestingModule();
  });

  it('loads the report on init when permitted', () => {
    const { reportSpy } = makeBed();
    const fixture = TestBed.createComponent(StockReportComponent);
    fixture.detectChanges();

    expect(reportSpy).toHaveBeenCalledOnce();
    expect(fixture.componentInstance.report()).toEqual(MOCK_REPORT);
    expect(fixture.componentInstance.state()).toBe('idle');
  });

  it('does not call the service when the user lacks INVENTORY.VALUATION.VIEW', () => {
    const { reportSpy } = makeBed({ session: makeSession(false) });
    const fixture = TestBed.createComponent(StockReportComponent);
    fixture.detectChanges();

    expect(reportSpy).not.toHaveBeenCalled();
    expect(fixture.componentInstance.canView()).toBe(false);
  });

  it('fmtMoney / fmtQty coerce numeric wire values (numeric-money guard)', () => {
    makeBed();
    const comp = TestBed.createComponent(StockReportComponent).componentInstance;
    expect(comp.fmtMoney(10000)).toMatch(/10,000\.00/);
    expect(comp.fmtMoney(null)).toMatch(/0\.00/);
    expect(comp.fmtMoney(undefined)).toMatch(/0\.00/);
    expect(() => comp.fmtMoney(10000)).not.toThrow();
    expect(comp.fmtQty(10)).toBe('10');
    expect(comp.fmtQty('10')).toBe('10');
  });

  it('sets state=forbidden on a 403 response', () => {
    const reportSpy = vi.fn(() =>
      throwError(() => new HttpErrorResponse({ status: 403, error: { errors: ['Forbidden'] } })),
    );
    makeBed({ reportSpy });
    const fixture = TestBed.createComponent(StockReportComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.state()).toBe('forbidden');
  });

  it('export() calls stockReportService.export with the requested format', () => {
    const { exportSpy } = makeBed();
    const comp = TestBed.createComponent(StockReportComponent).componentInstance;

    vi.spyOn(document.body, 'appendChild').mockImplementation((n) => n as Node);
    vi.spyOn(document.body, 'removeChild').mockImplementation((n) => n as Node);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);

    comp.export('XLSX');

    expect(exportSpy).toHaveBeenCalledWith('XLSX');
    expect(comp.exporting()).toBe(false);
  });
});
