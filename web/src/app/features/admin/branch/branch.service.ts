import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  Branch,
  CreateBranchRequest,
  UpdateBranchRequest,
} from '../models/branch.model';

/** Branch API client, including the set-default action. Typed to the unwrapped DTO. */
@Injectable({ providedIn: 'root' })
export class BranchService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/branches`;

  list(companyUid: string): Observable<Branch[]> {
    return this.http.get<Branch[]>(this.base, { params: { companyUid } });
  }

  create(request: CreateBranchRequest): Observable<Branch> {
    return this.http.post<Branch>(this.base, request);
  }

  update(uid: string, request: UpdateBranchRequest): Observable<Branch> {
    return this.http.put<Branch>(`${this.base}/uid/${uid}`, request);
  }

  setDefault(uid: string): Observable<Branch> {
    return this.http.put<Branch>(`${this.base}/uid/${uid}/default`, {});
  }

  archive(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }
}
