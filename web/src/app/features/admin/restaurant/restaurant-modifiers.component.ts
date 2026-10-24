import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, map, of, switchMap } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { UidOption, UidPickerComponent } from '../../../shared/uid-picker/uid-picker.component';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PRODUCT_PICKER_SEARCH_SIZE, ProductService } from '../products/product.service';
import { MasterStatus, ModifierGroupDto, ProductRef } from './models/restaurant.model';
import { RestaurantService } from './restaurant.service';

/** One option row in the editor. */
interface OptionRow {
  uid: string | null;
  label: string;
  productUid: string;
  productName: string | null;
}

/**
 * Choices and add-ons offered on menu items (ADR-0066 D-4): "Milk" (choose one), "Extras" (any).
 * An option priced by an item ("Syrup extra") is charged and deducts stock; an option without one
 * ("No sugar") is an instruction to the kitchen. Company-wide, like the menu.
 */
@Component({
  selector: 'app-restaurant-modifiers',
  imports: [FormsModule, UidPickerComponent],
  templateUrl: './restaurant-modifiers.component.html',
  styles: ['.offered-on-picker { max-width: 360px; }'],
})
export class RestaurantModifiersComponent {
  private readonly restaurant = inject(RestaurantService);
  private readonly products = inject(ProductService);
  private readonly organisations = inject(OrganisationService);
  private readonly companies = inject(CompanyService);
  private readonly alerts = inject(AlertService);

  readonly groups = signal<ModifierGroupDto[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');
  private readonly companyId = signal('');

  // ── Editor ────────────────────────────────────────────────────────────────
  readonly editing = signal(false);
  readonly editUid = signal<string | null>(null);
  readonly name = signal('');
  readonly minSelect = signal<number | null>(0);
  readonly maxSelect = signal<number | null>(null);
  readonly active = signal(true);
  readonly options = signal<OptionRow[]>([]);
  readonly items = signal<ProductRef[]>([]);
  readonly addItemUid = signal('');
  readonly error = signal<string | null>(null);
  readonly saving = signal(false);

  /** Server-side product search for both pickers (arrow: `this` survives the binding). */
  readonly searchProducts = (q: string): Observable<readonly UidOption[]> =>
    this.companyId()
      ? this.products.list(this.companyId(), q, 0, PRODUCT_PICKER_SEARCH_SIZE).pipe(
          map(({ rows }) =>
            rows
              .filter((p) => p.status === 'ACTIVE')
              .map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
          ),
        )
      : of([]);

  constructor() {
    this.organisations
      .current()
      .pipe(switchMap((org) => this.companies.list(org.uid)))
      .subscribe({
        next: (list) => this.companyId.set(list[0]?.id ?? ''),
        error: () => this.companyId.set(''),
      });
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.restaurant.listModifierGroups().subscribe({
      next: (g) => {
        this.groups.set(g);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }

  rule(g: ModifierGroupDto): string {
    if (g.minSelect === 0 && g.maxSelect === null) return 'Optional — any number';
    if (g.minSelect === 0) return `Optional — up to ${g.maxSelect}`;
    if (g.minSelect === g.maxSelect) return `Choose ${g.minSelect}`;
    return `Choose ${g.minSelect} to ${g.maxSelect ?? 'any'}`;
  }

  optionsText(g: ModifierGroupDto): string {
    return g.options
      .filter((o) => o.status === 'ACTIVE')
      .map((o) => (o.productName ? `${o.label} (priced)` : o.label))
      .join(', ');
  }

  startNew(): void {
    this.editing.set(true);
    this.editUid.set(null);
    this.name.set('');
    this.minSelect.set(0);
    this.maxSelect.set(null);
    this.active.set(true);
    this.options.set([{ uid: null, label: '', productUid: '', productName: null }]);
    this.items.set([]);
    this.error.set(null);
  }

  edit(g: ModifierGroupDto): void {
    this.editing.set(true);
    this.editUid.set(g.uid);
    this.name.set(g.name);
    this.minSelect.set(g.minSelect);
    this.maxSelect.set(g.maxSelect);
    this.active.set(g.status === 'ACTIVE');
    this.options.set(
      g.options
        .filter((o) => o.status === 'ACTIVE')
        .map((o) => ({ uid: o.uid, label: o.label, productUid: o.productUid ?? '', productName: o.productName })),
    );
    this.items.set([...g.products]);
    this.error.set(null);
  }

  cancel(): void {
    this.editing.set(false);
  }

  addOption(): void {
    this.options.update((o) => [...o, { uid: null, label: '', productUid: '', productName: null }]);
  }

  removeOption(i: number): void {
    this.options.update((o) => o.filter((_, j) => j !== i));
  }

  setOption(i: number, patch: Partial<OptionRow>): void {
    this.options.update((o) => o.map((row, j) => (j === i ? { ...row, ...patch } : row)));
  }

  /** Adds the item picked in "Offered on" (its name comes from the product read). */
  addItem(uid: string): void {
    this.addItemUid.set(uid);
    if (!uid || this.items().some((p) => p.uid === uid)) return;
    this.products.getByUid(uid).subscribe({
      next: (p) => {
        this.items.update((list) => [...list, { uid: p.uid, name: p.name }]);
        this.addItemUid.set('');
      },
    });
  }

  removeItem(uid: string): void {
    this.items.update((list) => list.filter((p) => p.uid !== uid));
  }

  save(): void {
    const name = this.name().trim();
    if (!name) {
      this.error.set('Give the choice a name, for example Milk or Extras.');
      return;
    }
    const options = this.options().filter((o) => o.label.trim());
    if (options.length === 0) {
      this.error.set('Add at least one option.');
      return;
    }
    const min = this.minSelect() ?? 0;
    const max = this.maxSelect();
    if (max !== null && max < Math.max(min, 1)) {
      this.error.set('"At most" cannot be less than "at least".');
      return;
    }
    const status: MasterStatus = this.active() ? 'ACTIVE' : 'INACTIVE';
    const request = {
      name,
      minSelect: min,
      maxSelect: max,
      status,
      options: options.map((o, i) => ({
        uid: o.uid,
        label: o.label.trim(),
        productUid: o.productUid || null,
        sortOrder: i,
      })),
      productUids: this.items().map((p) => p.uid),
    };
    this.saving.set(true);
    this.error.set(null);
    const uid = this.editUid();
    const call = uid
      ? this.restaurant.updateModifierGroup(uid, request)
      : this.restaurant.createModifierGroup(request);
    call.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.editing.set(false);
        this.alerts.success(uid ? 'Choice updated' : 'Choice added', saved.name);
        this.load();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.error.set(messageFrom(err, 'Could not save the choice.'));
      },
    });
  }

  optionId(i: number): string {
    return `mod-opt-${i}`;
  }
}

function messageFrom(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    const errors = (err.error as { errors?: string[] })?.errors;
    if (errors?.length) return errors[0];
  }
  return fallback;
}
