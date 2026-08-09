import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';

import { SupplierBillsListComponent } from './supplier-bills-list.component';
import { ApService } from './ap.service';
import { SupplierService } from '../parties/supplier.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DirectReceiptRatificationState, SupplierBillDto } from './models/ap.model';
import { PageMeta } from '../../../core/api/api-response.model';

const META: PageMeta = { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false };

function makeSession() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBill(
  n: number,
  ratification?: DirectReceiptRatificationState | null,
): SupplierBillDto {
  return {
    id: String(n),
    uid: `bill-uid-${n}`,
    companyId: '1',
    branchId: '1',
    supplierId: '1',
    billNumber: `BILL-00${n}`,
    supplierInvoiceNo: `INV-${n}`,
    source: 'PURCHASE_ORDER',
    purchaseOrderUid: null,
    billDate: '2026-08-01',
    dueDate: '2026-08-31',
    netAmount: '1000.00',
    vatAmount: '0.00',
    grossAmount: '1000.00',
    outstandingAmount: '1000.00',
    currency: 'TZS',
    status: 'MATCHED',
    postedGlEntryUid: null,
    directReceiptRatification: ratification,
    lines: [],
  };
}

async function mount(rows: SupplierBillDto[]): Promise<HTMLElement> {
  const api = TestBed.inject(ApService) as unknown as { listBills: ReturnType<typeof vi.fn> };
  api.listBills.mockReturnValue(of({ rows, meta: META }));
  const fixture = TestBed.createComponent(SupplierBillsListComponent);
  fixture.detectChanges();
  await vi.runAllTimersAsync();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('SupplierBillsListComponent — direct-receipt ratification chip', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [SupplierBillsListComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SessionStore, useValue: makeSession() },
        {
          provide: OrganisationService,
          useValue: { current: () => of({ uid: 'org-1', name: 'Test Org' }) },
        },
        {
          provide: CompanyService,
          useValue: { list: () => of([{ id: '1', uid: 'co-1', name: 'Acme' }]) },
        },
        {
          provide: ApService,
          useValue: {
            listBills: vi.fn(() => of({ rows: [], meta: META })),
            raiseDebitNote: vi.fn(),
          },
        },
        { provide: SupplierService, useValue: { list: vi.fn(() => of({ rows: [], meta: META })) } },
        { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('adds no chip to an ordinary bill row', async () => {
    const host = await mount([makeBill(1, 'NOT_APPLICABLE'), makeBill(2, null)]);
    expect(host.querySelector('app-direct-receipt-ratification .status-tag')).toBeNull();
    expect(host.textContent).not.toContain('atification');
  });

  it('shows a warning chip on a bill awaiting ratification', async () => {
    const host = await mount([makeBill(1, 'AWAITING_RATIFICATION')]);
    const chip = host.querySelector('app-direct-receipt-ratification .status-tag');
    expect(chip).not.toBeNull();
    expect(chip!.classList.contains('status-tag--warn')).toBe(true);
    expect(chip!.textContent).toContain('Awaiting ratification');
    // Screen readers get the consequence, not just the label.
    expect(chip!.textContent).toContain('before the bill can be paid');
  });

  it('shows a danger chip when ratification was refused', async () => {
    const host = await mount([makeBill(1, 'RATIFICATION_REFUSED')]);
    const chip = host.querySelector('app-direct-receipt-ratification .status-tag')!;
    expect(chip.classList.contains('status-tag--danger')).toBe(true);
    expect(chip.textContent).toContain('Ratification refused');
  });

  it('shows a quiet success chip once ratified', async () => {
    const host = await mount([makeBill(1, 'RATIFIED')]);
    const chip = host.querySelector('app-direct-receipt-ratification .status-tag')!;
    expect(chip.classList.contains('status-tag--ok')).toBe(true);
    expect(chip.textContent).toContain('Ratified');
  });

  it('renders one chip per direct-receipt row and none for the rest', async () => {
    const host = await mount([
      makeBill(1, 'NOT_APPLICABLE'),
      makeBill(2, 'AWAITING_RATIFICATION'),
      makeBill(3, 'NOT_APPLICABLE'),
      makeBill(4, 'RATIFIED'),
    ]);
    expect(host.querySelectorAll('app-direct-receipt-ratification .status-tag').length).toBe(2);
  });
});
