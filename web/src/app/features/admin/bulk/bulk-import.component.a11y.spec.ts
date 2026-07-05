/**
 * Accessibility gate — BulkImportComponent.
 *
 * Covers: the entity-picker + template-download step, and the validation-results table
 * (with an error row rendered) — each scanned with axe-core against WCAG 2.1 AA.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { BulkImportService } from './bulk-import.service';
import { BulkImportComponent } from './bulk-import.component';
import { assertA11y } from '../../../../testing/a11y.helper';
import type { EntityDescriptor, ImportReport } from './bulk-import.model';

const ENTITIES: EntityDescriptor[] = [
  { key: 'PRODUCT', label: 'Products', permissionCode: 'PRODUCT.IMPORT' },
  { key: 'CUSTOMER', label: 'Customers', permissionCode: 'CUSTOMER.IMPORT' },
];

const REPORT: ImportReport = {
  entityKey: 'PRODUCT',
  mode: 'VALIDATE',
  total: 2,
  created: 1,
  updated: 0,
  errors: 1,
  rows: [
    { rowNumber: 2, action: 'CREATE', reference: 'SKU-1', message: null },
    { rowNumber: 3, action: 'ERROR', reference: 'SKU-2', message: 'Missing required field: name' },
  ],
};

function makeBed() {
  TestBed.configureTestingModule({
    imports: [BulkImportComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: BulkImportService,
        useValue: {
          listEntities: vi.fn(() => of(ENTITIES)),
          downloadTemplate: vi.fn(() => of(new Blob(['xlsx-bytes']))),
          validate: vi.fn(() => of(REPORT)),
          commit: vi.fn(() => of({ ...REPORT, mode: 'COMMIT' as const, errors: 0 })),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
    ],
  });
}

describe('BulkImportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('entity-picker + template-download step has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(BulkImportComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('validation-results table with an error row has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(BulkImportComponent);
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    const file = new File(['dummy'], 'products.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });
    comp.onFileSelected({ target: { files: [file], value: '' } } as unknown as Event);
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
