/**
 * JournalEntryListComponent spec (ACC-19): the journal list can be searched and filtered, and the
 * document column shows the source document's number instead of a truncated ULID.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from './gl.service';
import { JournalEntryListComponent } from './journal-entry-list.component';
import type { JournalEntryDto } from './models/gl.model';

const SALE: JournalEntryDto = {
  id: '1', uid: 'JE1', companyId: '10', batchNumber: 'JB-0001', postingDate: '2026-01-05',
  description: 'Sale 01HZZZZZZZZZZZZZZZZZZZZZZZ', sourceType: 'SALES',
  sourceRef: '01HZZZZZZZZZZZZZZZZZZZZZZZ', reversalOfId: null, documentRef: 'INV-0453',
  lines: [{ lineNo: 1, accountCode: '1000', accountName: 'Cash', debitAmount: '118', creditAmount: '0', currency: 'TZS', lineMemo: null }],
};

function makeBed() {
  const listSpy = vi.fn(() => of({ rows: [SALE], meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false } }));
  TestBed.configureTestingModule({
    imports: [JournalEntryListComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      {
        provide: GlService,
        useValue: {
          listJournals: listSpy,
          listAllActiveAccounts: vi.fn(() => of([{ id: '5', uid: 'ACC5', companyId: '10', accountCode: '1300', name: 'Inventory', accountType: 'ASSET', normalBalance: 'DEBIT', active: true, status: 'ACTIVE' }])),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true), user: signal(null), permissions: signal([]), activeBranchUid: signal(null),
        },
      },
    ],
  });
  return { listSpy };
}

describe('JournalEntryListComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('shows the document number, not the ULID', () => {
    makeBed();
    const fixture = TestBed.createComponent(JournalEntryListComponent);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('INV-0453');
    expect(text).not.toContain('01HZZZZZZZZZ…');
  });

  it('sends the filters and clears them', () => {
    const { listSpy } = makeBed();
    const fixture = TestBed.createComponent(JournalEntryListComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.searchText.set(' INV-0453 ');
    c.fromDate.set('2026-01-01');
    c.toDate.set('2026-01-31');
    c.sourceType.set('SALES');
    c.accountUid.set('ACC5');
    c.applyFilters();
    expect(listSpy).toHaveBeenLastCalledWith('10', 0, 20, {
      from: '2026-01-01', to: '2026-01-31', sourceType: 'SALES', accountUid: 'ACC5', q: 'INV-0453',
    });
    expect(c.hasFilter()).toBe(true);
    c.clearFilters();
    expect(listSpy).toHaveBeenLastCalledWith('10', 0, 20, {
      from: undefined, to: undefined, sourceType: undefined, accountUid: undefined, q: undefined,
    });
  });

  it('falls back to a shortened ref when no document number is known', () => {
    makeBed();
    const fixture = TestBed.createComponent(JournalEntryListComponent);
    const c = fixture.componentInstance;
    expect(c.documentLabel({ ...SALE, documentRef: null })).toBe('01HZZZZZZZZZ…');
    expect(c.documentLabel({ ...SALE, documentRef: null, sourceRef: null })).toBe('—');
  });

  it('has no axe violations with the filter bar', async () => {
    makeBed();
    const fixture = TestBed.createComponent(JournalEntryListComponent);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
