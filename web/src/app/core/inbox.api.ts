import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ActivityEntry, Dashboard, Notification, Workload } from './models';

@Injectable({ providedIn: 'root' })
export class InboxApi {
  private readonly http = inject(HttpClient);

  notifications(orgId: number, unreadOnly = false): Observable<Notification[]> {
    return this.http.get<Notification[]>('/api/notifications', { params: { orgId, unread: unreadOnly } });
  }

  unreadCount(orgId: number): Observable<{ count: number }> {
    return this.http.get<{ count: number }>('/api/notifications/unread-count', { params: { orgId } });
  }

  markRead(id: number): Observable<void> {
    return this.http.post<void>(`/api/notifications/${id}/read`, {});
  }

  activity(orgId: number, limit = 50): Observable<ActivityEntry[]> {
    return this.http.get<ActivityEntry[]>(`/api/orgs/${orgId}/activity`, { params: { limit } });
  }

  dashboard(orgId: number): Observable<Dashboard> {
    return this.http.get<Dashboard>(`/api/orgs/${orgId}/dashboard`);
  }

  workload(orgId: number, from: string, weeks: number, projectId?: number | null): Observable<Workload> {
    const params: Record<string, string | number> = { from, weeks };
    if (projectId) {
      params['projectId'] = projectId;
    }
    return this.http.get<Workload>(`/api/orgs/${orgId}/workload`, { params });
  }
}
