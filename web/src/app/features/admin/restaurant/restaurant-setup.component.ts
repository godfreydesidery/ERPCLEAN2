import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { UserBranch } from '../models/user-branch.model';
import { MasterStatus, PrepStationDto, RestaurantTableDto } from './models/restaurant.model';
import { RestaurantModifiersComponent } from './restaurant-modifiers.component';
import { RestaurantService } from './restaurant.service';

/** One row of the routing grid: a product category and the station it prints at ('' = default). */
interface RouteRow {
  category: string;
  stationUid: string;
}

/**
 * Restaurant setup for one branch (ADR-0065 D-3, D-4): the floor's tables, the prep stations
 * (Kitchen, Bar…) with their network printer, and which product categories print where.
 * Orders themselves are taken on the till. Gated: RESTAURANT.SETUP.MANAGE.
 */
@Component({
  selector: 'app-restaurant-setup',
  imports: [FormsModule, RestaurantModifiersComponent],
  templateUrl: './restaurant-setup.component.html',
  styleUrl: './restaurant-setup.component.scss',
})
export class RestaurantSetupComponent {
  private readonly restaurant = inject(RestaurantService);
  private readonly auth = inject(AuthService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Branch ────────────────────────────────────────────────────────────────
  readonly branches = signal<UserBranch[]>([]);
  readonly branchUid = signal('');
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('loading');

  // ── Data ──────────────────────────────────────────────────────────────────
  readonly tables = signal<RestaurantTableDto[]>([]);
  readonly stations = signal<PrepStationDto[]>([]);
  readonly routeRows = signal<RouteRow[]>([]);
  readonly activeStations = computed(() => this.stations().filter((s) => s.status === 'ACTIVE'));
  /** The station unrouted items print at: the marked default, else the first active one. */
  readonly defaultStation = computed<PrepStationDto | null>(() => {
    const active = this.activeStations();
    return active.find((s) => s.defaultStation) ?? (active.length > 0 ? active[0] : null);
  });

  // ── Table form (add or edit) ──────────────────────────────────────────────
  readonly tableEditUid = signal<string | null>(null);
  readonly tableName = signal('');
  readonly tableSeats = signal<number | null>(4);
  readonly tableArea = signal('');
  readonly tableSort = signal<number | null>(null);
  readonly tableActive = signal(true);
  readonly tableError = signal<string | null>(null);
  readonly tableSaving = signal(false);

  // ── Station form (add or edit) ────────────────────────────────────────────
  readonly stationEditUid = signal<string | null>(null);
  readonly stationName = signal('');
  readonly stationHost = signal('');
  readonly stationPort = signal<number | null>(9100);
  readonly stationDefault = signal(false);
  readonly stationActive = signal(true);
  readonly stationError = signal<string | null>(null);
  readonly stationSaving = signal(false);

  // ── Routing ───────────────────────────────────────────────────────────────
  readonly routesSaving = signal(false);
  readonly routesError = signal<string | null>(null);

  readonly branchName = computed(
    () => this.branches().find((b) => b.branchUid === this.branchUid())?.branchName ?? '',
  );

  constructor() {
    this.auth.myBranches().subscribe({
      next: (list) => {
        this.branches.set(list);
        const active = this.session.activeBranchUid();
        const pick = list.find((b) => b.branchUid === active) ?? list.find((b) => b.isDefault) ?? list[0];
        this.branchUid.set(pick?.branchUid ?? '');
        this.load();
      },
      // Fall back to the active branch from the session header.
      error: () => this.load(),
    });
  }

  onBranchChange(uid: string): void {
    this.branchUid.set(uid);
    this.resetTableForm();
    this.resetStationForm();
    this.load();
  }

  load(): void {
    const b = this.branchUid() || undefined;
    this.state.set('loading');
    forkJoin({
      tables: this.restaurant.listTables(b),
      stations: this.restaurant.listStations(b),
      routes: this.restaurant.listCategoryRoutes(b),
      categories: this.restaurant.listProductCategories(),
    }).subscribe({
      next: ({ tables, stations, routes, categories }) => {
        this.tables.set(tables);
        this.stations.set(stations);
        this.routeRows.set(buildRouteRows(categories, routes.map((r) => ({
          category: r.category,
          stationUid: r.prepStationUid ?? '',
        }))));
        this.state.set('idle');
      },
      error: (err: unknown) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Tables ────────────────────────────────────────────────────────────────

  editTable(t: RestaurantTableDto): void {
    this.tableEditUid.set(t.uid);
    this.tableName.set(t.name);
    this.tableSeats.set(t.seats);
    this.tableArea.set(t.area ?? '');
    this.tableSort.set(t.sortOrder);
    this.tableActive.set(t.status === 'ACTIVE');
    this.tableError.set(null);
  }

  resetTableForm(): void {
    this.tableEditUid.set(null);
    this.tableName.set('');
    this.tableSeats.set(4);
    this.tableArea.set('');
    this.tableSort.set(null);
    this.tableActive.set(true);
    this.tableError.set(null);
  }

  saveTable(): void {
    const name = this.tableName().trim();
    if (!name) {
      this.tableError.set('Give the table a name, for example T1.');
      return;
    }
    const seats = this.tableSeats();
    if (seats !== null && (seats < 1 || seats > 999)) {
      this.tableError.set('Seats must be between 1 and 999.');
      return;
    }
    this.tableSaving.set(true);
    this.tableError.set(null);
    const editUid = this.tableEditUid();
    const common = { name, seats, area: this.tableArea().trim() || null, sortOrder: this.tableSort() };
    const call = editUid
      ? this.restaurant.updateTable(editUid, { ...common, status: statusOf(this.tableActive()) })
      : this.restaurant.createTable({ ...common, branchUid: this.branchUid() || null });
    call.subscribe({
      next: (saved) => {
        this.tableSaving.set(false);
        this.alerts.success(editUid ? 'Table updated' : 'Table added', saved.name);
        this.resetTableForm();
        this.load();
      },
      error: (err: unknown) => {
        this.tableSaving.set(false);
        this.tableError.set(messageFrom(err, 'Could not save the table.'));
      },
    });
  }

  // ── Stations ──────────────────────────────────────────────────────────────

  editStation(s: PrepStationDto): void {
    this.stationEditUid.set(s.uid);
    this.stationName.set(s.name);
    this.stationHost.set(s.printerHost ?? '');
    this.stationPort.set(s.printerPort);
    this.stationDefault.set(s.defaultStation);
    this.stationActive.set(s.status === 'ACTIVE');
    this.stationError.set(null);
  }

  resetStationForm(): void {
    this.stationEditUid.set(null);
    this.stationName.set('');
    this.stationHost.set('');
    this.stationPort.set(9100);
    this.stationDefault.set(false);
    this.stationActive.set(true);
    this.stationError.set(null);
  }

  saveStation(): void {
    const name = this.stationName().trim();
    if (!name) {
      this.stationError.set('Give the station a name, for example Kitchen or Bar.');
      return;
    }
    const port = this.stationPort();
    if (port !== null && (port < 1 || port > 65535)) {
      this.stationError.set('The printer port must be between 1 and 65535 (usually 9100).');
      return;
    }
    this.stationSaving.set(true);
    this.stationError.set(null);
    const editUid = this.stationEditUid();
    const common = {
      name,
      printerHost: this.stationHost().trim() || null,
      printerPort: port,
      defaultStation: this.stationDefault(),
    };
    const call = editUid
      ? this.restaurant.updateStation(editUid, { ...common, status: statusOf(this.stationActive()) })
      : this.restaurant.createStation({ ...common, branchUid: this.branchUid() || null });
    call.subscribe({
      next: (saved) => {
        this.stationSaving.set(false);
        this.alerts.success(editUid ? 'Station updated' : 'Station added', saved.name);
        this.resetStationForm();
        this.load();
      },
      error: (err: unknown) => {
        this.stationSaving.set(false);
        this.stationError.set(messageFrom(err, 'Could not save the station.'));
      },
    });
  }

  // ── Routing ───────────────────────────────────────────────────────────────

  setRoute(category: string, stationUid: string): void {
    this.routeRows.update((rows) =>
      rows.map((r) => (r.category === category ? { ...r, stationUid } : r)),
    );
  }

  saveRoutes(): void {
    this.routesSaving.set(true);
    this.routesError.set(null);
    const routes = this.routeRows()
      .filter((r) => r.stationUid)
      .map((r) => ({ category: r.category, prepStationUid: r.stationUid }));
    this.restaurant.replaceCategoryRoutes({ branchUid: this.branchUid() || null, routes }).subscribe({
      next: () => {
        this.routesSaving.set(false);
        this.alerts.success('Routing saved', `${routes.length} categories routed`);
        this.load();
      },
      error: (err: unknown) => {
        this.routesSaving.set(false);
        this.routesError.set(messageFrom(err, 'Could not save the routing.'));
      },
    });
  }

  // ── Display ───────────────────────────────────────────────────────────────

  stateLabel(t: RestaurantTableDto): string {
    if (t.state === 'FREE') return 'Free';
    const n = t.liveOrderCount > 1 ? ` (${t.liveOrderCount} orders)` : '';
    return (t.state === 'BILLED' ? 'Bill printed' : 'Occupied') + n;
  }

  printerLabel(s: PrepStationDto): string {
    return s.printerHost ? `${s.printerHost}:${s.printerPort}` : 'Not set (till prints locally)';
  }

  routeId(i: number): string {
    return `route-${i}`;
  }
}

/** Every category in use, plus any routed category no product uses any more, sorted. */
function buildRouteRows(categories: string[], routed: RouteRow[]): RouteRow[] {
  const byKey = new Map<string, RouteRow>();
  for (const c of categories) byKey.set(c.toLowerCase(), { category: c, stationUid: '' });
  for (const r of routed) {
    const key = r.category.toLowerCase();
    byKey.set(key, { category: byKey.get(key)?.category ?? r.category, stationUid: r.stationUid });
  }
  return [...byKey.values()].sort((a, b) => a.category.localeCompare(b.category));
}

function statusOf(active: boolean): MasterStatus {
  return active ? 'ACTIVE' : 'INACTIVE';
}

function messageFrom(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    const errors = (err.error as { errors?: string[] })?.errors;
    if (errors?.length) return errors[0];
  }
  return fallback;
}
