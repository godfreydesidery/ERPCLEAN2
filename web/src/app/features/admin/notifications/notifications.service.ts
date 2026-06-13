import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  NotificationDeliveryDto,
  NotificationDto,
  NotificationPreferenceDto,
  NotificationTypeDto,
  SetCompanyTypeStateRequest,
  SetPreferenceRequest,
  UnreadCountDto,
} from './models/notifications.model';

export interface NotificationPage {
  rows: NotificationDto[];
  meta: PageMeta;
}

export interface DeliveryLogPage {
  rows: NotificationDeliveryDto[];
  meta: PageMeta;
}

/**
 * Notifications API client.
 * listInbox() and listDeliveries() use SKIP_UNWRAP → {rows, meta}.
 * All other methods use the auto-unwrap path (interceptor strips envelope).
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);
  private readonly notifBase = `${environment.apiBaseUrl}/notifications`;
  private readonly prefBase = `${environment.apiBaseUrl}/notification-preferences`;
  private readonly adminBase = `${environment.apiBaseUrl}/admin/notifications`;

  // ── Inbox ─────────────────────────────────────────────────────────────────────

  listInbox(unread: boolean, page = 0, size = 20): Observable<NotificationPage> {
    let params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    if (unread) {
      params = params.set('unread', 'true');
    }
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<NotificationDto[]>>(this.notifBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getUnreadCount(): Observable<UnreadCountDto> {
    return this.http.get<UnreadCountDto>(`${this.notifBase}/unread-count`);
  }

  /** Mark a single notification read. Returns 204 No Content → typed void. */
  markRead(uid: string): Observable<void> {
    return this.http.post<void>(`${this.notifBase}/uid/${uid}/read`, {});
  }

  /** Mark all unread notifications read for the active company. Returns 204 No Content. */
  markAllRead(): Observable<void> {
    return this.http.post<void>(`${this.notifBase}/read-all`, {});
  }

  // ── Preferences ───────────────────────────────────────────────────────────────

  /** Returns a plain (non-paginated) array. Interceptor unwraps. */
  listPreferences(): Observable<NotificationPreferenceDto[]> {
    return this.http.get<NotificationPreferenceDto[]>(this.prefBase);
  }

  upsertPreference(typeKey: string, request: SetPreferenceRequest): Observable<NotificationPreferenceDto> {
    return this.http.put<NotificationPreferenceDto>(`${this.prefBase}/${typeKey}`, request);
  }

  // ── Admin: Types ──────────────────────────────────────────────────────────────

  /** Returns a plain (non-paginated) array. Interceptor unwraps. */
  listTypes(): Observable<NotificationTypeDto[]> {
    return this.http.get<NotificationTypeDto[]>(`${this.adminBase}/types`);
  }

  /** Toggle company_enabled for a type. Uses typeKey (e.g. LOW_STOCK), not uid. */
  setTypeState(typeKey: string, request: SetCompanyTypeStateRequest): Observable<NotificationTypeDto> {
    return this.http.put<NotificationTypeDto>(`${this.adminBase}/types/${typeKey}/state`, request);
  }

  // ── Admin: Delivery Log ───────────────────────────────────────────────────────

  listDeliveries(channel?: string, outcome?: string, page = 0, size = 20): Observable<DeliveryLogPage> {
    let params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    if (channel) params = params.set('channel', channel);
    if (outcome) params = params.set('outcome', outcome);
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<NotificationDeliveryDto[]>>(`${this.adminBase}/deliveries`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }
}
