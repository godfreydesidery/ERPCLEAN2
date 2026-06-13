import { HttpErrorResponse } from '@angular/common/http';
import { SlicePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { FiscalYearDto, PeriodStatus } from '../models/gl.model';
import { GlService } from '../gl.service';
import { YearEndCloseService } from './year-end-close.service';

type LoadState = 'idle' | 'loading' | 'error' | 'forbidden';

/**
 * Year-End Close screen.
 * Lists all fiscal years for the company; provides Close / Reopen actions gated by GL.YEAR.CLOSE.
 * Close action shows a confirmation warning about the retained-earnings posting before executing.
 * Uses GlService.listFiscalYears (existing) + YearEndCloseService (new).
 */
@Component({
  selector: 'app-year-end-close',
  imports: [FormsModule, RouterLink, SlicePipe],
  templateUrl: './year-end-close.component.html',
  styleUrl: './year-end-close.component.scss',
})
export class YearEndCloseComponent {
  private readonly glService = inject(GlService);
  private readonly yearEndService = inject(YearEndCloseService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Fiscal years ──────────────────────────────────────────────────────────
  readonly fiscalYears = signal<FiscalYearDto[]>([]);
  readonly state = signal<LoadState>('idle');
  readonly rowBusyUid = signal<string | null>(null);

  // ── Confirmation dialog state ─────────────────────────────────────────────
  /** UID of the year awaiting close confirmation; null = no dialog shown. */
  readonly confirmCloseUid = signal<string | null>(null);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canView = computed(() => this.session.hasPermission('GL.VIEW'));
  readonly canYearClose = computed(() => this.session.hasPermission('GL.YEAR.CLOSE'));

  readonly isEmpty = computed(
    () => this.state() === 'idle' && this.fiscalYears().length === 0,
  );

  constructor() {
    this.loadCompanies();
  }

  // ── Company loading ────────────────────────────────────────────────────────

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
              this.loadFiscalYears(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadFiscalYears(companyId: string): void {
    this.state.set('loading');
    this.glService.listFiscalYears(companyId).subscribe({
      next: (list) => {
        this.fiscalYears.set(list);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 403) {
          this.state.set('forbidden');
        } else {
          this.state.set('error');
        }
      },
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.fiscalYears.set([]);
    this.confirmCloseUid.set(null);
    if (id) this.loadFiscalYears(id);
  }

  // ── Close actions ──────────────────────────────────────────────────────────

  /** Show the confirmation panel for the given year. */
  requestClose(year: FiscalYearDto): void {
    this.confirmCloseUid.set(year.uid);
  }

  cancelClose(): void {
    this.confirmCloseUid.set(null);
  }

  /** Confirmed: execute the close. */
  confirmClose(): void {
    const uid = this.confirmCloseUid();
    if (!uid || this.rowBusyUid() !== null) return;
    this.confirmCloseUid.set(null);
    this.rowBusyUid.set(uid);
    this.yearEndService.closeFiscalYear(uid).subscribe({
      next: (updated) => {
        this.rowBusyUid.set(null);
        this.alerts.success(`Fiscal year ${updated.yearCode} closed`, 'Closing journal posted to Retained Earnings.');
        this.reloadRow(updated);
      },
      error: (err: unknown) => {
        this.rowBusyUid.set(null);
        this.alerts.error('Could not close fiscal year', this.messageFrom(err, 'An error occurred.'));
      },
    });
  }

  reopen(year: FiscalYearDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(year.uid);
    this.yearEndService.reopenFiscalYear(year.uid).subscribe({
      next: (updated) => {
        this.rowBusyUid.set(null);
        this.alerts.success(`Fiscal year ${updated.yearCode} reopened`, 'Closing journal reversed.');
        this.reloadRow(updated);
      },
      error: (err: unknown) => {
        this.rowBusyUid.set(null);
        this.alerts.error('Could not reopen fiscal year', this.messageFrom(err, 'An error occurred.'));
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  statusBadgeClass(status: PeriodStatus): string {
    return status === 'OPEN' ? 'text-bg-success' : 'text-bg-secondary';
  }

  /** Replace the updated row in the local signal — avoids full reload. */
  private reloadRow(updated: FiscalYearDto): void {
    this.fiscalYears.update((list) =>
      list.map((y) => (y.uid === updated.uid ? updated : y)),
    );
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
