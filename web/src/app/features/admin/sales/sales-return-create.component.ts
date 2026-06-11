import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  CreateSalesReturnRequest,
  DeliveryDto,
  DeliveryLineDto,
  SalesReturnDto,
} from '../models/sales-orders.model';
import { SalesOrdersService } from './sales-orders.service';

/** Per-line entry state for the return form. */
interface ReturnLineEntry {
  line: DeliveryLineDto;
  /** Returnable = qtyDelivered − returnedQtyBase */
  returnable: number;
  /** User-entered return qty; string for two-way binding, validated on submit */
  qtyInput: string;
}

type LoadState = 'idle' | 'loading' | 'error';

/**
 * Create a sales return against a delivery.
 * Route: /admin/sales-returns/create?deliveryUid=<uid>
 * Gate: SALES.RETURN.CREATE
 *
 * Shows delivery lines with their delivered qty and the returnable balance
 * (delivered − already returned). User enters returned qty per line (≤ returnable).
 * Submitting creates + confirms the return (backend atomically: stock-in + COGS reversal + credit note).
 */
@Component({
  selector: 'app-sales-return-create',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './sales-return-create.component.html',
  styleUrl: './sales-return-create.component.scss',
})
export class SalesReturnCreateComponent implements OnInit {
  private readonly soService = inject(SalesOrdersService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  protected readonly session = inject(SessionStore);

  /** Bound from query param ?deliveryUid= via withComponentInputBinding. */
  readonly deliveryUid = input<string>();

  // ── Delivery lookup ────────────────────────────────────────────────────────
  readonly deliveryUidInput = signal('');
  readonly delivery = signal<DeliveryDto | null>(null);
  readonly deliveryState = signal<LoadState>('idle');
  readonly deliveryError = signal<string | null>(null);

  // ── Return form ────────────────────────────────────────────────────────────
  readonly returnDate = signal(new Date().toISOString().slice(0, 10));
  readonly reason = signal('');
  readonly lineEntries = signal<ReturnLineEntry[]>([]);
  readonly formError = signal<string | null>(null);
  readonly saving = signal(false);

  // ── Permissions ────────────────────────────────────────────────────────────
  readonly canCreate = computed(() => this.session.hasPermission('SALES.RETURN.CREATE'));

  readonly hasReturnableLines = computed(() =>
    this.lineEntries().some((e) => e.returnable > 0),
  );

  private readonly lookupTrigger$ = new Subject<string>();

  ngOnInit(): void {
    const uid = this.deliveryUid();
    if (uid) {
      this.deliveryUidInput.set(uid);
      this.lookupTrigger$.next(uid);
    }
  }

  constructor() {
    this.lookupTrigger$
      .pipe(
        switchMap((uid) => {
          this.deliveryState.set('loading');
          this.deliveryError.set(null);
          this.delivery.set(null);
          this.lineEntries.set([]);
          return this.soService.getDeliveryByUid(uid);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (d) => {
          this.delivery.set(d);
          this.deliveryState.set('idle');
          this.buildLineEntries(d);
        },
        error: (err: unknown) => {
          this.deliveryState.set('error');
          this.deliveryError.set(this.messageFrom(err, 'Could not load delivery.'));
        },
      });
  }

  loadDelivery(): void {
    const uid = this.deliveryUidInput().trim();
    if (!uid) { this.deliveryError.set('Enter a delivery UID.'); return; }
    this.lookupTrigger$.next(uid);
  }

  private buildLineEntries(d: DeliveryDto): void {
    const entries: ReturnLineEntry[] = d.lines.map((line) => {
      const delivered = +(line.qtyDelivered ?? 0);
      const alreadyReturned = +(line.returnedQtyBase ?? 0);
      const returnable = Math.max(0, delivered - alreadyReturned);
      return { line, returnable, qtyInput: '' };
    });
    this.lineEntries.set(entries);
  }

  // ── Validation helpers ─────────────────────────────────────────────────────

  /** Returns an error string if the qty input for this entry is invalid, else null. */
  lineQtyError(entry: ReturnLineEntry): string | null {
    const raw = entry.qtyInput.trim();
    if (raw === '' || raw === '0') return null; // blank / zero = skip this line
    const n = +raw;
    if (Number.isNaN(n) || n < 0) return 'Must be a positive number.';
    if (n > entry.returnable) return `Max returnable: ${entry.returnable.toFixed(3)}`;
    return null;
  }

  // ── Submit ─────────────────────────────────────────────────────────────────

  submit(): void {
    if (this.saving()) return;

    const d = this.delivery();
    if (!d) { this.formError.set('Load a delivery first.'); return; }

    const date = this.returnDate().trim();
    if (!date) { this.formError.set('Return date is required.'); return; }

    // Validate all line qty inputs
    const entries = this.lineEntries();
    for (const entry of entries) {
      const err = this.lineQtyError(entry);
      if (err) { this.formError.set(`Line ${entry.line.lineNo}: ${err}`); return; }
    }

    // Collect lines with a non-zero qty
    const returnLines = entries
      .filter((e) => {
        const n = +(e.qtyInput.trim() || '0');
        return n > 0;
      })
      .map((e) => ({
        deliveryLineUid: e.line.uid,
        qtyReturned: String(+(e.qtyInput.trim())),
      }));

    if (returnLines.length === 0) {
      this.formError.set('Enter a return quantity for at least one line.');
      return;
    }

    const request: CreateSalesReturnRequest = {
      deliveryUid: d.uid,
      returnDate: date,
      reason: this.reason().trim() || undefined,
      lines: returnLines,
    };

    this.saving.set(true);
    this.formError.set(null);

    this.soService.createReturn(request).subscribe({
      next: (ret: SalesReturnDto) => {
        this.saving.set(false);
        this.alerts.success('Return created', ret.returnNumber ?? ret.uid);
        void this.router.navigate(['/admin/sales-returns/uid', ret.uid]);
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create return.'));
        this.saving.set(false);
      },
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
