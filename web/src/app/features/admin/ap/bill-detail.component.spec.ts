import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';
import { of } from 'rxjs';

import { BillDetailComponent } from './bill-detail.component';
import { ApService } from './ap.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DirectReceiptRatificationState, SupplierBillDto } from './models/ap.model';

function makeSession() {
  return {
    hasPermission: vi.fn(() => true),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBill(ratification?: DirectReceiptRatificationState | null): SupplierBillDto {
  return {
    id: '1',
    uid: 'bill-uid-1',
    companyId: '1',
    branchId: '1',
    supplierId: '1',
    billNumber: 'BILL-001',
    supplierInvoiceNo: 'INV-9',
    source: 'PURCHASE_ORDER',
    purchaseOrderUid: 'po-uid-1',
    billDate: '2026-08-01',
    dueDate: '2026-08-31',
    netAmount: '1000.00',
    vatAmount: '180.00',
    grossAmount: '1180.00',
    outstandingAmount: '1180.00',
    currency: 'TZS',
    status: 'MATCHED',
    postedGlEntryUid: null,
    directReceiptRatification: ratification,
    lines: [],
  };
}

function mount(bill: SupplierBillDto): HTMLElement {
  const api = TestBed.inject(ApService) as unknown as { getBill: ReturnType<typeof vi.fn> };
  api.getBill.mockReturnValue(of(bill));
  const fixture = TestBed.createComponent(BillDetailComponent);
  fixture.componentRef.setInput('uid', bill.uid);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('BillDetailComponent — direct-receipt ratification', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [BillDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SessionStore, useValue: makeSession() },
        { provide: ApService, useValue: { getBill: vi.fn(() => of(makeBill())) } },
      ],
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('shows no ratification notice on an ordinary bill', () => {
    const host = mount(makeBill('NOT_APPLICABLE'));
    expect(host.textContent).not.toContain('ratification');
    expect(host.textContent).not.toContain('Ratified');
    expect(host.querySelector('.alert')).toBeNull();
    // and the Record Payment link stays live
    expect(host.querySelector('a[href="/admin/ap/payments/record"]')).not.toBeNull();
  });

  it('shows no ratification notice when the field is absent', () => {
    const host = mount(makeBill(undefined));
    expect(host.querySelector('.alert')).toBeNull();
    expect(host.textContent).not.toContain('ratification');
  });

  it('AWAITING_RATIFICATION explains the hold and disables Record Payment', () => {
    const host = mount(makeBill('AWAITING_RATIFICATION'));
    const alert = host.querySelector('.alert');
    expect(alert).not.toBeNull();
    expect(alert!.classList.contains('alert-warning')).toBe(true);
    expect(alert!.textContent).toContain('manager');
    expect(host.textContent).toContain('Awaiting ratification');

    expect(host.querySelector('a[href="/admin/ap/payments/record"]')).toBeNull();
    const payButton = host.querySelector('button[disabled]');
    expect(payButton).not.toBeNull();
    expect(payButton!.textContent).toContain('Record Payment');
    const noteId = payButton!.getAttribute('aria-describedby');
    expect(noteId).toBeTruthy();
    expect(host.querySelector(`#${noteId}`)?.textContent).toContain(
      'on hold until a manager confirms',
    );
  });

  it('RATIFICATION_REFUSED reads worse than awaiting and still blocks payment', () => {
    const host = mount(makeBill('RATIFICATION_REFUSED'));
    const alert = host.querySelector('.alert');
    expect(alert!.classList.contains('alert-danger')).toBe(true);
    expect(host.textContent).toContain('Ratification refused');
    expect(host.querySelector('a[href="/admin/ap/payments/record"]')).toBeNull();
    expect(host.querySelector('button[disabled]')).not.toBeNull();
    expect(host.textContent).toContain('a manager refused this delivery');
  });

  it('RATIFIED reassures quietly and leaves payment available', () => {
    const host = mount(makeBill('RATIFIED'));
    expect(host.querySelector('.alert')).toBeNull();
    expect(host.textContent).toContain('Ratified');
    expect(host.textContent).toContain('pays as normal');
    expect(host.querySelector('a[href="/admin/ap/payments/record"]')).not.toBeNull();
  });
});

describe('BillDetailComponent — AP-01 held / unposted bill is not a dead end', () => {
  let api: {
    getBill: ReturnType<typeof vi.fn>;
    runMatch: ReturnType<typeof vi.fn>;
    acceptVariance: ReturnType<typeof vi.fn>;
    deleteBill: ReturnType<typeof vi.fn>;
  };

  function heldBill(): SupplierBillDto {
    return {
      ...makeBill('NOT_APPLICABLE'),
      status: 'HELD',
      lines: [
        {
          id: '11', uid: 'line-1', supplierBillId: '1', lineNo: 1, productId: null,
          poLineUid: 'pol-1', grLineUid: 'grl-1', description: 'Soda crates',
          billedQty: 10, unitCostAmount: 100, lineNetAmount: 1000, currency: 'TZS',
        } as unknown as SupplierBillDto['lines'][number],
      ],
    };
  }

  const heldResult = {
    billUid: 'bill-uid-1',
    billStatus: 'HELD' as const,
    lineResults: [
      {
        billLineId: '11', billLineUid: 'line-1', matchStatus: 'HELD_QTY_VARIANCE' as const,
        priceVarianceAmount: 0, priceVariancePct: 0, qtyVariance: 5,
        poUnitCostAmount: 100, grReceivedQty: 5, billedQty: 10, matchedAt: null,
        comparisonPerformed: true, matchNote: 'More is billed than was received.',
      },
    ],
  };

  beforeEach(() => {
    api = {
      getBill: vi.fn(() => of(heldBill())),
      runMatch: vi.fn(() => of(heldResult)),
      acceptVariance: vi.fn(() => of({ ...heldResult, billStatus: 'MATCHED' })),
      deleteBill: vi.fn(() => of(undefined)),
    };
    TestBed.configureTestingModule({
      imports: [BillDetailComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        // The delete navigates back to the list; give it somewhere to land.
        provideRouter([{ path: 'admin/ap/supplier-bills', children: [] }]),
        { provide: SessionStore, useValue: makeSession() },
        { provide: ApService, useValue: api },
      ],
    });
  });

  afterEach(() => vi.restoreAllMocks());

  function mountHeld() {
    const fixture = TestBed.createComponent(BillDetailComponent);
    fixture.componentRef.setInput('uid', 'bill-uid-1');
    fixture.detectChanges();
    return fixture;
  }

  function button(host: HTMLElement, text: string): HTMLButtonElement | undefined {
    return Array.from(host.querySelectorAll('button')).find((b) =>
      (b.textContent ?? '').includes(text),
    ) as HTMLButtonElement | undefined;
  }

  it('offers Run match and Delete bill on a HELD bill', () => {
    const host = mountHeld().nativeElement as HTMLElement;
    expect(host.textContent).toContain('This bill is on hold');
    expect(button(host, 'Run match')).toBeDefined();
    expect(button(host, 'Delete bill')).toBeDefined();
  });

  it('does not offer the corrections on a posted (MATCHED) bill', () => {
    api.getBill.mockReturnValue(of(makeBill('NOT_APPLICABLE')));
    const host = mountHeld().nativeElement as HTMLElement;
    expect(button(host, 'Run match')).toBeUndefined();
    expect(button(host, 'Delete bill')).toBeUndefined();
  });

  it('Run match shows each line result with an Accept variance action', () => {
    const fixture = mountHeld();
    const host = fixture.nativeElement as HTMLElement;
    button(host, 'Run match')!.click();
    fixture.detectChanges();
    expect(api.runMatch).toHaveBeenCalledWith('bill-uid-1');
    expect(host.textContent).toContain('On hold — quantity differs');
    expect(host.textContent).toContain('More is billed than was received.');

    button(host, 'Accept variance')!.click();
    fixture.detectChanges();
    expect(api.acceptVariance).toHaveBeenCalledWith('bill-uid-1', { billLineUid: 'line-1' });
  });

  it('Delete bill asks first, then deletes', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const fixture = mountHeld();
    button(fixture.nativeElement as HTMLElement, 'Delete bill')!.click();
    expect(confirmSpy).toHaveBeenCalled();
    expect(api.deleteBill).toHaveBeenCalledWith('bill-uid-1');
  });

  it('Delete bill does nothing when the clerk cancels', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const fixture = mountHeld();
    button(fixture.nativeElement as HTMLElement, 'Delete bill')!.click();
    expect(api.deleteBill).not.toHaveBeenCalled();
  });
});
