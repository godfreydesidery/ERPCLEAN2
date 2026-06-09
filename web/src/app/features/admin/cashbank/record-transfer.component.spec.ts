import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { RecordTransferComponent } from './record-transfer.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

// Record Transfer guard: source must NOT equal destination.
// Payload: companyUid, sourceAccountUid, destinationAccountUid, amount, transferDate, reference.
// Money arrives as numbers on the wire.

function makeSession(canTransfer = true) {
  return {
    hasPermission: vi.fn(() => canTransfer),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

const MOCK_ACCOUNTS = [
  { uid: 'ACC1', id: '1', companyId: '10', code: 'CASH-001', name: 'Main Cash', accountType: 'CASH', active: true, isDefault: true, currency: 'TZS', glAccountId: '50', bankName: null, bankAccountNo: null, bankBranch: null, branchId: null },
  { uid: 'ACC2', id: '2', companyId: '10', code: 'BANK-001', name: 'CRDB Bank', accountType: 'BANK', active: true, isDefault: false, currency: 'TZS', glAccountId: '51', bankName: 'CRDB', bankAccountNo: '0001234', bankBranch: 'HQ', branchId: null },
];

const MOCK_BALANCE = { cashBankAccountId: '1', cashBankAccountUid: 'ACC1', accountCode: 'CASH-001', accountName: 'Main Cash', bookBalance: 5000, currency: 'TZS' };

function makeBed(canTransfer = true) {
  TestBed.configureTestingModule({
    imports: [RecordTransferComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: CashbankService,
        useValue: {
          listAllAccounts: vi.fn(() => of(MOCK_ACCOUNTS)),
          getAccountBalance: vi.fn(() => of(MOCK_BALANCE)),
          recordTransfer: vi.fn(() => of({ uid: 'TRF1', transferNumber: 'TRF-001', amount: 1000, currency: 'TZS' })),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSession(canTransfer) },
    ],
  });
}

function primeValid(comp: any) {
  comp.selectedCompanyId.set('10');
  comp.sourceAccountUid.set('ACC1');
  comp.destinationAccountUid.set('ACC2');
  comp.amount.set('1000');
  comp.transferDate.set('2026-06-09');
}

describe('RecordTransferComponent — source≠dest guard', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('submit disabled initially', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordTransferComponent).componentInstance as any;
    expect(comp.submitDisabled()).toBe(true);
  });

  it('sameAccountError is false when src != dst', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordTransferComponent).componentInstance as any;
    primeValid(comp);
    expect(comp.sameAccountError()).toBe(false);
    expect(comp.submitDisabled()).toBe(false);
  });

  it('sameAccountError is true when src === dst, submit disabled', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordTransferComponent).componentInstance as any;
    primeValid(comp);
    comp.destinationAccountUid.set('ACC1'); // same as source
    expect(comp.sameAccountError()).toBe(true);
    expect(comp.submitDisabled()).toBe(true);
  });

  it('submit disabled when amount is zero', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordTransferComponent).componentInstance as any;
    primeValid(comp);
    comp.amount.set('0');
    expect(comp.submitDisabled()).toBe(true);
  });

  it('submit disabled when amount is negative', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordTransferComponent).componentInstance as any;
    primeValid(comp);
    comp.amount.set('-100');
    expect(comp.amountNum()).toBeLessThanOrEqual(0);
    expect(comp.submitDisabled()).toBe(true);
  });

  it('recordTransfer payload contains correct fields', () => {
    vi.useFakeTimers();
    makeBed();
    const fixture = TestBed.createComponent(RecordTransferComponent);
    const comp = fixture.componentInstance as any;
    const cashService = TestBed.inject(CashbankService) as any;

    primeValid(comp);
    comp.reference.set('REF-001');
    comp.submit();

    expect(cashService.recordTransfer).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceAccountUid: 'ACC1',
        destinationAccountUid: 'ACC2',
        amount: '1000',
        transferDate: '2026-06-09',
        reference: 'REF-001',
      }),
    );
  });

  it('fmtMoney coerces number wire value correctly', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordTransferComponent).componentInstance as any;
    // Money arrives as number on the wire — NEVER call .startsWith/.trim on it
    expect(comp.fmtMoney(5000)).toBe('5000.00');
    expect(comp.fmtMoney(0)).toBe('0.00');
    expect(comp.fmtMoney(null)).toBe('0.00');
    expect(comp.fmtMoney(undefined)).toBe('0.00');
    expect(comp.fmtMoney('1500.5')).toBe('1500.50');
  });
});
