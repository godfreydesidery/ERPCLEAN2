/**
 * BulkImportComponent spec.
 *
 * Covers:
 *  1. Startup: loads entities and defaults the picker to the first one.
 *  2. entitiesState 'forbidden' on a 403 (or an empty list — the caller can import nothing).
 *  3. entitiesState 'error' on a non-403 failure.
 *  4. downloadTemplate() calls the service with the selected key.
 *  5. onFileSelected() rejects a non-.xlsx file without calling validate().
 *  6. onFileSelected() triggers validate() and populates the report.
 *  7. canCommit is false while errors > 0; true once a clean file validates.
 *  8. commit() is guarded against firing without a validated, error-free file.
 *  9. commit() calls the service and flips `committed` on success.
 *  10. onEntityChange() resets the file/report state.
 *  11. exportCurrent(): non-prices entity exports with no priceList param.
 *  12. exportCurrent(): prices entity requires a price-list selection first.
 *  13. exportCurrent(): prices entity passes the selected price-list code through.
 *  14. Selecting the `prices` entity loads companies + price lists (lazily, once).
 *  15. commit() with errors>0 shows an error toast (not success) stating the failure count.
 *  16. commit() with errors===0 keeps the green success toast.
 */
import { HttpErrorResponse } from '@angular/common/http';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { BulkImportService } from './bulk-import.service';
import { BulkImportComponent } from './bulk-import.component';
import type { EntityDescriptor, ImportReport } from './bulk-import.model';
import type { PriceListDto } from '../models/product.model';

vi.useFakeTimers();

// ── Fixtures ──────────────────────────────────────────────────────────────────

const ENTITIES: EntityDescriptor[] = [
  { key: 'PRODUCT', label: 'Products', permissionCode: 'PRODUCT.IMPORT' },
  { key: 'CUSTOMER', label: 'Customers', permissionCode: 'CUSTOMER.IMPORT' },
];

const PRICES_ENTITIES: EntityDescriptor[] = [
  { key: 'prices', label: 'Prices', permissionCode: 'PRICE.MASS_UPDATE' },
];

function makePriceList(): PriceListDto {
  return {
    id: '30', uid: 'PL1', companyId: '10', code: 'RETAIL', name: 'Retail Price',
    isDefault: true, status: 'ACTIVE', version: null,
    createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  };
}

function makeReport(overrides: Partial<ImportReport> = {}): ImportReport {
  return {
    entityKey: 'PRODUCT',
    mode: 'VALIDATE',
    total: 2,
    created: 1,
    updated: 1,
    skipped: 0,
    errors: 0,
    rows: [
      { rowNumber: 2, action: 'CREATE', reference: 'SKU-1', message: null },
      { rowNumber: 3, action: 'UPDATE', reference: 'SKU-2', message: null },
    ],
    ...overrides,
  };
}

function makeXlsxFile(name = 'products.xlsx'): File {
  return new File(['dummy'], name, {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
}

function fileChangeEvent(file: File | null): Event {
  return { target: { files: file ? [file] : [], value: '' } } as unknown as Event;
}

function makeBed(opts: {
  listEntitiesImpl?: () => any;
  validateImpl?: () => any;
  commitImpl?: () => any;
} = {}) {
  const listEntitiesImpl = opts.listEntitiesImpl ?? (() => of(ENTITIES));
  const validateImpl = opts.validateImpl ?? (() => of(makeReport()));
  const commitImpl = opts.commitImpl ?? (() => of(makeReport({ mode: 'COMMIT' })));

  TestBed.configureTestingModule({
    imports: [BulkImportComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: BulkImportService,
        useValue: {
          listEntities: vi.fn(listEntitiesImpl),
          downloadTemplate: vi.fn(() => of(new Blob(['xlsx-bytes']))),
          exportCurrent: vi.fn(() => of(new Blob(['xlsx-bytes']))),
          validate: vi.fn(validateImpl),
          commit: vi.fn(commitImpl),
        },
      },
      {
        provide: ProductService,
        useValue: { listPriceLists: vi.fn(() => of([makePriceList()])) },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
    ],
  });
}

afterEach(() => TestBed.resetTestingModule());

// ── Startup ───────────────────────────────────────────────────────────────────

describe('BulkImportComponent — startup', () => {
  it('loads entities and defaults to the first key', async () => {
    makeBed();
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.entities().length).toBe(2);
    expect(comp.selectedKey()).toBe('PRODUCT');
    expect(comp.entitiesState()).toBe('idle');
  });

  it('sets entitiesState to forbidden on a 403', async () => {
    makeBed({
      listEntitiesImpl: () => throwError(() => new HttpErrorResponse({ status: 403, statusText: 'Forbidden' })),
    });
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.entitiesState()).toBe('forbidden');
  });

  it('sets entitiesState to forbidden on an empty list (defensive)', async () => {
    makeBed({ listEntitiesImpl: () => of([]) });
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.entitiesState()).toBe('forbidden');
  });

  it('sets entitiesState to error on a non-403 failure', async () => {
    makeBed({
      listEntitiesImpl: () => throwError(() => new HttpErrorResponse({ status: 500, statusText: 'Server Error' })),
    });
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.entitiesState()).toBe('error');
  });
});

// ── Template download ─────────────────────────────────────────────────────────

describe('BulkImportComponent — downloadTemplate', () => {
  it('calls the service with the selected entity key', async () => {
    makeBed();
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const svc = TestBed.inject(BulkImportService) as any;
    await vi.runAllTimersAsync();

    comp.downloadTemplate();
    await vi.runAllTimersAsync();

    expect(svc.downloadTemplate).toHaveBeenCalledWith('PRODUCT');
    expect(comp.downloadingTemplate()).toBe(false);
    expect(comp.templateError()).toBeNull();
  });
});

// ── Upload & validate ─────────────────────────────────────────────────────────

describe('BulkImportComponent — upload & validate', () => {
  it('rejects a non-.xlsx file without calling validate()', async () => {
    makeBed();
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const svc = TestBed.inject(BulkImportService) as any;
    await vi.runAllTimersAsync();

    const badFile = new File(['x'], 'products.csv', { type: 'text/csv' });
    comp.onFileSelected(fileChangeEvent(badFile));

    expect(svc.validate).not.toHaveBeenCalled();
    expect(comp.validateError()).toBeTruthy();
  });

  it('validates a selected .xlsx file and populates the report', async () => {
    makeBed();
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const svc = TestBed.inject(BulkImportService) as any;
    await vi.runAllTimersAsync();

    comp.onFileSelected(fileChangeEvent(makeXlsxFile()));
    await vi.runAllTimersAsync();

    expect(svc.validate).toHaveBeenCalledWith('PRODUCT', expect.any(File));
    expect(comp.report()?.mode).toBe('VALIDATE');
    expect(comp.report()?.errors).toBe(0);
  });

  it('canCommit is false when the validation report has errors', async () => {
    makeBed({ validateImpl: () => of(makeReport({ errors: 1 })) });
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.onFileSelected(fileChangeEvent(makeXlsxFile()));
    await vi.runAllTimersAsync();

    expect(comp.report()?.errors).toBe(1);
    expect(comp.canCommit()).toBe(false);
  });

  it('canCommit is true once a clean file validates', async () => {
    makeBed();
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.onFileSelected(fileChangeEvent(makeXlsxFile()));
    await vi.runAllTimersAsync();

    expect(comp.canCommit()).toBe(true);
  });
});

// ── Commit ────────────────────────────────────────────────────────────────────

describe('BulkImportComponent — commit', () => {
  it('does nothing when the report has errors', async () => {
    makeBed({ validateImpl: () => of(makeReport({ errors: 1 })) });
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const svc = TestBed.inject(BulkImportService) as any;
    await vi.runAllTimersAsync();

    comp.onFileSelected(fileChangeEvent(makeXlsxFile()));
    await vi.runAllTimersAsync();
    comp.commit();

    expect(svc.commit).not.toHaveBeenCalled();
  });

  it('commits a clean, validated file and flips committed on success', async () => {
    makeBed();
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const svc = TestBed.inject(BulkImportService) as any;
    const alerts = TestBed.inject(AlertService) as any;
    await vi.runAllTimersAsync();

    comp.onFileSelected(fileChangeEvent(makeXlsxFile()));
    await vi.runAllTimersAsync();
    comp.commit();
    await vi.runAllTimersAsync();

    expect(svc.commit).toHaveBeenCalledWith('PRODUCT', expect.any(File));
    expect(comp.committed()).toBe(true);
    expect(comp.report()?.mode).toBe('COMMIT');
    expect(alerts.success).toHaveBeenCalledOnce();
    expect(alerts.error).not.toHaveBeenCalled();
  });

  it('shows an error toast (not success) when the commit report has failed rows', async () => {
    makeBed({ commitImpl: () => of(makeReport({ mode: 'COMMIT', created: 1, updated: 1, errors: 2 })) });
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const alerts = TestBed.inject(AlertService) as any;
    await vi.runAllTimersAsync();

    // A clean pre-commit validate (errors:0) is required to unlock canCommit(); the commit()
    // response itself is what carries the failures — mirrors a partially-failed commit.
    comp.onFileSelected(fileChangeEvent(makeXlsxFile()));
    await vi.runAllTimersAsync();
    comp.commit();
    await vi.runAllTimersAsync();

    expect(comp.committed()).toBe(true);
    expect(comp.report()?.errors).toBe(2);
    expect(alerts.success).not.toHaveBeenCalled();
    expect(alerts.error).toHaveBeenCalledOnce();
    const [title, message] = alerts.error.mock.calls[0];
    expect(title).toMatch(/errors/i);
    expect(message).toContain('2 failed');
  });

  it('is guarded against a second commit while one is in flight', async () => {
    makeBed();
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const svc = TestBed.inject(BulkImportService) as any;
    await vi.runAllTimersAsync();

    comp.onFileSelected(fileChangeEvent(makeXlsxFile()));
    await vi.runAllTimersAsync();
    comp.commit();
    comp.commit(); // second call while the first is in flight
    await vi.runAllTimersAsync();

    expect(svc.commit).toHaveBeenCalledOnce();
  });
});

// ── Entity change ─────────────────────────────────────────────────────────────

describe('BulkImportComponent — onEntityChange', () => {
  it('resets the file/report state', async () => {
    makeBed();
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.onFileSelected(fileChangeEvent(makeXlsxFile()));
    await vi.runAllTimersAsync();
    expect(comp.report()).not.toBeNull();

    comp.onEntityChange('CUSTOMER');

    expect(comp.selectedKey()).toBe('CUSTOMER');
    expect(comp.report()).toBeNull();
    expect(comp.selectedFile()).toBeNull();
  });
});

// ── Export current data ───────────────────────────────────────────────────────

describe('BulkImportComponent — exportCurrent (non-prices entity)', () => {
  it('exports with no priceList param', async () => {
    makeBed();
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const svc = TestBed.inject(BulkImportService) as any;
    await vi.runAllTimersAsync();

    comp.exportCurrent();
    await vi.runAllTimersAsync();

    expect(svc.exportCurrent).toHaveBeenCalledWith('PRODUCT', undefined);
    expect(comp.exportingCurrent()).toBe(false);
    expect(comp.exportError()).toBeNull();
  });
});

describe('BulkImportComponent — prices entity export', () => {
  it('selecting the prices entity lazily loads companies and its active price lists', async () => {
    makeBed({ listEntitiesImpl: () => of(PRICES_ENTITIES) });
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const productSvc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    expect(comp.isPricesEntity()).toBe(true);
    expect(comp.companies().length).toBe(1);
    expect(productSvc.listPriceLists).toHaveBeenCalledWith('10');
    expect(comp.priceLists().length).toBe(1);
    expect(comp.priceLists()[0].code).toBe('RETAIL');
  });

  it('requires a price-list selection before exporting', async () => {
    makeBed({ listEntitiesImpl: () => of(PRICES_ENTITIES) });
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const svc = TestBed.inject(BulkImportService) as any;
    await vi.runAllTimersAsync();

    comp.exportCurrent();

    expect(svc.exportCurrent).not.toHaveBeenCalled();
    expect(comp.exportError()).toBeTruthy();
  });

  it('passes the selected price-list code through to the export call', async () => {
    makeBed({ listEntitiesImpl: () => of(PRICES_ENTITIES) });
    const comp = TestBed.createComponent(BulkImportComponent).componentInstance;
    const svc = TestBed.inject(BulkImportService) as any;
    await vi.runAllTimersAsync();

    comp.onPriceListCodeChange('RETAIL');
    comp.exportCurrent();
    await vi.runAllTimersAsync();

    expect(svc.exportCurrent).toHaveBeenCalledWith('prices', 'RETAIL');
  });
});
