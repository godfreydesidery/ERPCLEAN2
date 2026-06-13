import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { DocumentsService } from './documents.service';
import {
  DocumentTemplateDto,
  MasterStatus,
  UpdateDocumentTemplateRequest,
} from './models/documents.model';

type LoadState = 'loading' | 'idle' | 'error' | 'forbidden';

interface EditRow {
  uid: string;
  title: string;
  status: MasterStatus;
  brandingId: string;
  version: string;
  saving: boolean;
  error: string | null;
}

/**
 * Document-templates admin screen — list the company's registry rows + inline edit each.
 * Route: /admin/document-templates
 * Perm: DOCUMENT.TEMPLATE.MANAGE
 * Note: NOT paginated; company resolved from RequestContext (no companyId param needed).
 */
@Component({
  selector: 'app-document-template-list',
  imports: [FormsModule],
  templateUrl: './document-template-list.component.html',
  styleUrl: './document-template-list.component.scss',
})
export class DocumentTemplateListComponent {
  private readonly docService = inject(DocumentsService);
  private readonly alerts = inject(AlertService);
  protected readonly session = inject(SessionStore);

  readonly rows = signal<DocumentTemplateDto[]>([]);
  readonly state = signal<LoadState>('loading');
  readonly editRows = signal<Map<string, EditRow>>(new Map());

  readonly canManage = computed(() => this.session.hasPermission('DOCUMENT.TEMPLATE.MANAGE'));
  readonly isEmpty = computed(() => this.state() === 'idle' && this.rows().length === 0);

  readonly statusOptions: MasterStatus[] = ['ACTIVE', 'INACTIVE', 'ARCHIVED'];

  constructor() {
    queueMicrotask(() => this.load());
  }

  private load(): void {
    this.state.set('loading');
    this.docService.listTemplates().subscribe({
      next: (list) => {
        this.rows.set(list);
        // Pre-populate edit rows with current values
        const map = new Map<string, EditRow>();
        list.forEach((t) =>
          map.set(t.uid, {
            uid: t.uid,
            title: t.title,
            status: t.status,
            brandingId: t.brandingId,
            version: t.version,
            saving: false,
            error: null,
          }),
        );
        this.editRows.set(map);
        this.state.set('idle');
      },
      error: (err: unknown) =>
        this.state.set(
          err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error',
        ),
    });
  }

  getEdit(uid: string): EditRow | undefined {
    return this.editRows().get(uid);
  }

  updateEditField(uid: string, field: keyof EditRow, value: string): void {
    this.editRows.update((map) => {
      const row = map.get(uid);
      if (!row) return map;
      const next = new Map(map);
      next.set(uid, { ...row, [field]: value, error: null });
      return next;
    });
  }

  save(uid: string): void {
    const row = this.editRows().get(uid);
    if (!row || row.saving) return;

    this.editRows.update((map) => {
      const next = new Map(map);
      next.set(uid, { ...row, saving: true, error: null });
      return next;
    });

    const request: UpdateDocumentTemplateRequest = {
      title: row.title,
      status: row.status,
      brandingId: row.brandingId,
      version: row.version,
    };

    this.docService.updateTemplate(uid, request).subscribe({
      next: (updated) => {
        // Update the row list and edit map with fresh data
        this.rows.update((list) => list.map((t) => (t.uid === uid ? updated : t)));
        this.editRows.update((map) => {
          const next = new Map(map);
          next.set(uid, {
            uid: updated.uid,
            title: updated.title,
            status: updated.status,
            brandingId: updated.brandingId,
            version: updated.version,
            saving: false,
            error: null,
          });
          return next;
        });
        this.alerts.success('Template updated', updated.title);
      },
      error: (err: unknown) => {
        const msg = this.messageFrom(err, 'Could not save template.');
        this.editRows.update((map) => {
          const next = new Map(map);
          const current = next.get(uid);
          if (current) next.set(uid, { ...current, saving: false, error: msg });
          return next;
        });
      },
    });
  }

  statusBadgeClass(status: MasterStatus): string {
    switch (status) {
      case 'ACTIVE': return 'text-bg-success';
      case 'INACTIVE': return 'text-bg-warning';
      case 'ARCHIVED': return 'text-bg-secondary';
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
