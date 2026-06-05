import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** The health payload as the backend returns it (already unwrapped by the interceptor). */
export interface Health {
  status: string;
  service: string;
  time: string;
}

/**
 * Slice 0 service. Typed to the unwrapped `Health` (never `ApiResponse<Health>`) — proving the
 * envelope interceptor works end-to-end. The pattern (service typed to raw T) is the template every
 * feature service follows.
 */
@Injectable({ providedIn: 'root' })
export class HealthService {
  private readonly http = inject(HttpClient);

  getHealth(): Observable<Health> {
    return this.http.get<Health>(`${environment.apiBaseUrl}/health`);
  }
}
