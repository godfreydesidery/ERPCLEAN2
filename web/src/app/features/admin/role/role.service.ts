import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CreateRoleRequest,
  Permission,
  Role,
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
}
