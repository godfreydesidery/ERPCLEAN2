import { HttpErrorResponse } from '@angular/common/http';
import { SlicePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, map, merge, skip, Subject, switchMap } from 'rxjs';
import { PageMeta } from '../../../core/api/api-response.model';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { Company } from '../models/company.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { DocumentsService } from './documents.service';
import type { GeneratedDocumentPage } from './documents.service';
import {
  DocumentType,
  GeneratedDocumentDto,
  RenderDocumentRequest,
} from './models/documents.model';
import { PaginatorComponent } from '../../../shared/paginator/paginator.component';
import { SalesService } from '../sales/sales.service';
import { SalesOrdersService } from '../sales/sales-orders.service';
import { PurchasesService } from '../purchases/purchases.service';
import { UidPickerComponent, UidOption } from '../../../shared/uid-picker/uid-picker.component';

const DEFAULT_SIZE = 20;

interface LoadTrigger { page: number }

/**
 * Generated-documents render log list.
 * Route: /admin/documents
 * Perm: DOCUMENT.VIEW (list), DOCUMENT.RENDER (render action)
 */
@Component({
  selector: 'app-document-list',
  imports: [FormsModule, RouterLink, SlicePipe, PaginatorComponent, UidPickerComponent],
  templateUrl: './document-list.component.html',
  styleUrl: './document-list.component.scss',
})
export class DocumentListComponent {
  private readonly docService = inject(DocumentsService);
  private readonly companyService = inject(CompanyService);
  private readonly organisationService = inject(OrganisationService);
  private readonly salesService = inject(SalesService);
  private readonly soService = inject(SalesOrdersService);
  private readonly purchasesService = inject(PurchasesService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  // ── Company context ─────────────────────────────────────────────────────────
  readonly companies = signal<Company[]>([]);
  readonly selectedCompanyId = signal('');
  readonly companyState = signal<'loading' | 'idle' | 'error'>('loading');

  // ── Filters ──────────────────────────────────────────────────────────────────
  readonly filterType = signal<DocumentType | ''>('');
  readonly filterSourceUid = signal('');

  // ── List state ───────────────────────────────────────────────────────────────
  readonly rows = signal<GeneratedDocumentDto[]>([]);
  readonly meta = signal<PageMeta>({
    page: 0,
    size: DEFAULT_SIZE,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
  });
  readonly state = signal<'loading' | 'idle' | 'error' | 'forbidden'>('idle');
  readonly currentPage = signal(0);
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  // ── Render form ───────────────────────────────────────────────────────────────
  readonly showRenderForm = signal(false);
  readonly newDocType = signal<DocumentType>('INVOICE');
  readonly newSourceUid = signal('');
  readonly newSourceParams = signal('');
  readonly rendering = signal(false);
  readonly formError = signal<string | null>(null);

  // ── Source pickers (render form) ──────────────────────────────────────────────
  /** Raw option arrays per document type, loaded once company is known. */
  private readonly sourceOptionsByType = signal<Partial<Record<DocumentType, UidOption[]>>>({});

  /** Options for the newSourceUid picker — scoped to the selected document type. */
  readonly sourceOptions = computed<UidOption[]>(() => {
    const t = this.newDocType();
    return this.sourceOptionsByType()[t] ?? [];
  });

  readonly canRender = computed(() => this.session.hasPermission('DOCUMENT.RENDER'));
  readonly canView = computed(() => this.session.hasPermission('DOCUMENT.VIEW'));

  readonly documentTypes: Array<{ value: DocumentType; label: string }> = [
    { value: 'INVOICE', label: 'Invoice' },
    { value: 'AR_STATEMENT', label: 'AR Statement' },
    { value: 'PURCHASE_ORDER', label: 'Purchase Order' },
    { value: 'GOODS_RECEIPT', label: 'Goods Receipt' },
    { value: 'DELIVERY_NOTE', label: 'Delivery Note' },
    { value: 'CREDIT_NOTE', label: 'Credit Note' },
  ];

  readonly filterTypeOptions: Array<{ value: DocumentType | ''; label: string }> = [
    { value: '', label: 'All types' },
    ...this.documentTypes,
  ];

  private readonly immediateTrigger$ = new Subject<LoadTrigger>();

  constructor() {
    const filterTrigger$ = toObservable(this.filterType).pipe(
      skip(1),
      debounceTime(50),
      distinctUntilChanged(),
      map((): LoadTrigger => ({ page: 0 })),
    );

    merge(filterTrigger$, this.immediateTrigger$)
      .pipe(
        switchMap(({ page }) => {
          const companyId = this.selectedCompanyId();
          if (!companyId) return [];
          this.state.set('loading');
          this.currentPage.set(page);
          return this.docService.list(
            companyId,
            page,
            DEFAULT_SIZE,
            this.filterType() || undefined,
            this.filterSourceUid().trim() || undefined,
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ rows, meta }: GeneratedDocumentPage) => {
          this.rows.set(rows);
          this.meta.set(meta);
          this.state.set('idle');
        },
        error: (err: unknown) =>
          this.state.set(
            err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
          ),
      });

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
              this.load(0);
              this.loadSourceOptions(list[0].id);
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
    if (id) {
      this.load(0);
      this.loadSourceOptions(id);
    }
  }

  private loadSourceOptions(companyId: string): void {
    // INVOICE — sales invoices
    this.salesService.list(companyId, undefined, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.mergeSourceOptions('INVOICE',
        rows.map((r) => ({ uid: r.uid, label: r.invoiceNumber ?? r.uid, hint: r.status }))),
      error: () => undefined,
    });
    // DELIVERY_NOTE — deliveries
    this.soService.listDeliveries(companyId, 0, 200).subscribe({
      next: ({ rows }) => this.mergeSourceOptions('DELIVERY_NOTE',
        rows.map((r) => ({ uid: r.uid, label: r.deliveryNumber ?? r.uid, hint: r.status }))),
      error: () => undefined,
    });
    // PURCHASE_ORDER — purchase orders
    this.purchasesService.listOrders(companyId, undefined, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.mergeSourceOptions('PURCHASE_ORDER',
        rows.map((r) => ({ uid: r.uid, label: r.orderNumber ?? r.uid, hint: r.status }))),
      error: () => undefined,
    });
    // GOODS_RECEIPT — goods receipts
    this.purchasesService.listReceipts(companyId, undefined, 0, 200).subscribe({
      next: ({ rows }) => this.mergeSourceOptions('GOODS_RECEIPT',
        rows.map((r) => ({ uid: r.uid, label: r.receiptNumber ?? r.uid, hint: r.status }))),
      error: () => undefined,
    });
    // CREDIT_NOTE — sales returns (source of credit notes)
    this.soService.listReturns(companyId, 0, 200).subscribe({
      next: ({ rows }) => this.mergeSourceOptions('CREDIT_NOTE',
        rows.map((r) => ({ uid: r.uid, label: r.returnNumber ?? r.uid, hint: r.status }))),
      error: () => undefined,
    });
  }

  private mergeSourceOptions(type: DocumentType, options: UidOption[]): void {
    this.sourceOptionsByType.update((map) => ({ ...map, [type]: options }));
  }

  load(page: number): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;
    this.immediateTrigger$.next({ page });
  }

  applyFilters(): void {
    this.load(0);
  }

  goToPage(page: number): void { this.load(page); }

  prevPage(): void {
    if (this.currentPage() > 0) this.load(this.currentPage() - 1);
  }

  nextPage(): void {
    if (this.meta().hasNext) this.load(this.currentPage() + 1);
  }

  // ── Render form ──────────────────────────────────────────────────────────────

  toggleRenderForm(): void {
    this.showRenderForm.update((v) => !v);
    this.formError.set(null);
    if (!this.showRenderForm()) this.resetRenderForm();
  }

  private resetRenderForm(): void {
    this.newDocType.set('INVOICE');
    this.newSourceUid.set('');
    this.newSourceParams.set('');
    this.formError.set(null);
  }

  isStatementType(): boolean {
    return this.newDocType() === 'AR_STATEMENT';
  }

  submitRender(): void {
    const docType = this.newDocType();
    const sourceUid = this.newSourceUid().trim();
    const sourceParams = this.newSourceParams().trim();

    if (docType === 'AR_STATEMENT') {
      if (!sourceParams) {
        this.formError.set('Source params (JSON) are required for AR Statement.');
        return;
      }
    } else {
      if (!sourceUid) {
        this.formError.set('Source UID is required.');
        return;
      }
    }

    this.rendering.set(true);
    this.formError.set(null);

    const request: RenderDocumentRequest = {
      documentType: docType,
      sourceUid: sourceUid || undefined,
      sourceParams: sourceParams || undefined,
    };

    this.docService.render(request).subscribe({
      next: (doc) => {
        this.rendering.set(false);
        this.resetRenderForm();
        this.showRenderForm.set(false);
        this.alerts.success('Document rendered', doc.documentNumber);
        this.load(this.currentPage());
      },
      error: (err: unknown) => {
        this.formError.set(this.messageFrom(err, 'Could not render document.'));
        this.rendering.set(false);
      },
    });
  }

  // ── Download ──────────────────────────────────────────────────────────────────

  download(doc: GeneratedDocumentDto): void {
    this.docService.downloadBlob(doc.uid).subscribe({
      next: (blob) => triggerBlobDownload(blob, `${doc.documentType.toLowerCase()}-${doc.documentNumber}.pdf`),
      error: () => this.alerts.error('Download failed', 'Could not fetch the PDF.'),
    });
  }

  // ── Display helpers ──────────────────────────────────────────────────────────

  statusBadgeClass(_status: string): string {
    return 'text-bg-secondary';
  }

  typeBadgeClass(type: DocumentType): string {
    switch (type) {
      case 'INVOICE': return 'text-bg-primary';
      case 'AR_STATEMENT': return 'text-bg-info';
      case 'PURCHASE_ORDER': return 'text-bg-warning';
      case 'GOODS_RECEIPT': return 'text-bg-success';
      case 'DELIVERY_NOTE': return 'text-bg-secondary';
      case 'CREDIT_NOTE': return 'text-bg-danger';
      default: return 'text-bg-secondary';
    }
  }

  private messageFrom(err: unknown, fallback: string): string {
    if (err instanceof HttpErrorResponse) {
      const errors = (err.error as { errors?: string[] })?.errors;
      if (errors?.length) return errors[0];
    }
    return fallback;
  }
}

function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
