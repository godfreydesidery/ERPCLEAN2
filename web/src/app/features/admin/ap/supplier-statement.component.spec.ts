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
//  1. Ageing bucket order is canonical (CURRENT first, DAYS_91_PLUS last).
//  2. Money fields coerced as numbers — +(b.amount) === 0 renders '—', positive renders formatted.
//  3. outstandingBalance computed from balance.outstandingBalance (number on wire).

const MOCK_AGEING = [
  { bucket: 'DAYS_91_PLUS', amount: 5000, currency: 'TZS' },
  { bucket: 'CURRENT', amount: 0, currency: 'TZS' },
  { bucket: 'DAYS_1_30', amount: 2000, currency: 'TZS' },
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
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('sortedAgeing orders buckets canonically (CURRENT first, DAYS_91_PLUS last)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(SupplierStatementComponent).componentInstance as any;
    comp.ageing.set(MOCK_AGEING);
    const sorted = comp.sortedAgeing();
    expect(sorted[0].bucket).toBe('CURRENT');
    expect(sorted[1].bucket).toBe('DAYS_1_30');
    expect(sorted[2].bucket).toBe('DAYS_91_PLUS');
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
    expect(comp.bucketLabel('DAYS_91_PLUS')).toBe('90+ days');
  });
});
