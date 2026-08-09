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
