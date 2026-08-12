/**
 * GoodsReceiptDetailComponent specs.
 *
 * The client asked for the GRN to show the supplier and the value received; the PDF already did,
 * the screen did not. Covers:
 *  1. Supplier name renders in the header.
 *  2. The receipt total renders in the lines-table footer, currency-prefixed and formatted.
 *  3. Print PDF button hidden without DOCUMENT.RENDER.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DocumentsService } from '../documents/documents.service';
import { PurchasesService } from './purchases.service';
import { GoodsReceiptDetailComponent } from './goods-receipt-detail.component';

// ── Stubs ──────────────────────────────────────────────────────────────────────

const STUB_LINE = {
  id: '31', uid: 'GRL-UID-1', goodsReceiptId: '21', purchaseOrderLineId: '11', lineNo: 1,
  productId: '5', productCode: 'P001', productName: 'Widget',
  unitId: '1', unitName: 'Each',
  receivedQty: '60', qtyInBase: '60',
  unitCostAmount: '500', lineCostAmount: '30000', currency: 'TZS',
  lotNumber: null, manufactureDate: null, expiryDate: null, serialNumbers: [],
};

const STUB_GR = {
  id: '21', uid: 'GR-UID-1', companyId: '10', branchId: '1',
  purchaseOrderId: '1', purchaseOrderUid: 'PO-UID-1',
  receiptNumber: 'GR-0001', status: 'RECEIVED',
  supplierId: '2', supplierName: 'Supplier A',
  receivedAt: '2026-08-08T10:00:00Z', voidedAt: null, voidReason: null, notes: null,
  currency: 'TZS',
  /** BigDecimal serialises as a JSON *number*, not a string — the total must survive that. */
  receiptTotalAmount: 30000 as unknown as string,
  createdAt: null, lines: [STUB_LINE],
};

function makeBed(overrides: {
  gr?: typeof STUB_GR;
  hasPermission?: ReturnType<typeof vi.fn>;
} = {}) {
  const gr = overrides.gr ?? STUB_GR;
  const hasPermission = overrides.hasPermission ?? vi.fn(() => true);
  const getReceiptByUidSpy = vi.fn(() => of(gr));

  TestBed.configureTestingModule({
    imports: [GoodsReceiptDetailComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: PurchasesService,
        useValue: {
          getReceiptByUid: getReceiptByUidSpy,
          voidReceipt: vi.fn(() => of(undefined)),
        },
      },
      {
        provide: DocumentsService,
        useValue: { renderBlob: vi.fn(() => of(new Blob(['%PDF']))) },
      },
      {
        provide: AlertService,
        useValue: { success: vi.fn(), error: vi.fn() },
      },
      {
        provide: SessionStore,
        useValue: {
          hasPermission,
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { getReceiptByUidSpy };
}

async function setup(overrides: Parameters<typeof makeBed>[0] = {}) {
  const spies = makeBed(overrides);
  const fixture = TestBed.createComponent(GoodsReceiptDetailComponent);
  fixture.componentRef.setInput('uid', 'GR-UID-1');
  await vi.runAllTimersAsync();
  fixture.detectChanges();
  return { fixture, comp: fixture.componentInstance, ...spies };
}

// ── Specs ──────────────────────────────────────────────────────────────────────

describe('GoodsReceiptDetailComponent', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('renders the supplier name in the header', async () => {
    const { fixture, getReceiptByUidSpy } = await setup();

    expect(getReceiptByUidSpy).toHaveBeenCalledWith('GR-UID-1');
    const header: HTMLElement = fixture.nativeElement.querySelector('.page-head');
    expect(header.textContent).toContain('Supplier');
    expect(header.textContent).toContain('Supplier A');
  });

  it('renders the receipt total in the lines-table footer', async () => {
    const { fixture } = await setup();

    const foot: HTMLElement = fixture.nativeElement.querySelector('table.erp-table tfoot');
    expect(foot).toBeTruthy();
    expect(foot.textContent).toContain('Total Received Value');
    expect(foot.textContent).toContain('TZS');
    expect(foot.textContent).toContain('30,000.00');
  });

  it('hides the Print PDF button when the user lacks DOCUMENT.RENDER', async () => {
    const { fixture } = await setup({
      hasPermission: vi.fn((code: string) => code !== 'DOCUMENT.RENDER'),
    });

    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    expect(buttons.some((b) => b.textContent?.includes('Print PDF'))).toBe(false);
  });
});
