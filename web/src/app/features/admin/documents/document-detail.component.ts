import { HttpErrorResponse } from '@angular/common/http';
import { SlicePipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DocumentsService } from './documents.service';
import { DocumentType, GeneratedDocumentDto } from './models/documents.model';

type LoadState = 'loading' | 'idle' | 'error' | 'forbidden';

/**
 * Generated-document detail screen — shows the render-log record + download action.
 * Route: /admin/documents/uid/:uid
 * Perm: DOCUMENT.VIEW (read), DOCUMENT.RENDER (download)
 */
@Component({
  selector: 'app-document-detail',
  imports: [RouterLink, SlicePipe],
  templateUrl: './document-detail.component.html',
  styleUrl: './document-detail.component.scss',
})
export class DocumentDetailComponent {
  private readonly docService = inject(DocumentsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  /** Bound from route :uid via withComponentInputBinding */
  readonly uid = input.required<string>();

  readonly entity = signal<GeneratedDocumentDto | null>(null);
  readonly state = signal<LoadState>('loading');
  readonly saveError = signal<string | null>(null);
  readonly downloading = signal(false);

  readonly canRender = computed(() => this.session.hasPermission('DOCUMENT.RENDER'));

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    this.loadDocument();
  }

  private loadDocument(): void {
    this.state.set('loading');
    this.docService.getByUid(this.uid()).subscribe({
      next: (doc) => {
        this.entity.set(doc);
        this.state.set('idle');
      },
      error: (err: unknown) => {
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        );
      },
    });
  }

  download(): void {
    const doc = this.entity();
    if (!doc || this.downloading()) return;
    this.downloading.set(true);
    this.docService.downloadBlob(doc.uid).subscribe({
      next: (blob) => {
        this.downloading.set(false);
        triggerBlobDownload(blob, `${doc.documentType.toLowerCase()}-${doc.documentNumber}.pdf`);
      },
      error: (err: unknown) => {
        this.downloading.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not download the PDF.'));
        this.alerts.error('Download failed', 'Could not fetch the PDF.');
      },
    });
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
