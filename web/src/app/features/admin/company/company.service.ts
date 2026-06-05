import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  Company,
  CreateCompanyRequest,
  UpdateCompanyRequest,
} from '../models/company.model';

/** Company API client. Typed to the unwrapped DTO — the interceptor strips the envelope. */
@Injectable({ providedIn: 'root' })
export class CompanyService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/companies`;

  list(organisationUid: string): Observable<Company[]> {
    return this.http.get<Company[]>(this.base, { params: { organisationUid } });
  }

  get(uid: string): Observable<Company> {
    return this.http.get<Company>(`${this.base}/uid/${uid}`);
  }

  create(request: CreateCompanyRequest): Observable<Company> {
    return this.http.post<Company>(this.base, request);
  }

  update(uid: string, request: UpdateCompanyRequest): Observable<Company> {
    return this.http.put<Company>(`${this.base}/uid/${uid}`, request);
  }

  archive(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }
}
