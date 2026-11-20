import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SearchResponse } from './models';

@Injectable({ providedIn: 'root' })
export class SearchApi {
  private readonly http = inject(HttpClient);

  search(orgId: number, text: string, limit = 6): Observable<SearchResponse> {
    return this.http.get<SearchResponse>(`/api/orgs/${orgId}/search`, { params: { q: text, limit } });
  }
}
