/**
 * PurchaseOrderDetailComponent specs.
 *
 * Suite A — product-scoped unit picker behaviour (pre-existing):
 *  1. Selecting a product calls listProductUnits(productUid).
 *  2. Unit dropdown is populated with ONLY the returned units.
 *  3. First returned unit (base unit) is pre-selected after product pick.
 *  4. Unit list is cleared and pre-selection reset when a new product search starts.
 *  5. lineUnitsState reflects loading → idle lifecycle.
 *  6. lineUnitsState set to 'error' when listProductUnits fails.
 *
 * Suite B — Submit for Approval (F25):
 *  7.  showSubmitForApproval is true for a DRAFT PO with total >= threshold when gate enabled.
 *  8.  showSubmitForApproval is false when gate disabled.
 *  9.  showSubmitForApproval is false when total < threshold.
 * 10.  showSubmitForApproval is false when approval status is PENDING.
 * 11.  showSubmitForApproval is false when approval status is APPROVED.
 * 12.  canPlace is true when approval is not required (gate disabled).
 * 13.  canPlace is false for a DRAFT PO when approval required and status is null.
 * 14.  canPlace is true when approval status is APPROVED.
 * 15.  submitForApproval() calls purchasesService.submitForApproval with the PO uid.
 * 16.  submitForApproval() sets localApprovalStatus to PENDING on success.
 * 17.  submitForApproval() sets submitError on failure.
 * 18.  effectiveApprovalStatus prefers po().approvalStatus over localApprovalStatus.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { ProductService } from '../products/product.service';
import { PurchasesService } from './purchases.service';
import { PurchaseSettingsService } from './settings/purchase-settings.service';
import { DocumentsService } from '../documents/documents.service';
import { PurchaseOrderDetailComponent } from './purchase-order-detail.component';

// ── Fixtures ───────────────────────────────────────────────────────────────────

const STUB_PO = {
  uid: 'PO-UID-1', id: '1', companyId: '10', branchId: '1',
  orderNumber: 'PO-0001', status: 'DRAFT', supplierId: '2',
  supplierCode: 'SUP001', supplierName: 'Supplier A',
  currency: 'TZS',
  /** BigDecimal serialises as a string on the wire. */
  orderTotalAmount: '500000',
  expectedDate: null, notes: null, orderedAt: null,
  voidedAt: null, voidReason: null, closedAt: null, createdAt: null, lines: null,
};

const STUB_SETTINGS_ENABLED = {
  id: '1', uid: 'PS-1', companyId: '10',
  poApprovalEnabled: true,
  poApprovalThresholdAmount: '100000',
  currency: 'TZS',
};

const STUB_SETTINGS_DISABLED = {
  ...STUB_SETTINGS_ENABLED,
  poApprovalEnabled: false,
};

const STUB_SETTINGS_HIGH_THRESHOLD = {
  ...STUB_SETTINGS_ENABLED,
  poApprovalThresholdAmount: '1000000',
};

const STUB_UNITS = [
  { uid: 'U-EACH', id: '1', code: 'EA', name: 'Each', symbol: 'ea', status: 'ACTIVE' },
  { uid: 'U-BOX',  id: '2', code: 'BX', name: 'Box',  symbol: 'bx', status: 'ACTIVE' },
];

const STUB_PRODUCT = {
  uid: 'PROD-UID-1', id: '5', code: 'P001', name: 'Widget',
  status: 'ACTIVE', companyId: '10',
};

// ── Test-bed factory ───────────────────────────────────────────────────────────

interface BedOptions {
  listProductUnitsSpy?: ReturnType<typeof vi.fn>;
  submitForApprovalSpy?: ReturnType<typeof vi.fn>;
  settingsResponse?: object | 'error';
  poOverride?: Partial<typeof STUB_PO>;
}

function makeBed(opts: BedOptions = {}) {
  const listProductUnitsSpy =
    opts.listProductUnitsSpy ?? vi.fn(() => of(STUB_UNITS));

  const submitForApprovalSpy =
    opts.submitForApprovalSpy ?? vi.fn(() => of({ ...STUB_PO }));

  const po = { ...STUB_PO, ...opts.poOverride };

  const settingsSpy =
    opts.settingsResponse === 'error'
      ? vi.fn(() => throwError(() => new Error('settings unavailable')))
      : vi.fn(() => of(opts.settingsResponse ?? STUB_SETTINGS_ENABLED));

  TestBed.configureTestingModule({
    imports: [PurchaseOrderDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PurchasesService,
        useValue: {
          getOrderByUid: vi.fn(() => of(po)),
          listOrderLines: vi.fn(() => of([])),
          addLine: vi.fn(() => of({})),
          removeLine: vi.fn(() => of(undefined)),
          placeOrder: vi.fn(() => of(po)),
          closeOrder: vi.fn(() => of(po)),
          voidOrder: vi.fn(() => of(po)),
          submitForApproval: submitForApprovalSpy,
        },
      },
      {
        provide: PurchaseSettingsService,
        useValue: { getByCompany: settingsSpy },
      },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: {} })),
          listProductUnits: listProductUnitsSpy,
        },
      },
      {
        provide: DocumentsService,
        useValue: { renderBlob: vi.fn(() => of(new Blob())) },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal({ activeCompanyUid: 'CO-UID-1', isRoot: false }),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { listProductUnitsSpy, submitForApprovalSpy, settingsSpy };
}

// ── Suite A: unit-picker (pre-existing) ───────────────────────────────────────

describe('PurchaseOrderDetailComponent — product-scoped unit picker', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('calls listProductUnits with the selected product uid', async () => {
    const { listProductUnitsSpy } = makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);

    expect(listProductUnitsSpy).toHaveBeenCalledWith('PROD-UID-1');
  });

  it('populates lineUnits exclusively with units from listProductUnits', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.lineUnits()).toHaveLength(2);
    expect(comp.lineUnits().map((u) => u.uid)).toEqual(['U-EACH', 'U-BOX']);
  });

  it('pre-selects the first returned unit after product pick', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.newLineUnitUid()).toBe('U-EACH');
  });

  it('clears lineUnits and resets unit selection when product search changes', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();
    expect(comp.lineUnits().length).toBeGreaterThan(0);

    comp.onProductSearchChange('');
    expect(comp.lineUnits()).toHaveLength(0);
    expect(comp.newLineUnitUid()).toBe('');
    expect(comp.selectedProduct()).toBeNull();
  });

  it('lineUnitsState is idle after listProductUnits resolves successfully', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.lineUnitsState()).toBe('idle');
  });

  it('sets lineUnitsState to error when listProductUnits fails', async () => {
    const { listProductUnitsSpy } = makeBed({
      listProductUnitsSpy: vi.fn(() => throwError(() => new Error('network'))),
    });
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();

    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();

    expect(comp.lineUnitsState()).toBe('error');
    expect(listProductUnitsSpy).toHaveBeenCalled();
  });
});

// ── Suite B: Submit for Approval (F25) ────────────────────────────────────────

describe('PurchaseOrderDetailComponent — Submit for Approval (F25)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  async function setup(opts: BedOptions = {}) {
    const spies = makeBed(opts);
    const fixture = TestBed.createComponent(PurchaseOrderDetailComponent);
    fixture.componentRef.setInput('uid', 'PO-UID-1');
    const comp = fixture.componentInstance;
    await vi.runAllTimersAsync();
    return { comp, fixture, ...spies };
  }

  // ── 7. showSubmitForApproval true when gate enabled and total >= threshold ──

  it('showSubmitForApproval is true for DRAFT PO with total >= threshold (gate enabled)', async () => {
    const { comp } = await setup({ settingsResponse: STUB_SETTINGS_ENABLED });
    // orderTotalAmount = '500000', threshold = '100000'
    expect(comp.showSubmitForApproval()).toBe(true);
  });

  // ── 8. showSubmitForApproval false when gate disabled ─────────────────────

  it('showSubmitForApproval is false when PO approval gate is disabled', async () => {
    const { comp } = await setup({ settingsResponse: STUB_SETTINGS_DISABLED });
    expect(comp.showSubmitForApproval()).toBe(false);
  });

  // ── 9. showSubmitForApproval false when total < threshold ─────────────────

  it('showSubmitForApproval is false when order total is below approval threshold', async () => {
    const { comp } = await setup({ settingsResponse: STUB_SETTINGS_HIGH_THRESHOLD });
    // orderTotalAmount = '500000', threshold = '1000000'
    expect(comp.showSubmitForApproval()).toBe(false);
  });

  // ── 10. showSubmitForApproval false when status is PENDING ────────────────

  it('showSubmitForApproval is false when approval status is PENDING', async () => {
    const { comp } = await setup({ settingsResponse: STUB_SETTINGS_ENABLED });
    comp.localApprovalStatus.set('PENDING');
    expect(comp.showSubmitForApproval()).toBe(false);
  });

  // ── 11. showSubmitForApproval false when status is APPROVED ───────────────

  it('showSubmitForApproval is false when approval status is APPROVED', async () => {
    const { comp } = await setup({ settingsResponse: STUB_SETTINGS_ENABLED });
    comp.localApprovalStatus.set('APPROVED');
    expect(comp.showSubmitForApproval()).toBe(false);
  });

  // ── 12. canPlace true when gate disabled (approval not required) ───────────

  it('canPlace is true when approval gate is disabled and PO has lines', async () => {
    const { comp } = await setup({ settingsResponse: STUB_SETTINGS_DISABLED });
    // Inject a line so hasLines() is true
    comp.lines.set([{ uid: 'L1' } as any]);
    expect(comp.canPlace()).toBe(true);
  });

  // ── 13. canPlace false when approval required and status not yet set ───────

  it('canPlace is false when approval is required and approval status is null', async () => {
    const { comp } = await setup({ settingsResponse: STUB_SETTINGS_ENABLED });
    comp.lines.set([{ uid: 'L1' } as any]);
    // localApprovalStatus defaults to null — no approval yet
    expect(comp.localApprovalStatus()).toBeNull();
    expect(comp.canPlace()).toBe(false);
  });

  // ── 14. canPlace true when approval status is APPROVED ────────────────────

  it('canPlace is true when approval status is APPROVED', async () => {
    const { comp } = await setup({ settingsResponse: STUB_SETTINGS_ENABLED });
    comp.lines.set([{ uid: 'L1' } as any]);
    comp.localApprovalStatus.set('APPROVED');
    expect(comp.canPlace()).toBe(true);
  });

  // ── 15. submitForApproval() calls the service with the PO uid ─────────────

  it('calls purchasesService.submitForApproval with the PO uid', async () => {
    const { comp, submitForApprovalSpy } = await setup({ settingsResponse: STUB_SETTINGS_ENABLED });
    comp.submitForApproval();
    await vi.runAllTimersAsync();
    expect(submitForApprovalSpy).toHaveBeenCalledWith('PO-UID-1');
  });

  // ── 16. submitForApproval() sets localApprovalStatus to PENDING on success ─

  it('sets localApprovalStatus to PENDING after successful submission', async () => {
    const { comp } = await setup({ settingsResponse: STUB_SETTINGS_ENABLED });
    comp.submitForApproval();
    await vi.runAllTimersAsync();
    expect(comp.localApprovalStatus()).toBe('PENDING');
  });

  // ── 17. submitForApproval() surfaces error on failure ─────────────────────

  it('sets submitError when submitForApproval fails', async () => {
    const errResponse = new HttpErrorResponse({
      error: { errors: ['Something went wrong. Please try again.'] },
      status: 409,
    });
    const { comp } = await setup({
      settingsResponse: STUB_SETTINGS_ENABLED,
      submitForApprovalSpy: vi.fn(() => throwError(() => errResponse)),
    });

    comp.submitForApproval();
    await vi.runAllTimersAsync();

    expect(comp.submitError()).toBe('Something went wrong. Please try again.');
    expect(comp.localApprovalStatus()).toBeNull();
  });

  // ── 18. effectiveApprovalStatus: DTO field takes precedence over local signal

  it('effectiveApprovalStatus prefers po().approvalStatus over localApprovalStatus', async () => {
    const { comp } = await setup({
      settingsResponse: STUB_SETTINGS_ENABLED,
      poOverride: { approvalStatus: 'APPROVED' } as any,
    });
    comp.localApprovalStatus.set('PENDING');
    // The DTO field should win
    expect(comp.effectiveApprovalStatus()).toBe('APPROVED');
  });
});
