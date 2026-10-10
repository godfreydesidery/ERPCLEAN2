/**
 * JournalEntryDetailComponent spec (ACC-26): reversing a manual journal asks for a date and a
 * reason and sends both; system journals offer no reverse action.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { assertA11y } from '../../../../testing/a11y.helper';
import { GlService } from './gl.service';
import { JournalEntryDetailComponent } from './journal-entry-detail.component';
import type { JournalEntryDto } from './models/gl.model';

const MANUAL: JournalEntryDto = {
  id: '9', uid: 'JE9', companyId: '10', batchNumber: 'JB-0009', postingDate: '2026-09-30',
  description: 'September accrual', sourceType: 'MANUAL', sourceRef: null, reversalOfId: null, reversed: false,
  lines: [
    { lineNo: 1, accountCode: '5200', accountName: 'Rent', debitAmount: '100', creditAmount: '0', currency: 'TZS', lineMemo: null },
    { lineNo: 2, accountCode: '3000', accountName: 'Capital', debitAmount: '0', creditAmount: '100', currency: 'TZS', lineMemo: null },
  ],
};

async function create(entry: JournalEntryDto) {
  const reverseSpy = vi.fn(() => of({ ...MANUAL, uid: 'JE10', batchNumber: 'JB-0010' }));
  TestBed.configureTestingModule({
    imports: [JournalEntryDetailComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: GlService, useValue: { getJournalByUid: vi.fn(() => of(entry)), reverseJournal: reverseSpy } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => true),
          isAuthenticated: signal(true), user: signal(null), permissions: signal([]), activeBranchUid: signal(null),
        },
      },
    ],
  });
  const router = TestBed.inject(Router);
  vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(JournalEntryDetailComponent);
  fixture.componentRef.setInput('uid', entry.uid);
  fixture.detectChanges();
  await Promise.resolve();
  fixture.detectChanges();
  return { fixture, reverseSpy };
}

describe('JournalEntryDetailComponent — reversal', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('requires a reason, then sends the chosen date and reason', async () => {
    const { fixture, reverseSpy } = await create(MANUAL);
    const c = fixture.componentInstance;
    expect(c.canReverse()).toBe(true);
    c.openReverseForm();
    c.reverse();
    expect(reverseSpy).not.toHaveBeenCalled();
    expect(c.reverseError()).toContain('reason');

    c.reversalDate.set('2026-09-30');
    c.reversalReason.set('Wrong account');
    c.reverse();
    expect(reverseSpy).toHaveBeenCalledWith('JE9', '2026-09-30', 'Wrong account');
  });

  it('offers no reversal for a system journal or one already reversed', async () => {
    const { fixture } = await create({ ...MANUAL, sourceType: 'SALES' });
    expect(fixture.componentInstance.canReverse()).toBe(false);
    TestBed.resetTestingModule();
    const again = await create({ ...MANUAL, reversed: true });
    expect(again.fixture.componentInstance.canReverse()).toBe(false);
  });

  it('has no axe violations with the reversal form open', async () => {
    const { fixture } = await create(MANUAL);
    fixture.componentInstance.openReverseForm();
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
