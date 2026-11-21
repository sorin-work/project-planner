import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  ApproveResult,
  MissingTimesheets,
  RemindResult,
  SheetToApprove,
  TimeEntry,
  TimesheetStatus,
  TimesheetSummary,
  Week,
} from './models';

export interface TimeEntryInput {
  projectId: number;
  taskId?: number | null;
  date: string;
  minutes: number;
  description?: string | null;
  billable?: boolean;
  /** Only for a create: made up here, so that the API makes one entry when the same create is sent again. */
  clientKey?: string;
}

export interface TimeQuery {
  from: string;
  to: string;
  userId?: number;
  projectId?: number;
  taskId?: number;
}

@Injectable({ providedIn: 'root' })
export class TimeApi {
  private readonly http = inject(HttpClient);

  entries(orgId: number, query: TimeQuery): Observable<TimeEntry[]> {
    let params = new HttpParams().set('from', query.from).set('to', query.to);
    for (const key of ['userId', 'projectId', 'taskId'] as const) {
      const value = query[key];
      if (value) {
        params = params.set(key, value);
      }
    }
    return this.http.get<TimeEntry[]>(`/api/orgs/${orgId}/time-entries`, { params });
  }

  create(orgId: number, body: TimeEntryInput): Observable<TimeEntry> {
    return this.http.post<TimeEntry>(`/api/orgs/${orgId}/time-entries`, body);
  }

  update(orgId: number, id: number, body: TimeEntryInput): Observable<TimeEntry> {
    return this.http.put<TimeEntry>(`/api/orgs/${orgId}/time-entries/${id}`, body);
  }

  delete(orgId: number, id: number): Observable<void> {
    return this.http.delete<void>(`/api/orgs/${orgId}/time-entries/${id}`);
  }

  timer(orgId: number): Observable<TimeEntry | null> {
    return this.http.get<TimeEntry | null>(`/api/orgs/${orgId}/timer`);
  }

  startTimer(orgId: number, body: { projectId: number; taskId?: number | null; description?: string }): Observable<TimeEntry> {
    return this.http.post<TimeEntry>(`/api/orgs/${orgId}/timer/start`, body);
  }

  stopTimer(orgId: number): Observable<TimeEntry> {
    return this.http.post<TimeEntry>(`/api/orgs/${orgId}/timer/stop`, {});
  }

  /** The week that holds {@code date}; without one, the current week by the organisation's date. */
  week(orgId: number, date?: string | null, userId?: number): Observable<Week> {
    let params = new HttpParams();
    if (date) {
      params = params.set('date', date);
    }
    if (userId) {
      params = params.set('userId', userId);
    }
    return this.http.get<Week>(`/api/orgs/${orgId}/timesheets/week`, { params });
  }

  submit(orgId: number, weekStart: string): Observable<Week> {
    return this.http.post<Week>(`/api/orgs/${orgId}/timesheets/submit`, { weekStart });
  }

  withdraw(orgId: number, weekStart: string): Observable<Week> {
    return this.http.post<Week>(`/api/orgs/${orgId}/timesheets/withdraw`, { weekStart });
  }

  timesheets(orgId: number, status: TimesheetStatus = 'SUBMITTED'): Observable<TimesheetSummary[]> {
    return this.http.get<TimesheetSummary[]>(`/api/orgs/${orgId}/timesheets`, { params: { status } });
  }

  /** Who has not submitted a week that is over; the last such week when none is given. */
  missing(orgId: number, weekStart?: string | null): Observable<MissingTimesheets> {
    const params = weekStart ? new HttpParams().set('weekStart', weekStart) : undefined;
    return this.http.get<MissingTimesheets>(`/api/orgs/${orgId}/timesheets/missing`, { params });
  }

  remind(orgId: number, weekStart: string, userIds: number[]): Observable<RemindResult> {
    return this.http.post<RemindResult>(`/api/orgs/${orgId}/timesheets/remind`, { weekStart, userIds });
  }

  timesheet(orgId: number, id: number): Observable<Week> {
    return this.http.get<Week>(`/api/orgs/${orgId}/timesheets/${id}`);
  }

  approve(orgId: number, id: number): Observable<Week> {
    return this.http.post<Week>(`/api/orgs/${orgId}/timesheets/${id}/approve`, {});
  }

  /** Approves the weeks that are still as they were in the queue; the answer says which were left out. */
  approveAll(orgId: number, sheets: SheetToApprove[]): Observable<ApproveResult> {
    return this.http.post<ApproveResult>(`/api/orgs/${orgId}/timesheets/approve`, { sheets });
  }

  reject(orgId: number, id: number, note: string): Observable<Week> {
    return this.http.post<Week>(`/api/orgs/${orgId}/timesheets/${id}/reject`, { note });
  }

  /** Downloads the CSV export as a blob so the auth header can be sent. */
  exportCsv(
    orgId: number,
    query: { from: string; to: string; userId?: number | null; projectId?: number | null; billable?: boolean | null },
  ): Observable<Blob> {
    let params = new HttpParams().set('from', query.from).set('to', query.to);
    if (query.userId) {
      params = params.set('userId', query.userId);
    }
    if (query.projectId) {
      params = params.set('projectId', query.projectId);
    }
    if (query.billable !== null && query.billable !== undefined) {
      params = params.set('billable', query.billable);
    }
    return this.http.get(`/api/orgs/${orgId}/exports/timesheets.csv`, { params, responseType: 'blob' });
  }
}
