import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Comment, Dependency, Priority, Task, TaskChange, TaskStatus } from './models';

export interface NewTask {
  title: string;
  description?: string | null;
  phaseId?: number | null;
  parentId?: number | null;
  status?: TaskStatus;
  priority?: Priority;
  estimateHours?: number | null;
  startDate?: string | null;
  dueDate?: string | null;
  assigneeId?: number | null;
  milestone?: boolean;
}

export interface TaskPatch {
  title?: string;
  description?: string;
  phaseId?: number;
  clearPhase?: boolean;
  status?: TaskStatus;
  priority?: Priority;
  estimateHours?: number;
  startDate?: string;
  dueDate?: string;
  clearDates?: boolean;
  assigneeId?: number;
  clearAssignee?: boolean;
  milestone?: boolean;
  position?: number;
}

@Injectable({ providedIn: 'root' })
export class TaskApi {
  private readonly http = inject(HttpClient);

  list(projectId: number): Observable<Task[]> {
    return this.http.get<Task[]>(`/api/projects/${projectId}/tasks`);
  }

  get(id: number): Observable<Task> {
    return this.http.get<Task>(`/api/tasks/${id}`);
  }

  create(projectId: number, body: NewTask): Observable<Task> {
    return this.http.post<Task>(`/api/projects/${projectId}/tasks`, body);
  }

  update(id: number, patch: TaskPatch): Observable<TaskChange> {
    return this.http.patch<TaskChange>(`/api/tasks/${id}`, patch);
  }

  shift(id: number, days: number): Observable<TaskChange> {
    return this.http.post<TaskChange>(`/api/tasks/${id}/shift`, { days });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`/api/tasks/${id}`);
  }

  dependencies(projectId: number): Observable<Dependency[]> {
    return this.http.get<Dependency[]>(`/api/projects/${projectId}/dependencies`);
  }

  addDependency(successorId: number, predecessorId: number, lagDays = 0): Observable<TaskChange> {
    return this.http.post<TaskChange>(`/api/tasks/${successorId}/dependencies`, { predecessorId, lagDays });
  }

  removeDependency(id: number): Observable<void> {
    return this.http.delete<void>(`/api/dependencies/${id}`);
  }

  comments(taskId: number): Observable<Comment[]> {
    return this.http.get<Comment[]>(`/api/tasks/${taskId}/comments`);
  }

  addComment(taskId: number, body: string): Observable<Comment> {
    return this.http.post<Comment>(`/api/tasks/${taskId}/comments`, { body });
  }

  editComment(id: number, body: string): Observable<Comment> {
    return this.http.put<Comment>(`/api/comments/${id}`, { body });
  }

  deleteComment(id: number): Observable<void> {
    return this.http.delete<void>(`/api/comments/${id}`);
  }
}
