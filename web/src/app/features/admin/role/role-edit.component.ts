import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Permission, Role, ScreenReadGap } from '../models/role.model';
import { RoleService } from './role.service';
import { AlertService } from '../../../core/feedback/alert.service';

/** Groups permissions by module for the checkbox UI. */
interface PermissionGroup {
  module: string;
  permissions: Permission[];
}

/**
 * Edits a role: name/description (PUT /roles/uid/{uid}) and permission set (PUT .../permissions).
 * Permissions are rendered as checkboxes grouped by PermissionDto.module. The `code` field is
 * read-only (immutable server-side). System roles show a notice but can still have permissions
 * adjusted. Handles 403 gracefully via the global error interceptor — on error the component
 * falls into the 'error' state without crashing.
 */
@Component({
  selector: 'app-role-edit',
  imports: [FormsModule, RouterLink],
  templateUrl: './role-edit.component.html',
  styleUrl: './role-edit.component.scss',
})
export class RoleEditComponent {
  private readonly roleService = inject(RoleService);
  private readonly alerts = inject(AlertService);

  /** Route input — Angular binds the `:uid` path param to this signal via withComponentInputBinding. */
  readonly uid = input.required<string>();

  readonly role = signal<Role | null>(null);
  readonly allPermissions = signal<Permission[]>([]);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');

  // Edit-details form state
  readonly editName = signal('');
  readonly editDescription = signal('');
  readonly savingDetails = signal(false);
  readonly detailsError = signal<string | null>(null);
  readonly detailsSaved = signal(false);

  // Permission-set state
  readonly selectedCodes = signal<Set<string>>(new Set());
  readonly savingPerms = signal(false);
  readonly permsError = signal<string | null>(null);
  readonly permsSaved = signal(false);

  // Advisory read-closure gap panel — non-blocking; errors leave this empty.
  readonly gaps = signal<ScreenReadGap[]>([]);

  readonly permGroups = computed<PermissionGroup[]>(() => {
    const perms = this.allPermissions();
    const map = new Map<string, Permission[]>();
    for (const p of perms) {
      const list = map.get(p.module) ?? [];
      list.push(p);
      map.set(p.module, list);
    }
    return Array.from(map.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([module, permissions]) => ({ module, permissions }));
  });

  constructor() {
    queueMicrotask(() => this.load());
  }

  private load(): void {
    this.state.set('loading');
    let roleLoaded = false;
    let permsLoaded = false;
    let failed = false;

    const tryReady = () => {
      if (!failed && roleLoaded && permsLoaded) {
        this.state.set('idle');
      }
    };

    this.roleService.get(this.uid()).subscribe({
      next: (r) => {
        this.role.set(r);
        this.editName.set(r.name);
        this.editDescription.set(r.description ?? '');
        this.selectedCodes.set(new Set(r.permissionCodes));
        roleLoaded = true;
        tryReady();
      },
      error: () => {
        failed = true;
        this.state.set('error');
      },
    });

    this.roleService.listPermissions().subscribe({
      next: (perms) => {
        this.allPermissions.set(perms);
        permsLoaded = true;
        tryReady();
      },
      error: () => {
        failed = true;
        this.state.set('error');
      },
    });

    // Non-blocking: a 403 or any error must NOT flip the screen to error state.
    this.loadGaps();
  }

  /**
   * Fetches the advisory read-closure gaps. Errors are swallowed — the panel simply
   * stays empty. Never touches `state` so a 403 here does not break the edit screen.
   */
  private loadGaps(): void {
    this.roleService.readClosureGaps(this.uid()).subscribe({
      next: (gs) => this.gaps.set(gs),
      error: () => this.gaps.set([]),
    });
  }

  isChecked(code: string): boolean {
    return this.selectedCodes().has(code);
  }

  togglePermission(code: string, checked: boolean): void {
    this.selectedCodes.update((set) => {
      const next = new Set(set);
      if (checked) {
        next.add(code);
      } else {
        next.delete(code);
      }
      return next;
    });
  }

  saveDetails(): void {
    const role = this.role();
    if (!role || !this.editName().trim()) {
      this.detailsError.set('Name is required.');
      return;
    }
    this.savingDetails.set(true);
    this.detailsError.set(null);
    this.detailsSaved.set(false);
    this.roleService
      .update(role.uid, {
        name: this.editName().trim(),
        description: this.editDescription().trim() || undefined,
      })
      .subscribe({
        next: (updated) => {
          this.role.set(updated);
          this.savingDetails.set(false);
          this.detailsSaved.set(true);
          this.alerts.success('Role details saved');
        },
        error: (err) => {
          this.detailsError.set(this.messageFrom(err, 'Could not save role details.'));
          this.savingDetails.set(false);
        },
      });
  }

  savePermissions(): void {
    const role = this.role();
    if (!role) {
      return;
    }
    this.savingPerms.set(true);
    this.permsError.set(null);
    this.permsSaved.set(false);
    this.roleService
      .setPermissions(role.uid, { permissionCodes: Array.from(this.selectedCodes()) })
      .subscribe({
        next: (updated) => {
          this.role.set(updated);
          this.selectedCodes.set(new Set(updated.permissionCodes));
          this.savingPerms.set(false);
          this.permsSaved.set(true);
          this.alerts.success('Permissions updated');
          // Re-fetch gaps so the advisory panel reflects the just-saved grant set.
          this.loadGaps();
        },
        error: (err) => {
          this.permsError.set(this.messageFrom(err, 'Could not save permissions.'));
          this.savingPerms.set(false);
        },
      });
  }

  /**
   * Maps a dotted screen key from screen-read-closure.json to a human-readable label.
   * Falls back to the key itself so new manifest entries are always visible even before
   * this lookup is extended.
   */
  screenLabel(key: string): string {
    return SCREEN_LABELS[key] ?? key;
  }

  private messageFrom(err: unknown, fallback: string): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors[0] : fallback;
  }
}

/**
 * Friendly labels for screen keys declared in screen-read-closure.json.
 * Add a row here whenever a new screen is added to the manifest.
 */
const SCREEN_LABELS: Record<string, string> = {
  'sales.record-receipt':        'Record Receipt',
  'sales.sales-invoice':         'Sales Invoice',
  'sales.sales-invoice-detail':  'Sales Invoice Detail',
  'sales.sales-order':           'Sales Order',
  'sales.sales-order-detail':    'Sales Order Detail',
  'sales.delivery':              'Delivery Note',
  'sales.quotation':             'Quotation',
  'purchases.purchase-order':    'Purchase Order',
  'purchases.purchase-order-detail': 'Purchase Order Detail',
  'purchases.goods-receipt':     'Goods Receipt',
  'purchases.goods-receipt-detail': 'Goods Receipt Detail',
  'purchases.rfq':               'Request for Quotation',
  'purchases.rfq-detail':        'RFQ Detail',
  'purchases.purchase-return':   'Purchase Return',
  'purchases.purchase-return-detail': 'Purchase Return Detail',
  'purchases.requisition':       'Purchase Requisition',
  'purchases.requisition-detail': 'Purchase Requisition Detail',
  'purchases.enter-bill':        'Enter Bill',
  'ar.record-receipt':           'AR Record Receipt',
  'ar.receipts-list':            'AR Receipts',
  'ap.record-payment':           'AP Record Payment',
  'ap.payments-list':            'AP Payments',
  'ap.supplier-statement':       'Supplier Statement',
  'stock.stock-transfer':        'Stock Transfer',
  'stock.stock-count':           'Stock Count',
  'gl.post-journal':             'Post Journal Entry',
  'gl.trial-balance':            'Trial Balance',
  'cashbank.bank-reconciliation': 'Bank Reconciliation',
  'cashbank.cash-account-statement': 'Cash Account Statement',
  'cashbank.record-transfer':    'Record Transfer',
};
