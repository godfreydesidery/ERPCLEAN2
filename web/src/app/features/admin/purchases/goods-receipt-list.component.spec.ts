/**
 * GoodsReceiptListComponent specs — the supplier and amount columns.
 *
 * Kilimanjaro 2026-09-12 #4: "Reports .. goods received ionyeshe supplier, date received and
 * amount in one window." The backend already returned `supplierName` and `receiptTotalAmount` on
 * this exact endpoint; the list simply never rendered them, so a storekeeper had to open every
 * receipt one at a time to answer "who delivered, when, and for how much".
 *
 * Covers:
 *  1. Supplier and amount render on the row, with the currency.
 *  2. A receipt with no resolvable supplier reads "—", not a blank cell.
 *  3. A null amount reads "—", never 0.00 — an unknown total is not a free delivery.
 *  4. Date received is still rendered (the third thing the client asked to see at a glance).
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PurchasesService } from './purchases.service';
import { GoodsReceiptListComponent } from './goods-receipt-list.component';

// ── Stubs ─────────────────────────────────────────────────────────────────────

const META = { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false };

/** `receiptTotalAmount` is a BigDecimal — a JSON number on the wire, despite the string typing. */
function receipt(overrides: Record<string, unknown> = {}) {
  return {
    id: '1', uid: 'GR1', companyId: '10', branchId: '1',
    purchaseOrderId: '7', purchaseOrderUid: 'PO1',
    receiptNumber: 'GRN-0001', status: 'RECEIVED',
    supplierId: '3', supplierName: 'Mbasha Holdings Ltd',
    receivedAt: '2026-09-10T08:30:00Z',
    voidedAt: null, voidReason: null, notes: null,
    currency: 'TZS', receiptTotalAmount: 450000,
    createdAt: null, lines: null,
    ...overrides,
  } as never;
}

function makeBed(rows: unknown[]) {
  TestBed.configureTestingModule({
    imports: [GoodsReceiptListComponent],
    providers: [
      provideRouter([]),
      {
        provide: PurchasesService,
        useValue: { listReceipts: vi.fn(() => of({ rows, meta: META })) },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ id: '10', uid: 'CO1', name: 'Kilimanjaro' }])) },
      },
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

async function render(rows: unknown[]) {
  makeBed(rows);
  const fixture = TestBed.createComponent(GoodsReceiptListComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture;
}

function cells(fixture: { nativeElement: HTMLElement }): string[] {
  return Array.from(fixture.nativeElement.querySelectorAll('tbody tr td'))
    .map((td) => td.textContent?.trim() ?? '');
}

describe('GoodsReceiptListComponent — supplier and amount', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('shows the supplier and the receipt total with its currency', async () => {
    const fixture = await render([receipt()]);

    const row = cells(fixture);
    expect(row).toContain('Mbasha Holdings Ltd');
    expect(row.some((c) => c.includes('450,000.00'))).toBe(true);
    expect(row.some((c) => c.includes('TZS'))).toBe(true);
  });

  it('renders the date received alongside them', async () => {
    const fixture = await render([receipt()]);

    expect(cells(fixture).some((c) => c.includes('10/09/2026'))).toBe(true);
  });

  it('reads an unresolvable supplier as a dash, not an empty cell', async () => {
    const fixture = await render([receipt({ supplierName: null })]);

    expect(cells(fixture)).toContain('—');
  });

  /** An unknown total is not a free delivery: it must not be coerced to 0.00. */
  it('reads a null total as unknown, never as zero', async () => {
    const fixture = await render([receipt({ receiptTotalAmount: null })]);

    const row = cells(fixture);
    expect(row.some((c) => c.includes('0.00'))).toBe(false);
    expect(row).toContain('—');
  });
});
