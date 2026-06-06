import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Organisation } from '../models/organisation.model';

/** Organisation API client. Typed to the unwrapped DTO — the interceptor strips the envelope. */
@Injectable({ providedIn: 'root' })
export class OrganisationService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/organisations`;

  /** The deployment's single organisation (by name) — used to scope the admin screens. */
  current(): Observable<Organisation> {
    return this.http.get<Organisation>(`${this.base}/current`);
  }

  list(): Observable<Organisation[]> {
    return this.http.get<Organisation[]>(this.base);
  }
}
