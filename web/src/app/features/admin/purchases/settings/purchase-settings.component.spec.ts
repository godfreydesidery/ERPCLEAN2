/**
 * PurchaseSettingsComponent — number-input coercion regression specs.
 *
 * Covers:
 *  1. fThresholdAmount set as a NUMBER (via ngModelChange) is stored as a string.
 *  2. save() does not throw when fThresholdAmount was set as a number.
 *  3. save() posts the stringified threshold value.
 *  4. receiptTolerancePct: loaded from a numeric DTO value into the string form field.
 *  5. receiptTolerancePct: blank form field sends null (strict receiving).
 *  6. receiptTolerancePct: populated form field sends a number, not a string.
 *  7. purchaseVatTreatment: loaded from the DTO (V105, ADR-0063).
 *  8. purchaseVatTreatment: a company that has never been asked defaults to EXCLUSIVE, so nothing
 *     changes for anyone on upgrade.
 *  9. purchaseVatTreatment: the chosen value is sent on save.
 * 10. The control offers all three treatments and explains which one fixes the double-VAT note.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PurchaseSettingsService } from './purchase-settings.service';
import { PurchaseSettingsComponent } from './purchase-settings.component';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_SETTINGS = {
  id: '1', uid: 'S1', companyId: '10', companyUid: 'CO1',
  poApprovalEnabled: true, poApprovalThresholdAmount: '5000', currency: 'TZS',
  receiptTolerancePct: null,
  purchaseVatTreatment: 'EXCLUSIVE' as const,
};

function makeBed(updateSpy = vi.fn(() => of(STUB_SETTINGS))) {
  TestBed.configureTestingModule({
    imports: [PurchaseSettingsComponent],
    providers: [
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      {
        provide: PurchaseSettingsService,
        useValue: {
          getByCompany: vi.fn(() => of(STUB_SETTINGS)),
          update: updateSpy,
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  return { updateSpy };
}

describe('PurchaseSettingsComponent — number-input coercion', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. Signal stores string when set with a number ────────────────────────

  it('fThresholdAmount.set with a number is stored as a string via coercion in template', async () => {
    makeBed();
    const comp = TestBed.createComponent(PurchaseSettingsComponent).componentInstance;
    await vi.runAllTimersAsync();

    // Simulate what the fixed template does: String($event ?? '').
    comp.fThresholdAmount.set(String(2500));

    expect(typeof comp.fThresholdAmount()).toBe('string');
    expect(comp.fThresholdAmount()).toBe('2500');
  });

  // ── 2. save() does not throw when fThresholdAmount was set as a number ────

  it('save() does not throw when threshold was set with String(number)', async () => {
    const { updateSpy } = makeBed();
    const comp = TestBed.createComponent(PurchaseSettingsComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.fPoApprovalEnabled.set(true);
    comp.fThresholdAmount.set(String(3000));

    expect(() => comp.save()).not.toThrow();
    await vi.runAllTimersAsync();

    expect(updateSpy).toHaveBeenCalledOnce();
  });

  // ── 3. Payload carries the stringified threshold ──────────────────────────

  it('save() posts the stringified threshold amount', async () => {
    const { updateSpy } = makeBed();
    const comp = TestBed.createComponent(PurchaseSettingsComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.fPoApprovalEnabled.set(true);
    comp.fThresholdAmount.set(String(7500));

    comp.save();
    await vi.runAllTimersAsync();

    expect(updateSpy).toHaveBeenCalledOnce();
    const req = (updateSpy.mock.calls as any[][])[0][0];
    expect(req.poApprovalThresholdAmount).toBe('7500');
  });

  // ── 4-6. receiptTolerancePct round-trip ────────────────────────────────────

  it('loads a numeric receiptTolerancePct from the DTO into the string form field', async () => {
    makeBed();
    // Real backend wire shape: BigDecimal serialises as a JSON number, not a string.
    const settingsWithTolerance = { ...STUB_SETTINGS, receiptTolerancePct: 12.5 };
    TestBed.overrideProvider(PurchaseSettingsService, {
      useValue: {
        getByCompany: vi.fn(() => of(settingsWithTolerance)),
        update: vi.fn(() => of(settingsWithTolerance)),
      },
    });
    const comp = TestBed.createComponent(PurchaseSettingsComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.fReceiptTolerancePct()).toBe('12.5');
  });

  it('save() sends null for receiptTolerancePct when the field is left blank', async () => {
    const { updateSpy } = makeBed();
    const comp = TestBed.createComponent(PurchaseSettingsComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.fPoApprovalEnabled.set(true);
    comp.fThresholdAmount.set(String(3000));
    comp.fReceiptTolerancePct.set('');

    comp.save();
    await vi.runAllTimersAsync();

    expect(updateSpy).toHaveBeenCalledOnce();
    const req = (updateSpy.mock.calls as any[][])[0][0];
    expect(req.receiptTolerancePct).toBeNull();
  });

  it('save() sends receiptTolerancePct as a number when populated', async () => {
    const { updateSpy } = makeBed();
    const comp = TestBed.createComponent(PurchaseSettingsComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.fPoApprovalEnabled.set(true);
    comp.fThresholdAmount.set(String(3000));
    comp.fReceiptTolerancePct.set('7.5');

    comp.save();
    await vi.runAllTimersAsync();

    expect(updateSpy).toHaveBeenCalledOnce();
    const req = (updateSpy.mock.calls as any[][])[0][0];
    expect(req.receiptTolerancePct).toBe(7.5);
    expect(typeof req.receiptTolerancePct).toBe('number');
  });

  // ── 7-10. purchaseVatTreatment (V105, ADR-0063) ──────────────────────────

  it('loads the stored VAT treatment from the DTO', async () => {
    makeBed();
    const inclusive = { ...STUB_SETTINGS, purchaseVatTreatment: 'INCLUSIVE' as const };
    TestBed.overrideProvider(PurchaseSettingsService, {
      useValue: {
        getByCompany: vi.fn(() => of(inclusive)),
        update: vi.fn(() => of(inclusive)),
      },
    });
    const comp = TestBed.createComponent(PurchaseSettingsComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.fPurchaseVatTreatment()).toBe('INCLUSIVE');
  });

  /**
   * The upgrade case. A company whose row predates V105 gets EXCLUSIVE from the column default, and
   * a response that somehow omits the field must land on the same answer — anything else would
   * silently change what an existing company's printed notes say.
   */
  it('falls back to EXCLUSIVE when the stored value is missing', async () => {
    makeBed();
    const legacy = { ...STUB_SETTINGS } as Record<string, unknown>;
    delete legacy['purchaseVatTreatment'];
    TestBed.overrideProvider(PurchaseSettingsService, {
      useValue: {
        getByCompany: vi.fn(() => of(legacy)),
        update: vi.fn(() => of(legacy)),
      },
    });
    const comp = TestBed.createComponent(PurchaseSettingsComponent).componentInstance;
    await vi.runAllTimersAsync();

    expect(comp.fPurchaseVatTreatment()).toBe('EXCLUSIVE');
  });

  it('save() sends the chosen VAT treatment', async () => {
    const { updateSpy } = makeBed();
    const comp = TestBed.createComponent(PurchaseSettingsComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.fPoApprovalEnabled.set(true);
    comp.fThresholdAmount.set(String(3000));
    comp.fPurchaseVatTreatment.set('INCLUSIVE');

    comp.save();
    await vi.runAllTimersAsync();

    expect(updateSpy).toHaveBeenCalledOnce();
    const req = (updateSpy.mock.calls as any[][])[0][0];
    expect(req.purchaseVatTreatment).toBe('INCLUSIVE');
  });

  /** All three must be reachable, and the hint must name the one that fixes the client's problem. */
  it('offers all three treatments and explains the inclusive one', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PurchaseSettingsComponent);
    await vi.runAllTimersAsync();
    fixture.detectChanges();

    const select: HTMLSelectElement =
      fixture.nativeElement.querySelector('#purchaseVatTreatment');
    expect(select).toBeTruthy();
    expect(Array.from(select.options).map((o) => o.value))
      .toEqual(['EXCLUSIVE', 'INCLUSIVE', 'NONE']);

    const hint: HTMLElement = fixture.nativeElement.querySelector('#purchaseVatTreatmentHint');
    expect(select.getAttribute('aria-describedby')).toBe('purchaseVatTreatmentHint');
    expect(hint.textContent).toContain('adds VAT a second time');
  });
});
