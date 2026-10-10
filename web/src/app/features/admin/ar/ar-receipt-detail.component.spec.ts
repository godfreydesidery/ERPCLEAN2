import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { ArReceiptDetailComponent } from './ar-receipt-detail.component';
import { ArService } from './ar.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

// ARC-06: a deposit held on account can be applied to invoices raised later. The PUT replaces the
// whole allocation set, so the screen must resend the existing allocations plus the new amounts.

const RECEIPT = {
  uid: 'RC1', companyId: '10', customerId: '5', customerUid: 'KIBO', customerCode: 'C001',
  customerName: 'Kibo Bar', receiptNumber: 'RCT-1', receiptDate: '2026-10-01', amount: 1000,
  unallocatedAmount: 700, currency: 'TZS', tenderType: 'CASH', status: 'PARTIAL',
  allocations: [{ arInvoiceUid: 'OLD', allocatedAmount: 300 }],
};
const OPEN = [
  { uid: 'INV1', documentNo: 'INV-1', invoiceDate: '2026-10-05', outstandingAmount: 500, status: 'OPEN' },
  { uid: 'INV2', documentNo: 'INV-2', invoiceDate: '2026-10-06', outstandingAmount: 400, status: 'OPEN' },
];

function setup(perms: string[] = ['AR.VIEW', 'AR.RECEIPT.ALLOCATE']) {
  const ar = {
    getReceipt: vi.fn(() => of(RECEIPT)),
    listOpenInvoices: vi.fn(() => of(OPEN)),
    reallocateReceipt: vi.fn(() => of({ ...RECEIPT, unallocatedAmount: 0 })),
  };
  TestBed.configureTestingModule({
    imports: [ArReceiptDetailComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: ArService, useValue: ar },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((c: string) => perms.includes(c)),
          isAuthenticated: signal(true), user: signal(null), permissions: signal(perms), activeBranchUid: signal(null),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(ArReceiptDetailComponent);
  fixture.componentRef.setInput('uid', 'RC1');
  const comp = fixture.componentInstance;
  comp.entity.set(RECEIPT as never);
  return { comp, ar };
}

describe('ArReceiptDetailComponent — apply on-account money (ARC-06)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('offers "Apply" only with money on account and an allocate/record permission', () => {
    expect(setup().comp.showApply()).toBe(true);
    TestBed.resetTestingModule();
    expect(setup(['AR.VIEW']).comp.showApply()).toBe(false);
  });

  it('fills oldest-first up to the money on account and keeps existing allocations on save', () => {
    const { comp, ar } = setup();
    comp.openApply();
    expect(ar.listOpenInvoices).toHaveBeenCalledWith('10', 'KIBO');
    comp.fillOldestFirst();
    expect(comp.applyRows().map((r) => r.applyInput)).toEqual(['500.00', '200.00']);
    comp.saveApply();
    expect(ar.reallocateReceipt).toHaveBeenCalledWith('RC1', [
      { arInvoiceUid: 'OLD', allocatedAmount: '300.00' },
      { arInvoiceUid: 'INV1', allocatedAmount: '500.00' },
      { arInvoiceUid: 'INV2', allocatedAmount: '200.00' },
    ]);
    expect(comp.applyOpen()).toBe(false);
  });

  it('refuses to apply more than is on account', () => {
    const { comp } = setup();
    comp.openApply();
    comp.updateApply('INV1', '500');
    comp.updateApply('INV2', '400');
    expect(comp.applyInvalid()).toBe(true);
    expect(comp.applyDisabled()).toBe(true);
  });
});
