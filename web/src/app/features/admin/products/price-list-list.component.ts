import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import {
  CreatePriceListRequest,
  PriceListDto,
  UpdatePriceListRequest,
} from '../models/product.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from './product.service';

/**
 * Price list admin screen. Lists all price lists for the selected company.
 * Supports create (inline), edit (inline), set default, archive, restore.
 * Gated by PRICELIST.VIEW; management actions require PRICELIST.MANAGE.
 *
 * The DEFAULT list is what the Product List and Stock Value reports read selling prices from — with
 * none resolvable, both reports show a blank Selling Price column and say so. It is deliberately a
 * per-row ACTION rather than a checkbox on the edit form: a checkbox can be un-ticked, which would
 * leave the company with NO default and both reports blank again, and it would turn every name/VAT
 * edit into a potential default-flag write.
 *
 * "Which list is the default" is not the flag alone — see resolvedDefault(): the reports fall back
 * to a list coded DEFAULT/STANDARD and then to the only ACTIVE list, so most shops are priced
 * correctly without the flag ever having been set. This screen judges the same way, over ACTIVE
 * lists only, or its warnings would contradict the reports they are about.
 */
@Component({
  selector: 'app-price-list-list',
  imports: [FormsModule],
  templateUrl: './price-list-list.component.html',
  styleUrl: './price-list-list.component.scss',
})
export class PriceListListComponent {
  private readonly productService = inject(ProductService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── List state ─────────────────────────────────────────────────────────────
  readonly rows = signal<PriceListDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');

  // ── Create form ────────────────────────────────────────────────────────────
  readonly showCreateForm = signal(false);
  readonly newCode = signal('');
  readonly newName = signal('');
  /** ADR-0056: new price lists default to VAT-inclusive, matching the backend default. */
  readonly newPriceIncludesVat = signal(true);
  /** Offered on CREATE only (it can only ever add a default, never remove the last one). */
  readonly newIsDefault = signal(false);
  readonly saving = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Inline edit ────────────────────────────────────────────────────────────
  readonly editingUid = signal<string | null>(null);
  readonly editName = signal('');
  readonly editPriceIncludesVat = signal(true);
  readonly editSaving = signal(false);
  readonly editError = signal<string | null>(null);
  readonly rowBusyUid = signal<string | null>(null);

  // ── Default flag ───────────────────────────────────────────────────────────
  readonly settingDefaultUid = signal<string | null>(null);

  readonly canManage = computed(() => this.session.hasPermission('PRICELIST.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  /**
   * ACTIVE lists only. Everything about "the default" is judged on these: an ARCHIVED list prices
   * nothing whatever its flag says, and the reports never look at one.
   */
  private readonly activeRows = computed(() => this.rows().filter((r) => r.status === 'ACTIVE'));

  /**
   * The list the reports will actually price from, resolved the way the server resolves it over
   * ACTIVE lists: the flagged one, else one coded DEFAULT or STANDARD, else the only ACTIVE list.
   * Null when nobody has decided — the one state that blanks the reports' Selling Price column.
   *
   * All three arms matter. A shop with a single unflagged "Retail" list is priced perfectly well,
   * so a flag-only check here would put a red warning on the normal case and have this screen
   * contradict the reports.
   */
  readonly resolvedDefault = computed<PriceListDto | null>(() => {
    const active = this.activeRows();
    const flagged = active.find((r) => r.isDefault);
    if (flagged) return flagged;
    const coded = active.find((r) => ['DEFAULT', 'STANDARD'].includes(r.code.toUpperCase()));
    if (coded) return coded;
    return active.length === 1 ? active[0] : null;
  });

  /** True when nothing resolves as the default — the state that blanks the reports' prices. */
  readonly hasNoDefault = computed(
    () => this.state() === 'idle' && this.rows().length > 0 && this.resolvedDefault() === null,
  );
  /**
   * More than one ACTIVE list flagged default. Nothing in the database prevents it, so say so rather
   * than let the reports quietly pick one: the reports resolve it deterministically, but the bulk
   * price-export template does not.
   */
  readonly hasManyDefaults = computed(() => this.activeRows().filter((r) => r.isDefault).length > 1);

  constructor() {
    this.loadCompanies();
  }

  private loadCompanies(): void {
    this.companyState.set('loading');
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            this.companies.set(list);
            this.companyState.set('idle');
            if (list.length > 0) {
              this.selectedCompanyId.set(list[0].id);
              this.load();
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    if (id) this.load();
  }

  load(): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.state.set('loading');
    this.productService.listPriceLists(companyId).subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.state.set('idle');
      },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Create ─────────────────────────────────────────────────────────────────

  toggleCreateForm(): void {
    this.showCreateForm.update((v) => !v);
    this.formError.set(null);
  }

  create(): void {
    const companyId = this.selectedCompanyId();
    const code = this.newCode().trim();
    const name = this.newName().trim();
    if (!code || !name) {
      this.formError.set('Code and name are required.');
      return;
    }
    const company = this.companies().find((c) => c.id === companyId);
    if (!company) {
      this.formError.set('Could not resolve selected company.');
      return;
    }
    this.saving.set(true);
    this.formError.set(null);
    const request: CreatePriceListRequest = {
      companyUid: company.uid,
      code,
      name,
      priceIncludesVat: this.newPriceIncludesVat(),
      isDefault: this.newIsDefault(),
    };
    this.productService.createPriceList(request).subscribe({
      next: (created) => {
        this.saving.set(false);
        this.newCode.set('');
        this.newName.set('');
        this.newPriceIncludesVat.set(true);
        this.newIsDefault.set(false);
        this.showCreateForm.set(false);
        this.alerts.success('Price list created', created.name);
        // Creating a default demotes the old one server-side, in the same transaction — nothing to
        // clear from here. Reload so the table shows which row moved.
        this.load();
      },
      error: (err) => {
        this.formError.set(this.messageFrom(err, 'Could not create price list.'));
        this.saving.set(false);
      },
    });
  }

  // ── Inline edit ────────────────────────────────────────────────────────────

  startEdit(pl: PriceListDto): void {
    this.editingUid.set(pl.uid);
    this.editName.set(pl.name);
    this.editPriceIncludesVat.set(pl.priceIncludesVat);
    this.editError.set(null);
  }

  cancelEdit(): void {
    this.editingUid.set(null);
    this.editError.set(null);
  }

  saveEdit(pl: PriceListDto): void {
    const name = this.editName().trim();
    if (!name) {
      this.editError.set('Name is required.');
      return;
    }
    this.editSaving.set(true);
    this.editError.set(null);
    const request: UpdatePriceListRequest = { name, priceIncludesVat: this.editPriceIncludesVat() };
    this.productService.updatePriceList(pl.uid, request).subscribe({
      next: (updated) => {
        this.editSaving.set(false);
        this.editingUid.set(null);
        this.rows.update((rows) => rows.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Price list updated', updated.name);
      },
      error: (err) => {
        this.editError.set(this.messageFrom(err, 'Could not update price list.'));
        this.editSaving.set(false);
      },
    });
  }

  // ── Default flag ───────────────────────────────────────────────────────────

  /**
   * Makes this list the company default — the list the Product List and Stock Value reports read
   * selling prices from.
   *
   * ONE request, on the purpose-built endpoint. It promotes this list and demotes the previous one
   * in a single transaction, so the company can never be left with two defaults or none; it touches
   * only the flag, where a generic update would rewrite fields on a record the admin never opened;
   * and it is the call the audit log records as setting a default rather than as a rename.
   */
  setDefault(pl: PriceListDto): void {
    if (pl.isDefault || this.settingDefaultUid() !== null || this.rowBusyUid() !== null) return;
    this.settingDefaultUid.set(pl.uid);
    this.productService.setDefaultPriceList(pl.uid).subscribe({
      next: () => {
        this.settingDefaultUid.set(null);
        this.alerts.success('Default price list set', pl.name);
        this.load(); // another row lost its flag too — reload rather than patch a single row
      },
      error: (err) => {
        this.settingDefaultUid.set(null);
        this.alerts.error(
          'Could not set the default price list',
          this.messageFrom(err, 'Please try again.'),
        );
      },
    });
  }

  /**
   * Whether the row wears the Default badge. Archived rows never do: a green Default tag one column
   * from an ARCHIVED status tag claims a list is pricing stock when nothing reads it.
   */
  showsDefaultBadge(pl: PriceListDto): boolean {
    return pl.isDefault && pl.status === 'ACTIVE';
  }

  // ── Archive / Restore ──────────────────────────────────────────────────────

  archive(pl: PriceListDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(pl.uid);
    this.productService.archivePriceList(pl.uid).subscribe({
      next: (updated) => {
        this.rowBusyUid.set(null);
        this.rows.update((rows) => rows.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Price list archived', updated.name);
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  restore(pl: PriceListDto): void {
    if (this.rowBusyUid() !== null) return;
    this.rowBusyUid.set(pl.uid);
    this.productService.restorePriceList(pl.uid).subscribe({
      next: (updated) => {
        this.rowBusyUid.set(null);
        this.rows.update((rows) => rows.map((r) => (r.uid === updated.uid ? updated : r)));
        this.alerts.success('Price list restored', updated.name);
      },
      error: () => this.rowBusyUid.set(null),
    });
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 409) {
        return 'A price list with that code or name already exists in this company.';
      }
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
