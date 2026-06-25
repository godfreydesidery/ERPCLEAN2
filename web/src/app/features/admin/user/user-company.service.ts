import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { AssignCompanyRequest, UserCompany } from '../models/user-company.model';

/** User-company membership API client. Typed to the unwrapped DTO — the interceptor strips the envelope. */
@Injectable({ providedIn: 'root' })
export class UserCompanyService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/user-companies`;

  listForUser(userUid: string): Observable<UserCompany[]> {
    return this.http.get<UserCompany[]>(this.base, { params: { userUid } });
  }

  assign(request: AssignCompanyRequest): Observable<UserCompany> {
    return this.http.post<UserCompany>(this.base, request);
  }

  remove(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }
}
