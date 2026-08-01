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
 * 19.  canPlace is true (and Place Order renders, enabled) from po().approvalStatus alone
 *      ('APPROVED' from the DTO — no local override needed).
 * 20.  canPlace is false and the awaiting-approval banner renders (no Place Order button) when
 *      po().approvalStatus is 'PENDING'.
 *
 * Suite C — unit-cost suggestion (SAM client feedback 2026-08):
 * 21.  Picking a product looks the suggestion up for the PO + product + defaulted unit.
 * 22.  A suggestion pre-fills the unit-cost box.
 * 23.  No suggestion leaves the box blank and shows no hint (and no error).
 * 24.  A cost the buyer typed is never overwritten by a later suggestion.
 * 25.  Changing the unit re-looks-up the suggestion for the new unit.
 * 25b. A pre-filled price is cleared when the new unit has no stored price (no stale carton
 *      price left on a line now ordered per piece).
 * 26.  costCurrencyMismatch flags a suggestion quoted in another currency.
 * 27.  The hint renders and is wired to the input via aria-describedby.
 * 28.  A failed lookup is silent: no hint, the typed cost untouched.
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

/** BigDecimal serialises as a JSON *number*; asOf is an ISO date. */
const STUB_SUGGESTION = {
  amount: 12500.5, currency: 'TZS', source: 'LAST_PURCHASE' as const, asOf: '2026-07-12',
};

// ── Test-bed factory ───────────────────────────────────────────────────────────

interface BedOptions {
  listProductUnitsSpy?: ReturnType<typeof vi.fn>;
  submitForApprovalSpy?: ReturnType<typeof vi.fn>;
  costSuggestionSpy?: ReturnType<typeof vi.fn>;
  settingsResponse?: object | 'error';
  poOverride?: Partial<typeof STUB_PO>;
}

function makeBed(opts: BedOptions = {}) {
  const listProductUnitsSpy =
    opts.listProductUnitsSpy ?? vi.fn(() => of(STUB_UNITS));

  const submitForApprovalSpy =
    opts.submitForApprovalSpy ?? vi.fn(() => of({ ...STUB_PO }));

  // Default: no stored price for the product — the suites that don't exercise the suggestion
  // then behave exactly as before (blank cost box, no hint).
  const costSuggestionSpy = opts.costSuggestionSpy ?? vi.fn(() => of(null));

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
          costSuggestion: costSuggestionSpy,
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

  return { listProductUnitsSpy, submitForApprovalSpy, settingsSpy, costSuggestionSpy };
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

  // ── 19. canPlace true + Place Order renders from the DTO field alone (APPROVED) ─

  it('canPlace is true and Place Order renders enabled when po().approvalStatus is APPROVED', async () => {
    const { comp, fixture } = await setup({
      settingsResponse: STUB_SETTINGS_ENABLED,
      poOverride: { approvalStatus: 'APPROVED' } as any,
    });
    comp.lines.set([{ uid: 'L1' } as any]);
    fixture.detectChanges();

    expect(comp.effectiveApprovalStatus()).toBe('APPROVED');
    expect(comp.canPlace()).toBe(true);

    const buttons: HTMLButtonElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    );
    const placeBtn = buttons.find((b) => b.textContent?.includes('Place Order'));
    expect(placeBtn).toBeTruthy();
    expect(placeBtn!.disabled).toBe(false);
  });

  // ── 20. canPlace false + awaiting-approval banner renders (PENDING, no local override) ─

  it('canPlace is false and shows the awaiting-approval banner when po().approvalStatus is PENDING', async () => {
    const { comp, fixture } = await setup({
      settingsResponse: STUB_SETTINGS_ENABLED,
      poOverride: { approvalStatus: 'PENDING' } as any,
    });
    comp.lines.set([{ uid: 'L1' } as any]);
    fixture.detectChanges();

    expect(comp.effectiveApprovalStatus()).toBe('PENDING');
    expect(comp.canPlace()).toBe(false);

    const buttons: HTMLButtonElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    );
    expect(buttons.some((b) => b.textContent?.includes('Place Order'))).toBe(false);

    const banner = fixture.nativeElement.querySelector(
      '[aria-label="This order is awaiting approval"]',
    );
    expect(banner).toBeTruthy();
    expect(banner.textContent).toContain('Awaiting approval');
  });
});

// ── Suite C: unit-cost suggestion (SAM client feedback 2026-08) ───────────────

describe('PurchaseOrderDetailComponent — unit-cost suggestion', () => {
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

  /** Pick a product and let the unit list + suggestion lookup settle. */
  async function pickProduct(comp: PurchaseOrderDetailComponent) {
    comp.selectProduct(STUB_PRODUCT as any);
    await vi.runAllTimersAsync();
  }

  // ── 21. looked up for the PO + product + the unit that was defaulted ────────

  it('looks the suggestion up for the PO, product and defaulted unit after a product pick', async () => {
    const { comp, costSuggestionSpy } = await setup({
      costSuggestionSpy: vi.fn(() => of(STUB_SUGGESTION)),
    });
    await pickProduct(comp);

    expect(costSuggestionSpy).toHaveBeenCalledWith('PO-UID-1', 'PROD-UID-1', 'U-EACH');
  });

  // ── 22. a suggestion pre-fills the cost box ────────────────────────────────

  it('pre-fills the unit cost from the suggestion', async () => {
    const { comp } = await setup({ costSuggestionSpy: vi.fn(() => of(STUB_SUGGESTION)) });
    await pickProduct(comp);

    expect(comp.newLineUnitCost()).toBe('12500.5');
    expect(comp.costSuggestion()).toEqual(STUB_SUGGESTION);
  });

  // ── 23. nothing found → blank field, no hint, no error ─────────────────────

  it('leaves the unit cost blank and shows no hint when nothing is found', async () => {
    const { comp, fixture } = await setup({ costSuggestionSpy: vi.fn(() => of(null)) });
    await pickProduct(comp);
    fixture.detectChanges();

    expect(comp.costSuggestion()).toBeNull();
    expect(comp.newLineUnitCost()).toBe('');
    expect(fixture.nativeElement.querySelector('#lineUnitCostHint')).toBeNull();
    expect(comp.lineFormError()).toBeNull();
  });

  // ── 24. a typed cost is never overwritten ──────────────────────────────────

  it('does not overwrite a unit cost the buyer typed', async () => {
    const { comp } = await setup({ costSuggestionSpy: vi.fn(() => of(STUB_SUGGESTION)) });
    await pickProduct(comp);

    comp.onUnitCostChange('999');
    comp.onLineUnitChange('U-BOX');
    await vi.runAllTimersAsync();

    expect(comp.newLineUnitCost()).toBe('999');
    // The hint still reports where the (unapplied) suggestion came from.
    expect(comp.costSuggestion()).toEqual(STUB_SUGGESTION);
  });

  // ── 25. a unit change re-looks-up the price ────────────────────────────────

  it('re-looks-up the suggestion when the unit changes', async () => {
    const { comp, costSuggestionSpy } = await setup({
      costSuggestionSpy: vi.fn(() => of(STUB_SUGGESTION)),
    });
    await pickProduct(comp);

    comp.onLineUnitChange('U-BOX');
    await vi.runAllTimersAsync();

    expect(comp.newLineUnitUid()).toBe('U-BOX');
    expect(costSuggestionSpy).toHaveBeenLastCalledWith('PO-UID-1', 'PROD-UID-1', 'U-BOX');
  });

  // ── 25b. a pre-filled price never survives a unit change that finds nothing ─

  it('clears a pre-filled cost when the new unit has no stored price', async () => {
    const costSuggestionSpy = vi
      .fn()
      .mockReturnValueOnce(of(STUB_SUGGESTION))   // for the defaulted base unit
      .mockReturnValueOnce(of(null));             // nothing quoted/bought in the pack unit
    const { comp } = await setup({ costSuggestionSpy });
    await pickProduct(comp);
    expect(comp.newLineUnitCost()).toBe('12500.5');

    comp.onLineUnitChange('U-BOX');
    await vi.runAllTimersAsync();

    expect(comp.newLineUnitCost()).toBe('');
    expect(comp.costSuggestion()).toBeNull();
  });

  // ── 25c. a pre-filled price never survives a PRODUCT change (regression) ───

  it('clears a pre-filled cost when the buyer switches product', async () => {
    const costSuggestionSpy = vi
      .fn()
      .mockReturnValueOnce(of(STUB_SUGGESTION))                          // first product: 12500.5
      .mockReturnValueOnce(of({ ...STUB_SUGGESTION, amount: 4500 }));    // second product: 4500
    const { comp } = await setup({ costSuggestionSpy });
    await pickProduct(comp);
    expect(comp.newLineUnitCost()).toBe('12500.5');

    // Typing in the product box to pick a different product must not strand the first product's
    // price in the cost field — a non-empty box would also block the new suggestion from applying.
    comp.onProductSearchChange('nails');
    await vi.runAllTimersAsync();
    expect(comp.newLineUnitCost()).toBe('');

    await pickProduct(comp);

    expect(comp.newLineUnitCost()).toBe('4500');
  });

  // ── 26. foreign-currency suggestion is flagged, not converted ──────────────

  it('flags a suggestion priced in a different currency from the order', async () => {
    const { comp } = await setup({
      costSuggestionSpy: vi.fn(() => of({ ...STUB_SUGGESTION, currency: 'USD' })),
    });
    await pickProduct(comp);

    // STUB_PO is in TZS
    expect(comp.costCurrencyMismatch()).toBe(true);
  });

  // ── 27. hint renders and describes the input ───────────────────────────────

  it('renders the provenance hint and links it to the cost input via aria-describedby', async () => {
    const { comp, fixture } = await setup({
      costSuggestionSpy: vi.fn(() => of(STUB_SUGGESTION)),
    });
    await pickProduct(comp);
    fixture.detectChanges();

    const hint: HTMLElement = fixture.nativeElement.querySelector('#lineUnitCostHint');
    expect(hint).toBeTruthy();
    expect(hint.textContent).toContain('From the last purchase');
    expect(hint.textContent).toContain('12 Jul 2026');

    const input: HTMLInputElement = fixture.nativeElement.querySelector('#lineUnitCost');
    expect(input.getAttribute('aria-describedby')).toBe('lineUnitCostHint');
  });

  // ── 28. a failed lookup is silent ──────────────────────────────────────────

  it('stays silent when the lookup fails', async () => {
    const { comp, fixture } = await setup({
      costSuggestionSpy: vi.fn(() => throwError(() => new Error('network'))),
    });
    await pickProduct(comp);
    fixture.detectChanges();

    expect(comp.costSuggestion()).toBeNull();
    expect(comp.newLineUnitCost()).toBe('');
    expect(comp.lineFormError()).toBeNull();
    expect(fixture.nativeElement.querySelector('#lineUnitCostHint')).toBeNull();
  });
});
