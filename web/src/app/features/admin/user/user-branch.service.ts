import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { AssignBranchRequest, UserBranch } from '../models/user-branch.model';

/** User-branch assignment API client. Typed to the unwrapped DTO — the interceptor strips the envelope. */
@Injectable({ providedIn: 'root' })
export class UserBranchService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/user-branches`;

  listForUser(userUid: string): Observable<UserBranch[]> {
    return this.http.get<UserBranch[]>(this.base, { params: { userUid } });
  }

  assign(request: AssignBranchRequest): Observable<UserBranch> {
    return this.http.post<UserBranch>(this.base, request);
  }

  setDefault(uid: string): Observable<UserBranch> {
    return this.http.put<UserBranch>(`${this.base}/uid/${uid}/default`, {});
  }

  remove(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }
}
