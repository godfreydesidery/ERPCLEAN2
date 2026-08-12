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
import {
  BillComparisonState,
  DirectReceiptRatificationState,
  SupplierBillDto,
} from './models/ap.model';
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
  comparisonState?: BillComparisonState | null,
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
    comparisonState,
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

/**
 * UAT 2026-08-12 — a bill that posted without anybody comparing it to an order or a delivery must be
 * visible from the list, and findable on purpose at period end.
 */
describe('SupplierBillsListComponent — three-way check column and filter', () => {
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

  it('shows on the row that a posted bill was never checked, without opening it', async () => {
    const host = await mount([makeBill(1, null, 'NEVER_MATCHED')]);
    const chip = host.querySelector('app-bill-comparison-badge .status-tag')!;
    expect(chip).not.toBeNull();
    expect(chip.textContent).toContain('No check run');
    expect(chip.classList.contains('status-tag--warn')).toBe(true);
  });

  it('distinguishes a checked bill from an unchecked one at a glance', async () => {
    const host = await mount([
      makeBill(1, null, 'ALL_LINES_COMPARED'),
      makeBill(2, null, 'NO_LINES_COMPARED'),
    ]);
    const chips = host.querySelectorAll('app-bill-comparison-badge .status-tag');
    expect(chips.length).toBe(2);
    expect(chips[0].classList.contains('status-tag--ok')).toBe(true);
    expect(chips[1].classList.contains('status-tag--warn')).toBe(true);
  });

  it('never leaves the check cell blank, even when the response omits the field', async () => {
    // A blank cell reads as "fine". The one thing this column must not do is look fine when nothing
    // was said.
    const host = await mount([makeBill(1, null, undefined)]);
    const chip = host.querySelector('app-bill-comparison-badge .status-tag')!;
    expect(chip).not.toBeNull();
    expect(chip.textContent).toContain('Not reported');
    expect(chip.classList.contains('status-tag--ok')).toBe(false);
  });

  it('asks the server for unchecked bills only, from page 0', async () => {
    const host = await mount([makeBill(1, null, 'ALL_LINES_COMPARED')]);
    const api = TestBed.inject(ApService) as unknown as { listBills: ReturnType<typeof vi.fn> };
    api.listBills.mockClear();

    const checkbox = host.querySelector<HTMLInputElement>('#uncomparedOnly')!;
    checkbox.checked = true;
    checkbox.dispatchEvent(new Event('change'));
    await vi.runAllTimersAsync();

    // (companyId, supplierUid, status, page, size, uncomparedOnly)
    expect(api.listBills).toHaveBeenCalledWith('1', undefined, undefined, 0, 20, true);
  });

  it('does not ask for the filter until it is switched on', async () => {
    await mount([makeBill(1, null, 'ALL_LINES_COMPARED')]);
    const api = TestBed.inject(ApService) as unknown as { listBills: ReturnType<typeof vi.fn> };
    expect(api.listBills).toHaveBeenCalledWith('1', undefined, undefined, 0, 20, false);
  });

  it('labels the filter control', async () => {
    const host = await mount([makeBill(1, null, 'ALL_LINES_COMPARED')]);
    const checkbox = host.querySelector<HTMLInputElement>('#uncomparedOnly')!;
    const label = host.querySelector<HTMLLabelElement>('label[for="uncomparedOnly"]')!;
    expect(checkbox).not.toBeNull();
    expect(label.textContent).toContain('Only bills not fully checked');
  });
});
