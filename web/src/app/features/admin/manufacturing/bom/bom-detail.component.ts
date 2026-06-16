import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../../core/feedback/alert.service';
import { SessionStore } from '../../../../core/auth/session.store';
import { CompanyService } from '../../company/company.service';
import { OrganisationService } from '../../organisation/organisation.service';
import { ProductService } from '../../products/product.service';
import { BomService } from './bom.service';
import {
  ActivateBomRequest,
  AddBomComponentRequest,
  BomComponentDto,
  BomDto,
  ComponentSourcing,
  UpdateBomComponentRequest,
  UpdateBomRequest,
} from './models/bom.model';
import { UidOption, UidPickerComponent } from '../../../../shared/uid-picker/uid-picker.component';

type LoadState = 'loading' | 'idle' | 'error';

/**
 * BOM detail + edit + component management + lifecycle actions.
 * Route: /admin/boms/uid/:uid
 *
 * Header edit (DRAFT only): outputQty, yieldPercent, notes
 * Component lines (DRAFT only): inline add / edit / remove
 * Lifecycle: activate (DRAFT → ACTIVE), archive (ACTIVE → ARCHIVED)
 */
@Component({
  selector: 'app-bom-detail',
  imports: [FormsModule, RouterLink, DecimalPipe, UidPickerComponent],
  templateUrl: './bom-detail.component.html',
  styleUrl: './bom-detail.component.scss',
})
export class BomDetailComponent {
  readonly uid = input.required<string>();

  private readonly bomService = inject(BomService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly productService = inject(ProductService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Picker options ────────────────────────────────────────────────────────
  readonly productOptions = signal<UidOption[]>([]);

  // ── Entity state ──────────────────────────────────────────────────────────
  readonly bom = signal<BomDto | null>(null);
  readonly state = signal<LoadState>('loading');

  // ── Header edit form ──────────────────────────────────────────────────────
  readonly fOutputQty = signal('');
  readonly fYieldPercent = signal('');
  readonly fNotes = signal('');
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  // ── Activate form ─────────────────────────────────────────────────────────
  readonly showActivateForm = signal(false);
  readonly fEffectiveFrom = signal('');
  readonly activating = signal(false);
  readonly activateError = signal<string | null>(null);

  // ── Archive ───────────────────────────────────────────────────────────────
  readonly archiving = signal(false);

  // ── Add component form ────────────────────────────────────────────────────
  readonly showAddComponent = signal(false);
  readonly newComponentProductUid = signal('');
  readonly newComponentQtyPer = signal('1');
  readonly newComponentSourcing = signal<ComponentSourcing | ''>('');
  readonly newComponentScrapPercent = signal('0');
  readonly newComponentReference = signal('');
  readonly addingComponent = signal(false);
  readonly addComponentError = signal<string | null>(null);

  // ── Inline component edit ─────────────────────────────────────────────────
  readonly editingComponentUid = signal<string | null>(null);
  readonly editQtyPer = signal('');
  readonly editSourcing = signal<ComponentSourcing | ''>('');
  readonly editScrapPercent = signal('');
  readonly editReference = signal('');
  readonly editingComponent = signal(false);
  readonly editComponentError = signal<string | null>(null);

  // ── Removing component ────────────────────────────────────────────────────
  readonly removingComponentUid = signal<string | null>(null);

  // ── Derived ───────────────────────────────────────────────────────────────
  readonly canManage = computed(() => this.session.hasPermission('BOM.MANAGE'));
  readonly isDraft = computed(() => this.bom()?.status === 'DRAFT');
  readonly isActive = computed(() => this.bom()?.status === 'ACTIVE');

  readonly sourcingOptions: Array<{ value: ComponentSourcing | ''; label: string }> = [
    { value: '', label: 'Auto (derive from child)' },
    { value: 'MAKE', label: 'MAKE' },
    { value: 'BUY', label: 'BUY' },
  ];

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.state.set('loading');
    this.bomService.getByUid(this.uid()).subscribe({
      next: (bom) => {
        this.bom.set(bom);
        this.patchForm(bom);
        this.state.set('idle');
        this.loadProductOptions(bom.companyId);
      },
      error: (err: unknown) => {
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'error' : 'error',
        );
      },
    });
  }

  private patchForm(bom: BomDto): void {
    this.fOutputQty.set(bom.outputQty);
    this.fYieldPercent.set(bom.yieldPercent ?? '100');
    this.fNotes.set(bom.notes ?? '');
  }

  private loadProductOptions(companyId: string): void {
    // Resolve companyId → list products: need companyId as string
    this.organisationService.current().subscribe({
      next: (org) => {
        this.companyService.list(org.uid).subscribe({
          next: (list) => {
            const company = list.find((c) => c.id === companyId);
            if (company) {
              this.productService.list(company.id, undefined, 0, 500).subscribe({
                next: ({ rows }) => {
                  this.productOptions.set(
                    rows
                      .filter((p) => p.status === 'ACTIVE')
                      .map((p) => ({ uid: p.uid, label: p.name, hint: p.code })),
                  );
                },
                error: () => {},
              });
            }
          },
          error: () => {},
        });
      },
      error: () => {},
    });
  }

  // ── Header save ───────────────────────────────────────────────────────────

  save(): void {
    const outputQty = this.fOutputQty().trim();
    if (!outputQty || isNaN(Number(outputQty)) || Number(outputQty) <= 0) {
      this.saveError.set('Output quantity must be a positive number.');
      return;
    }

    this.saving.set(true);
    this.saveError.set(null);

    const request: UpdateBomRequest = {
      outputQty,
      yieldPercent: this.fYieldPercent().trim() || undefined,
      notes: this.fNotes().trim() || undefined,
    };

    this.bomService.update(this.uid(), request).subscribe({
      next: (updated) => {
        this.bom.set(updated);
        this.patchForm(updated);
        this.saving.set(false);
        this.alerts.success('BOM updated', updated.uid);
      },
      error: (err: unknown) => {
        this.saveError.set(this.messageFrom(err, 'Could not save BOM.'));
        this.saving.set(false);
      },
    });
  }

  // ── Activate ──────────────────────────────────────────────────────────────

  toggleActivateForm(): void {
    this.showActivateForm.update((v) => !v);
    this.activateError.set(null);
    if (!this.showActivateForm()) this.fEffectiveFrom.set('');
  }

  activate(): void {
    const effectiveFrom = this.fEffectiveFrom().trim();
    if (!effectiveFrom) {
      this.activateError.set('Effective-from date is required.');
      return;
    }

    this.activating.set(true);
    this.activateError.set(null);

    const request: ActivateBomRequest = { effectiveFrom };
    this.bomService.activate(this.uid(), request).subscribe({
      next: (updated) => {
        this.bom.set(updated);
        this.patchForm(updated);
        this.activating.set(false);
        this.showActivateForm.set(false);
        this.fEffectiveFrom.set('');
        this.alerts.success('BOM activated', updated.uid);
      },
      error: (err: unknown) => {
        this.activateError.set(this.messageFrom(err, 'Could not activate BOM.'));
        this.activating.set(false);
      },
    });
  }

  // ── Archive ───────────────────────────────────────────────────────────────

  archive(): void {
    this.archiving.set(true);
    this.bomService.archive(this.uid()).subscribe({
      next: (updated) => {
        this.bom.set(updated);
        this.patchForm(updated);
        this.archiving.set(false);
        this.alerts.success('BOM archived', updated.uid);
      },
      error: (err: unknown) => {
        this.alerts.error('Archive failed', this.messageFrom(err, 'Could not archive BOM.'));
        this.archiving.set(false);
      },
    });
  }

  // ── Add component ─────────────────────────────────────────────────────────

  toggleAddComponent(): void {
    this.showAddComponent.update((v) => !v);
    this.addComponentError.set(null);
    if (!this.showAddComponent()) this.resetAddComponentForm();
  }

  private resetAddComponentForm(): void {
    this.newComponentProductUid.set('');
    this.newComponentQtyPer.set('1');
    this.newComponentSourcing.set('');
    this.newComponentScrapPercent.set('0');
    this.newComponentReference.set('');
  }

  addComponent(): void {
    const componentProductUid = this.newComponentProductUid().trim();
    const qtyPer = this.newComponentQtyPer().trim();

    if (!componentProductUid) {
      this.addComponentError.set('Component product is required.');
      return;
    }
    if (!qtyPer || isNaN(Number(qtyPer)) || Number(qtyPer) <= 0) {
      this.addComponentError.set('Quantity per must be a positive number.');
      return;
    }

    this.addingComponent.set(true);
    this.addComponentError.set(null);

    const sourcing = this.newComponentSourcing();
    const request: AddBomComponentRequest = {
      componentProductUid,
      qtyPer,
      sourcing: sourcing || undefined,
      scrapPercent: this.newComponentScrapPercent().trim() || undefined,
      reference: this.newComponentReference().trim() || undefined,
    };

    this.bomService.addComponent(this.uid(), request).subscribe({
      next: (newLine) => {
        const current = this.bom();
        if (current) {
          this.bom.set({ ...current, components: [...current.components, newLine] });
        }
        this.addingComponent.set(false);
        this.resetAddComponentForm();
        this.showAddComponent.set(false);
        this.alerts.success('Component added', newLine.componentProductCode);
      },
      error: (err: unknown) => {
        this.addComponentError.set(this.messageFrom(err, 'Could not add component.'));
        this.addingComponent.set(false);
      },
    });
  }

  // ── Edit component (inline) ───────────────────────────────────────────────

  startEditComponent(c: BomComponentDto): void {
    this.editingComponentUid.set(c.uid);
    this.editQtyPer.set(c.qtyPer);
    this.editSourcing.set(c.sourcing ?? '');
    this.editScrapPercent.set(c.scrapPercent ?? '0');
    this.editReference.set(c.reference ?? '');
    this.editComponentError.set(null);
  }

  cancelEditComponent(): void {
    this.editingComponentUid.set(null);
    this.editComponentError.set(null);
  }

  saveComponent(componentUid: string): void {
    const qtyPer = this.editQtyPer().trim();
    if (!qtyPer || isNaN(Number(qtyPer)) || Number(qtyPer) <= 0) {
      this.editComponentError.set('Quantity per must be a positive number.');
      return;
    }

    this.editingComponent.set(true);
    this.editComponentError.set(null);

    const sourcing = this.editSourcing();
    const request: UpdateBomComponentRequest = {
      qtyPer,
      sourcing: sourcing || undefined,
      scrapPercent: this.editScrapPercent().trim() || undefined,
      reference: this.editReference().trim() || undefined,
    };

    this.bomService.updateComponent(this.uid(), componentUid, request).subscribe({
      next: (updated) => {
        const current = this.bom();
        if (current) {
          this.bom.set({
            ...current,
            components: current.components.map((c) => (c.uid === componentUid ? updated : c)),
          });
        }
        this.editingComponent.set(false);
        this.editingComponentUid.set(null);
        this.alerts.success('Component updated', updated.componentProductCode);
      },
      error: (err: unknown) => {
        this.editComponentError.set(this.messageFrom(err, 'Could not update component.'));
        this.editingComponent.set(false);
      },
    });
  }

  // ── Remove component ──────────────────────────────────────────────────────

  removeComponent(componentUid: string): void {
    this.removingComponentUid.set(componentUid);
    this.bomService.removeComponent(this.uid(), componentUid).subscribe({
      next: () => {
        const current = this.bom();
        if (current) {
          this.bom.set({
            ...current,
            components: current.components.filter((c) => c.uid !== componentUid),
          });
        }
        this.removingComponentUid.set(null);
        this.alerts.success('Component removed', componentUid);
      },
      error: (err: unknown) => {
        this.alerts.error('Remove failed', this.messageFrom(err, 'Could not remove component.'));
        this.removingComponentUid.set(null);
      },
    });
  }

  // ── Display helpers ───────────────────────────────────────────────────────

  /**
   * Resolve a product uid to its human-readable label (name + code hint).
   * Falls back to a short truncation if the product is not yet loaded.
   */
  productLabel(uid: string): string {
    const opt = this.productOptions().find((o) => o.uid === uid);
    if (opt) return opt.hint ? `${opt.label} (${opt.hint})` : opt.label;
    return uid ? uid.slice(0, 8) + '…' : '—';
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
