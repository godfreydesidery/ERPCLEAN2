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
}
