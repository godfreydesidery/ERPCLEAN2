import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import { AuditFilter, AuditLogEntry } from '../models/audit.model';

export interface AuditPage {
  rows: AuditLogEntry[];
  meta: PageMeta;
}

/**
 * Audit API client. Uses the {@link SKIP_UNWRAP} context token so it receives the full
 * {@code ApiResponse<AuditLogEntry[]>} envelope, giving access to both `data` (the rows) and
 * `meta` (page / totalElements / hasNext). All other services use the auto-unwrap path.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/audit`;

  search(filters: AuditFilter, page: number, size: number): Observable<AuditPage> {
    let params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));

    if (filters.actorUid?.trim())   params = params.set('actorUid',   filters.actorUid.trim());
    if (filters.action?.trim())     params = params.set('action',     filters.action.trim());
    if (filters.targetType?.trim()) params = params.set('targetType', filters.targetType.trim());
    if (filters.targetUid?.trim())  params = params.set('targetUid',  filters.targetUid.trim());
    if (filters.from?.trim())       params = params.set('from',       filters.from.trim());
    if (filters.to?.trim())         params = params.set('to',         filters.to.trim());

    const context = new HttpContext().set(SKIP_UNWRAP, true);

    return this.http
      .get<ApiResponse<AuditLogEntry[]>>(this.base, { params, context })
      .pipe(
        map((envelope) => ({
          rows: envelope.data ?? [],
          meta: envelope.meta ?? {
            page,
            size,
            totalElements: envelope.data?.length ?? 0,
            totalPages: 1,
            hasNext: false,
          },
        })),
      );
  }
}
