import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import { DepartmentDto, CreateDepartmentRequest } from '../models/hr-payroll.model';

/**
 * HR Department API client.
 * GET list is NOT paginated — returns raw list via auto-unwrap.
 */
@Injectable({ providedIn: 'root' })
export class HrDepartmentService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/hr/departments`;

  list(companyId: string): Observable<DepartmentDto[]> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<DepartmentDto[]>(this.base, { params });
  }

  getByUid(uid: string): Observable<DepartmentDto> {
    return this.http.get<DepartmentDto>(`${this.base}/uid/${uid}`);
  }

  create(req: CreateDepartmentRequest): Observable<DepartmentDto> {
    return this.http.post<DepartmentDto>(this.base, req);
  }

  update(uid: string, req: CreateDepartmentRequest): Observable<DepartmentDto> {
    return this.http.put<DepartmentDto>(`${this.base}/uid/${uid}`, req);
  }

  deactivate(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}`);
  }
}
