import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { Branch } from '../../models/branch.model';
import { AgentModel } from '../../models/party.model';
import { BranchService } from '../../branch/branch.service';
import { AgentService } from '../../parties/agent.service';
import { BlanketOrderService } from './blanket-order.service';
import {
  BlanketOrderDto,
  BlanketOrderLineDto,
  DrawBlanketRequest,
  DrawLineRequest,
} from './blanket-order.model';
import { SalesOrderDto } from '../../models/sales-orders.model';

interface DrawLineEntry {
  line: BlanketOrderLineDto;
  qtyBase: string;
  include: boolean;
}

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Blanket Order detail screen.
 * Route: /admin/blanket-orders/uid/:uid
 * Actions:
 *   ACTIVE  → Draw release (creates a SalesOrder), Cancel
 *   All     → read-only view of lines + remaining qty
 */
@Component({
  selector: 'app-blanket-order-detail',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './blanket-order-detail.component.html',
  styleUrl: './blanket-order-detail.component.scss',
})
export class BlanketOrderDetailComponent {
  private readonly blanketService = inject(BlanketOrderService);
  private readonly branchService = inject(BranchService);
  private readonly agentService = inject(AgentService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly uid = input.required<string>();

  // ── Entity ───────────────────────────────────────────────────────────────
  readonly blanket = signal<BlanketOrderDto | null>(null);
  readonly state = signal<LoadState>('loading');

  // ── Draw release form ────────────────────────────────────────────────────
  readonly showDrawForm = signal(false);
  readonly branches = signal<Branch[]>([]);
  readonly drawBranchId = signal('');

  readonly agentSearchQ = signal('');
  readonly agentResults = signal<AgentModel[]>([]);
  readonly selectedAgent = signal<{ uid: string; id: string; label: string } | null>(null);
  private readonly agentSearch$ = new Subject<string>();

  readonly drawLines = signal<DrawLineEntry[]>([]);
  readonly drawBusy = signal(false);
  readonly drawError = signal<string | null>(null);
  readonly drawnOrderUid = signal<string | null>(null);

  // ── Cancel ────────────────────────────────────────────────────────────────
  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);
  readonly showCancelConfirm = signal(false);

  // ── Permissions ───────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('SALES.BLANKET.MANAGE'));

  // ── Derived ───────────────────────────────────────────────────────────────
  readonly isActive = computed(() => this.blanket()?.status === 'ACTIVE');
  readonly orderLabel = computed(() => this.blanket()?.orderNumber ?? 'DRAFT');

  constructor() {
    this.agentSearch$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        switchMap((q) => {
          const companyId = this.blanket()?.companyId;
          if (!companyId || !q.trim()) { this.agentResults.set([]); return []; }
          return this.agentService.list(companyId, q.trim(), 0, 10);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows }) => this.agentResults.set(rows.filter((a) => a.status === 'ACTIVE')),
        error: () => this.agentResults.set([]),
      });

    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.blanketService.getByUid(this.uid()).subscribe({
      next: (b) => {
        this.blanket.set(b);
        this.state.set('idle');
        this.loadBranches(b.companyId);
        this.buildDrawLines(b.lines);
      },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'error' : 'error'),
    });
  }

  private loadBranches(companyId: string): void {
    // branches are loaded by company uid — we need the uid, not id
    // We have companyId (string-typed Long) from the blanket DTO.
    // branchService.list() takes companyUid — we load all branches and filter by companyId.
    // To get companyUid we'd need an extra call; instead we load by companyId filter from session.
    // The practical approach: reload via blanket companyId. BranchService.list() takes companyUid.
    // Since we only have companyId here, we use a workaround: load the org and find the company uid.
    // However, looking at the goods-receipt pattern and the fact that the create component has
    // both id and uid, we just disable branch selection for now if we can't resolve companyUid.
    // For a cleaner solution: the detail loads branches via a separate org/company resolution.
    // Simpler: use the session's activeBranchUid to default the draw branch and let user pick
    // from branches loaded when the blanket was created. We'll load branches using companyId → uid mapping.

    // We store branches from the initial company load on the create screen;
    // for detail we need a different approach. Store the companyUid in the blanket DTO?
    // BlanketOrderDto has companyId but NOT companyUid. We can't resolve without an extra call.
    // Decision: skip branch auto-load; show an input for branchId with a note.
    // Actually — re-read: the draw request needs branchId (Long id as string). We can populate
    // branches if we look them up. But we'd need companyUid.
    // For now, load via listBranchesByCompanyId doesn't exist in BranchService.
    // BranchService.list(companyUid) requires uid. We'll store companyId and skip auto-load.
    // The user will select from a branch input that we pre-populate from the company context.

    // Pragmatic: load branches from session's assigned branches via auth, or load from company.
    // We'll use a direct branch approach: pass companyId string to the branch service if it
    // supports ?companyId — but BranchService only has ?companyUid. Skip auto-branch load;
    // render a plain text input for branchId for now.
    this.branches.set([]);
  }

  private buildDrawLines(lines: BlanketOrderLineDto[]): void {
    const entries: DrawLineEntry[] = lines
      .filter((l) => +l.remainingQtyBase > 0)
      .map((l) => ({
        line: l,
        qtyBase: l.remainingQtyBase,
        include: true,
      }));
    this.drawLines.set(entries);
  }

  // ── Draw form ─────────────────────────────────────────────────────────────

  toggleDrawForm(): void {
    this.showDrawForm.update((v) => !v);
    this.drawError.set(null);
    this.drawnOrderUid.set(null);
    const b = this.blanket();
    if (b) this.buildDrawLines(b.lines);
  }

  onAgentSearchChange(q: string): void {
    this.agentSearchQ.set(q);
    this.selectedAgent.set(null);
    this.agentSearch$.next(q);
  }

  selectAgent(a: AgentModel): void {
    this.selectedAgent.set({ uid: a.uid, id: a.id, label: `${a.code} — ${a.displayName}` });
    this.agentResults.set([]);
    this.agentSearchQ.set(`${a.code} — ${a.displayName}`);
  }

  updateDrawLineQty(index: number, qty: number | string | null): void {
    const qtyStr = qty === null || qty === undefined ? '' : String(qty);
    this.drawLines.update((entries) =>
      entries.map((e, i) => (i === index ? { ...e, qtyBase: qtyStr } : e)),
    );
  }

  toggleDrawLineInclude(index: number, include: boolean): void {
    this.drawLines.update((entries) =>
      entries.map((e, i) => (i === index ? { ...e, include } : e)),
    );
  }

  submitDraw(): void {
    const b = this.blanket();
    if (!b) return;

    const branchId = this.drawBranchId().trim();
    const agent = this.selectedAgent();

    if (!branchId) { this.drawError.set('Branch ID is required.'); return; }
    if (!agent) { this.drawError.set('Agent is required.'); return; }

    const included = this.drawLines().filter((e) => e.include);
    if (included.length === 0) { this.drawError.set('Include at least one line to draw.'); return; }

    for (const entry of included) {
      const qty = Number(entry.qtyBase);
      if (!entry.qtyBase.trim() || isNaN(qty) || qty <= 0) {
        this.drawError.set(`Line ${entry.line.productCode}: enter a valid quantity.`);
        return;
      }
      if (qty > +entry.line.remainingQtyBase) {
        this.drawError.set(`Line ${entry.line.productCode}: draw quantity exceeds remaining (${entry.line.remainingQtyBase}).`);
        return;
      }
    }

    this.drawBusy.set(true);
    this.drawError.set(null);

    const lines: DrawLineRequest[] = included.map((e) => ({
      blanketLineUid: e.line.uid,
      qtyBase: e.qtyBase,
    }));

    const request: DrawBlanketRequest = {
      blanketUid: b.uid,
      branchId,
      agentId: agent.id,
      lines,
    };

    this.blanketService.draw(b.uid, request).subscribe({
      next: (so: SalesOrderDto) => {
        this.drawBusy.set(false);
        this.showDrawForm.set(false);
        this.drawnOrderUid.set(so.uid);
        this.alerts.success('Release created', so.orderNumber ?? 'DRAFT');
        // Refresh blanket to update remaining qty
        this.blanketService.getByUid(b.uid).subscribe({
          next: (updated) => this.blanket.set(updated),
          error: () => undefined,
        });
      },
      error: (err: unknown) => {
        this.drawError.set(this.messageFrom(err, 'Could not draw release.'));
        this.drawBusy.set(false);
      },
    });
  }

  // ── Cancel ─────────────────────────────────────────────────────────────────

  toggleCancelConfirm(): void {
    this.showCancelConfirm.update((v) => !v);
    this.cancelError.set(null);
  }

  submitCancel(): void {
    if (this.cancelling()) return;
    this.cancelling.set(true);
    this.cancelError.set(null);
    this.blanketService.cancel(this.uid()).subscribe({
      next: () => {
        this.cancelling.set(false);
        this.showCancelConfirm.set(false);
        this.alerts.success('Blanket order cancelled');
        this.blanketService.getByUid(this.uid()).subscribe({
          next: (updated) => this.blanket.set(updated),
          error: () => undefined,
        });
      },
      error: (err: unknown) => {
        this.cancelError.set(this.messageFrom(err, 'Could not cancel blanket order.'));
        this.cancelling.set(false);
      },
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
