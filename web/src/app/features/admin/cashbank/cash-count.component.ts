import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import {
  CashBankAccountDto,
  CashCountDto,
  OpenCashCountRequest,
  RecordDenominationsRequest,
} from './models/cashbank.model';
import { CashbankService } from './cashbank.service';
import { formatMoney } from '../../../shared/money.util';

/**
 * Hardcoded TZS denomination ladder (ADR-0050 D-7.7 — a configurable per-currency
 * denomination master is an explicit follow-up, not built in this slice).
 */
const DENOMINATION_LADDER: number[] = [10000, 5000, 2000, 1000, 500, 200, 100, 50];

type VarianceTone = 'ok' | 'danger' | 'neutral';

/**
 * End-of-day Cash Count screen (ADR-0050 D-7 PR-A). Dual mode, one component:
 *  - `/admin/cash/counts/new`      — no `uid` input: pick till + business date, Open.
 *  - `/admin/cash/counts/uid/:uid` — `uid` bound via withComponentInputBinding: load existing.
 *
 * Flow: Open (till + businessDate → server derives expectedAmount, status OPEN) →
 * enter denomination counts (client computes countedAmount + varianceAmount live) →
 * Save count (PUT denominations, status COUNTED) → Reconcile (POST reconcile, status RECONCILED,
 * posts the variance to GL server-side).
 *
 * Money coerced as numbers — NEVER call .startsWith/.trim on a money value.
 * Gated CASH.COUNT.MANAGE (open/count/reconcile actions); listing is CASH.COUNT.VIEW (route guard).
 */
@Component({
  selector: 'app-cash-count',
  imports: [FormsModule, RouterLink],
  templateUrl: './cash-count.component.html',
  styleUrl: './cash-count.component.scss',
})
export class CashCountComponent {
  /** Route input — bound from `:uid` via withComponentInputBinding. Absent on the `new` route. */
  readonly uid = input<string | undefined>();

  private readonly cashbankService = inject(CashbankService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  readonly DENOMINATION_LADDER = DENOMINATION_LADDER;

  // ── Company context (new-count mode only) ─────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('idle');

  // ── Till picker (new-count mode only) ─────────────────────────────────────
  readonly tills = signal<CashBankAccountDto[]>([]);
  readonly selectedTillUid = signal('');
  readonly businessDate = signal('');

  // ── Load / open state ─────────────────────────────────────────────────────
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly opening = signal(false);
  readonly openError = signal<string | null>(null);

  // ── The count itself (once opened or loaded) ──────────────────────────────
  readonly entity = signal<CashCountDto | null>(null);

  // ── Denomination grid: denomination(number) → quantity input (raw string) ──
  private readonly denomQty = signal<Map<number, string>>(new Map());

  // ── Save / reconcile state ─────────────────────────────────────────────────
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly reconciling = signal(false);
  readonly reconcileError = signal<string | null>(null);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('CASH.COUNT.MANAGE'));

  // ── Computed ───────────────────────────────────────────────────────────────

  readonly isNewMode = computed(() => !this.uid());

  /** Denominations are editable while a count is open and not yet reconciled, and the user can manage. */
  readonly editable = computed(() =>
    !!this.entity() && this.entity()!.status !== 'RECONCILED' && this.canManage(),
  );

  readonly expectedAmount = computed(() => +(this.entity()?.expectedAmount ?? 0));

  /** Live Σ(denomination × quantity) across the ladder — recomputes on every keystroke. */
  readonly countedAmount = computed(() => {
    const map = this.denomQty();
    return DENOMINATION_LADDER.reduce((sum, denom) => {
      const qty = +(map.get(denom) ?? '0') || 0;
      return sum + denom * qty;
    }, 0);
  });

  readonly varianceAmount = computed(() => this.countedAmount() - this.expectedAmount());

  /**
   * True while nothing has been counted yet on a not-yet-finalised count. Before any denominations
   * are entered, counted=0 makes the raw variance = −expected, which must NOT read as a real "Short".
   * A COUNTED/RECONCILED count with a genuine 0 count is a real shortage, so it is excluded here.
   */
  readonly notCountedYet = computed(() => {
    const status = this.entity()?.status;
    return this.countedAmount() < 0.005 && status !== 'COUNTED' && status !== 'RECONCILED';
  });

  readonly varianceTone = computed<VarianceTone>(() => {
    if (this.notCountedYet()) return 'neutral';
    const v = this.varianceAmount();
    if (Math.abs(v) < 0.005) return 'neutral';
    return v > 0 ? 'ok' : 'danger';
  });

  readonly varianceLabel = computed(() => {
    if (this.notCountedYet()) return 'Not counted yet';
    const tone = this.varianceTone();
    if (tone === 'neutral') return 'Balanced';
    return tone === 'ok' ? 'Over' : 'Short';
  });

  readonly openDisabled = computed(() =>
    !this.selectedTillUid() ||
    !String(this.businessDate() ?? '').trim() ||
    !this.selectedCompanyId() ||
    this.opening(),
  );

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    const uid = this.uid();
    if (uid) {
      this.loadExisting(uid);
    } else {
      this.businessDate.set(new Date().toISOString().slice(0, 10));
      this.loadCompaniesForOpen();
    }
  }

  private loadExisting(uid: string): void {
    this.state.set('loading');
    this.cashbankService.getCashCount(uid).subscribe({
      next: (dto) => {
        this.entity.set(dto);
        this.seedDenominations(dto);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  private loadCompaniesForOpen(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
              this.loadTills(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  /** Tills = CASH-type cash_bank_accounts only (a BANK account cannot be physically counted). */
  private loadTills(companyId: string): void {
    this.cashbankService.listAllAccounts(companyId).subscribe({
      next: (list) => this.tills.set(list.filter((a) => a.accountType === 'CASH')),
      error: () => {},
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedTillUid.set('');
    if (id) this.loadTills(id);
  }

  private seedDenominations(dto: CashCountDto): void {
    const map = new Map<number, string>();
    for (const line of dto.denominations ?? []) {
      map.set(+line.denomination, String(line.quantity));
    }
    this.denomQty.set(map);
  }

  // ── Denomination grid bindings ─────────────────────────────────────────────

  quantityOf(denom: number): string {
    return this.denomQty().get(denom) ?? '';
  }

  /**
   * `value` is typed `string` but a `type="number"` NgModel actually emits a JS number
   * (NumberValueAccessor) — coerce to string on write so `denomQty` stays consistently typed.
   */
  setQuantity(denom: number, value: string | number): void {
    this.denomQty.update((m) => {
      const next = new Map(m);
      next.set(denom, value === '' || value === null || value === undefined ? '' : String(value));
      return next;
    });
  }

  lineAmountFor(denom: number): number {
    const qty = +(this.quantityOf(denom) || 0);
    return Number.isFinite(qty) ? denom * qty : 0;
  }

  // ── Open a new count ───────────────────────────────────────────────────────

  openCount(): void {
    if (this.openDisabled()) return;

    const company = this.companies().find((c) => c.id === this.selectedCompanyId());
    if (!company) { this.openError.set('Could not resolve company.'); return; }

    const tillUid = String(this.selectedTillUid() ?? '').trim();
    const date = String(this.businessDate() ?? '').trim();
    if (!tillUid) { this.openError.set('Till is required.'); return; }
    if (!date) { this.openError.set('Business date is required.'); return; }

    const request: OpenCashCountRequest = {
      companyUid: company.uid,
      cashBankAccountUid: tillUid,
      businessDate: date,
    };

    this.opening.set(true);
    this.openError.set(null);

    this.cashbankService.openCashCount(request).subscribe({
      next: (dto) => {
        this.opening.set(false);
        this.entity.set(dto);
        this.seedDenominations(dto);
        this.alerts.success('Cash count opened', dto.countNumber);
        void this.router.navigate(['/admin/cash/counts/uid', dto.uid], { replaceUrl: true });
      },
      error: (err) => {
        this.openError.set(this.messageFrom(err, 'Could not open cash count.'));
        this.opening.set(false);
      },
    });
  }

  // ── Save denominations ─────────────────────────────────────────────────────

  saveCount(): void {
    const dto = this.entity();
    if (!dto || !this.editable() || this.saving()) return;

    const request: RecordDenominationsRequest = {
      lines: DENOMINATION_LADDER.map((denom) => ({
        denomination: denom,
        quantity: Math.max(0, Math.trunc(+(this.quantityOf(denom) || 0)) || 0),
      })),
    };

    this.saving.set(true);
    this.saveError.set(null);

    this.cashbankService.recordDenominations(dto.uid, request).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.entity.set(updated);
        this.seedDenominations(updated);
        this.alerts.success('Count saved', updated.countNumber);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the count.'));
        this.saving.set(false);
      },
    });
  }

  // ── Reconcile ──────────────────────────────────────────────────────────────

  reconcileCount(): void {
    const dto = this.entity();
    if (!dto || dto.status !== 'COUNTED' || !this.canManage() || this.reconciling()) return;

    this.reconciling.set(true);
    this.reconcileError.set(null);

    this.cashbankService.reconcileCashCount(dto.uid).subscribe({
      next: (updated) => {
        this.reconciling.set(false);
        this.entity.set(updated);
        // Re-seed the grid from the server response (mirrors saveCount) so the displayed
        // counted/variance KPIs match exactly what was posted to GL, not stale grid state.
        this.seedDenominations(updated);
        this.alerts.success('Cash count reconciled', updated.countNumber);
      },
      error: (err) => {
        this.reconcileError.set(this.messageFrom(err, 'Could not reconcile the count.'));
        this.reconciling.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  readonly fmtMoney = formatMoney;

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
