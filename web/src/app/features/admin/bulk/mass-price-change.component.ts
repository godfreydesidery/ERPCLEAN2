import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { PriceListDto } from '../models/product.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';
import { formatMoney } from '../../../shared/money.util';
import { MassPriceChangeRequest, MassPriceChangeResult, PriceChangeType } from './mass-price-change.model';
import { MassPriceChangeService } from './mass-price-change.service';

/**
 * Rule-based mass price change: preview (dryRun) then apply a PERCENT/AMOUNT/SET change across
 * every price row in a price list. Route: /admin/mass-price-change. Gated PRICE.MASS_UPDATE.
 *
 * Apply is only enabled immediately after a successful Preview against the CURRENT field values —
 * editing any field afterwards clears the result and re-locks Apply until the user previews again,
 * so the samples the user confirms can never go stale relative to what will actually be applied.
 */
@Component({
  selector: 'app-mass-price-change',
  imports: [FormsModule, UidPickerComponent],
  templateUrl: './mass-price-change.component.html',
  styleUrl: './mass-price-change.component.scss',
})
export class MassPriceChangeComponent {
  private readonly priceChangeService = inject(MassPriceChangeService);
  private readonly productService = inject(ProductService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly canManage = computed(() => this.session.hasPermission('PRICE.MASS_UPDATE'));

  // ── Company context ────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Price list options ───────────────────────────────────────────────────────
  readonly priceLists = signal<PriceListDto[]>([]);
  readonly priceListOptions = computed<UidOption[]>(() =>
    this.priceLists().map((pl) => ({ uid: pl.uid, label: pl.name, hint: pl.code })),
  );

  // ── Form fields ──────────────────────────────────────────────────────────────
  readonly priceListUid = signal('');
  readonly changeType = signal<PriceChangeType>('PERCENT');
  readonly value = signal('');
  readonly roundToDecimals = signal('2');

  readonly changeTypeLabel = computed(() => {
    switch (this.changeType()) {
      case 'AMOUNT':
        return 'Amount';
      case 'SET':
        return 'New Price';
      default:
        return 'Percentage';
    }
  });

  readonly changeTypePlaceholder = computed(() => {
    switch (this.changeType()) {
      case 'AMOUNT':
        return 'e.g. 500 or -500';
      case 'SET':
        return 'e.g. 1200.00';
      default:
        return 'e.g. 10 for +10%, -5 for -5%';
    }
  });

  // ── Preview / Apply ──────────────────────────────────────────────────────────
  readonly previewing = signal(false);
  readonly applying = signal(false);
  readonly formError = signal<string | null>(null);
  readonly result = signal<MassPriceChangeResult | null>(null);

  /** Apply is enabled only right after a preview of the CURRENT fields — any field edit clears
   * `result` (see the markDirty() setters below), so this can never go stale. */
  readonly canApply = computed(() => {
    const res = this.result();
    return res !== null && res.dryRun === true;
  });

  readonly fmtMoney = formatMoney;

  constructor() {
    this.loadCompanies();
  }

  // ── Company / price-list loading ─────────────────────────────────────────────

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
              this.loadPriceLists(list[0].id);
            }
          },
          error: () => this.companyState.set('error'),
        });
      },
      error: () => this.companyState.set('error'),
    });
  }

  private loadPriceLists(companyId: string): void {
    this.productService.listPriceLists(companyId).subscribe({
      next: (rows) => this.priceLists.set(rows.filter((pl) => pl.status === 'ACTIVE')),
      error: () => this.priceLists.set([]),
    });
  }

  onCompanyChange(id: string): void {
    this.selectedCompanyId.set(id);
    this.priceLists.set([]);
    this.resetForm();
    if (id) this.loadPriceLists(id);
  }

  private resetForm(): void {
    this.priceListUid.set('');
    this.value.set('');
    this.roundToDecimals.set('2');
    this.result.set(null);
    this.formError.set(null);
  }

  // ── Field setters — any change invalidates the last preview ──────────────────

  onPriceListChange(uid: string): void {
    this.priceListUid.set(uid);
    this.markDirty();
  }

  onTypeChange(type: PriceChangeType): void {
    this.changeType.set(type);
    this.markDirty();
  }

  onValueChange(v: string): void {
    this.value.set(v);
    this.markDirty();
  }

  onRoundChange(v: string): void {
    this.roundToDecimals.set(v);
    this.markDirty();
  }

  private markDirty(): void {
    this.result.set(null);
    this.formError.set(null);
  }

  // ── Preview ──────────────────────────────────────────────────────────────────

  preview(): void {
    if (this.previewing()) return;
    this.formError.set(null);
    const request = this.buildRequest(true);
    if (!request) return;
    this.previewing.set(true);
    this.priceChangeService.apply(request).subscribe({
      next: (res) => {
        this.result.set(res);
        this.previewing.set(false);
      },
      error: (err) => {
        this.previewing.set(false);
        this.formError.set(this.messageFrom(err, 'Could not preview the price change.'));
      },
    });
  }

  // ── Apply ────────────────────────────────────────────────────────────────────

  apply(): void {
    if (!this.canApply() || this.applying()) return;
    const preview = this.result();
    if (!preview) return;
    const confirmed = window.confirm(
      `Apply this price change to ${preview.affected} price row(s) in "${preview.priceListName}"? This cannot be undone.`,
    );
    if (!confirmed) return;

    const request = this.buildRequest(false);
    if (!request) return;
    this.applying.set(true);
    this.formError.set(null);
    this.priceChangeService.apply(request).subscribe({
      next: (res) => {
        this.result.set(res);
        this.applying.set(false);
        this.alerts.success('Price change applied', `${res.affected} of ${res.totalRows} prices changed.`);
      },
      error: (err) => {
        this.applying.set(false);
        this.formError.set(this.messageFrom(err, 'Could not apply the price change.'));
      },
    });
  }

  // ── Request building / validation ─────────────────────────────────────────────

  private buildRequest(dryRun: boolean): MassPriceChangeRequest | null {
    const priceListUid = this.priceListUid();
    if (!priceListUid) {
      this.formError.set('Select a price list.');
      return null;
    }
    const valueStr = this.value().trim();
    if (!valueStr || isNaN(Number(valueStr))) {
      this.formError.set(`Enter a valid numeric ${this.changeTypeLabel().toLowerCase()}.`);
      return null;
    }
    const roundStr = this.roundToDecimals().trim();
    let roundToDecimals: number | null = null;
    if (roundStr !== '') {
      if (isNaN(Number(roundStr)) || Number(roundStr) < 0) {
        this.formError.set('Round to decimals must be a non-negative number.');
        return null;
      }
      roundToDecimals = Number(roundStr);
    }
    return {
      priceListUid,
      type: this.changeType(),
      value: Number(valueStr),
      roundToDecimals,
      dryRun,
    };
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}
