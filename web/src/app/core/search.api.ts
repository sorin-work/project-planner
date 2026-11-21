import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SearchHit, SearchHitType, SearchResponse, TaskStatus } from './models';

/** The tasks of one person, or of everybody with the given statuses. */
export interface TaskQuery {
  assigneeId?: number;
  statuses?: TaskStatus[];
  limit?: number;
}

@Injectable({ providedIn: 'root' })
export class SearchApi {
  private readonly http = inject(HttpClient);

  search(orgId: number, text: string, limit = 6): Observable<SearchResponse> {
    return this.http.get<SearchResponse>(`/api/orgs/${orgId}/search`, { params: { q: text, limit } });
  }

  /** Only tasks, the earliest due first and those without a date last, up to 200. */
  tasks(orgId: number, query: TaskQuery): Observable<SearchResponse> {
    const params: Record<string, string | number | string[]> = { limit: query.limit ?? 200 };
    if (query.assigneeId !== undefined) {
      params['assigneeId'] = query.assigneeId;
    }
    if (query.statuses?.length) {
      params['status'] = query.statuses;
    }
    return this.http.get<SearchResponse>(`/api/orgs/${orgId}/search`, { params });
  }

  /** Picks made before, as they are now. Those that are gone or out of reach are left out. */
  recent(orgId: number, picks: { type: SearchHitType; id: number }[]): Observable<SearchHit[]> {
    return this.http.post<SearchHit[]>(`/api/orgs/${orgId}/search/recent`, { picks });
  }
}
