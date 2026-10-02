import { describe, it, expect, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { SupplierStatementComponent } from './supplier-statement.component';
import { ApService } from './ap.service';
import { SupplierService } from '../parties/supplier.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SessionStore } from '../../../core/auth/session.store';

// Supplier Statement tests:
//  1. Ageing bucket order is canonical (CURRENT first, D90_PLUS last).
//  2. Money fields coerced as numbers — +(b.amount) === 0 renders '—', positive renders formatted.
//  3. outstandingBalance computed from balance.outstandingBalance (number on wire).

const MOCK_AGEING = [
  { bucket: 'D90_PLUS', amount: 5000, currency: 'TZS' },
  { bucket: 'CURRENT', amount: 0, currency: 'TZS' },
  { bucket: 'D1_30', amount: 2000, currency: 'TZS' },
];

const MOCK_BALANCE = {
  companyId: '10',
  supplierId: 'SUP1',
  outstandingBalance: 7000,   // arrives as number
  currency: 'TZS',
};

function makeSession(canView = true) {
  return {
    hasPermission: vi.fn(() => canView),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

const MOCK_RECON = {
  companyId: '10',
  subLedgerTotal: 7000,
  glControlBalance: 7000,
  difference: 0,
  currency: 'TZS',
};

let recon: typeof MOCK_RECON = MOCK_RECON;

function makeBed() {
  TestBed.configureTestingModule({
    imports: [SupplierStatementComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      {
        provide: ApService,
        useValue: {
          getBalance: vi.fn(() => of(MOCK_BALANCE)),
          getAgeing: vi.fn(() => of(MOCK_AGEING)),
          listBills: vi.fn(() => of({ rows: [], meta: {} })),
          getReconciliation: vi.fn(() => of(recon)),
          exportStatement: vi.fn(() => of(new Blob())),
          exportAgeing: vi.fn(() => of(new Blob())),
        },
      },
      { provide: SupplierService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: SessionStore, useValue: makeSession() },
    ],
  });
}

describe('SupplierStatementComponent', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); recon = MOCK_RECON; });

  it('loads the AP-to-GL reconciliation with the company, before any supplier is picked', () => {
    makeBed();
    const comp = TestBed.createComponent(SupplierStatementComponent).componentInstance as any;
    const ap = TestBed.inject(ApService) as any;
    expect(ap.getReconciliation).toHaveBeenCalledWith('10');
    expect(comp.selectedSupplier()).toBeNull();
    expect(comp.reconciled()).toBe(true);
  });

  it('reads "out by X" when the sub-ledger and the GL control account disagree', () => {
    recon = { ...MOCK_RECON, glControlBalance: 7150, difference: -150 };
    makeBed();
    const fixture = TestBed.createComponent(SupplierStatementComponent);
    fixture.detectChanges();
    const comp = fixture.componentInstance as any;
    expect(comp.reconciled()).toBe(false);
    expect(comp.absDifference()).toBe(150);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Out by TZS 150.00');
    expect(text).toContain('The GL control account is higher than the supplier sub-ledger.');
  });

  it('exportStatement sends the company, the picked supplier uid, the period and the format', () => {
    makeBed();
    const comp = TestBed.createComponent(SupplierStatementComponent).componentInstance as any;
    const ap = TestBed.inject(ApService) as any;
    comp.selectedSupplier.set({ uid: 'SUPUID', label: 'S1 — Supplier' });
    comp.exportFrom.set('2026-09-01');
    comp.exportTo.set('2026-09-30');
    const createObjectURL = vi.fn(() => 'blob:x');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    comp.exportStatement('XLSX');
    expect(ap.exportStatement).toHaveBeenCalledWith('10', 'SUPUID', '2026-09-01', '2026-09-30', 'XLSX');
    expect(comp.exporting()).toBe(false);
  });

  it('sortedAgeing orders buckets canonically (CURRENT first, D90_PLUS last)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(SupplierStatementComponent).componentInstance as any;
    comp.ageing.set(MOCK_AGEING);
    const sorted = comp.sortedAgeing();
    expect(sorted[0].bucket).toBe('CURRENT');
    expect(sorted[1].bucket).toBe('D1_30');
    expect(sorted[2].bucket).toBe('D90_PLUS');
  });

  it('outstandingBalance coerces number from balance signal', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(SupplierStatementComponent).componentInstance as any;
    comp.balance.set(MOCK_BALANCE);
    expect(comp.outstandingBalance()).toBe(7000);
  });

  it('fmtMoney coerces number wire value correctly', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(SupplierStatementComponent).componentInstance as any;
    // Money arrives as number on the wire — must NOT call .startsWith/.trim on it
    expect(comp.fmtMoney(2000)).toBe('2000.00');
    expect(comp.fmtMoney(0)).toBe('0.00');
    expect(comp.fmtMoney(null)).toBe('0.00');
    expect(comp.fmtMoney(undefined)).toBe('0.00');
    expect(comp.fmtMoney('1500.5')).toBe('1500.50');
  });

  it('isEmpty true initially before any supplier selected', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(SupplierStatementComponent).componentInstance as any;
    expect(comp.isEmpty()).toBe(true);
  });

  it('bucketLabel returns human-readable strings', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(SupplierStatementComponent).componentInstance as any;
    expect(comp.bucketLabel('CURRENT')).toBe('Current');
    expect(comp.bucketLabel('D90_PLUS')).toBe('90+ days');
  });
});
