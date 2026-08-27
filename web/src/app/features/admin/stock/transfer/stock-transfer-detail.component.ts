import { HttpErrorResponse } from '@angular/common/http';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { StockTransferDto } from './stock-transfer.model';
import { StockTransferService } from './stock-transfer.service';
import { StockLocationService } from '../locations/stock-location.service';
import { downloadBlob } from '../../reporting/reporting.utils';

@Component({
  selector: 'app-stock-transfer-detail',
  imports: [DatePipe, DecimalPipe, RouterLink],
  templateUrl: './stock-transfer-detail.component.html',
  styleUrl: './stock-transfer-detail.component.scss',
})
export class StockTransferDetailComponent {
  readonly uid = input.required<string>();

  private readonly transferService = inject(StockTransferService);
  private readonly locationService = inject(StockLocationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly entity = signal<StockTransferDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  /** id → "code — name" map loaded once for label resolution. */
  private readonly locationNameMap = signal<Map<string, string>>(new Map());

  readonly actionBusy = signal(false);
  readonly actionError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('STOCK.TRANSFER.CREATE'));
  readonly canReceive = computed(() => this.session.hasPermission('STOCK.TRANSFER.RECEIVE'));

  // ── Derived booleans gated by status ─────────────────────────────────────────
  readonly canDispatch = computed(() => {
    const e = this.entity();
    return this.canCreate() && e?.status === 'DRAFT' && e?.transferMode === 'IN_TRANSIT';
  });
  readonly canCompleteInstant = computed(() => {
    const e = this.entity();
    return this.canCreate() && e?.status === 'DRAFT' && e?.transferMode === 'INSTANT';
  });
  readonly canReceiveTransfer = computed(() => {
    const e = this.entity();
    return this.canReceive() && e?.status === 'DISPATCHED';
  });
  readonly canCancel = computed(() => {
    const e = this.entity();
    return this.canCreate() && e?.status === 'DRAFT';
  });

  // ── Export / print ───────────────────────────────────────────────────────────
  /** The endpoint requires REPORT.EXPORT on top of the view gate, so the buttons follow it. */
  readonly canExport = computed(() => this.session.hasPermission('REPORT.EXPORT'));
  readonly exporting = signal<'PDF' | 'XLSX' | null>(null);

  /**
   * Downloads the transfer as a document. "Print" is the PDF: browsers open it in a viewer that
   * prints, so a separate print path would only duplicate what the PDF already does — and it would
   * print the screen furniture with it.
   */
  exportAs(format: 'PDF' | 'XLSX'): void {
    const e = this.entity();
    if (!e || this.exporting()) return;
    this.exporting.set(format);
    this.transferService.export(e.uid, format).subscribe({
      next: (blob) => {
        const ext = format === 'PDF' ? 'pdf' : 'xlsx';
        downloadBlob(blob, `stock-transfer-${e.transferNumber ?? e.uid}.${ext}`);
        this.exporting.set(null);
      },
      error: () => {
        this.exporting.set(null);
        this.alerts.error('Could not prepare the download', 'Please try again.');
      },
    });
  }

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    const uid = this.uid();
    if (!uid) return;
    this.state.set('loading');
    this.transferService.getByUid(uid).subscribe({
      next: (t) => {
        this.entity.set(t);
        this.state.set('idle');
        this.loadLocationNames();
      },
      error: (err) =>
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        ),
    });
  }

  private loadLocationNames(): void {
    this.locationService.list(0, 500).subscribe({
      next: ({ rows }) => {
        const map = new Map<string, string>();
        for (const loc of rows) map.set(loc.id, `${loc.code} — ${loc.name}`);
        this.locationNameMap.set(map);
      },
      error: () => undefined, // non-fatal — label falls back
    });
  }

  locationLabel(locationId: string): string {
    return this.locationNameMap().get(locationId) ?? locationId.slice(0, 8) + '…';
  }

  dispatch(): void {
    this.runAction(() => this.transferService.dispatch(this.uid()), 'dispatched');
  }

  completeInstant(): void {
    this.runAction(() => this.transferService.completeInstant(this.uid()), 'completed');
  }

  receive(): void {
    this.runAction(() => this.transferService.receive(this.uid()), 'received');
  }

  cancel(): void {
    if (this.actionBusy()) return;
    this.actionBusy.set(true);
    this.actionError.set(null);
    this.transferService.cancel(this.uid()).subscribe({
      next: () => {
        this.actionBusy.set(false);
        this.alerts.success('Transfer cancelled');
        // Reload to reflect CANCELLED status
        this.init();
      },
      error: (err: unknown) => {
        this.actionError.set(this.messageFrom(err, 'Could not cancel transfer.'));
        this.actionBusy.set(false);
      },
    });
  }

  private runAction(
    call: () => ReturnType<typeof this.transferService.dispatch>,
    verb: string,
  ): void {
    if (this.actionBusy()) return;
    this.actionBusy.set(true);
    this.actionError.set(null);
    call().subscribe({
      next: (updated) => {
        this.entity.set(updated);
        this.actionBusy.set(false);
        this.alerts.success(`Transfer ${verb}`);
      },
      error: (err: unknown) => {
        this.actionError.set(this.messageFrom(err, `Could not perform action.`));
        this.actionBusy.set(false);
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
