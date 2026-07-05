/**
 * Accessibility gate — BulkImportComponent.
 *
 * Covers: the entity-picker + template/export step (including the prices entity's price-list
 * picker), and the validation-results table (with CREATE/UPDATE/SKIP/ERROR rows all rendered) —
 * each scanned with axe-core against WCAG 2.1 AA.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { BulkImportService } from './bulk-import.service';
import { BulkImportComponent } from './bulk-import.component';
import { assertA11y } from '../../../../testing/a11y.helper';
import type { EntityDescriptor, ImportReport } from './bulk-import.model';
import type { PriceListDto } from '../models/product.model';

const ENTITIES: EntityDescriptor[] = [
  { key: 'PRODUCT', label: 'Products', permissionCode: 'PRODUCT.IMPORT' },
  { key: 'CUSTOMER', label: 'Customers', permissionCode: 'CUSTOMER.IMPORT' },
];

const PRICES_ENTITIES: EntityDescriptor[] = [
  { key: 'prices', label: 'Prices', permissionCode: 'PRICE.MASS_UPDATE' },
];

const PRICE_LIST: PriceListDto = {
  id: '30', uid: 'PL1', companyId: '10', code: 'RETAIL', name: 'Retail Price',
  priceIncludesVat: true, isDefault: true, status: 'ACTIVE', version: null,
  createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
};

const REPORT: ImportReport = {
  entityKey: 'PRODUCT',
  mode: 'VALIDATE',
  total: 3,
  created: 1,
  updated: 0,
  skipped: 1,
  errors: 1,
  rows: [
    { rowNumber: 2, action: 'CREATE', reference: 'SKU-1', message: null },
    { rowNumber: 3, action: 'SKIP', reference: 'SKU-2', message: 'Blank price — left unchanged.' },
    { rowNumber: 4, action: 'ERROR', reference: 'SKU-3', message: 'Missing required field: name' },
  ],
};

function makeBed(entities: EntityDescriptor[] = ENTITIES) {
  TestBed.configureTestingModule({
    imports: [BulkImportComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: BulkImportService,
        useValue: {
          listEntities: vi.fn(() => of(entities)),
          downloadTemplate: vi.fn(() => of(new Blob(['xlsx-bytes']))),
          exportCurrent: vi.fn(() => of(new Blob(['xlsx-bytes']))),
          validate: vi.fn(() => of(REPORT)),
          commit: vi.fn(() => of({ ...REPORT, mode: 'COMMIT' as const, errors: 0 })),
        },
      },
      {
        provide: ProductService,
        useValue: { listPriceLists: vi.fn(() => of([PRICE_LIST])) },
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

describe('BulkImportComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('entity-picker + template/export step has no axe violations', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(BulkImportComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('the prices entity export step (with the price-list picker) has no axe violations', async () => {
    makeBed(PRICES_ENTITIES);
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(BulkImportComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('validation-results table with CREATE/SKIP/ERROR rows has no axe violations', async () => {
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
