import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SearchHit, SearchHitType, SearchResponse } from './models';

@Injectable({ providedIn: 'root' })
export class SearchApi {
  private readonly http = inject(HttpClient);

  search(orgId: number, text: string, limit = 6): Observable<SearchResponse> {
    return this.http.get<SearchResponse>(`/api/orgs/${orgId}/search`, { params: { q: text, limit } });
  }

  /** Picks made before, as they are now. Those that are gone or out of reach are left out. */
  recent(orgId: number, picks: { type: SearchHitType; id: number }[]): Observable<SearchHit[]> {
    return this.http.post<SearchHit[]>(`/api/orgs/${orgId}/search/recent`, { picks });
  }
}
