import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, shareReplay, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { UidOption } from '../../../shared/uid-picker/uid-picker.component';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { Company } from '../models/company.model';
import { OrganisationService } from '../organisation/organisation.service';

/**
 * Filter-picker options shared by the operational report screens (Sales Summary, Payment Summary,
 * Reorder, Stock Ageing).
 *
 * The branch picker offers only the branches the caller can READ: the report endpoints refuse a
 * branch filter the caller is not assigned to, so listing every branch would offer choices that
 * always fail. GET /auth/my-branches is self-scoped and needs no BRANCH.VIEW. Root forks to the full
 * company list, because the server exempts root from the assignment check but root usually holds
 * one assignment or none — narrowing would hide branches root may read. The same rule the
 * Profitability and Stock Value screens apply, kept in one place for the new screens.
 *
 * Every failure is non-fatal: an empty picker means "all branches", which is the report's default.
 */
@Injectable({ providedIn: 'root' })
export class ReportFilterOptionsService {
  private readonly organisationService = inject(OrganisationService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionStore);

  /** The caller's first accessible company — the one the report pickers are loaded against. */
  company(): Observable<Company | null> {
    return this.organisationService.current().pipe(
      switchMap((org) => this.companyService.list(org.uid)),
      map((list) => list[0] ?? null),
      catchError(() => of(null)),
      shareReplay({ bufferSize: 1, refCount: true }),
    );
  }

  branchOptions(company$: Observable<Company | null> = this.company()): Observable<UidOption[]> {
    return company$.pipe(
      switchMap((company) => {
        if (!company) return of<UidOption[]>([]);
        if (this.session.user()?.isRoot === true) {
          return this.branchService.list(company.uid).pipe(
            map((branches) =>
              branches
                .filter((b) => b.status === 'ACTIVE')
                .map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
            ),
          );
        }
        return this.auth.myBranches().pipe(
          map((branches) =>
            branches
              .filter((b) => b.companyUid === company.uid)
              .map((b) => ({ uid: b.branchUid, label: b.branchName, hint: b.branchCode })),
          ),
        );
      }),
      catchError(() => of<UidOption[]>([])),
    );
  }
}

/** Prefers the server's user-safe sentence (it names the remedy); otherwise the given fallback. */
export function serverMessage(err: unknown, fallback: string): string {
  const errors = (err as { error?: { errors?: unknown } } | null)?.error?.errors;
  if (Array.isArray(errors) && typeof errors[0] === 'string' && errors[0]) return errors[0];
  return fallback;
}

/** Money to two places; an UNKNOWN amount is an em dash, never 0.00. */
export function fmtMoneyOrDash(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = +v;
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : '—';
}

/** Quantities: whole numbers plainly, weighed goods to three places. */
export function fmtQuantity(v: number | string | null | undefined): string {
  const n = +(v ?? 0);
  return Number.isFinite(n)
    ? n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 3 })
    : '0';
}

/** A percentage to two places, or an em dash when it cannot be worked out. */
export function fmtPercentOrDash(v: number | string | null | undefined): string {
  if (v === null || v === undefined || v === '') return '—';
  const n = +v;
  return Number.isFinite(n)
    ? `${n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`
    : '—';
}

/** Today in the browser's calendar, as yyyy-MM-dd. */
export function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function firstOfMonthIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`;
}
