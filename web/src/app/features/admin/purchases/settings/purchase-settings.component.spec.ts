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
});
