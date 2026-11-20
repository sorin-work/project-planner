import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ActivityEntry,
  CriticalPath,
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
