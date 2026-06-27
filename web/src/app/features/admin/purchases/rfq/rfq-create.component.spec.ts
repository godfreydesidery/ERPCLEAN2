/**
 * RfqCreateComponent — number-input coercion regression specs.
 *
 * Covers:
 *  1. onQtyChange with a NUMBER (simulating type="number" ngModelChange) stores a string.
 *  2. submit() does not throw when qty was set as a number and sends the trimmed string value.
 *  3. submit() validates correctly when qty is zero (stored as number).
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { SupplierService } from '../../parties/supplier.service';
import { RfqService } from './rfq.service';
import { RfqCreateComponent } from './rfq-create.component';

const STUB_ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const STUB_COMPANY = { uid: 'CO1', id: '10', name: 'Main Co' };
const STUB_RFQ = {
  uid: 'RFQ1', id: '1', rfqNumber: 'RFQ-0001', status: 'DRAFT' as const,
  companyId: '10', branchId: '1', sourceRequisitionUid: null,
  responseDueDate: null, awardedQuoteUid: null, awardedPoUid: null,
  notes: null, sentAt: null, awardedAt: null, cancelledAt: null, createdAt: null,
  lines: [], supplierUids: [],
};

function makeBed(createRfqSpy = vi.fn(() => of(STUB_RFQ))) {
  TestBed.configureTestingModule({
    imports: [RfqCreateComponent],
    providers: [
      // Wildcard route so the post-submit router.navigate() resolves (avoids NG04002).
      provideRouter([{ path: '**', children: [] }]),
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(STUB_ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([STUB_COMPANY])) } },
      {
        provide: ProductService,
        useValue: {
          list: vi.fn(() => of({ rows: [], meta: {} })),
          listUnits: vi.fn(() => of({ rows: [], meta: {} })),
          listProductUnits: vi.fn(() => of([])),
        },
      },
      {
        provide: SupplierService,
        useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) },
      },
      { provide: RfqService, useValue: { createRfq: createRfqSpy } },
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
  return { createRfqSpy };
}

describe('RfqCreateComponent — number-input coercion', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  // ── 1. onQtyChange stores a string even when called with a number ─────────

  it('onQtyChange coerces a numeric value to a string', async () => {
    makeBed();
    const comp = TestBed.createComponent(RfqCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    // Simulate ngModelChange emitting a number (what type="number" does at runtime).
    comp.onQtyChange(0, 5 as unknown as string);

    expect(typeof comp.lines()[0].quantity).toBe('string');
    expect(comp.lines()[0].quantity).toBe('5');
  });

  // ── 2. submit() does not throw when qty arrived as a number ──────────────

  it('submit() does not throw when qty was set as a number and sends stringified value', async () => {
    const { createRfqSpy } = makeBed();
    const comp = TestBed.createComponent(RfqCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    // Wire up a line with product + unit ids so submit can proceed past those guards.
    comp.lines.update((ls) =>
      ls.map((l) => ({ ...l, productUid: 'P1', productId: '20', unitUid: 'U1', unitId: '1' })),
    );
    // Simulate numeric value from number input (user didn't re-type).
    comp.onQtyChange(0, 10 as unknown as string);

    comp.invitedSupplierUids.set(['SUP1']);
    comp.selectedCompanyUid.set('CO1');

    expect(() => comp.submit()).not.toThrow();
    await vi.runAllTimersAsync();

    expect(createRfqSpy).toHaveBeenCalledOnce();
    const payload = (createRfqSpy.mock.calls as any[][])[0][0];
    expect(payload.lines[0].quantity).toBe('10');
  });

  // ── 3. submit() rejects zero when qty arrived as a number ────────────────

  it('submit() sets formError when qty is zero even if set as a number', async () => {
    const { createRfqSpy } = makeBed();
    const comp = TestBed.createComponent(RfqCreateComponent).componentInstance;
    await vi.runAllTimersAsync();

    comp.lines.update((ls) =>
      ls.map((l) => ({ ...l, productUid: 'P1', productId: '20', unitUid: 'U1', unitId: '1' })),
    );
    comp.onQtyChange(0, 0 as unknown as string);
    comp.invitedSupplierUids.set(['SUP1']);
    comp.selectedCompanyUid.set('CO1');

    expect(() => comp.submit()).not.toThrow();

    expect(createRfqSpy).not.toHaveBeenCalled();
    expect(comp.formError()).toMatch(/quantity must be greater than zero/i);
  });
});
