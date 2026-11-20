import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ReportGroupBy, ReportPreset, TimeReport, UtilisationReport } from './models';

/** Either a preset, which the API turns into dates by the organisation's calendar, or two dates (both included). */
export type ReportRange = { preset: ReportPreset } | { from: string; to: string };

export interface TimeReportQuery {
  range: ReportRange;
  groupBy: ReportGroupBy;
  projectId?: number | null;
  userId?: number | null;
  clientId?: number | null;
  billable?: boolean | null;
}

export function rangeParams(range: ReportRange): HttpParams {
  return 'preset' in range ? new HttpParams().set('preset', range.preset) : new HttpParams().set('from', range.from).set('to', range.to);
}

function timeParams(query: TimeReportQuery): HttpParams {
  let params = rangeParams(query.range).set('groupBy', query.groupBy.toLowerCase());
  for (const key of ['projectId', 'userId', 'clientId'] as const) {
    const value = query[key];
    if (value) {
      params = params.set(key, value);
    }
  }
  if (query.billable !== null && query.billable !== undefined) {
    params = params.set('billable', query.billable);
  }
  return params;
}

@Injectable({ providedIn: 'root' })
export class ReportApi {
  private readonly http = inject(HttpClient);

  time(orgId: number, query: TimeReportQuery): Observable<TimeReport> {
    return this.http.get<TimeReport>(`/api/orgs/${orgId}/reports/time`, { params: timeParams(query) });
  }

  /** The same rows as CSV, as a blob so the auth header can be sent. */
  timeCsv(orgId: number, query: TimeReportQuery): Observable<Blob> {
    return this.http.get(`/api/orgs/${orgId}/reports/time.csv`, { params: timeParams(query), responseType: 'blob' });
  }

  utilisation(orgId: number, range: ReportRange): Observable<UtilisationReport> {
    return this.http.get<UtilisationReport>(`/api/orgs/${orgId}/reports/utilisation`, { params: rangeParams(range) });
  }

  utilisationCsv(orgId: number, range: ReportRange): Observable<Blob> {
    return this.http.get(`/api/orgs/${orgId}/reports/utilisation.csv`, { params: rangeParams(range), responseType: 'blob' });
  }
}
