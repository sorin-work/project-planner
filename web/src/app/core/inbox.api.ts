import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ActivityEntry, Dashboard, Delivery, Notification, NotificationPreferences, NotificationType, Workload } from './models';

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

  markAllRead(orgId: number): Observable<void> {
    return this.http.post<void>('/api/notifications/read-all', {}, { params: { orgId } });
  }

  preferences(): Observable<NotificationPreferences> {
    return this.http.get<NotificationPreferences>('/api/me/notification-preferences');
  }

  setPreferences(preferences: { type: NotificationType; delivery: Delivery }[]): Observable<NotificationPreferences> {
    return this.http.put<NotificationPreferences>('/api/me/notification-preferences', { preferences });
  }

  /** The stop link of a notification mail, which works without signing in. */
  unsubscribe(token: string): Observable<{ type: NotificationType }> {
    return this.http.post<{ type: NotificationType }>('/api/auth/unsubscribe', { token });
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
