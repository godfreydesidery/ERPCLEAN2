/**
 * Accessibility gate — SupplierStatementComponent.
 *
 * Covers the company-wide AP-to-GL reconciliation box (reconciled and out-of-balance states) and the
 * Printable Statement box that appears once a supplier is picked.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { SupplierService } from '../parties/supplier.service';
import { ApService } from './ap.service';
import { SupplierStatementComponent } from './supplier-statement.component';
import { assertA11y } from '../../../../testing/a11y.helper';

let difference = 0;

function makeBed() {
  TestBed.configureTestingModule({
    imports: [SupplierStatementComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: ApService,
        useValue: {
          getBalance: vi.fn(() => of({ companyId: '10', supplierId: '1', outstandingBalance: 0, currency: 'TZS' })),
          getAgeing: vi.fn(() => of([])),
          listBills: vi.fn(() => of({ rows: [], meta: {} })),
          getReconciliation: vi.fn(() => of({
            companyId: '10', subLedgerTotal: 7000, glControlBalance: 7000 - difference,
            difference, currency: 'TZS',
          })),
          exportStatement: vi.fn(() => of(new Blob())),
          exportAgeing: vi.fn(() => of(new Blob())),
        },
      },
      { provide: SupplierService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })) } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('SupplierStatementComponent — a11y', () => {
  afterEach(() => { TestBed.resetTestingModule(); difference = 0; });

  it('has no axe violations with the reconciliation reconciled', async () => {
    makeBed();
    const fixture = TestBed.createComponent(SupplierStatementComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when out of balance and a supplier is picked (printable box shown)', async () => {
    difference = 150;
    makeBed();
    const fixture = TestBed.createComponent(SupplierStatementComponent);
    fixture.componentInstance.selectedSupplier.set({ uid: 'SUP1', label: 'S1 — Supplier' });
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
