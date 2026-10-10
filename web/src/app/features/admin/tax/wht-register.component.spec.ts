/**
 * WhtRegisterComponent — ACC-07 "Record payment to TRA": pays the period's unpaid supplier WHT
 * from a cash/bank account (POST /wht/register/payments), gated WHT.REMIT.
 */
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CashbankService } from '../cashbank/cashbank.service';
import { TaxService } from './tax.service';
import { WhtRegisterComponent } from './wht-register.component';
import type { WhtRegisterDto } from './models/tax.model';

const REGISTER: WhtRegisterDto = {
  companyId: '10',
  periodStart: '2026-09-01',
  periodEnd: '2026-09-30',
  payableRows: [
    { whtNumber: 'WHT-1', kind: 'WHT_ON_PAYMENT', partyKind: 'SUPPLIER', partyName: 'Mbasha',
      sourceRef: 'P1', taxableBase: 100000, whtAmount: 5000, certificateDate: '2026-09-03',
      uid: 'w1', remitted: true },
    { whtNumber: 'WHT-2', kind: 'WHT_ON_PAYMENT', partyKind: 'SUPPLIER', partyName: 'Kariakoo',
      sourceRef: 'P2', taxableBase: 200000, whtAmount: 10000, certificateDate: '2026-09-09',
      uid: 'w2', remitted: false },
  ],
  totalPayable: 15000,
  receivableRows: [],
  totalReceivable: 0,
};

function setup(permissions: string[]) {
  const tax = {
    getWhtRegisterByMonth: vi.fn(() => of(REGISTER)),
    getWhtRegisterByRange: vi.fn(() => of(REGISTER)),
    exportWhtRegister: vi.fn(),
    payWhtPeriod: vi.fn(() => of({ certificatesRemitted: 1, amountPaid: 10000, cashTransactionUid: 'c1' })),
  };
  TestBed.configureTestingModule({
    imports: [WhtRegisterComponent],
    providers: [
      { provide: TaxService, useValue: tax },
      { provide: OrganisationService, useValue: { current: () => of({ uid: 'ORG1' }) } },
      { provide: CompanyService, useValue: { list: () => of([{ id: '10', uid: 'C1', name: 'Acme' }]) } },
      {
        provide: CashbankService,
        useValue: {
          listAccountOptions: vi.fn(() => of([
            { id: '5', uid: 'bank-1', code: 'CRDB', name: 'CRDB Main', accountType: 'BANK',
              branchId: null, currency: 'TZS', isDefault: true, inCurrentBranch: true },
          ])),
        },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((c: string) => permissions.includes(c)),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal(permissions),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(WhtRegisterComponent);
  fixture.detectChanges();
  fixture.componentInstance.load();
  fixture.detectChanges();
  return { fixture, tax };
}

describe('WhtRegisterComponent — record payment to TRA (ACC-07)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('counts only the unpaid certificates and shows the action to WHT.REMIT', () => {
    const { fixture } = setup(['WHT.VIEW', 'WHT.REMIT']);
    const comp = fixture.componentInstance;
    expect(comp.unpaidCount()).toBe(1);
    expect(comp.unpaidTotal()).toBe(10000);
    expect(fixture.nativeElement.textContent).toContain('Record payment to TRA');
    expect(fixture.nativeElement.textContent).toContain('Remitted');
    expect(fixture.nativeElement.textContent).toContain('Unpaid');
  });

  it('hides the action without WHT.REMIT', () => {
    const { fixture } = setup(['WHT.VIEW']);
    expect(fixture.nativeElement.textContent).not.toContain('Record payment to TRA');
  });

  it('posts the period payment with the chosen account and reference', () => {
    const { fixture, tax } = setup(['WHT.VIEW', 'WHT.REMIT']);
    const comp = fixture.componentInstance;
    comp.openPayForm();
    expect(comp.payAccountUid()).toBe('bank-1');
    comp.payRef.set('TRA-PRN-1');
    comp.payDate.set('2026-10-07');
    comp.submitPayment();
    expect(tax.payWhtPeriod).toHaveBeenCalledWith({
      companyId: '10', periodStart: '2026-09-01', periodEnd: '2026-09-30',
      cashBankAccountUid: 'bank-1', paymentDate: '2026-10-07', remittanceRef: 'TRA-PRN-1',
    });
    expect(comp.showPayForm()).toBe(false);
  });

  it('requires the TRA reference', () => {
    const { fixture, tax } = setup(['WHT.VIEW', 'WHT.REMIT']);
    const comp = fixture.componentInstance;
    comp.openPayForm();
    comp.payRef.set('  ');
    comp.submitPayment();
    expect(tax.payWhtPeriod).not.toHaveBeenCalled();
    expect(comp.payError()).toContain('TRA');
  });
});
