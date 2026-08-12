/**
 * Accessibility gate — EnterBillComponent (UAT 2026-08-12).
 *
 * Two states matter here, and both are new:
 *  1. the line editor once the goods-receipt picker is on screen — a labelled control per line;
 *  2. the match result, where a held line's status must be readable without seeing the colour,
 *     and an unchecked figure must announce itself as "not checked" rather than as a bare dash.
 *
 * Color-contrast and scrollable-region-focusable are disabled under jsdom (see the helper).
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierService } from '../parties/supplier.service';
import { PurchasesService } from '../purchases/purchases.service';
import { ApService } from './ap.service';
import { EnterBillComponent } from './enter-bill.component';
import { assertA11y } from '../../../../testing/a11y.helper';

const RECEIPTS = [
  {
    uid: 'GR-UID-1',
    receiptNumber: 'GRN-000123',
    status: 'RECEIVED',
    supplierId: 'SUP-ID-1',
    purchaseOrderUid: null,
    lines: [
      {
        uid: 'GRL-UID-1',
        productCode: 'WID-1',
        productName: 'Widget',
        unitName: 'PCS',
        receivedQty: 10,
        qtyInBase: 10,
      },
    ],
  },
];

function makeBed() {
  TestBed.configureTestingModule({
    imports: [EnterBillComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: ApService,
        useValue: { enterBill: vi.fn(), runMatch: vi.fn(), acceptVariance: vi.fn() },
      },
      { provide: SupplierService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      {
        provide: PurchasesService,
        useValue: {
          listOrders: vi.fn(() => of({ rows: [], meta: {} })),
          listOrderLines: vi.fn(() => of([])),
          listReceipts: vi.fn(() => of({ rows: RECEIPTS, meta: {} })),
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
}

describe('EnterBillComponent — a11y', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('has no axe violations with the goods-receipt picker on the line editor', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(EnterBillComponent);
    // Cast: the spec drives protected signals directly, which is the established pattern here.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const comp = fixture.componentInstance as any;
    await vi.runAllTimersAsync();
    comp.selectedCompanyId.set('10');
    comp.selectSupplier({ id: 'SUP-ID-1', uid: 'SUP1', code: 'S001', displayName: 'Acme Supplies' });
    vi.useRealTimers(); // restore before running axe (axe uses real timers internally)
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations on a held match result', async () => {
    makeBed();
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(EnterBillComponent);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const comp = fixture.componentInstance as any;
    await vi.runAllTimersAsync();
    comp.matchResult.set({
      billUid: 'B1',
      billStatus: 'HELD',
      lineResults: [
        {
          billLineId: '1',
          billLineUid: 'LINE-1',
          matchStatus: 'HELD_PRICE_VARIANCE',
          priceVarianceAmount: null,
          priceVariancePct: null,
          qtyVariance: null,
          poUnitCostAmount: null,
          grReceivedQty: null,
          billedQty: 15,
          matchedAt: null,
          comparisonPerformed: false,
          matchNote:
            'The goods receipt for this line could not be found, so the quantity billed could not '
            + 'be checked against what was received.',
        },
      ],
    });
    comp.matchState.set('done');
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
