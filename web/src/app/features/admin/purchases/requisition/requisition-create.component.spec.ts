/**
 * RequisitionCreateComponent — product-scoped unit picker specs.
 *
 * Covers:
 *  1. Selecting a product on a line calls listProductUnits (not listUnits).
 *  2. Line unit options contain only that product's units after selection.
 *  3. Unit is auto-selected to the base unit (first returned).
 *  4. Unit options are empty / picker disabled before a product is chosen.
 *  5. Changing product on a line clears the previous unit selection.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../../core/auth/session.store';
import { AlertService } from '../../../../core/feedback/alert.service';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { PurchaseRequisitionService } from './purchase-requisition.service';
import { RequisitionCreateComponent } from './requisition-create.component';

// ── Stubs ──────────────────────────────────────────────────────────────────────

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };

const STUB_PRODUCT = {
  id: '20', uid: 'PROD1', companyId: '10', code: 'SKU-A', name: 'Widget A',
  type: 'GOODS', sellable: true, stockable: true,
  baseUnitUid: 'U1', baseUnitCode: 'PCS', baseUnitName: 'Pieces',
  cost: null, vatStatus: 'STANDARD', status: 'ACTIVE',
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
} as any;

const STUB_BASE_UNIT = {
  id: '1', uid: 'U1', companyId: '10', code: 'PCS', name: 'Pieces',
  status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
};
const STUB_PACK_UNIT = {
  id: '2', uid: 'U2', companyId: '10', code: 'CTN', name: 'Carton',
  status: 'ACTIVE' as const,
  version: null, createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
};

function makeSessionStore() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed() {
  const sessionStore = makeSessionStore();

  TestBed.configureTestingModule({
    imports: [RequisitionCreateComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of(STUB_ORG)) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([STUB_COMPANY])) },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [STUB_PRODUCT], meta: {} })),
          listProductUnits: vi.fn(() => of([STUB_BASE_UNIT, STUB_PACK_UNIT])),
        },
      },
      {
        provide: PurchaseRequisitionService,
        useValue: { create: vi.fn() },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      { provide: SessionStore, useValue: sessionStore },
    ],
  });
}

// ── product-scoped unit picker ─────────────────────────────────────────────────

describe('RequisitionCreateComponent — product-scoped unit picker', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('line unit options are empty before a product is selected', async () => {
    const comp = TestBed.createComponent(RequisitionCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.lines()[0].lineUnitOptions).toHaveLength(0);
    expect(comp.lines()[0].unitUid).toBe('');
  });

  it('calls listProductUnits (not listUnits) when a product is selected on a line', async () => {
    const comp = TestBed.createComponent(RequisitionCreateComponent).componentInstance;
    const prodSvc = TestBed.inject(ProductService) as any;
    await vi.runAllTimersAsync();

    comp.onLineProductChange(0, 'PROD1');
    await vi.runAllTimersAsync();

    expect(prodSvc.listProductUnits).toHaveBeenCalledWith('PROD1');
    // listUnits must not exist on the mock — the company-wide load is gone.
    expect(prodSvc.listUnits).toBeUndefined();
  });

  it('populates lineUnitOptions with only that product units', async () => {
    const comp = TestBed.createComponent(RequisitionCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.onLineProductChange(0, 'PROD1');
    await vi.runAllTimersAsync();

    const opts = comp.lines()[0].lineUnitOptions;
    expect(opts).toHaveLength(2);
    expect(opts.map((o) => o.uid)).toEqual(['U1', 'U2']);
  });

  it('auto-selects the base unit (first returned) after product selection', async () => {
    const comp = TestBed.createComponent(RequisitionCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.onLineProductChange(0, 'PROD1');
    await vi.runAllTimersAsync();

    expect(comp.lines()[0].unitUid).toBe('U1');
  });

  it('clears the unit selection when the product changes on a line', async () => {
    const comp = TestBed.createComponent(RequisitionCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    // First product selection.
    comp.onLineProductChange(0, 'PROD1');
    await vi.runAllTimersAsync();
    expect(comp.lines()[0].unitUid).toBe('U1');

    // Change to no product — unit must be cleared.
    comp.onLineProductChange(0, '');
    expect(comp.lines()[0].unitUid).toBe('');
    expect(comp.lines()[0].lineUnitOptions).toHaveLength(0);
  });
});

// ── Number-input coercion regression ──────────────────────────────────────────

describe('RequisitionCreateComponent — number-input coercion', () => {
  beforeEach(() => { vi.useFakeTimers(); makeBed(); });
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('updateLine with numeric requestedQty (via template coercion) stores a string', async () => {
    const comp = TestBed.createComponent(RequisitionCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    // Template coerces via ('' + $event); simulate the same.
    comp.updateLine(0, { requestedQty: ('' + (10 as unknown as string)) || '' });

    expect(typeof comp.lines()[0].requestedQty).toBe('string');
    expect(comp.lines()[0].requestedQty).toBe('10');
  });

  it('updateLine with numeric estimatedUnitCost stores a string', async () => {
    const comp = TestBed.createComponent(RequisitionCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.updateLine(0, { estimatedUnitCost: ('' + (250.5 as unknown as string)) || '' });

    expect(typeof comp.lines()[0].estimatedUnitCost).toBe('string');
    expect(comp.lines()[0].estimatedUnitCost).toBe('250.5');
  });
});
