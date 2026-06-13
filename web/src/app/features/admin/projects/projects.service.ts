import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, PageMeta } from '../../../core/api/api-response.model';
import { SKIP_UNWRAP } from '../../../core/api/http-context.tokens';
import { environment } from '../../../../environments/environment';
import {
  CreateProjectRequest,
  CreateProjectTaskRequest,
  CreateTimesheetRequest,
  IssueToProjectRequest,
  IssueToProjectResultDto,
  ProjectDto,
  ProjectPnlDto,
  ProjectStatus,
  ProjectTaskDto,
  ProjectTimesheetDto,
  ProjectWipRowDto,
  UpdateProjectRequest,
} from './models/projects.model';

export interface ProjectPage {
  rows: ProjectDto[];
  meta: PageMeta;
}

export interface TimesheetPage {
  rows: ProjectTimesheetDto[];
  meta: PageMeta;
}

/**
 * Projects module HTTP client.
 * list() and listTimesheets() use SKIP_UNWRAP → {rows, meta} (paginated).
 * listTasks() and WIP report are NOT paginated — plain Observable<T[]>.
 * All other calls go through the auto-unwrap interceptor.
 */
@Injectable({ providedIn: 'root' })
export class ProjectsService {
  private readonly http = inject(HttpClient);
  private readonly projectBase = `${environment.apiBaseUrl}/projects`;
  private readonly taskBase = `${environment.apiBaseUrl}/project-tasks`;
  private readonly timesheetBase = `${environment.apiBaseUrl}/project-timesheets`;
  private readonly issueBase = `${environment.apiBaseUrl}/project-issues`;
  private readonly costingBase = `${environment.apiBaseUrl}/project-costing`;

  // ── Projects ───────────────────────────────────────────────────────────────

  list(companyId: string, page = 0, size = 20): Observable<ProjectPage> {
    const params = new HttpParams()
      .set('companyId', companyId)
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ProjectDto[]>>(this.projectBase, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  getByUid(uid: string): Observable<ProjectDto> {
    return this.http.get<ProjectDto>(`${this.projectBase}/uid/${uid}`);
  }

  create(request: CreateProjectRequest): Observable<ProjectDto> {
    return this.http.post<ProjectDto>(this.projectBase, request);
  }

  update(uid: string, request: UpdateProjectRequest): Observable<ProjectDto> {
    return this.http.put<ProjectDto>(`${this.projectBase}/uid/${uid}`, request);
  }

  changeStatus(uid: string, targetStatus: ProjectStatus): Observable<ProjectDto> {
    const params = new HttpParams().set('targetStatus', targetStatus);
    return this.http.patch<ProjectDto>(`${this.projectBase}/uid/${uid}/status`, null, { params });
  }

  archive(uid: string): Observable<ProjectDto> {
    return this.http.patch<ProjectDto>(`${this.projectBase}/uid/${uid}/archive`, null);
  }

  // ── Tasks ──────────────────────────────────────────────────────────────────

  listTasks(projectUid: string): Observable<ProjectTaskDto[]> {
    return this.http.get<ProjectTaskDto[]>(`${this.taskBase}/project/uid/${projectUid}`);
  }

  getTaskByUid(uid: string): Observable<ProjectTaskDto> {
    return this.http.get<ProjectTaskDto>(`${this.taskBase}/uid/${uid}`);
  }

  createTask(projectUid: string, request: CreateProjectTaskRequest): Observable<ProjectTaskDto> {
    return this.http.post<ProjectTaskDto>(`${this.taskBase}/project/uid/${projectUid}`, request);
  }

  updateTask(uid: string, request: CreateProjectTaskRequest): Observable<ProjectTaskDto> {
    return this.http.put<ProjectTaskDto>(`${this.taskBase}/uid/${uid}`, request);
  }

  deactivateTask(uid: string): Observable<ProjectTaskDto> {
    return this.http.patch<ProjectTaskDto>(`${this.taskBase}/uid/${uid}/deactivate`, null);
  }

  // ── Timesheets ─────────────────────────────────────────────────────────────

  listTimesheets(projectUid: string, page = 0, size = 20): Observable<TimesheetPage> {
    const params = new HttpParams()
      .set('page', String(page))
      .set('size', String(size));
    const context = new HttpContext().set(SKIP_UNWRAP, true);
    return this.http
      .get<ApiResponse<ProjectTimesheetDto[]>>(`${this.timesheetBase}/project/uid/${projectUid}`, { params, context })
      .pipe(
        map((env) => ({
          rows: env.data ?? [],
          meta: env.meta ?? { page, size, totalElements: env.data?.length ?? 0, totalPages: 1, hasNext: false },
        })),
      );
  }

  recordTimesheet(projectUid: string, request: CreateTimesheetRequest): Observable<ProjectTimesheetDto> {
    return this.http.post<ProjectTimesheetDto>(`${this.timesheetBase}/project/uid/${projectUid}`, request);
  }

  // ── Issue to Project ───────────────────────────────────────────────────────

  issueToProject(request: IssueToProjectRequest): Observable<IssueToProjectResultDto> {
    return this.http.post<IssueToProjectResultDto>(this.issueBase, request);
  }

  // ── Costing / Reports ──────────────────────────────────────────────────────

  getProjectPnl(projectUid: string): Observable<ProjectPnlDto> {
    return this.http.get<ProjectPnlDto>(`${this.costingBase}/projects/uid/${projectUid}/pnl`);
  }

  getWipReport(companyId: string): Observable<ProjectWipRowDto[]> {
    // NOT paginated — returns ApiResponse<List<...>> but without PageMeta.
    // Interceptor auto-unwraps to the array.
    const params = new HttpParams().set('companyId', companyId);
    return this.http.get<ProjectWipRowDto[]>(`${this.costingBase}/wip`, { params });
  }
}
