import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Holiday, TimeOff } from './models';

export interface TimeOffInput {
  userId?: number;
  firstDay: string;
  lastDay: string;
  note?: string | null;
}

/** Holidays and time off: `/api/orgs/{id}/holidays` and `/api/orgs/{id}/time-off`. */
@Injectable({ providedIn: 'root' })
export class CalendarApi {
  private readonly http = inject(HttpClient);

  holidays(orgId: number, range: { from?: string; to?: string } = {}): Observable<Holiday[]> {
    return this.http.get<Holiday[]>(`/api/orgs/${orgId}/holidays`, { params: params(range) });
  }

  addHoliday(orgId: number, body: { date: string; name: string }): Observable<Holiday> {
    return this.http.post<Holiday>(`/api/orgs/${orgId}/holidays`, body);
  }

  deleteHoliday(orgId: number, id: number): Observable<void> {
    return this.http.delete<void>(`/api/orgs/${orgId}/holidays/${id}`);
  }

  timeOff(orgId: number, range: { from?: string; to?: string; userId?: number } = {}): Observable<TimeOff[]> {
    return this.http.get<TimeOff[]>(`/api/orgs/${orgId}/time-off`, { params: params(range) });
  }

  addTimeOff(orgId: number, body: TimeOffInput): Observable<TimeOff> {
    return this.http.post<TimeOff>(`/api/orgs/${orgId}/time-off`, body);
  }

  deleteTimeOff(orgId: number, id: number): Observable<void> {
    return this.http.delete<void>(`/api/orgs/${orgId}/time-off/${id}`);
  }
}

function params(values: Record<string, string | number | undefined>): HttpParams {
  let result = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== '') {
      result = result.set(key, value);
    }
  }
  return result;
}
