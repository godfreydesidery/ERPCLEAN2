import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AlertService } from '../../../core/feedback/alert.service';
import { blobErrorMessage } from '../../../core/api/blob-error';
import { EntityDescriptor, ImportReport } from './bulk-import.model';
import { BulkImportService } from './bulk-import.service';

type EntitiesState = 'loading' | 'idle' | 'error' | 'forbidden';

/**
 * Generic bulk-import wizard: pick an entity type → download its XLSX template → upload a filled
 * file for a validate dry-run → commit. Drives entirely off `GET /bulk/entities`, so it grows with
 * the backend's importable entity registry with no client-side entity list to maintain.
 *
 * Route: /admin/bulk-import. The route guard admits ANY of the four import permission codes
 * (PRODUCT.IMPORT / CUSTOMER.IMPORT / SUPPLIER.IMPORT — a mass-price-change 4th code belongs to the
 * sibling screen, not this one); the entities endpoint itself 403s when the caller can import
 * nothing, which this component renders as a calm "no permission" state rather than a hard failure.
 */
@Component({
  selector: 'app-bulk-import',
  imports: [FormsModule],
  templateUrl: './bulk-import.component.html',
  styleUrl: './bulk-import.component.scss',
})
export class BulkImportComponent {
  private readonly bulkService = inject(BulkImportService);
  private readonly alerts = inject(AlertService);

  /** Template ref to the native file input — cleared programmatically on reset/entity change so a
   * stale filename never lingers in the OS-rendered picker after "Import another file". */
  private readonly fileInputEl = viewChild<ElementRef<HTMLInputElement>>('bulkFileInput');

  // ── Entity picker ────────────────────────────────────────────────────────────
  readonly entities = signal<EntityDescriptor[]>([]);
  readonly entitiesState = signal<EntitiesState>('loading');
  readonly selectedKey = signal('');
  readonly selectedEntity = computed<EntityDescriptor | null>(
    () => this.entities().find((e) => e.key === this.selectedKey()) ?? null,
  );

  // ── Template download ────────────────────────────────────────────────────────
  readonly downloadingTemplate = signal(false);
  readonly templateError = signal<string | null>(null);

  // ── Upload & validate ────────────────────────────────────────────────────────
  readonly selectedFile = signal<File | null>(null);
  readonly validating = signal(false);
  readonly validateError = signal<string | null>(null);

  // ── Commit ───────────────────────────────────────────────────────────────────
  readonly committing = signal(false);
  readonly commitError = signal<string | null>(null);
  readonly committed = signal(false);

  // ── Report (shared by both validate and commit — mode discriminates the copy) ─
  readonly report = signal<ImportReport | null>(null);

  readonly canCommit = computed(() => {
    const rep = this.report();
    return rep !== null && rep.mode === 'VALIDATE' && rep.errors === 0 && !this.committed();
  });

  constructor() {
    queueMicrotask(() => this.loadEntities());
  }

  // ── Entities ─────────────────────────────────────────────────────────────────

  private loadEntities(): void {
    this.entitiesState.set('loading');
    this.bulkService.listEntities().subscribe({
      next: (list) => {
        this.entities.set(list);
        this.entitiesState.set(list.length > 0 ? 'idle' : 'forbidden');
        if (list.length > 0) {
          this.selectedKey.set(list[0].key);
        }
      },
      error: (err) =>
        this.entitiesState.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  onEntityChange(key: string): void {
    this.selectedKey.set(key);
    this.templateError.set(null);
    this.resetFileState();
  }

  // ── Template download ────────────────────────────────────────────────────────

  downloadTemplate(): void {
    const key = this.selectedKey();
    if (!key || this.downloadingTemplate()) return;
    this.downloadingTemplate.set(true);
    this.templateError.set(null);
    this.bulkService.downloadTemplate(key).subscribe({
      next: (blob) => {
        this.downloadingTemplate.set(false);
        triggerBlobDownload(blob, `${key}-import-template.xlsx`);
      },
      error: (err) => {
        this.downloadingTemplate.set(false);
        void blobErrorMessage(err, 'Could not download the template.').then((m) => this.templateError.set(m));
      },
    });
  }

  // ── Upload & validate ────────────────────────────────────────────────────────

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    input.value = ''; // allow re-selecting the same file later
    if (!file) return;
    if (!file.name.toLowerCase().endsWith('.xlsx')) {
      this.validateError.set('Please choose an .xlsx file — the template you downloaded in step 2.');
      return;
    }
    this.report.set(null);
    this.commitError.set(null);
    this.committed.set(false);
    this.selectedFile.set(file);
    this.validateFile(file);
  }

  private validateFile(file: File): void {
    const key = this.selectedKey();
    if (!key || this.validating()) return;
    this.validating.set(true);
    this.validateError.set(null);
    this.bulkService.validate(key, file).subscribe({
      next: (rep) => {
        this.report.set(rep);
        this.validating.set(false);
      },
      error: (err) => {
        this.validating.set(false);
        this.validateError.set(this.messageFrom(err, 'Could not validate the file.'));
      },
    });
  }

  // ── Commit ───────────────────────────────────────────────────────────────────

  commit(): void {
    const key = this.selectedKey();
    const file = this.selectedFile();
    if (!key || !file || !this.canCommit() || this.committing()) return;
    this.committing.set(true);
    this.commitError.set(null);
    this.bulkService.commit(key, file).subscribe({
      next: (rep) => {
        this.report.set(rep);
        this.committing.set(false);
        this.committed.set(true);
        this.alerts.success('Import completed', `Created ${rep.created}, updated ${rep.updated}.`);
      },
      error: (err) => {
        this.committing.set(false);
        this.commitError.set(this.messageFrom(err, 'Could not commit the import.'));
      },
    });
  }

  /** Clears the picked file, the report, and every per-file error/flag — used on entity change and
   * by the "Import another file" action after a successful commit. */
  resetFileState(): void {
    this.selectedFile.set(null);
    this.report.set(null);
    this.validateError.set(null);
    this.commitError.set(null);
    this.committed.set(false);
    const input = this.fileInputEl()?.nativeElement;
    if (input) input.value = '';
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

/** Create an object URL for a blob and click a synthetic anchor to download it. */
function triggerBlobDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
