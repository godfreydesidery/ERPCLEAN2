import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import { ContractDto, CreateContractRequest } from '../models/hr-payroll.model';

/**
 * HR Contract API client.
 * list() is NOT paginated — returns raw array via auto-unwrap (no ApiResponse envelope).
 */
@Injectable({ providedIn: 'root' })
export class HrContractService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/hr/contracts`;

  /** GET /api/v1/hr/contracts?companyId=X&employeeId=Y */
  listByEmployee(companyId: string, employeeId: string): Observable<ContractDto[]> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('employeeId', employeeId);
    return this.http.get<ContractDto[]>(this.base, { params });
  }

  getByUid(uid: string): Observable<ContractDto> {
    return this.http.get<ContractDto>(`${this.base}/uid/${uid}`);
  }

  /** POST /api/v1/hr/contracts/employee/{employeeUid} */
  create(employeeUid: string, req: CreateContractRequest): Observable<ContractDto> {
    return this.http.post<ContractDto>(`${this.base}/employee/${employeeUid}`, req);
  }

  /** DELETE /api/v1/hr/contracts/uid/{uid}/terminate */
  terminate(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/uid/${uid}/terminate`);
  }
}
