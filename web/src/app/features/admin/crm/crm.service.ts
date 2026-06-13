import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  AddOpportunityLineRequest,
  AdvanceStageRequest,
  ConvertOpportunityRequest,
  ConvertResultDto,
  CreateLeadRequest,
  CreateOpportunityRequest,
  CreatePipelineStageRequest,
  CrmKpiDto,
  DisqualifyLeadRequest,
  ForecastDto,
  LeadDto,
  LoseOpportunityRequest,
  OpportunityDto,
  OpportunityLineDto,
  PipelineStageDto,
  PipelineSummaryDto,
  QualifyLeadRequest,
  UpdateLeadRequest,
  UpdateOpportunityRequest,
  UpdatePipelineStageRequest,
  WinOpportunityRequest,
} from './models/crm.model';

export interface LeadPage {
  rows: LeadDto[];
  meta: PageMeta;
}

export interface OpportunityPage {
  rows: OpportunityDto[];
  meta: PageMeta;
}

/**
 * CRM API client.
 * listLeads() / listOpportunities() use SKIP_UNWRAP (paginated, ApiResponse envelope).
 * All other methods use the auto-unwrap path (interceptor strips envelope).
 * Pipeline-stages list, lines list, and all report endpoints return bare objects/arrays.
 */
@Injectable({ providedIn: 'root' })
export class CrmService {
  private readonly http = inject(HttpClient);
  private readonly leadBase = `${environment.apiBaseUrl}/crm/leads`;
  private readonly oppBase = `${environment.apiBaseUrl}/crm/opportunities`;
  private readonly stageBase = `${environment.apiBaseUrl}/crm/pipeline-stages`;
  private readonly pipelineBase = `${environment.apiBaseUrl}/crm/pipeline`;

  // ── Leads ─────────────────────────────────────────────────────────────────────

  listLeads(companyId: string, page = 0, size = 20): Observable<LeadPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<LeadDto[]>>(this.leadBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getLeadByUid(uid: string): Observable<LeadDto> {
    return this.http.get<LeadDto>(`${this.leadBase}/uid/${uid}`);
  }

  createLead(request: CreateLeadRequest): Observable<LeadDto> {
    return this.http.post<LeadDto>(this.leadBase, request);
  }

  updateLead(uid: string, request: UpdateLeadRequest): Observable<LeadDto> {
    return this.http.put<LeadDto>(`${this.leadBase}/uid/${uid}`, request);
  }

  contactLead(uid: string): Observable<LeadDto> {
    return this.http.put<LeadDto>(`${this.leadBase}/uid/${uid}/contact`, {});
  }

  qualifyLead(uid: string, request: QualifyLeadRequest): Observable<LeadDto> {
    return this.http.post<LeadDto>(`${this.leadBase}/uid/${uid}/qualify`, request);
  }

  disqualifyLead(uid: string, request: DisqualifyLeadRequest): Observable<LeadDto> {
    return this.http.post<LeadDto>(`${this.leadBase}/uid/${uid}/disqualify`, request);
  }

  // ── Opportunities ─────────────────────────────────────────────────────────────

  listOpportunities(companyId: string, page = 0, size = 20): Observable<OpportunityPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<OpportunityDto[]>>(this.oppBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getOpportunityByUid(uid: string): Observable<OpportunityDto> {
    return this.http.get<OpportunityDto>(`${this.oppBase}/uid/${uid}`);
  }

  createOpportunity(request: CreateOpportunityRequest): Observable<OpportunityDto> {
    return this.http.post<OpportunityDto>(this.oppBase, request);
  }

  updateOpportunity(uid: string, request: UpdateOpportunityRequest): Observable<OpportunityDto> {
    return this.http.put<OpportunityDto>(`${this.oppBase}/uid/${uid}`, request);
  }

  listOpportunityLines(uid: string): Observable<OpportunityLineDto[]> {
    return this.http.get<OpportunityLineDto[]>(`${this.oppBase}/uid/${uid}/lines`);
  }

  addOpportunityLine(uid: string, request: AddOpportunityLineRequest): Observable<OpportunityLineDto> {
    return this.http.post<OpportunityLineDto>(`${this.oppBase}/uid/${uid}/lines`, request);
  }

  removeOpportunityLine(uid: string, lineUid: string): Observable<void> {
    return this.http.delete<void>(`${this.oppBase}/uid/${uid}/lines/${lineUid}`);
  }

  advanceStage(uid: string, request: AdvanceStageRequest): Observable<OpportunityDto> {
    return this.http.post<OpportunityDto>(`${this.oppBase}/uid/${uid}/advance-stage`, request);
  }

  winOpportunity(uid: string, request: WinOpportunityRequest): Observable<OpportunityDto> {
    return this.http.post<OpportunityDto>(`${this.oppBase}/uid/${uid}/win`, request);
  }

  loseOpportunity(uid: string, request: LoseOpportunityRequest): Observable<OpportunityDto> {
    return this.http.post<OpportunityDto>(`${this.oppBase}/uid/${uid}/lose`, request);
  }

  convertOpportunity(uid: string, request: ConvertOpportunityRequest): Observable<ConvertResultDto> {
    return this.http.post<ConvertResultDto>(`${this.oppBase}/uid/${uid}/convert`, request);
  }

  // ── Pipeline Stages ───────────────────────────────────────────────────────────

  listPipelineStages(companyId: string): Observable<PipelineStageDto[]> {
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<PipelineStageDto[]>(this.stageBase, { params });
  }

  getPipelineStageByUid(uid: string): Observable<PipelineStageDto> {
    return this.http.get<PipelineStageDto>(`${this.stageBase}/uid/${uid}`);
  }

  createPipelineStage(request: CreatePipelineStageRequest): Observable<PipelineStageDto> {
    return this.http.post<PipelineStageDto>(this.stageBase, request);
  }

  updatePipelineStage(uid: string, request: UpdatePipelineStageRequest): Observable<PipelineStageDto> {
    return this.http.put<PipelineStageDto>(`${this.stageBase}/uid/${uid}`, request);
  }

  deactivatePipelineStage(uid: string): Observable<void> {
    return this.http.delete<void>(`${this.stageBase}/uid/${uid}`);
  }

  // ── Pipeline Reports ──────────────────────────────────────────────────────────

  getPipelineSummary(companyId: string, branchId: string): Observable<PipelineSummaryDto> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('branchId', branchId);
    return this.http.get<PipelineSummaryDto>(this.pipelineBase, { params });
  }

  getForecast(companyId: string, branchId: string, from: string, to: string): Observable<ForecastDto> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('branchId', branchId)
      .set('from', from)
      .set('to', to);
    return this.http.get<ForecastDto>(`${this.pipelineBase}/forecast`, { params });
  }

  getKpis(companyId: string, branchId: string, from: string, to: string): Observable<CrmKpiDto> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('branchId', branchId)
      .set('from', from)
      .set('to', to);
    return this.http.get<CrmKpiDto>(`${this.pipelineBase}/kpis`, { params });
  }
}
