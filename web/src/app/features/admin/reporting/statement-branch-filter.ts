import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, map, of, catchError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { UidOption } from '../../../shared/uid-picker/uid-picker.component';
import { BranchService } from '../branch/branch.service';
import { StatementBranchFilter } from './models/reporting.model';

/**
 * Picker value meaning "the company-level journals that carry no branch" (manual journals,
 * opening balances, FX revaluation, year-end close). Not a uid — it maps to `unassigned=true`.
 * A real branch uid is a 26-character ULID, so this can never collide with one.
 */
export const COMPANY_LEVEL_OPTION = '__company_level__';

/**
 * What a branch-filtered statement means, printed under it. The screen must say it: a branch
 * statement that silently disagreed with what the branch holds would be read as a bug.
 */
export const BRANCH_STATEMENT_NOTE =
  'This statement shows only the journals posted at the chosen branch. Stock transfers between ' +
  'branches move quantities but no ledger value, so a branch that receives stock can show low or ' +
  'even negative Inventory, and the sender too much. Manual journals, opening balances, FX ' +
  'revaluation and the year-end close carry no branch — they appear only under "Company-level ' +
  'entries". The branches plus the company-level entries always add up to the whole company.';

/**
 * Loads the branch options a statement screen may offer for one company: the branches the caller
 * is assigned to (root: every active branch — the server exempts root from the assignment check),
 * optionally followed by the "company-level entries" slice.
 */
@Injectable({ providedIn: 'root' })
export class StatementBranchOptionsService {
  private readonly branchService = inject(BranchService);
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionStore);

  load(companyUid: string, includeCompanyLevel: boolean): Observable<UidOption[]> {
    const branches$: Observable<UidOption[]> =
      this.session.user()?.isRoot === true
        ? this.branchService.list(companyUid).pipe(
            map((list) =>
              list
                .filter((b) => b.status === 'ACTIVE')
                .map((b) => ({ uid: b.uid, label: b.name, hint: b.code })),
            ),
          )
        : this.auth.myBranches().pipe(
            map((list) =>
              list
                .filter((b) => b.companyUid === companyUid)
                .map((b) => ({ uid: b.branchUid, label: b.branchName, hint: b.branchCode })),
            ),
          );
    return branches$.pipe(
      catchError(() => of([] as UidOption[])),
      map((options) =>
        includeCompanyLevel
          ? [...options, { uid: COMPANY_LEVEL_OPTION, label: 'Company-level entries (no branch)' }]
          : options,
      ),
    );
  }
}

/**
 * Per-screen branch filter state: the picked value, its options, and the request filter it maps to.
 * Create one in a component field initializer (it calls inject()).
 */
export class StatementBranchFilterState {
  private readonly optionsService = inject(StatementBranchOptionsService);

  /** '' = all branches; a branch uid; or {@link COMPANY_LEVEL_OPTION}. */
  readonly value = signal('');
  readonly options = signal<UidOption[]>([]);
  readonly isScoped = computed(() => this.value() !== '');

  constructor(private readonly includeCompanyLevel: boolean) {}

  /** Reloads the options for a company and clears a pick that belonged to the previous one. */
  loadFor(companyUid: string | null | undefined): void {
    this.value.set('');
    this.options.set([]);
    if (!companyUid) return;
    this.optionsService
      .load(companyUid, this.includeCompanyLevel)
      .subscribe((options) => this.options.set(options));
  }

  filter(): StatementBranchFilter {
    const v = this.value();
    if (!v) return {};
    if (v === COMPANY_LEVEL_OPTION) return { unassigned: true };
    return { branchUid: v };
  }
}
