import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CreateRoleRequest,
  Permission,
  Role,
  ScreenReadGap,
  SetRolePermissionsRequest,
  UpdateRoleRequest,
} from '../models/role.model';

/** Role API client. Typed to the unwrapped DTO — the interceptor strips the envelope. */
@Injectable({ providedIn: 'root' })
export class RoleService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/roles`;

  list(): Observable<Role[]> {
    return this.http.get<Role[]>(this.base);
  }

  get(uid: string): Observable<Role> {
    return this.http.get<Role>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateRoleRequest): Observable<Role> {
    return this.http.post<Role>(this.base, request);
  }

  update(uid: string, request: UpdateRoleRequest): Observable<Role> {
    return this.http.put<Role>(`${this.base}/uid/${uid}`, request);
  }

  setPermissions(uid: string, request: SetRolePermissionsRequest): Observable<Role> {
    return this.http.put<Role>(`${this.base}/uid/${uid}/permissions`, request);
  }

  archive(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }

  listPermissions(): Observable<Permission[]> {
    return this.http.get<Permission[]>(`${this.base}/permissions`);
  }

  /**
   * Advisory read-closure gaps for a role.
   * GET /api/v1/roles/uid/{uid}/read-closure-gaps  (gated: ROLE.VIEW)
   * Returns screens where the role holds the access permission but is missing required reads.
   * Never used to block — informational only.
   */
  readClosureGaps(uid: string): Observable<ScreenReadGap[]> {
    return this.http.get<ScreenReadGap[]>(`${this.base}/uid/${uid}/read-closure-gaps`);
  }
}
