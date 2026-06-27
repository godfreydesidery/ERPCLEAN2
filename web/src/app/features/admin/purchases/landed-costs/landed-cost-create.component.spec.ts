/**
 * LandedCostCreateComponent — number-input coercion regression specs.
 *
 * Covers:
 *  1. updateChargeAmount with a NUMBER coerces to a string.
 *  2. submit() does not throw when amount was set as a number.
 *  3. submit() sends the correct stringified amount in the payload.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { PurchasesService } from '../purchases.service';
import { LandedCostService } from './landed-cost.service';
import { LandedCostCreateComponent } from './landed-cost-create.component';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_LANDED_COST = {
  uid: 'LC1', id: '1', landedCostNumber: 'LC-0001', status: 'DRAFT',
};

function makeBed(createSpy = vi.fn(() => of(STUB_LANDED_COST))) {
  TestBed.configureTestingModule({
    imports: [LandedCostCreateComponent],
    providers: [
      // Wildcard route so the post-submit router.navigate() resolves (avoids NG04002).
      provideRouter([{ path: '**', children: [] }]),
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      {
        provide: PurchasesService,
        useValue: {
          listReceipts: vi.fn(() => of({
            rows: [{ uid: 'GR1', receiptNumber: 'GR-0001', status: 'RECEIVED' }],
            meta: {},
          })),
        },
      },
      { provide: LandedCostService, useValue: { create: createSpy } },
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
  return { createSpy };
}

describe('LandedCostCreateComponent — number-input coercion', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. updateChargeAmount coerces number to string ────────────────────────

  it('updateChargeAmount coerces a numeric value to a string', async () => {
    makeBed();
    const comp = TestBed.createComponent(LandedCostCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    // Simulate ngModelChange emitting a number from type="number" input.
    comp.updateChargeAmount(0, 500 as unknown as string);

    expect(typeof comp.charges()[0].amount).toBe('string');
    expect(comp.charges()[0].amount).toBe('500');
  });

  // ── 2. submit() does not throw when amount was set as a number ────────────

  it('submit() does not throw when charge amount was set as a number', async () => {
    const { createSpy } = makeBed();
    const comp = TestBed.createComponent(LandedCostCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.selectedGrUids.set(['GR1']);
    comp.updateChargeAmount(0, 250.50 as unknown as string);

    expect(() => comp.submit()).not.toThrow();
    await vi.runAllTimersAsync();
  });

  // ── 3. Payload carries the stringified amount ──────────────────────────────

  it('submit() posts the stringified amount when it arrived as a number', async () => {
    const { createSpy } = makeBed();
    const comp = TestBed.createComponent(LandedCostCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.selectedGrUids.set(['GR1']);
    comp.updateChargeAmount(0, 1000 as unknown as string);

    comp.submit();
    await vi.runAllTimersAsync();

    expect(createSpy).toHaveBeenCalledOnce();
    const payload = (createSpy.mock.calls as any[][])[0][0];
    expect(payload.charges[0].amount).toBe('1000');
  });
});
