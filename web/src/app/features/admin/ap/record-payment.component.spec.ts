import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { RecordPaymentComponent } from './record-payment.component';
import { ApService } from './ap.service';
import { SupplierService } from '../parties/supplier.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

// Record Payment guard: submit must be disabled when zero bills are selected,
// and enabled as soon as at least one bill is checked.
// Payload shape: supplierUid, dueOnOrBefore, paymentDate, tenderType, bankReference, billUids.

function makeSession(canPay = true) {
  return {
    hasPermission: vi.fn(() => canPay),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(canPay = true) {
  TestBed.configureTestingModule({
    imports: [RecordPaymentComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: ApService,
        useValue: {
          listBills: vi.fn(() => of({ rows: [], meta: {} })),
          paymentRun: vi.fn(() => of([])),
        },
      },
      { provide: SupplierService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSession(canPay) },
    ],
  });
}

/** Prime the component into a state with a supplier selected, a date set, company set. */
function primeSupplierSelected(comp: any) {
  comp.selectedCompanyId.set('10');
  comp.selectedSupplier.set({ uid: 'SUP1', label: 'Acme Supplies' });
  comp.paymentDate.set('2026-06-09');
}

function makePayableBill(uid: string, outstanding = 1000): any {
  return {
    uid,
    billNumber: `BILL-${uid}`,
    supplierInvoiceNo: `INV-${uid}`,
    billDate: '2026-05-01',
    dueDate: '2026-06-01',
    grossAmount: outstanding,
    outstandingAmount: outstanding,
    currency: 'TZS',
    status: 'MATCHED',
    lines: [],
    supplierId: 'SUP1',
    companyId: '10',
    branchId: 'BR1',
    source: 'PURCHASE_ORDER',
    purchaseOrderUid: null,
    netAmount: outstanding,
    vatAmount: 0,
    postedGlEntryUid: null,
  };
}

describe('RecordPaymentComponent — bill-selection guard', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('submit disabled initially (no supplier, no bills)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordPaymentComponent).componentInstance as any;
    expect(comp.submitDisabled()).toBe(true);
  });

  it('submit disabled when supplier set but zero bills selected', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordPaymentComponent).componentInstance as any;
    primeSupplierSelected(comp);
    comp.bills.set([makePayableBill('B1'), makePayableBill('B2')]);
    // selectedBillUids is empty — guard must fire
    expect(comp.selectedCount()).toBe(0);
    expect(comp.submitDisabled()).toBe(true);
  });

  it('submit enabled when at least one bill is selected', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordPaymentComponent).componentInstance as any;
    primeSupplierSelected(comp);
    comp.bills.set([makePayableBill('B1'), makePayableBill('B2')]);
    comp.toggleBill('B1', true);
    expect(comp.selectedCount()).toBe(1);
    expect(comp.submitDisabled()).toBe(false);
  });

  it('selectAll selects all bills; clearSelection removes all', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordPaymentComponent).componentInstance as any;
    primeSupplierSelected(comp);
    const bills = [makePayableBill('B1'), makePayableBill('B2'), makePayableBill('B3')];
    comp.bills.set(bills);
    comp.selectAll();
    expect(comp.selectedCount()).toBe(3);
    expect(comp.submitDisabled()).toBe(false);
    comp.clearSelection();
    expect(comp.selectedCount()).toBe(0);
    expect(comp.submitDisabled()).toBe(true);
  });

  it('selectedTotal coerces outstanding as number (wire sends numbers)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordPaymentComponent).componentInstance as any;
    primeSupplierSelected(comp);
    // outstandingAmount arrives as number on the wire
    comp.bills.set([makePayableBill('B1', 500), makePayableBill('B2', 300)]);
    comp.toggleBill('B1', true);
    comp.toggleBill('B2', true);
    expect(comp.selectedTotal()).toBeCloseTo(800, 5);
  });

  it('paymentRun payload contains correct billUids', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(RecordPaymentComponent);
    const comp = fixture.componentInstance as any;
    const apService = TestBed.inject(ApService) as any;
    apService.paymentRun.mockReturnValue(of([{ uid: 'PAY1', paymentNumber: 'PMT-001', amount: 500, currency: 'TZS', tenderType: 'BANK_TRANSFER', bankReference: 'REF1', allocations: [] }]));

    primeSupplierSelected(comp);
    comp.bills.set([makePayableBill('B1', 500), makePayableBill('B2', 300)]);
    comp.toggleBill('B1', true);
    comp.bankReference.set('REF1');
    comp.submit();

    expect(apService.paymentRun).toHaveBeenCalledWith(
      expect.objectContaining({
        supplierUid: 'SUP1',
        billUids: ['B1'],
        bankReference: 'REF1',
      }),
    );
  });
});
