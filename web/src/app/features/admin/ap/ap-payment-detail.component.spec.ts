import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { ApPaymentDetailComponent } from './ap-payment-detail.component';
import { ApService } from './ap.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

// AP-03: a wrong supplier payment is reversed from its detail page, with a required reason.

const PAYMENT = {
  id: '1', uid: 'PAY1', companyId: '10', branchId: '2', supplierId: '7', paymentNumber: 'PAY-1',
  kind: 'SINGLE', paymentDate: '2026-10-01', amount: 1000, currency: 'TZS', tenderType: 'CASH',
  bankReference: null, glEntryUid: 'GL1', cashBankAccountId: '3', cashBankAccountUid: 'CB1',
  cashBankAccountName: 'Main Cash', cashBankAccountNumber: null,
  allocations: [{ id: '9', supplierBillId: '4', supplierBillUid: 'BILL1', allocatedAmount: 1000 }],
  reversedAt: null,
};

function setup(perms: string[] = ['AP.VIEW']) {
  const ap = {
    getPayment: vi.fn(() => of(PAYMENT)),
    reversePayment: vi.fn(() => of({ ...PAYMENT, reversedAt: '2026-10-10T08:00:00Z' })),
  };
  TestBed.configureTestingModule({
    imports: [ApPaymentDetailComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: ApService, useValue: ap },
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
  const fixture = TestBed.createComponent(ApPaymentDetailComponent);
  fixture.componentRef.setInput('uid', 'PAY1');
  const comp = fixture.componentInstance;
  comp.entity.set(PAYMENT as never);
  return { comp, ap };
}

describe('ApPaymentDetailComponent — reverse a wrong payment (AP-03)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('offers "Reverse payment" only with AP.PAYMENT.REVERSE', () => {
    expect(setup().comp.showReverse()).toBe(false);
    TestBed.resetTestingModule();
    expect(setup(['AP.VIEW', 'AP.PAYMENT.REVERSE']).comp.showReverse()).toBe(true);
  });

  it('needs a reason, then reverses and shows the payment as reversed', () => {
    const { comp, ap } = setup(['AP.VIEW', 'AP.PAYMENT.REVERSE']);
    comp.openReverse();
    expect(comp.reverseDisabled()).toBe(true);
    comp.confirmReverse();
    expect(ap.reversePayment).not.toHaveBeenCalled();

    comp.reverseReason.set('  Paid the wrong bill ');
    comp.confirmReverse();
    expect(ap.reversePayment).toHaveBeenCalledWith('PAY1', 'Paid the wrong bill');
    expect(comp.isReversed()).toBe(true);
    expect(comp.reverseOpen()).toBe(false);
    expect(comp.showReverse()).toBe(false);
  });
});
