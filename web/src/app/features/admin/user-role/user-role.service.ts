import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { GrantRoleRequest, UserRole } from '../models/user-role.model';

/** User-role grant API client. Typed to the unwrapped DTO — the interceptor strips the envelope. */
@Injectable({ providedIn: 'root' })
export class UserRoleService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/user-roles`;

  grant(request: GrantRoleRequest): Observable<UserRole> {
    return this.http.post<UserRole>(this.base, request);
  }

  revoke(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }

  listForUser(userUid: string): Observable<UserRole[]> {
    return this.http.get<UserRole[]>(this.base, { params: { userUid } });
  }
}
