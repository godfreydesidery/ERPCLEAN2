import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { AccountDto, PostJournalLineRequest, PostJournalRequest } from './models/gl.model';
import { GlService } from './gl.service';

interface DraftLine {
  /** Local UI key for @for track. */
  localId: number;
  accountUid: string;
  debitAmount: string;
  creditAmount: string;
  lineMemo: string;
}

let _lineIdSeq = 0;
function nextLineId(): number { return ++_lineIdSeq; }

/**
 * Manual journal entry form. Gated GL.POST.
 * Mirror of the sales invoice line editor pattern:
 * - dynamic lines editor (add / remove line)
 * - running Debits / Credits / Difference totals
 * - Post disabled until balanced (debits === credits, >= 2 lines)
 * - Client-side guard mirrors the server rule; server is still authoritative
 * - On success navigate to the entry detail
 *
 * IMPORTANT: amount inputs use String(v ?? '').trim() — never .trim() directly on
 * a signal value which may be non-string (the crash we've hit before).
 */
@Component({
  selector: 'app-post-journal',
  imports: [FormsModule, RouterLink],
  templateUrl: './post-journal.component.html',
  styleUrl: './post-journal.component.scss',
})
export class PostJournalComponent {
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Account picker ─────────────────────────────────────────────────────────
  readonly accounts = signal<AccountDto[]>([]);
  readonly accountsState = signal<'idle' | 'loading' | 'error'>('idle');

  // ── Header fields ──────────────────────────────────────────────────────────
  readonly postingDate = signal('');
  readonly description = signal('');
  readonly sourceRef = signal('');

  // ── Lines ──────────────────────────────────────────────────────────────────
  readonly lines = signal<DraftLine[]>([
    { localId: nextLineId(), accountUid: '', debitAmount: '', creditAmount: '', lineMemo: '' },
    { localId: nextLineId(), accountUid: '', debitAmount: '', creditAmount: '', lineMemo: '' },
  ]);

  // ── Submit state ───────────────────────────────────────────────────────────
  readonly posting = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Running totals (computed from line signals) ────────────────────────────
  readonly totalDebits = computed(() =>
    this.lines().reduce((sum, l) => {
      const v = Number.parseFloat(String(l.debitAmount ?? '').trim() || '0');
      return sum + (Number.isFinite(v) ? v : 0);
    }, 0),
  );

  readonly totalCredits = computed(() =>
    this.lines().reduce((sum, l) => {
      const v = Number.parseFloat(String(l.creditAmount ?? '').trim() || '0');
      return sum + (Number.isFinite(v) ? v : 0);
    }, 0),
  );

  readonly difference = computed(() =>
    Math.abs(this.totalDebits() - this.totalCredits()),
  );

  readonly isBalanced = computed(() =>
    this.lines().length >= 2 &&
    this.totalDebits() > 0 &&
    this.difference() < 0.000001,
  );

  readonly canPost = computed(() => this.session.hasPermission('GL.POST'));

  /** Post button disabled when: not balanced, no company, no date, no description, or posting. */
  readonly postDisabled = computed(() =>
    !this.isBalanced() ||
    !this.selectedCompanyId() ||
    !String(this.postingDate() ?? '').trim() ||
    !String(this.description() ?? '').trim() ||
    this.posting(),
  );

  constructor() {
    this.loadCompanies();
    // Default posting date to today.
    this.postingDate.set(new Date().toISOString().slice(0, 10));
  }

  private loadCompanies(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
              this.loadAccounts(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadAccounts(companyId: string): void {
    this.accountsState.set('loading');
    this.glService.listAllActiveAccounts(companyId).subscribe({
      next: (list) => {
        this.accounts.set(list);
        this.accountsState.set('idle');
      },
      error: () => this.accountsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.accounts.set([]);
    if (id) this.loadAccounts(id);
  }

  // ── Line editor ────────────────────────────────────────────────────────────

  addLine(): void {
    this.lines.update((ls) => [
      ...ls,
      { localId: nextLineId(), accountUid: '', debitAmount: '', creditAmount: '', lineMemo: '' },
    ]);
  }

  removeLine(localId: number): void {
    if (this.lines().length <= 2) return; // minimum 2 lines
    this.lines.update((ls) => ls.filter((l) => l.localId !== localId));
  }

  updateLine(localId: number, field: keyof Omit<DraftLine, 'localId'>, value: string): void {
    this.lines.update((ls) =>
      ls.map((l) => l.localId === localId ? { ...l, [field]: value } : l),
    );
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  post(): void {
    if (this.postDisabled()) return;

    const companyId = this.selectedCompanyId();
    const company = this.companies().find((c) => c.id === companyId);
    if (!company) {
      this.formError.set('Could not resolve selected company.');
      return;
    }

    const date = String(this.postingDate() ?? '').trim();
    const desc = String(this.description() ?? '').trim();
    const ref = String(this.sourceRef() ?? '').trim();

    if (!date) { this.formError.set('Posting date is required.'); return; }
    if (!desc) { this.formError.set('Description is required.'); return; }

    // Validate each line has an account and at least one non-zero amount.
    for (const line of this.lines()) {
      if (!line.accountUid) {
        this.formError.set('All lines must have an account selected.');
        return;
      }
      const dr = Number.parseFloat(String(line.debitAmount ?? '').trim() || '0');
      const cr = Number.parseFloat(String(line.creditAmount ?? '').trim() || '0');
      if (dr === 0 && cr === 0) {
        this.formError.set('Each line must have a debit or credit amount.');
        return;
      }
      if (dr > 0 && cr > 0) {
        this.formError.set('A line cannot have both a debit and a credit amount.');
        return;
      }
    }

    const postLines: PostJournalLineRequest[] = this.lines().map((l) => ({
      accountUid: l.accountUid,
      debitAmount: String(l.debitAmount ?? '').trim() || '0',
      creditAmount: String(l.creditAmount ?? '').trim() || '0',
      lineMemo: String(l.lineMemo ?? '').trim() || undefined,
    }));

    const request: PostJournalRequest = {
      companyUid: company.uid,
      postingDate: date,
      description: desc,
      sourceType: 'MANUAL',
      sourceRef: ref || undefined,
      lines: postLines,
    };

    this.posting.set(true);
    this.formError.set(null);

    this.glService.postJournal(request).subscribe({
      next: (created) => {
        this.posting.set(false);
        this.alerts.success('Journal posted', created.batchNumber);
        this.router.navigate(['/admin/gl/journals/uid', created.uid]);
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not post journal entry.'));
        this.posting.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtAmount(n: number): string {
    return n.toFixed(2);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
