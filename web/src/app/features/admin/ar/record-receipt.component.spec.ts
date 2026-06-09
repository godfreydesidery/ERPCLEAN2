import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { RecordReceiptComponent } from './record-receipt.component';
import { ArService } from './ar.service';
import { CustomerService } from '../parties/customer.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';

// The centerpiece of the AR receipt screen is the client-side allocation GUARD:
// submit is disabled when allocated total > receipt amount, or any line allocation > that
// invoice's outstanding. Money fields arrive as NUMBERS on the wire — the component coerces
// with +(...), so outstandingAmount as a number must work. These tests drive the computeds
// directly (the highest-value, lowest-flake surface).

function makeSession(canRecord = true) {
  return {
    hasPermission: vi.fn(() => canRecord),
    isAuthenticated: signal(true),
    user: signal(null),
    permissions: signal([]),
    activeBranchUid: signal(null),
  };
}

function makeBed(canRecord = true) {
  TestBed.configureTestingModule({
    imports: [RecordReceiptComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([{ path: '**', redirectTo: '' }]),
      { provide: ArService, useValue: { listOpenInvoices: vi.fn(() => of([])), recordReceipt: vi.fn(() => of({})) } },
      { provide: CustomerService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: makeSession(canRecord) },
    ],
  });
}

/** Build the component into a "ready to submit" valid state, then let each test perturb it. */
function primeValid(comp: any) {
  comp.selectedCompanyId.set('10');
  comp.selectedCustomer.set({ uid: 'CUST1', label: 'Acme Ltd' });
  comp.receiptAmount.set('100');
  comp.receiptDate.set('2026-06-09');
  // two open invoices; money arrives as NUMBERS on the wire
  comp.allocationRows.set([
    { invoice: { uid: 'ARI1', outstandingAmount: 60 } as any, allocInput: '60' },
    { invoice: { uid: 'ARI2', outstandingAmount: 40 } as any, allocInput: '40' },
  ]);
}

describe('RecordReceiptComponent — allocation guard', () => {
  afterEach(() => { vi.useRealTimers(); TestBed.resetTestingModule(); });

  it('submit disabled initially (no customer / amount)', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordReceiptComponent).componentInstance as any;
    expect(comp.submitDisabled()).toBe(true);
  });

  it('valid balanced allocation → not over-allocated, submit enabled, zero unallocated', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordReceiptComponent).componentInstance as any;
    primeValid(comp);
    expect(comp.allocatedTotal()).toBeCloseTo(100, 5);
    expect(comp.overAllocated()).toBe(false);
    expect(comp.anyAllocationExceedsOutstanding()).toBe(false);
    expect(comp.unallocated()).toBeCloseTo(0, 5);
    expect(comp.submitDisabled()).toBe(false);
  });

  it('over-allocation (allocated > receipt amount) → overAllocated true, submit disabled', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordReceiptComponent).componentInstance as any;
    primeValid(comp);
    comp.allocationRows.set([
      { invoice: { uid: 'ARI1', outstandingAmount: 200 } as any, allocInput: '90' },
      { invoice: { uid: 'ARI2', outstandingAmount: 200 } as any, allocInput: '60' }, // total 150 > 100
    ]);
    expect(comp.overAllocated()).toBe(true);
    expect(comp.submitDisabled()).toBe(true);
  });

  it('a line allocation exceeding its invoice outstanding → guard trips, submit disabled', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordReceiptComponent).componentInstance as any;
    primeValid(comp);
    comp.receiptAmount.set('100');
    comp.allocationRows.set([
      { invoice: { uid: 'ARI1', outstandingAmount: 40 } as any, allocInput: '90' }, // 90 > 40 outstanding
    ]);
    expect(comp.anyAllocationExceedsOutstanding()).toBe(true);
    expect(comp.submitDisabled()).toBe(true);
  });

  it('on-account remainder allowed: allocate less than receipt → positive unallocated, submit enabled', () => {
    vi.useFakeTimers();
    makeBed();
    const comp = TestBed.createComponent(RecordReceiptComponent).componentInstance as any;
    primeValid(comp);
    comp.allocationRows.set([
      { invoice: { uid: 'ARI1', outstandingAmount: 60 } as any, allocInput: '60' }, // allocate 60 of 100
      { invoice: { uid: 'ARI2', outstandingAmount: 40 } as any, allocInput: '' },
    ]);
    expect(comp.allocatedTotal()).toBeCloseTo(60, 5);
    expect(comp.unallocated()).toBeCloseTo(40, 5);
    expect(comp.overAllocated()).toBe(false);
    expect(comp.submitDisabled()).toBe(false);
  });
});
