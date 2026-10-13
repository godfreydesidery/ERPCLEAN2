import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { RecordEntryComponent } from './record-entry.component';
import { CashbankService } from './cashbank.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from '../gl/gl.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

// ARC-12 / LBO-17: the expense screen defaults to money OUT, offers expense accounts first, and
// lists what has already been recorded on the chosen account (the list call must carry accountId).

const OPTIONS = [
  { id: '1', uid: 'CB1', code: 'CB-0001', name: 'HQ cash', accountType: 'CASH', branchId: null, currency: 'TZS', isDefault: true, inCurrentBranch: false },
  { id: '2', uid: 'CB2', code: 'CB-0002', name: 'Arusha cash', accountType: 'CASH', branchId: '7', currency: 'TZS', isDefault: false, inCurrentBranch: true },
];
const GL = [
  { id: '90', uid: 'GL-INC', accountCode: '4000', name: 'Sales', accountType: 'INCOME' },
  { id: '91', uid: 'GL-EXP', accountCode: '6100', name: 'Rent', accountType: 'EXPENSE' },
  { id: '92', uid: 'GL-AST', accountCode: '1000', name: 'Cash', accountType: 'ASSET' },
];
const ENTRIES = [
  { uid: 'T1', txnNumber: 'CT-1', txnDate: '2026-10-01', direction: 'IN', amount: 100, currency: 'TZS', txnType: 'AR_RECEIPT', counterGlAccountId: null, memo: null },
  { uid: 'T2', txnNumber: 'CT-2', txnDate: '2026-10-02', direction: 'OUT', amount: 50, currency: 'TZS', txnType: 'DIRECT_ENTRY', counterGlAccountId: '91', memo: 'October rent' },
];

function makeBed(perms: string[] = ['CASH.ENTRY.RECORD', 'CASH.VIEW']) {
  const cash = {
    listAccountOptions: vi.fn(() => of(OPTIONS)),
    listEntries: vi.fn(() => of(ENTRIES)),
    recordEntry: vi.fn(() => of({ txnNumber: 'CT-3', direction: 'OUT', amount: 10 })),
  };
  TestBed.configureTestingModule({
    imports: [RecordEntryComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: CashbankService, useValue: cash },
      { provide: GlService, useValue: { listAllActiveAccounts: vi.fn(() => of(GL)) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
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
  return cash;
}

describe('RecordEntryComponent — expense entry (ARC-12 / LBO-17)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('defaults to money OUT and offers expense accounts first, never the asset accounts', () => {
    makeBed();
    const comp = TestBed.createComponent(RecordEntryComponent).componentInstance as RecordEntryComponent;
    expect(comp.direction()).toBe('OUT');
    expect(comp.counterGlOptions().map((a) => a.uid)).toEqual(['GL-EXP', 'GL-INC']);
    comp.direction.set('IN');
    expect(comp.counterGlOptions().map((a) => a.uid)).toEqual(['GL-INC', 'GL-EXP']);
  });

  it('preselects this branch\'s cash drawer and lists its direct entries with the accountId', () => {
    const cash = makeBed();
    const comp = TestBed.createComponent(RecordEntryComponent).componentInstance as RecordEntryComponent;
    expect(comp.selectedAccountUid()).toBe('CB2');
    expect(cash.listEntries).toHaveBeenCalledWith('10', '2');
    expect(comp.recentEntries().map((t) => t.uid)).toEqual(['T2']);
    expect(comp.counterAccountLabel('91')).toBe('6100 — Rent');
  });

  it('without CASH.VIEW the form still works but no list is requested', () => {
    const cash = makeBed(['CASH.ENTRY.RECORD']);
    const comp = TestBed.createComponent(RecordEntryComponent).componentInstance as RecordEntryComponent;
    expect(comp.selectedAccountUid()).toBe('CB2');
    expect(cash.listEntries).not.toHaveBeenCalled();
  });

  it('after "Record another" the direction is OUT again and the account is kept', () => {
    makeBed();
    const comp = TestBed.createComponent(RecordEntryComponent).componentInstance as RecordEntryComponent;
    comp.direction.set('IN');
    comp.reset();
    expect(comp.direction()).toBe('OUT');
    expect(comp.selectedAccountUid()).toBe('CB2');
  });
});

describe('RecordEntryComponent — input VAT on an expense (ACC-13 / PAR-08)', () => {
  afterEach(() => TestBed.resetTestingModule());

  function filled() {
    const cash = makeBed();
    const comp = TestBed.createComponent(RecordEntryComponent).componentInstance as RecordEntryComponent;
    comp.amount.set('1180');
    comp.txnDate.set('2026-10-05');
    comp.counterGlAccountUid.set('GL-EXP');
    return { cash, comp };
  }

  it('sends the VAT part with a money-OUT entry; "18% incl." computes it from the amount', () => {
    const { cash, comp } = filled();
    comp.vatFromAmount();
    expect(comp.vatAmount()).toBe('180.00');
    comp.submit();
    expect(cash.recordEntry).toHaveBeenCalledWith(expect.objectContaining({
      direction: 'OUT', amount: '1180', vatAmount: '180.00',
    }));
  });

  it('never sends VAT on money IN, and refuses VAT not below the amount', () => {
    const { cash, comp } = filled();
    comp.vatAmount.set('1180');
    comp.submit();
    expect(cash.recordEntry).not.toHaveBeenCalled();
    expect(comp.formError()).toContain('VAT');

    comp.direction.set('IN');
    comp.counterGlAccountUid.set('GL-INC');
    comp.submit();
    expect(cash.recordEntry).toHaveBeenCalledWith(expect.objectContaining({
      direction: 'IN', vatAmount: undefined,
    }));
  });
});

describe('CashbankService.listEntries (ARC-12)', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('sends the accountId the server requires', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const svc = TestBed.inject(CashbankService);
    const http = TestBed.inject(HttpTestingController);
    let got: unknown;
    svc.listEntries('10', '2').subscribe((rows) => (got = rows));
    const req = http.expectOne((r) => r.url.endsWith('/cash/entries'));
    expect(req.request.params.get('companyId')).toBe('10');
    expect(req.request.params.get('accountId')).toBe('2');
    req.flush([]);
    expect(got).toEqual([]);
    http.verify();
  });
});
