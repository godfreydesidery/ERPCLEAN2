/**
 * PostingExceptionsComponent spec (ACC-02).
 *
 *  1. Lists the open exceptions for the active company with the default month filter.
 *  2. Apply sends the source/date/resolved filters and reloads the tie-out for the same dates.
 *  3. Re-post is offered only to GL.POST holders, sends the optional date, then refreshes.
 *  4. A refused re-post shows the server's user-safe message and keeps the panel open.
 *  5. No axe violations with rows, tie-out and the re-post panel open.
 */
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { assertA11y } from '../../../../testing/a11y.helper';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { GlService } from './gl.service';
import type { GlPostingExceptionDto } from './models/gl.model';
import { PostingExceptionsComponent } from './posting-exceptions.component';

const ROW: GlPostingExceptionDto = {
  uid: 'EX1', kind: 'SALE', sourceType: 'SALES', sourceRef: '01HZZZZZZZZZZZZZZZZZZZZZZZ',
  documentNumber: null, postingDate: '2026-01-05', amount: 118, reason: 'The fiscal period January 2026 is closed.',
  failedAt: '2026-01-05T10:00:00Z', status: 'OPEN', resolvedAt: null, resolvedBy: null, outcome: null,
  journalEntryUid: null, batchNumber: null,
};

const TIE_OUT = {
  from: '2026-01-01', to: '2026-01-31', salesNet: 1000, salesVat: 180, glRevenue: 900, glVat: 162,
  revenueDifference: 100, vatDifference: 18,
};

function makeBed(opts: { canPost?: boolean; repost?: () => unknown } = {}) {
  const { canPost = true } = opts;
  const listSpy = vi.fn(() => of({ rows: [ROW], meta: { page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false } }));
  const tieSpy = vi.fn(() => of(TIE_OUT));
  const repostSpy = vi.fn(opts.repost ?? (() => of({
    exceptionUid: 'EX1', outcome: 'REPOSTED', journalEntryUid: 'JE1', batchNumber: 'JB-0009', postingDate: '2026-01-05',
  })));
  TestBed.configureTestingModule({
    imports: [PostingExceptionsComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      {
        provide: GlService,
        useValue: { listPostingExceptions: listSpy, getSalesTieOut: tieSpy, repostPostingException: repostSpy },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn((code: string) => code === 'GL.VIEW' || (code === 'GL.POST' && canPost)),
          isAuthenticated: signal(true), user: signal(null), permissions: signal([]), activeBranchUid: signal(null),
        },
      },
    ],
  });
  return { listSpy, tieSpy, repostSpy };
}

describe('PostingExceptionsComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('lists open exceptions and the tie-out for the active company', () => {
    const { listSpy, tieSpy } = makeBed();
    const fixture = TestBed.createComponent(PostingExceptionsComponent);
    fixture.detectChanges();
    expect(listSpy).toHaveBeenCalledWith('10', expect.objectContaining({ includeResolved: false }), 0, 20);
    expect(tieSpy).toHaveBeenCalled();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('is closed');
    expect(text).toContain('118.00');
    expect(fixture.componentInstance.tieOutBalanced()).toBe(false);
  });

  it('applies the filters to the list and the tie-out', () => {
    const { listSpy, tieSpy } = makeBed();
    const fixture = TestBed.createComponent(PostingExceptionsComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.sourceType.set('COGS');
    c.from.set('2026-02-01');
    c.to.set('2026-02-28');
    c.includeResolved.set(true);
    c.refresh();
    expect(listSpy).toHaveBeenLastCalledWith('10',
      { sourceType: 'COGS', from: '2026-02-01', to: '2026-02-28', includeResolved: true }, 0, 20);
    expect(tieSpy).toHaveBeenLastCalledWith('10', '2026-02-01', '2026-02-28');
  });

  it('re-posts with the chosen date and refreshes', () => {
    const { repostSpy, listSpy } = makeBed();
    const fixture = TestBed.createComponent(PostingExceptionsComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.openRepost(ROW);
    c.repostDate.set('2026-02-03');
    c.confirmRepost();
    expect(repostSpy).toHaveBeenCalledWith('10', 'EX1', '2026-02-03');
    expect(c.repostTarget()).toBeNull();
    expect(c.notice()).toContain('JB-0009');
    expect(listSpy).toHaveBeenCalledTimes(2);
  });

  it('hides the re-post action without GL.POST', () => {
    makeBed({ canPost: false });
    const fixture = TestBed.createComponent(PostingExceptionsComponent);
    fixture.detectChanges();
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .filter((b) => (b.textContent ?? '').includes('Re-post'));
    expect(buttons.length).toBe(0);
  });

  it('shows the refusal and keeps the panel open', () => {
    makeBed({
      repost: () => throwError(() => new HttpErrorResponse({
        status: 409, error: { errors: ['The posting still fails: the period is closed.'] },
      })),
    });
    const fixture = TestBed.createComponent(PostingExceptionsComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.openRepost(ROW);
    c.confirmRepost();
    expect(c.repostError()).toContain('still fails');
    expect(c.repostTarget()).not.toBeNull();
  });

  it('has no axe violations with the re-post panel open', async () => {
    makeBed();
    const fixture = TestBed.createComponent(PostingExceptionsComponent);
    fixture.detectChanges();
    fixture.componentInstance.openRepost(ROW);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
