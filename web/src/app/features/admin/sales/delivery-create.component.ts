import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import {
  CreateDeliveryRequest,
  SalesOrderDto,
  SalesOrderLineDto,
} from '../models/sales-orders.model';
import { SalesOrdersService } from './sales-orders.service';

interface DeliveryLineEntry {
  line: SalesOrderLineDto;
  /** User-entered qty string; validated before submit. */
  qtyInput: string;
  include: boolean;
}

type LoadState = 'loading' | 'idle' | 'error';

/**
 * Create Delivery from a Sales Order. Route: /admin/deliveries/create?soUid=<uid>.
 * Loads the SO and its open lines, lets user pick which lines to deliver and at what qty
 * (up to openQtyBase per line — supports partial delivery).
 */
@Component({
  selector: 'app-delivery-create',
  imports: [FormsModule, RouterLink, DecimalPipe],
  templateUrl: './delivery-create.component.html',
  styleUrl: './delivery-create.component.scss',
})
export class DeliveryCreateComponent {
  private readonly soService = inject(SalesOrdersService);
  private readonly alerts = inject(AlertService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionStore);

  readonly soUid = signal('');
  readonly order = signal<SalesOrderDto | null>(null);
  readonly orderState = signal<LoadState>('loading');
  readonly lineEntries = signal<DeliveryLineEntry[]>([]);

  readonly deliveryDate = signal('');
  readonly notes = signal('');
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  readonly canCreate = computed(() => this.session.hasPermission('SALES.DELIVERY.CREATE'));

  readonly hasSelected = computed(() => this.lineEntries().some((e) => e.include));

  constructor() {
    const uid = this.route.snapshot.queryParamMap.get('soUid') ?? '';
    this.soUid.set(uid);
    if (uid) {
      this.loadOrder(uid);
    } else {
      this.orderState.set('error');
    }
  }

  private loadOrder(uid: string): void {
    this.orderState.set('loading');
    this.soService.getOrderByUid(uid).subscribe({
      next: (o) => {
        this.order.set(o);
        this.orderState.set('idle');
        this.soService.listOrderLines(uid).subscribe({
          next: (lines) => {
            const openLines = lines.filter((l) => +l.openQtyBase > 0);
            this.lineEntries.set(
              openLines.map((l) => ({
                line: l,
                qtyInput: String(+l.openQtyBase),
                include: true,
              })),
            );
          },
        });
      },
      error: () => this.orderState.set('error'),
    });
  }

  toggleLine(index: number, include: boolean): void {
    this.lineEntries.update((entries) => {
      const copy = [...entries];
      copy[index] = { ...copy[index], include };
      return copy;
    });
  }

  updateQty(index: number, qty: number | string | null): void {
    // type="number" emits a JS number via ngModelChange; coerce so .qtyInput is always a string.
    const qtyStr = qty === null || qty === undefined ? '' : String(qty);
    this.lineEntries.update((entries) => {
      const copy = [...entries];
      copy[index] = { ...copy[index], qtyInput: qtyStr };
      return copy;
    });
  }

  submit(): void {
    if (this.saving()) return;

    const deliveryDate = this.deliveryDate().trim();
    if (!deliveryDate) { this.formError.set('Delivery date is required.'); return; }

    const selected = this.lineEntries().filter((e) => e.include);
    if (selected.length === 0) { this.formError.set('Select at least one line to deliver.'); return; }

    for (const entry of selected) {
      const qty = Number(entry.qtyInput);
      if (isNaN(qty) || qty <= 0) {
        this.formError.set(`Invalid quantity for ${entry.line.productCode}.`);
        return;
      }
      if (qty > +entry.line.openQtyBase) {
        this.formError.set(
          `Quantity for ${entry.line.productCode} exceeds open balance (${entry.line.openQtyBase}).`,
        );
        return;
      }
    }

    this.saving.set(true);
    this.formError.set(null);

    const req: CreateDeliveryRequest = {
      salesOrderUid: this.soUid(),
      deliveryDate,
      notes: this.notes().trim() || undefined,
      lines: selected.map((e) => ({
        salesOrderLineUid: e.line.uid,
        // type="number" may have stored a JS number in qtyInput; coerce so payload is always a string.
        qtyDelivered: String(e.qtyInput ?? ''),
      })),
    };

    this.soService.createDelivery(req).subscribe({
      next: (d) => {
        this.saving.set(false);
        this.alerts.success('Delivery created', d.deliveryNumber ?? 'DRAFT');
        this.router.navigateByUrl(`/admin/deliveries/uid/${d.uid}`);
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not create delivery.'));
        this.saving.set(false);
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
