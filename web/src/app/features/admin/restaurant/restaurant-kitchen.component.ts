import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { UserBranch } from '../models/user-branch.model';
import { KitchenTicketDto, PrepStationDto } from './models/restaurant.model';
import { RestaurantService } from './restaurant.service';

/** How often the display asks for new tickets. */
export const KITCHEN_REFRESH_MS = 10_000;

/**
 * The kitchen display (ADR-0066 D-6) — one station's tickets on a screen or TV in the kitchen or
 * bar. Tap an item to mark it ready, or "Done" for the whole ticket; "Undo" brings the last bump
 * back. Refreshes itself. Gated: RESTAURANT.KITCHEN.MANAGE.
 */
@Component({
  selector: 'app-restaurant-kitchen',
  imports: [FormsModule],
  templateUrl: './restaurant-kitchen.component.html',
  styleUrl: './restaurant-kitchen.component.scss',
})
export class RestaurantKitchenComponent {
  private readonly restaurant = inject(RestaurantService);
  private readonly auth = inject(AuthService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly branches = signal<UserBranch[]>([]);
  readonly branchUid = signal('');
  readonly stations = signal<PrepStationDto[]>([]);
  readonly stationUid = signal('');
  readonly tickets = signal<KitchenTicketDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');
  readonly now = signal(Date.now());
  /** The last bump, for Undo. */
  readonly lastBumped = signal<string[]>([]);
  readonly busy = signal(false);

  readonly stationName = computed(
    () => this.stations().find((s) => s.uid === this.stationUid())?.name ?? '',
  );

  constructor() {
    const timer = setInterval(() => this.refresh(true), KITCHEN_REFRESH_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
    this.auth.myBranches().subscribe({
      next: (list) => {
        this.branches.set(list);
        const active = this.session.activeBranchUid();
        const pick = list.find((b) => b.branchUid === active) ?? list.find((b) => b.isDefault) ?? list[0];
        this.branchUid.set(pick?.branchUid ?? '');
        this.loadStations();
      },
      error: () => this.loadStations(),
    });
  }

  onBranchChange(uid: string): void {
    this.branchUid.set(uid);
    this.loadStations();
  }

  onStationChange(uid: string): void {
    this.stationUid.set(uid);
    this.lastBumped.set([]);
    this.refresh();
  }

  private loadStations(): void {
    this.restaurant.listStations(this.branchUid() || undefined).subscribe({
      next: (list) => {
        const active = list.filter((s) => s.status === 'ACTIVE');
        this.stations.set(active);
        if (!active.some((s) => s.uid === this.stationUid())) {
          this.stationUid.set(active[0]?.uid ?? '');
        }
        this.refresh();
      },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  /** Reloads the tickets; a background refresh keeps the screen as it is when it fails. */
  refresh(background = false): void {
    const uid = this.stationUid();
    this.now.set(Date.now());
    if (!uid) {
      this.tickets.set([]);
      this.state.set('idle');
      return;
    }
    if (!background) this.state.set('loading');
    this.restaurant.kitchenTickets(uid).subscribe({
      next: (t) => {
        this.tickets.set(t);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        if (background) return;
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error');
      },
    });
  }

  where(t: KitchenTicketDto): string {
    return t.orderType === 'TAKEAWAY' ? 'Takeaway' : `Table ${t.tableName ?? ''}`.trim();
  }

  /** Minutes since the ticket was sent — the cook's urgency cue. */
  minutes(t: KitchenTicketDto): number {
    return Math.max(0, Math.floor((this.now() - new Date(t.sentAt).getTime()) / 60_000));
  }

  bumpItem(lineUid: string): void {
    this.bump([lineUid]);
  }

  bumpTicket(t: KitchenTicketDto): void {
    this.bump(t.items.map((i) => i.lineUid));
  }

  private bump(lineUids: string[]): void {
    if (this.busy() || lineUids.length === 0) return;
    this.busy.set(true);
    this.restaurant.kitchenReady(lineUids).subscribe({
      next: () => {
        this.busy.set(false);
        this.lastBumped.set(lineUids);
        this.refresh(true);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.alerts.error('Could not mark ready', messageFrom(err, 'Please try again.'));
      },
    });
  }

  undo(): void {
    const uids = this.lastBumped();
    if (this.busy() || uids.length === 0) return;
    this.busy.set(true);
    this.restaurant.kitchenRecall(uids).subscribe({
      next: () => {
        this.busy.set(false);
        this.lastBumped.set([]);
        this.refresh(true);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.alerts.error('Could not undo', messageFrom(err, 'Please try again.'));
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
