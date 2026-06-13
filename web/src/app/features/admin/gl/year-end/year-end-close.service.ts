import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../../environments/environment';
import { FiscalYearDto } from '../models/gl.model';

/**
 * Year-End Close service. Wraps POST /api/v1/gl/periods/fiscal-years/uid/{uid}/close
 * and POST /api/v1/gl/periods/fiscal-years/uid/{uid}/reopen.
 * Returns unwrapped FiscalYearDto (interceptor strips envelope).
 * Permission: GL.YEAR.CLOSE.
 */
@Injectable({ providedIn: 'root' })
export class YearEndCloseService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/gl/periods/fiscal-years`;

  closeFiscalYear(uid: string): Observable<FiscalYearDto> {
    return this.http.post<FiscalYearDto>(`${this.base}/uid/${uid}/close`, {});
  }

  reopenFiscalYear(uid: string): Observable<FiscalYearDto> {
    return this.http.post<FiscalYearDto>(`${this.base}/uid/${uid}/reopen`, {});
  }
}
