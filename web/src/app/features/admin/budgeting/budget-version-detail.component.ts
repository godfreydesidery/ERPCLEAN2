import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { GlService } from '../gl/gl.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  BudgetLineDto,
  BudgetVersionDto,
  EntryMode,
  LineInputDto,
  UpsertBudgetLineRequest,
} from './models/budgeting.model';
import { BudgetingService } from './budgeting.service';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Budget version detail screen — shows lines and allows upsert when DRAFT.
 * Route: /admin/budget-versions/uid/:uid
 */
@Component({
  selector: 'app-budget-version-detail',
  imports: [FormsModule, RouterLink, DecimalPipe, UidPickerComponent],
  templateUrl: './budget-version-detail.component.html',
  styleUrl: './budget-version-detail.component.scss',
})
export class BudgetVersionDetailComponent {
  private readonly budgetingService = inject(BudgetingService);
  private readonly glService = inject(GlService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Picker options ────────────────────────────────────────────────────────
  readonly accountOptions = signal<UidOption[]>([]);
  readonly fiscalPeriodOptions = signal<UidOption[]>([]);
  readonly budgetVersionOptions = signal<UidOption[]>([]);

  /** Route input bound via withComponentInputBinding. */
  readonly uid = input.required<string>();

  // ── Version state ─────────────────────────────────────────────────────────
  readonly version = signal<BudgetVersionDto | null>(null);
  readonly versionState = signal<LoadState>('loading');

  // ── Upsert lines form ─────────────────────────────────────────────────────
  readonly showUpsertForm = signal(false);
  readonly upsertMode = signal<EntryMode>('DIRECT');

  // DIRECT mode
  readonly directLines = signal<Array<{ accountUid: string; fiscalPeriodUid: string; amount: string; lineMemo: string }>>([]);

  // ANNUAL_SPREAD mode
  readonly fSpreadAccountUid = signal('');
  readonly fAnnualAmount = signal('');

  // SEED mode
  readonly fSeedVersionUid = signal('');

  readonly upserting = signal(false);
  readonly upsertError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('BUDGETING.BUDGET.MANAGE'));
  readonly isDraft = computed(() => this.version()?.status === 'DRAFT');
  readonly canEditLines = computed(() => this.canManage() && this.isDraft());

  readonly modeOptions: Array<{ value: EntryMode; label: string }> = [
    { value: 'DIRECT', label: 'Direct entry (line by line)' },
    { value: 'ANNUAL_SPREAD', label: 'Annual spread (spread evenly over 12 periods)' },
    { value: 'SEED', label: 'Seed from another version' },
  ];

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadVersion();
  }

  private loadVersion(): void {
    this.versionState.set('loading');
    this.budgetingService.getVersionByUid(this.uid()).subscribe({
      next: (v) => {
        this.version.set(v);
        this.versionState.set('idle');
        this.loadPickerOptions(v);
      },
      error: () => this.versionState.set('error'),
    });
  }

  private loadPickerOptions(v: BudgetVersionDto): void {
    // Resolve companyId (numeric string) → companyUid for GL account/period lists.
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (companies) => {
            const company = companies.find((c) => c.id === v.companyId);
            if (!company) return;
            const companyId = company.id;
            // Load accounts
            this.glService.listAllActiveAccounts(companyId).subscribe({
              next: (accounts) => {
                this.accountOptions.set(
                  accounts.map((a) => ({ uid: a.uid, label: a.name, hint: a.accountCode })),
                );
              },
              error: () => {},
            });
            // Load fiscal periods
            this.glService.listPeriods(companyId).subscribe({
              next: (periods) => {
                this.fiscalPeriodOptions.set(
                  periods.map((p) => ({
                    uid: p.uid,
                    label: `P${p.periodNo} (${p.startDate} – ${p.endDate})`,
                    hint: p.status,
                  })),
                );
              },
              error: () => {},
            });
          },
          error: () => {},
        });
      },
      error: () => {},
    });
    // Load budget versions for seed picker
    this.budgetingService.getByUid(v.budgetUid).subscribe({
      next: (budget) => {
        this.budgetVersionOptions.set(
          (budget.versions ?? [])
            .filter((bv) => bv.uid !== v.uid)
            .map((bv) => ({
              uid: bv.uid,
              label: `V${bv.versionNo}${bv.label ? ' — ' + bv.label : ''}`,
              hint: bv.status,
            })),
        );
      },
      error: () => {},
    });
  }

  // ── DIRECT lines helpers ──────────────────────────────────────────────────

  addDirectLine(): void {
    this.directLines.update((ls) => [...ls, { accountUid: '', fiscalPeriodUid: '', amount: '', lineMemo: '' }]);
  }

  removeDirectLine(idx: number): void {
    this.directLines.update((ls) => ls.filter((_, i) => i !== idx));
  }

  updateDirectLine(idx: number, field: 'accountUid' | 'fiscalPeriodUid' | 'amount' | 'lineMemo', value: string | number | null): void {
    // ngModel on type="number" emits a number; coerce numeric fields to string so .trim() never crashes.
    const stored: string = (field === 'amount')
      ? (value === null || value === undefined ? '' : String(value as string | number))
      : (value as string) ?? '';
    this.directLines.update((ls) => ls.map((l, i) => i === idx ? { ...l, [field]: stored } : l));
  }

  toggleUpsertForm(): void {
    this.showUpsertForm.update((v) => !v);
    if (!this.showUpsertForm()) this.resetUpsertForm();
  }

  private resetUpsertForm(): void {
    this.upsertMode.set('DIRECT');
    this.directLines.set([]);
    this.fSpreadAccountUid.set('');
    this.fAnnualAmount.set('');
    this.fSeedVersionUid.set('');
    this.upsertError.set(null);
  }

  upsertLines(): void {
    if (this.upserting()) return;
    const mode = this.upsertMode();
    let request: UpsertBudgetLineRequest;

    if (mode === 'DIRECT') {
      const lines = this.directLines();
      if (lines.length === 0) { this.upsertError.set('Add at least one line.'); return; }
      const invalid = lines.some((l) => !l.accountUid.trim() || !l.fiscalPeriodUid.trim() || !l.amount.trim());
      if (invalid) { this.upsertError.set('Each line needs account UID, period UID and amount.'); return; }
      const lineInputs: LineInputDto[] = lines.map((l) => ({
        accountUid: l.accountUid.trim(),
        fiscalPeriodUid: l.fiscalPeriodUid.trim(),
        amount: l.amount.trim(),
        lineMemo: l.lineMemo.trim() || undefined,
      }));
      request = { mode: 'DIRECT', lines: lineInputs };

    } else if (mode === 'ANNUAL_SPREAD') {
      if (!this.fSpreadAccountUid().trim()) { this.upsertError.set('Account UID is required.'); return; }
      if (!this.fAnnualAmount().trim()) { this.upsertError.set('Annual amount is required.'); return; }
      request = { mode: 'ANNUAL_SPREAD', accountUid: this.fSpreadAccountUid().trim(), annualAmount: this.fAnnualAmount().trim() };

    } else {
      if (!this.fSeedVersionUid().trim()) { this.upsertError.set('Seed version UID is required.'); return; }
      request = { mode: 'SEED', seedFromVersionUid: this.fSeedVersionUid().trim() };
    }

    this.upserting.set(true);
    this.upsertError.set(null);
    this.budgetingService.upsertLines(this.uid(), request).subscribe({
      next: (updated) => {
        this.upserting.set(false);
        this.showUpsertForm.set(false);
        this.resetUpsertForm();
        this.version.set(updated);
        this.alerts.success('Budget lines updated', `${updated.lines?.length ?? 0} line(s)`);
      },
      error: (err) => {
        this.upserting.set(false);
        this.upsertError.set(this.messageFrom(err, 'Could not update lines.'));
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  /**
   * Coerce a value emitted by ngModel on type="number" to a string.
   * Angular emits a JS number when the user edits a numeric input; calling
   * .trim() on a raw number throws "x.trim is not a function".
   */
  coerceNumStr(v: string | number | null | undefined): string {
    return v === null || v === undefined ? '' : String(v);
  }

  grandTotal(lines: BudgetLineDto[]): number {
    return lines.reduce((sum, l) => sum + (+l.amount), 0);
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
