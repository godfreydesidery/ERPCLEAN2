import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CreateUserRequest,
  SetPasswordRequest,
  UpdateUserRequest,
  User,
} from '../models/user.model';

/** User API client. Typed to the unwrapped DTO — the interceptor strips the envelope. */
@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/users`;

  list(): Observable<User[]> {
    return this.http.get<User[]>(this.base);
  }

  /**
   * Returns all users across the entire organisation regardless of company.
   * Requires USER.MANAGE. Use only for pickers that must cross company boundaries
   * (e.g. role-grant assignment); leave normal admin lists on list().
   */
  listOrgWide(): Observable<User[]> {
    return this.http.get<User[]>(`${this.base}/org-wide`);
  }

  get(uid: string): Observable<User> {
    return this.http.get<User>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateUserRequest): Observable<User> {
    return this.http.post<User>(this.base, request);
  }

  update(uid: string, request: UpdateUserRequest): Observable<User> {
    return this.http.put<User>(`${this.base}/uid/${uid}`, request);
  }

  disable(uid: string): Observable<void> {
    return this.http.put<void>(`${this.base}/uid/${uid}/disable`, {});
  }

  enable(uid: string): Observable<void> {
    return this.http.put<void>(`${this.base}/uid/${uid}/enable`, {});
  }

  unlock(uid: string): Observable<void> {
    return this.http.put<void>(`${this.base}/uid/${uid}/unlock`, {});
  }

  setPassword(uid: string, request: SetPasswordRequest): Observable<void> {
    return this.http.put<void>(`${this.base}/uid/${uid}/password`, request);
  }

  /** Set or reset a waiter's 4-digit POS PIN. */
  setPin(uid: string, pin: string): Observable<void> {
    return this.http.put<void>(`${this.base}/uid/${uid}/pin`, { pin });
  }

  /** Remove a waiter's POS PIN; they sign in with their password again. */
  clearPin(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/pin`);
  }
}
