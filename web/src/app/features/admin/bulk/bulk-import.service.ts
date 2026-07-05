import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { EntityDescriptor, ImportReport } from './bulk-import.model';

/**
 * Bulk-import API client (template download → validate dry-run → commit).
 * Base: /api/v1/bulk. All JSON endpoints are auto-unwrapped by the interceptor.
 */
@Injectable({ providedIn: 'root' })
export class BulkImportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/bulk`;

  /**
   * Entity types the current user may import. The backend 403s when the user can import nothing —
   * the caller (not the route guard) surfaces that as a calm "no permission" state, so a user who
   * holds none of the four import permissions isn't hard-blocked from opening the wizard shell.
   */
  listEntities(): Observable<EntityDescriptor[]> {
    return this.http.get<EntityDescriptor[]>(`${this.base}/entities`);
  }

  /**
   * Downloads the XLSX import template for the given entity key.
   * `ResponseEntity<byte[]>` on the backend — NOT the ApiResponse envelope — so this request opts
   * out of the auto-unwrap via {@link SKIP_UNWRAP} (a Blob body never matches the envelope shape
   * anyway, but the token documents that this endpoint is intentionally different).
   */
  downloadTemplate(key: string): Observable<Blob> {
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http.get(`${this.base}/${key}/template`, { responseType: 'blob', context });
  }

  /**
   * Downloads the entity's CURRENT rows pre-filled into the same template — the download → edit →
   * re-upload round-trip (re-uploading the edited file to commit() applies the changes as updates).
   * `priceListCode` is only meaningful for the `prices` entity (an optional `?priceList=<code>`
   * query param); other entities need no extra params.
   */
  exportCurrent(key: string, priceListCode?: string): Observable<Blob> {
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    let params = new HttpParams();
    if (priceListCode) params = params.set('priceList', priceListCode);
    return this.http.get(`${this.base}/${key}/export`, { responseType: 'blob', context, params });
  }

  /** Dry-run validation — nothing is written; returns the same report shape as commit(). */
  validate(key: string, file: File): Observable<ImportReport> {
    return this.http.post<ImportReport>(`${this.base}/${key}/validate`, toFormData(file));
  }

  /** Performs the create/update for every non-error row. */
  commit(key: string, file: File): Observable<ImportReport> {
    return this.http.post<ImportReport>(`${this.base}/${key}/commit`, toFormData(file));
  }
}

/** Field name must be `file` per the backend multipart contract. No explicit Content-Type — the
 * browser sets the multipart boundary itself. */
function toFormData(file: File): FormData {
  const fd = new FormData();
  fd.append('file', file);
  return fd;
}
