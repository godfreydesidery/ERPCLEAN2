/**
 * Accessibility gate — CustomerListComponent.
 *
 * Checks the list screen (most common data-entry + table pattern in the app).
 * Color-contrast and scrollable-region-focusable disabled (jsdom limitation).
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { CustomerService } from './customer.service';
import type { CustomerPage } from './customer.service';
import { CustomerListComponent } from './customer-list.component';
import { assertA11y } from '../../../../testing/a11y.helper';

const emptyPage = (): CustomerPage => ({
  rows: [],
  meta: { page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false },
});

const oneRow = (): CustomerPage => ({
  rows: [
    {
      uid: 'C1', id: '1', displayName: 'Acme Ltd',
      partyType: 'BUSINESS', tin: '123-456-789',
      vatRegistered: false, vrn: null, companyId: '10',
    } as any,
  ],
  meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false },
});

function makeBed(listReturnValue: CustomerPage) {
  TestBed.configureTestingModule({
    imports: [CustomerListComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      {
        provide: CustomerService,
        useValue: { list: vi.fn(() => of(listReturnValue)), create: vi.fn(() => of({})) },
      },
      {
        provide: OrganisationService,
        useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) },
      },
      {
        provide: CompanyService,
        useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) },
      },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => false),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });
}

describe('CustomerListComponent — a11y', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('has no axe violations in the empty-state', async () => {
    makeBed(emptyPage());
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(CustomerListComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers(); // restore before running axe (axe uses real timers internally)
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);

  it('has no axe violations when the table is populated', async () => {
    makeBed(oneRow());
    vi.useFakeTimers();
    const fixture = TestBed.createComponent(CustomerListComponent);
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
