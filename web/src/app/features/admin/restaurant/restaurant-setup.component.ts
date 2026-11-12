import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  EMPTY,
  Observable,
  catchError,
  concatMap,
  expand,
  forkJoin,
  from,
  map,
  of,
  reduce,
  switchMap,
  toArray,
} from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { CompanyService } from '../company/company.service';
import { ProductModel } from '../models/product.model';
import { UserBranch } from '../models/user-branch.model';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import {
  MasterStatus,
  PrepStationCategoryDto,
  PrepStationDto,
  RestaurantTableDto,
} from './models/restaurant.model';
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
  private readonly products = inject(ProductService);
  private readonly organisations = inject(OrganisationService);
  private readonly companies = inject(CompanyService);
  protected readonly session = inject(SessionStore);

  // ── Items and their departments (Tulia 2026-11-12) ───────────────────────
  // An item's department is company-wide; where a department prints is per branch. This list
  // changes the DEPARTMENT only — Product Master stays the full product editor.
  private readonly companyId = signal('');
  readonly items = signal<ProductModel[]>([]);
  readonly itemSearch = signal('');
  /** '' = every item; NO_DEPARTMENT = items without one; else a department key. */
  readonly itemDeptFilter = signal('');
  /** Department typed for an item; absent = unchanged. */
  readonly categoryDraft = signal<Record<string, string>>({});
  /** Station chosen for an item whose new department does not print anywhere yet. */
  readonly stationDraft = signal<Record<string, string>>({});
  readonly selected = signal<ReadonlySet<string>>(new Set<string>());
  readonly bulkDept = signal('');
  readonly bulkStation = signal('');
  /** The item uid being saved, or 'bulk'. */
  readonly itemSaving = signal<string | null>(null);
  readonly canEditProducts = computed(() => this.session.hasPermission('PRODUCT.MANAGE'));
  readonly knownCategories = computed(() => this.routeRows().map((r) => r.category));
  /** The branch's routing as saved (the grid above may hold unsaved edits). */
  readonly savedRoutes = signal<PrepStationCategoryDto[]>([]);
  readonly noDepartment = NO_DEPARTMENT;
  readonly uncategorisedCount = computed(
    () => this.items().filter((p) => !departmentKey(p.category)).length,
  );
  readonly filteredItems = computed(() => {
    const q = this.itemSearch().trim().toLowerCase();
    const dept = this.itemDeptFilter();
    return this.items().filter((p) => {
      const key = departmentKey(p.category);
      if (dept === NO_DEPARTMENT ? key !== '' : dept !== '' && key !== dept) return false;
      return !q || p.name.toLowerCase().includes(q) || (p.code ?? '').toLowerCase().includes(q);
    });
  });
  readonly shownItems = computed(() => this.filteredItems().slice(0, ITEMS_SHOWN));
  readonly allShownSelected = computed(() => {
    const shown = this.shownItems();
    const sel = this.selected();
    return shown.length > 0 && shown.every((p) => sel.has(p.uid));
  });

  // ── Add several tables at once ────────────────────────────────────────────
  readonly bulkPrefix = signal('T');
  readonly bulkFrom = signal<number | null>(1);
  readonly bulkTo = signal<number | null>(10);
  readonly bulkSaving = signal(false);

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
    this.organisations
      .current()
      .pipe(switchMap((org) => this.companies.list(org.uid)))
      .subscribe({
        next: (list) => {
          this.companyId.set(list[0]?.id ?? '');
          this.loadItems();
        },
        error: () => this.companyId.set(''),
      });
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
        this.savedRoutes.set(routes);
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

  // ── Items and their departments ──────────────────────────────────────────

  /**
   * The company's active sellable items, read in pages of 200 (up to 2,000 — a menu is rarely more
   * than a few pages). Only someone who may edit products sees the list.
   */
  loadItems(): void {
    const companyId = this.companyId();
    if (!companyId || !this.canEditProducts()) return;
    const size = 200;
    this.products
      .list(companyId, '', 0, size)
      .pipe(
        expand((res) => (res.meta.hasNext && res.meta.page < 9
          ? this.products.list(companyId, '', res.meta.page + 1, size)
          : EMPTY)),
        reduce((all, res) => all.concat(res.rows), [] as ProductModel[]),
      )
      .subscribe({
        next: (rows) => this.items.set(
          rows
            .filter((p) => p.sellable && p.status === 'ACTIVE')
            .sort((a, b) => a.name.localeCompare(b.name)),
        ),
        error: () => this.items.set([]),
      });
  }

  /** The saved route of a department in this branch, or null (blank or not routed). */
  routeOf(department: string | null | undefined): PrepStationCategoryDto | null {
    const key = departmentKey(department);
    if (!key) return null;
    return this.savedRoutes().find((r) => departmentKey(r.category) === key && r.prepStationUid) ?? null;
  }

  /** Where an item of [department] prints in this branch, for the list. */
  printsAt(department: string | null | undefined): string {
    const route = this.routeOf(department);
    const station = route && this.stations().find((s) => s.uid === route.prepStationUid);
    if (route && station) return station.name;
    if (route?.prepStationName) return route.prepStationName;
    return `Default (${this.defaultStation()?.name ?? 'none'})`;
  }

  /** A department that does not print anywhere yet in this branch: it must be given a station. */
  needsStation(department: string | null | undefined): boolean {
    return this.activeStations().length > 0 && !!departmentKey(department) && !this.routeOf(department);
  }

  /** The department shown in an item's box: what was typed, else its current one. */
  draftOf(p: ProductModel): string {
    return this.categoryDraft()[p.uid] ?? p.category ?? '';
  }

  setDraft(uid: string, value: string): void {
    this.categoryDraft.update((d) => ({ ...d, [uid]: value }));
  }

  stationDraftOf(uid: string): string {
    return this.stationDraft()[uid] || '';
  }

  setStationDraft(uid: string, value: string): void {
    this.stationDraft.update((d) => ({ ...d, [uid]: value }));
  }

  /** The item's department was changed to one that prints nowhere yet in this branch. */
  rowNeedsStation(p: ProductModel): boolean {
    const draft = this.categoryDraft()[p.uid];
    return draft !== undefined && draft.trim() !== (p.category ?? '').trim() && this.needsStation(draft);
  }

  /** True when the item's department was changed and can be saved. */
  canSaveItem(p: ProductModel): boolean {
    const draft = this.categoryDraft()[p.uid];
    if (draft === undefined || draft.trim() === (p.category ?? '').trim()) return false;
    if (this.needsStation(draft) && !this.stationDraft()[p.uid]) return false;
    return this.itemSaving() === null;
  }

  /** Saves one item's department; a new department is routed first, so it never lands unrouted. */
  saveCategory(p: ProductModel): void {
    if (!this.canSaveItem(p)) return;
    const value = (this.categoryDraft()[p.uid] ?? '').trim();
    const station = this.needsStation(value) ? this.stationDraft()[p.uid] : null;
    this.itemSaving.set(p.uid);
    this.routeFirst(value, station)
      .pipe(switchMap(() => this.products.setCategory(p.uid, value)))
      .subscribe({
        next: () => {
          this.itemSaving.set(null);
          this.applyLocally([p.uid], value);
          this.alerts.success('Department saved', `${p.name}: ${value || 'no department'}`);
          this.load();
        },
        error: (err: unknown) => {
          this.itemSaving.set(null);
          this.alerts.error('Could not save the department', messageFrom(err, 'Please try again.'));
        },
      });
  }

  // ── Several items at once ──

  toggleItem(uid: string, on: boolean): void {
    this.selected.update((s) => {
      const next = new Set(s);
      if (on) next.add(uid);
      else next.delete(uid);
      return next;
    });
  }

  clearSelection(): void {
    this.selected.set(new Set<string>());
  }

  /** The matching key of a department, for the filter's option values. */
  deptKey(department: string): string {
    return departmentKey(department);
  }

  toggleAllShown(on: boolean): void {
    this.selected.update((s) => {
      const next = new Set(s);
      for (const p of this.shownItems()) {
        if (on) next.add(p.uid);
        else next.delete(p.uid);
      }
      return next;
    });
  }

  readonly canApplyBulk = computed(() => {
    const dept = this.bulkDept().trim();
    return this.selected().size > 0 && !!dept && this.itemSaving() === null
      && (!this.needsStation(dept) || !!this.bulkStation());
  });

  /** Moves every selected item to one department, after the user confirms. */
  applyBulk(): void {
    if (!this.canApplyBulk()) return;
    const dept = this.bulkDept().trim();
    const uids = [...this.selected()];
    const station = this.needsStation(dept)
      ? this.activeStations().find((s) => s.uid === this.bulkStation()) ?? null
      : null;
    const where = station
      ? ` "${dept}" is new here: it will print at ${station.name} in ${this.branchName() || 'this branch'}.`
      : '';
    const question = `Move ${uids.length} item${uids.length === 1 ? '' : 's'} to "${dept}"? `
      + `An item's department is the same in every branch.${where}`;
    if (!window.confirm(question)) return;

    this.itemSaving.set('bulk');
    this.routeFirst(dept, station?.uid ?? null)
      .pipe(
        switchMap(() => from(uids)),
        concatMap((uid) => this.products.setCategory(uid, dept).pipe(
          map(() => ({ uid, ok: true })),
          catchError(() => of({ uid, ok: false })),
        )),
        toArray(),
      )
      .subscribe({
        next: (results) => {
          this.itemSaving.set(null);
          const done = results.filter((r) => r.ok).map((r) => r.uid);
          this.applyLocally(done, dept);
          this.selected.set(new Set(results.filter((r) => !r.ok).map((r) => r.uid)));
          const failed = results.length - done.length;
          if (failed === 0) {
            this.alerts.success('Departments saved', `${done.length} moved to ${dept}`);
            this.bulkDept.set('');
            this.bulkStation.set('');
          } else {
            this.alerts.error('Some items were not moved',
              `${done.length} moved to ${dept}; ${failed} could not be saved and are still selected.`);
          }
          this.load();
        },
        error: (err: unknown) => {
          this.itemSaving.set(null);
          this.alerts.error('Could not set where it prints', messageFrom(err, 'Nothing was moved.'));
        },
      });
  }

  /** Routes a new department in this branch before items are moved into it; else nothing. */
  private routeFirst(department: string, stationUid: string | null): Observable<unknown> {
    if (!department || !stationUid) return of(null);
    return this.restaurant.setCategoryRoute(department, stationUid, this.branchUid() || undefined);
  }

  private applyLocally(uids: string[], department: string): void {
    const set = new Set(uids);
    this.items.update((list) => list.map((p) => (set.has(p.uid) ? { ...p, category: department || null } : p)));
    const clear = (d: Record<string, string>) =>
      Object.fromEntries(Object.entries(d).filter(([k]) => !set.has(k)));
    this.categoryDraft.update(clear);
    this.stationDraft.update(clear);
  }

  // ── Add several tables ──────────────────────────────────────────────────

  /** Adds tables [prefix][from]..[prefix][to], skipping names the branch already has. */
  addTables(): void {
    const from_ = this.bulkFrom() ?? 0;
    const to = this.bulkTo() ?? 0;
    const prefix = this.bulkPrefix().trim();
    if (from_ < 1 || to < from_ || to - from_ > 99) {
      this.tableError.set('Choose a range of up to 100 tables, for example 1 to 12.');
      return;
    }
    const existing = new Set(this.tables().map((t) => t.name.toLowerCase()));
    const names: string[] = [];
    for (let n = from_; n <= to; n++) {
      const name = `${prefix}${n}`;
      if (!existing.has(name.toLowerCase())) names.push(name);
    }
    if (names.length === 0) {
      this.tableError.set('Those tables already exist.');
      return;
    }
    this.bulkSaving.set(true);
    this.tableError.set(null);
    from(names)
      .pipe(
        concatMap((name, i) => this.restaurant.createTable({
          branchUid: this.branchUid() || null, name, seats: 4, sortOrder: from_ + i,
        })),
        toArray(),
      )
      .subscribe({
        next: (created) => {
          this.bulkSaving.set(false);
          this.alerts.success('Tables added', `${created.length} tables`);
          this.load();
        },
        error: (err: unknown) => {
          this.bulkSaving.set(false);
          this.tableError.set(messageFrom(err, 'Could not add the tables.'));
          this.load();
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
    return s.printerHost
      ? `${s.printerHost}:${s.printerPort}`
      : 'No network printer — prints from a PC at the station, or the Kitchen Display';
  }

  routeId(i: number): string {
    return `route-${i}`;
  }
}

/** Every category in use, plus any routed category no product uses any more, sorted. */
/** How many items the list shows at once; search or filter to reach the rest. */
const ITEMS_SHOWN = 100;
const NO_DEPARTMENT = '\u0000none';

/** The server's department matching key: trimmed, inner spaces collapsed, any case. */
function departmentKey(value: string | null | undefined): string {
  return (value ?? '').trim().replace(/\s+/g, ' ').toLowerCase();
}

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
