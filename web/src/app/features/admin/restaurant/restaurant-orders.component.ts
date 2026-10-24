import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { AppDatePipe } from '../../../shared/app-date.pipe';
import { formatMoney } from '../../../shared/money.util';
import { UserBranch } from '../models/user-branch.model';
import { RestaurantOrderDto } from './models/restaurant.model';
import { RestaurantService } from './restaurant.service';

/**
 * Live restaurant orders for one branch (ADR-0065): what is open on the floor right now, what has
 * gone to the kitchen and bar, and which bills are out. A manager can cancel an order here, e.g. one
 * a till left behind; orders are otherwise worked on the till. Gated: RESTAURANT.ORDER.MANAGE.
 */
@Component({
  selector: 'app-restaurant-orders',
  imports: [FormsModule, AppDatePipe],
  templateUrl: './restaurant-orders.component.html',
  styleUrl: './restaurant-orders.component.scss',
})
export class RestaurantOrdersComponent {
  private readonly restaurant = inject(RestaurantService);
  private readonly auth = inject(AuthService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly branches = signal<UserBranch[]>([]);
  readonly branchUid = signal('');
  readonly orders = signal<RestaurantOrderDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly expandedUid = signal<string | null>(null);

  readonly cancelUid = signal<string | null>(null);
  readonly cancelReason = signal('');
  readonly cancelError = signal<string | null>(null);
  readonly cancelling = signal(false);

  readonly isEmpty = computed(() => this.state() === 'idle' && this.orders().length === 0);
  readonly formatMoney = formatMoney;

  constructor() {
    this.auth.myBranches().subscribe({
      next: (list) => {
        this.branches.set(list);
        const active = this.session.activeBranchUid();
        const pick = list.find((b) => b.branchUid === active) ?? list.find((b) => b.isDefault) ?? list[0];
        this.branchUid.set(pick?.branchUid ?? '');
        this.load();
      },
      error: () => this.load(),
    });
  }

  onBranchChange(uid: string): void {
    this.branchUid.set(uid);
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.restaurant.listOrders(this.branchUid() || undefined).subscribe({
      next: (rows) => {
        this.orders.set(rows);
        this.state.set('idle');
      },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  toggle(uid: string): void {
    this.expandedUid.update((cur) => (cur === uid ? null : uid));
  }

  where(o: RestaurantOrderDto): string {
    return o.orderType === 'TAKEAWAY' ? 'Takeaway' : `Table ${o.tableName ?? ''}`.trim();
  }

  itemCount(o: RestaurantOrderDto): number {
    return o.lines.filter((l) => l.status !== 'VOIDED').length;
  }

  statusLabel(o: RestaurantOrderDto): string {
    if (o.status === 'BILLED') return o.billPrintCount > 1 ? `Bill printed (${o.billPrintCount}×)` : 'Bill printed';
    return o.pendingLineCount > 0 ? `Open · ${o.pendingLineCount} not sent` : 'Open';
  }

  startCancel(o: RestaurantOrderDto): void {
    this.cancelUid.set(o.uid);
    this.cancelReason.set('');
    this.cancelError.set(null);
  }

  confirmCancel(o: RestaurantOrderDto): void {
    const reason = this.cancelReason().trim();
    const anySent = o.lines.some((l) => l.status === 'SENT');
    if (anySent && !reason) {
      this.cancelError.set('Some items were already sent. Give a reason for cancelling.');
      return;
    }
    this.cancelling.set(true);
    this.cancelError.set(null);
    this.restaurant.cancelOrder(o.uid, { reason: reason || null }).subscribe({
      next: () => {
        this.cancelling.set(false);
        this.cancelUid.set(null);
        this.alerts.success('Order cancelled', o.orderNumber);
        this.load();
      },
      error: (err: unknown) => {
        this.cancelling.set(false);
        this.cancelError.set(messageFrom(err, 'Could not cancel the order.'));
      },
    });
  }
}

function messageFrom(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    const errors = (err.error as { errors?: string[] })?.errors;
    if (errors?.length) return errors[0];
  }
  return fallback;
}
