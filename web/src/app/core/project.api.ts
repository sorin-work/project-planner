import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ActivityEntry,
  Baseline,
  BaselineComparison,
  CriticalPath,
  DuplicateDefaults,
  Phase,
  Project,
  ProjectHealth,
  ProjectListItem,
  ProjectStatus,
  ScheduleIssue,
} from './models';

export interface ProjectInput {
  name: string;
  code: string;
  description?: string | null;
  clientId?: number | null;
  status?: ProjectStatus;
  startDate?: string | null;
  endDate?: string | null;
  budgetHours?: number | null;
  budgetAmount?: number | null;
  hourlyRate?: number | null;
  color?: string;
}

/** A copy of a project's plan. The client and the assignees are only copied when asked for. */
export interface DuplicateInput {
  name: string;
  code: string;
  clientId: number | null;
  startDate: string;
  keepAssignees: boolean;
  /** Copy the files attached to the tasks as well. */
  copyAttachments: boolean;
}

export interface PhaseInput {
  name: string;
  position?: number;
  startDate?: string | null;
  endDate?: string | null;
}

@Injectable({ providedIn: 'root' })
export class ProjectApi {
  private readonly http = inject(HttpClient);

  list(orgId: number, filter: { status?: ProjectStatus | ''; clientId?: number | null } = {}): Observable<ProjectListItem[]> {
    let params = new HttpParams();
    if (filter.status) {
      params = params.set('status', filter.status);
    }
    if (filter.clientId) {
      params = params.set('clientId', filter.clientId);
    }
    return this.http.get<ProjectListItem[]>(`/api/orgs/${orgId}/projects`, { params });
  }

  create(orgId: number, body: ProjectInput): Observable<Project> {
    return this.http.post<Project>(`/api/orgs/${orgId}/projects`, body);
  }

  get(id: number): Observable<Project> {
    return this.http.get<Project>(`/api/projects/${id}`);
  }

  update(id: number, body: ProjectInput): Observable<Project> {
    return this.http.put<Project>(`/api/projects/${id}`, body);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/projects/${id}`);
  }

  duplicateDefaults(id: number): Observable<DuplicateDefaults> {
    return this.http.get<DuplicateDefaults>(`/api/projects/${id}/duplicate`);
  }

  duplicate(id: number, body: DuplicateInput): Observable<Project> {
    return this.http.post<Project>(`/api/projects/${id}/duplicate`, body);
  }

  health(id: number): Observable<ProjectHealth> {
    return this.http.get<ProjectHealth>(`/api/projects/${id}/health`);
  }

  criticalPath(id: number): Observable<CriticalPath> {
    return this.http.get<CriticalPath>(`/api/projects/${id}/critical-path`);
  }

  scheduleIssues(id: number): Observable<ScheduleIssue[]> {
    return this.http.get<ScheduleIssue[]>(`/api/projects/${id}/schedule-issues`);
  }

  activity(id: number, limit = 50): Observable<ActivityEntry[]> {
    return this.http.get<ActivityEntry[]>(`/api/projects/${id}/activity`, { params: { limit } });
  }

  baselines(projectId: number): Observable<Baseline[]> {
    return this.http.get<Baseline[]>(`/api/projects/${projectId}/baselines`);
  }

  takeBaseline(projectId: number, name: string): Observable<Baseline> {
    return this.http.post<Baseline>(`/api/projects/${projectId}/baselines`, { name });
  }

  updateBaseline(id: number, body: { name?: string; reference?: boolean }): Observable<Baseline> {
    return this.http.patch<Baseline>(`/api/baselines/${id}`, body);
  }

  deleteBaseline(id: number): Observable<void> {
    return this.http.delete<void>(`/api/baselines/${id}`);
  }

  baselineComparison(id: number): Observable<BaselineComparison> {
    return this.http.get<BaselineComparison>(`/api/baselines/${id}/comparison`);
  }

  phases(id: number): Observable<Phase[]> {
    return this.http.get<Phase[]>(`/api/projects/${id}/phases`);
  }

  createPhase(projectId: number, body: PhaseInput): Observable<Phase> {
    return this.http.post<Phase>(`/api/projects/${projectId}/phases`, body);
  }

  updatePhase(id: number, body: PhaseInput): Observable<Phase> {
    return this.http.put<Phase>(`/api/phases/${id}`, body);
  }

  deletePhase(id: number): Observable<void> {
    return this.http.delete<void>(`/api/phases/${id}`);
  }
}
