import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Company } from '../../models/company.model';
import { Branch } from '../../models/branch.model';
import { CompanyService } from '../../company/company.service';
import { BranchService } from '../../branch/branch.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { StockLocationService } from '../locations/stock-location.service';
import { UidPickerComponent, UidOption } from '../../../../shared/uid-picker/uid-picker.component';
import { formatMoney } from '../../../../shared/money.util';
import {
  CreateVanReconciliationRequest,
  EnterVanReconciliationLinesRequest,
  VanReconciliationDto,
} from './van-reconciliation.model';
import { VanReconciliationService } from './van-reconciliation.service';

/** Per-line raw edit state (string-backed inputs, coerced with + at compute time). */
interface LineEditRow {
  productUid: string;
  productName: string;
  unitCostAmount: number | null;
  loaded: string;
  sold: string;
  returned: string;
  physical: string;
}

type VarianceTone = 'ok' | 'danger' | 'neutral';

/** A line's live-computed figures, derived from the current edit state. */
interface ComputedLine {
  productUid: string;
  productName: string;
  loadedQty: number;
  soldQty: number;
  returnedQty: number;
  expectedQty: number;
  physicalQty: number | null;
  varianceQty: number | null;
  varianceValue: number | null;
  tone: VarianceTone;
  label: string;
}

function numOrZero(v: string): number {
  const n = +(v || 0);
  return Number.isFinite(n) ? n : 0;
}

/**
 * Van-Stock Reconciliation screen (ADR-0051 D-8). Dual mode, one component:
 *  - `/admin/van-reconciliations/new`      — no `uid` input: pick VAN location + business date, Create.
 *  - `/admin/van-reconciliations/uid/:uid` — `uid` bound via withComponentInputBinding: load existing.
 *
 * Flow: Create (van location + businessDate → server derives loaded/returned from van transfers,
 * status OPEN) → enter sold + physical per product (loaded/returned prefilled, editable) → client
 * live-computes expected = loaded − sold − returned and variance = physical − expected →
 * Save Lines (POST lines) → Reconcile (freezes the worksheet, record-only — no stock/GL posting,
 * ADR-0051 D-8.2) or Cancel.
 *
 * Quantities are typed `number` on the wire (unlike stock-count's BigDecimal-as-string); money-like
 * fields (unitCostAmount/varianceValue) render via the shared formatMoney util, qty via DecimalPipe.
 * Gated STOCK.VAN_RECON.MANAGE (create/save/reconcile/cancel); viewing is STOCK.VAN_RECON.VIEW
 * (route guard admits either).
 */
@Component({
  selector: 'app-van-reconciliation',
  imports: [FormsModule, RouterLink, DecimalPipe, DatePipe, UidPickerComponent],
  templateUrl: './van-reconciliation.component.html',
  styleUrl: './van-reconciliation.component.scss',
})
export class VanReconciliationComponent {
  /** Route input — bound from `:uid` via withComponentInputBinding. Absent on the `new` route. */
  readonly uid = input<string | undefined>();

  private readonly reconService = inject(VanReconciliationService);
  private readonly companyService = inject(CompanyService);
  private readonly branchService = inject(BranchService);
  private readonly organisationService = inject(OrganisationService);
  private readonly locationService = inject(StockLocationService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  readonly fmtMoney = formatMoney;

  // ── Company / Branch context (create mode only) ───────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('idle');
  readonly branches = signal<Branch[]>([]);
  readonly selectedBranchUid = signal('');

  // ── VAN location picker (create mode only) ────────────────────────────────────
  readonly vanLocationOptions = signal<UidOption[]>([]);
  readonly locationsState = signal<'idle' | 'loading' | 'error'>('idle');
  readonly fVanLocationUid = signal('');
  readonly fBusinessDate = signal('');
  readonly fNotes = signal('');

  // ── Load / create state ────────────────────────────────────────────────────────
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly creating = signal(false);
  readonly createError = signal<string | null>(null);

  // ── The reconciliation itself (once created or loaded) ─────────────────────────
  readonly entity = signal<VanReconciliationDto | null>(null);

  // ── Lines grid: productUid → edit row ──────────────────────────────────────────
  private readonly lineEdits = signal<Map<string, LineEditRow>>(new Map());
  /**
   * Product uids the agent has edited this session. `enterLines` is a patch (the backend updates
   * only the lines it receives and leaves the rest untouched), and `seedLineEdits` pre-fills every
   * row's SOLD from the server — so a value-present test can't tell an edited line from a seeded one.
   * We therefore save exactly the lines the agent touched. Reset whenever we (re)seed from a DTO.
   */
  private readonly touchedLines = signal<Set<string>>(new Set());

  // ── Save / reconcile / cancel state ─────────────────────────────────────────────
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly reconciling = signal(false);
  readonly reconcileError = signal<string | null>(null);
  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);

  // ── Permissions ──────────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('STOCK.VAN_RECON.MANAGE'));

  // ── Computed ─────────────────────────────────────────────────────────────────────

  readonly isNewMode = computed(() => !this.uid());
  readonly isOpen = computed(() => this.entity()?.status === 'OPEN');

  /** Lines are editable while the worksheet is OPEN and the user can manage. */
  readonly editable = computed(() => this.isOpen() && this.canManage());

  readonly createDisabled = computed(() =>
    !this.selectedBranchUid() ||
    !this.fVanLocationUid() ||
    !String(this.fBusinessDate() ?? '').trim() ||
    this.creating(),
  );

  /** Live per-line figures — recomputes on every keystroke. */
  readonly computedRows = computed<ComputedLine[]>(() => {
    const order = this.entity()?.lines.map((l) => l.productUid) ?? [];
    const edits = this.lineEdits();
    return order
      .map((productUid) => edits.get(productUid))
      .filter((row): row is LineEditRow => !!row)
      .map((row) => {
        const loadedQty = numOrZero(row.loaded);
        const soldQty = numOrZero(row.sold);
        const returnedQty = numOrZero(row.returned);
        const expectedQty = loadedQty - soldQty - returnedQty;
        const hasPhysical = row.physical !== '' && row.physical !== null && row.physical !== undefined;
        const physicalQty = hasPhysical ? +row.physical : null;
        const varianceQty = hasPhysical ? physicalQty! - expectedQty : null;
        const varianceValue = hasPhysical ? varianceQty! * (row.unitCostAmount ?? 0) : null;
        const tone: VarianceTone = !hasPhysical
          ? 'neutral'
          : Math.abs(varianceQty!) < 0.0005
            ? 'neutral'
            : varianceQty! > 0
              ? 'ok'
              : 'danger';
        const label = !hasPhysical ? 'Not counted' : tone === 'neutral' ? 'Balanced' : tone === 'ok' ? 'Over' : 'Short';
        return {
          productUid: row.productUid,
          productName: row.productName,
          loadedQty,
          soldQty,
          returnedQty,
          expectedQty,
          physicalQty,
          varianceQty,
          varianceValue,
          tone,
          label,
        };
      });
  });

  readonly totals = computed(() => {
    const rows = this.computedRows();
    return rows.reduce(
      (acc, r) => ({
        loadedQty: acc.loadedQty + r.loadedQty,
        soldQty: acc.soldQty + r.soldQty,
        returnedQty: acc.returnedQty + r.returnedQty,
        expectedQty: acc.expectedQty + r.expectedQty,
        physicalQty: acc.physicalQty + (r.physicalQty ?? 0),
        varianceQty: acc.varianceQty + (r.varianceQty ?? 0),
      }),
      { loadedQty: 0, soldQty: 0, returnedQty: 0, expectedQty: 0, physicalQty: 0, varianceQty: 0 },
    );
  });

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    const uid = this.uid();
    if (uid) {
      this.loadExisting(uid);
    } else {
      this.fBusinessDate.set(new Date().toISOString().slice(0, 10));
      this.loadCompaniesForCreate();
    }
  }

  private loadExisting(uid: string): void {
    this.state.set('loading');
    this.reconService.get(uid).subscribe({
      next: (dto) => {
        this.entity.set(dto);
        this.seedLineEdits(dto);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  private loadCompaniesForCreate(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              const activeCompanyUid = this.session.user()?.activeCompanyUid ?? null;
              const active = activeCompanyUid
                ? (list.find((c) => c.uid === activeCompanyUid) ?? list[0])
                : list[0];
              this.selectedCompanyId.set(active.id);
              this.loadBranches(active.uid);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadBranches(companyUid: string): void {
    this.branchService.list(companyUid).subscribe({
      next: (list) => {
        this.branches.set(list);
        if (list.length > 0) {
          const activeBranchUid = this.session.activeBranchUid() ?? null;
          const active = activeBranchUid
            ? (list.find((b) => b.uid === activeBranchUid) ?? list[0])
            : list[0];
          this.selectedBranchUid.set(active.uid);
          this.loadVanLocations(active.uid);
        } else {
          this.selectedBranchUid.set('');
        }
      },
      error: () => this.branches.set([]),
    });
  }

  /** Filters active locations for the branch down to `locationType === 'VAN'`. */
  private loadVanLocations(branchUid: string): void {
    this.locationsState.set('loading');
    this.fVanLocationUid.set('');
    this.vanLocationOptions.set([]);
    this.locationService.activeForBranch(branchUid).subscribe({
      next: (locs) => {
        const vans = locs.filter((l) => l.locationType === 'VAN');
        this.vanLocationOptions.set(
          vans.map((l) => ({
            uid: l.uid,
            label: l.name,
            hint: l.agentName ? `${l.code} — Agent: ${l.agentName}` : l.code,
          })),
        );
        this.locationsState.set('idle');
      },
      error: () => this.locationsState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.selectedBranchUid.set('');
    this.branches.set([]);
    this.vanLocationOptions.set([]);
    this.fVanLocationUid.set('');
    const company = this.companies().find((c) => c.id === id);
    if (company) this.loadBranches(company.uid);
  }

  onBranchChange(uid: string): void {
    this.selectedBranchUid.set(uid);
    this.loadVanLocations(uid);
  }

  // ── Seed edit state from a server DTO (create response, load, or save response) ─

  private seedLineEdits(dto: VanReconciliationDto): void {
    const map = new Map<string, LineEditRow>();
    for (const l of dto.lines) {
      map.set(l.productUid, {
        productUid: l.productUid,
        productName: l.productName,
        unitCostAmount: l.unitCostAmount,
        loaded: String(l.loadedQty ?? 0),
        sold: String(l.soldQty ?? 0),
        returned: String(l.returnedQty ?? 0),
        physical: l.physicalQty === null || l.physicalQty === undefined ? '' : String(l.physicalQty),
      });
    }
    this.lineEdits.set(map);
    this.touchedLines.set(new Set());
  }

  // ── Lines grid bindings ────────────────────────────────────────────────────────

  loadedOf(productUid: string): string { return this.lineEdits().get(productUid)?.loaded ?? ''; }
  soldOf(productUid: string): string { return this.lineEdits().get(productUid)?.sold ?? ''; }
  returnedOf(productUid: string): string { return this.lineEdits().get(productUid)?.returned ?? ''; }
  physicalOf(productUid: string): string { return this.lineEdits().get(productUid)?.physical ?? ''; }

  setLoaded(productUid: string, v: string | number | null): void { this.updateField(productUid, 'loaded', v); }
  setSold(productUid: string, v: string | number | null): void { this.updateField(productUid, 'sold', v); }
  setReturned(productUid: string, v: string | number | null): void { this.updateField(productUid, 'returned', v); }
  setPhysical(productUid: string, v: string | number | null): void { this.updateField(productUid, 'physical', v); }

  /**
   * `v` is typed `string` but a `type="number"` NgModel actually emits a JS number
   * (NumberValueAccessor) — coerce to string on write so the edit map stays consistently typed.
   */
  private updateField(productUid: string, field: 'loaded' | 'sold' | 'returned' | 'physical', v: string | number | null): void {
    const value = v === null || v === undefined ? '' : String(v);
    this.lineEdits.update((m) => {
      const row = m.get(productUid);
      if (!row) return m;
      const next = new Map(m);
      next.set(productUid, { ...row, [field]: value });
      return next;
    });
    this.touchedLines.update((s) => (s.has(productUid) ? s : new Set(s).add(productUid)));
  }

  // ── Create ─────────────────────────────────────────────────────────────────────

  createReconciliation(): void {
    if (this.createDisabled()) return;

    const vanLocationUid = this.fVanLocationUid();
    const businessDate = String(this.fBusinessDate() ?? '').trim();
    if (!vanLocationUid) { this.createError.set('Select a van location.'); return; }
    if (!businessDate) { this.createError.set('Business date is required.'); return; }

    const request: CreateVanReconciliationRequest = {
      vanLocationUid,
      businessDate,
      notes: this.fNotes().trim() || undefined,
    };

    this.creating.set(true);
    this.createError.set(null);

    this.reconService.create(request).subscribe({
      next: (dto) => {
        this.creating.set(false);
        this.entity.set(dto);
        this.seedLineEdits(dto);
        this.alerts.success('Van reconciliation created', dto.reconNumber);
        void this.router.navigate(['/admin/van-reconciliations/uid', dto.uid], { replaceUrl: true });
      },
      error: (err) => {
        this.createError.set(this.messageFrom(err, 'Could not create van reconciliation.'));
        this.creating.set(false);
      },
    });
  }

  // ── Save lines (sold + physical, with loaded/returned confirmations) ───────────

  saveLines(): void {
    const dto = this.entity();
    if (!dto || !this.editable() || this.saving()) return;

    const has = (v: unknown) => v !== '' && v !== null && v !== undefined;
    const edits = this.lineEdits();
    const touched = this.touchedLines();
    // Save exactly the lines the agent edited this session (enterLines is a patch — untouched lines
    // are left as-is). A SOLD figure must persist even before the physical count is done: physical is
    // sent null in that case, and variance is only computed once a physical count exists.
    const entries = Array.from(touched)
      .map((productUid) => edits.get(productUid))
      .filter((row): row is LineEditRow => !!row)
      .map((row) => ({
        productUid: row.productUid,
        soldQty: numOrZero(row.sold),
        physicalQty: has(row.physical) ? +row.physical : null,
        loadedQty: numOrZero(row.loaded),
        returnedQty: numOrZero(row.returned),
      }));

    if (entries.length === 0) {
      this.saveError.set('Enter a sold or physical value for at least one line before saving.');
      return;
    }

    const request: EnterVanReconciliationLinesRequest = { lines: entries };

    this.saving.set(true);
    this.saveError.set(null);

    this.reconService.enterLines(dto.uid, request).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.entity.set(updated);
        this.seedLineEdits(updated);
        this.alerts.success('Lines saved', updated.reconNumber);
      },
      error: (err) => {
        this.saveError.set(this.messageFrom(err, 'Could not save the reconciliation lines.'));
        this.saving.set(false);
      },
    });
  }

  // ── Reconcile ──────────────────────────────────────────────────────────────────

  reconcile(): void {
    const dto = this.entity();
    if (!dto || dto.status !== 'OPEN' || !this.canManage() || this.reconciling()) return;

    this.reconciling.set(true);
    this.reconcileError.set(null);

    this.reconService.reconcile(dto.uid).subscribe({
      next: (updated) => {
        this.reconciling.set(false);
        this.entity.set(updated);
        this.seedLineEdits(updated);
        this.alerts.success('Van reconciliation reconciled', updated.reconNumber);
      },
      error: (err) => {
        this.reconcileError.set(this.messageFrom(err, 'Could not reconcile.'));
        this.reconciling.set(false);
      },
    });
  }

  // ── Cancel ─────────────────────────────────────────────────────────────────────

  cancel(): void {
    const dto = this.entity();
    if (!dto || dto.status !== 'OPEN' || !this.canManage() || this.cancelling()) return;

    this.cancelling.set(true);
    this.cancelError.set(null);

    this.reconService.cancel(dto.uid).subscribe({
      next: (updated) => {
        this.cancelling.set(false);
        this.entity.set(updated);
        this.seedLineEdits(updated);
        this.alerts.success('Van reconciliation cancelled', updated.reconNumber);
      },
      error: (err) => {
        this.cancelError.set(this.messageFrom(err, 'Could not cancel.'));
        this.cancelling.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────────

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
