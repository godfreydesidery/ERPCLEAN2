import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  DocumentBrandingDto,
  DocumentTemplateDto,
  DocumentType,
  GeneratedDocumentDto,
  RenderDocumentRequest,
  UpdateDocumentBrandingRequest,
  UpdateDocumentTemplateRequest,
} from './models/documents.model';

export interface GeneratedDocumentPage {
  rows: GeneratedDocumentDto[];
  meta: PageMeta;
}

/**
 * Documents module HTTP client.
 * - list() uses SKIP_UNWRAP → {rows, meta} (only paginated endpoint).
 * - All other GETs/POSTs/PUTs are auto-unwrapped by the interceptor → raw T.
 * - PDF byte-stream endpoints (download/render-stream) use responseType:'blob'.
 */
@Injectable({ providedIn: 'root' })
export class DocumentsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/documents`;

  // ── Generated Documents (render log) ─────────────────────────────────────

  /**
   * Paginated render-log list.
   * Uses SKIP_UNWRAP so the caller receives both rows and PageMeta.
   * companyId is mandatory per the backend controller.
   */
  list(
    companyId: string,
    page = 0,
    size = 20,
    type?: DocumentType,
    sourceUid?: string,
    from?: string,
    to?: string,
  ): Observable<GeneratedDocumentPage> {
    let params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    if (type) params = params.set('type', type);
    if (sourceUid) params = params.set('sourceUid', sourceUid);
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);

    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<GeneratedDocumentDto[]>>(this.base, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? {
            page,
            size,
            totalElements: env.data?.length ?? 0,
            totalPages: 1,
            hasNext: false,
          },
        })),
      );
  }

  getByUid(uid: string): Observable<GeneratedDocumentDto> {
    return this.http.get<GeneratedDocumentDto>(`${this.base}/${uid}`);
  }

  /** POST render — returns the log record (JSON). */
  render(request: RenderDocumentRequest): Observable<GeneratedDocumentDto> {
    return this.http.post<GeneratedDocumentDto>(`${this.base}/render`, request);
  }

  /**
   * Download re-render for a previously generated document.
   * Returns a Blob (application/pdf).
   */
  downloadBlob(uid: string): Observable<Blob> {
    return this.http.get(`${this.base}/${uid}/download`, { responseType: 'blob' });
  }

  /**
   * GET /render convenience stream — returns PDF bytes directly.
   * Use for AR_STATEMENT and ad-hoc convenience renders.
   */
  renderBlob(type: DocumentType, source?: string, params?: string): Observable<Blob> {
    let httpParams = new HttpParams().set('type', type);
    if (source) httpParams = httpParams.set('source', source);
    if (params) httpParams = httpParams.set('params', params);
    return this.http.get(`${this.base}/render`, { params: httpParams, responseType: 'blob' });
  }

  // ── Document Templates ────────────────────────────────────────────────────

  /**
   * List the active company's template registry (NOT paginated, NOT wrapped).
   * Company resolved from RequestContext — no query params needed.
   */
  listTemplates(): Observable<DocumentTemplateDto[]> {
    return this.http.get<DocumentTemplateDto[]>(`${this.base}/templates`);
  }

  updateTemplate(uid: string, request: UpdateDocumentTemplateRequest): Observable<DocumentTemplateDto> {
    return this.http.put<DocumentTemplateDto>(`${this.base}/templates/${uid}`, request);
  }

  // ── Document Branding ─────────────────────────────────────────────────────

  /** GET the active company's singleton branding profile. */
  getBranding(): Observable<DocumentBrandingDto> {
    return this.http.get<DocumentBrandingDto>(`${this.base}/branding`);
  }

  /** PUT upsert the active company's branding profile. */
  updateBranding(request: UpdateDocumentBrandingRequest): Observable<DocumentBrandingDto> {
    return this.http.put<DocumentBrandingDto>(`${this.base}/branding`, request);
  }
}
