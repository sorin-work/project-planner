import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { Announcer } from '../../core/announcer';
import { TimeEntry } from '../../core/models';
import { TimeApi } from '../../core/time.api';
import { formatMinutes } from '../../shared/format';

/** The signed-in user's running timer, shared by the top bar and task panels. */
@Injectable({ providedIn: 'root' })
export class TimerService {
  private readonly api = inject(TimeApi);
  private readonly announcer = inject(Announcer);

  readonly running = signal<TimeEntry | null>(null);
  private readonly now = signal(Date.now());
  private ticker: ReturnType<typeof setInterval> | null = null;

  /** Elapsed seconds of the running timer. */
  readonly elapsed = computed(() => {
    const entry = this.running();
    if (!entry?.startedAt) {
      return 0;
    }
    return Math.max(0, Math.floor((this.now() - Date.parse(entry.startedAt)) / 1000));
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopTicking());
  }

  refresh(orgId: number): void {
    this.api.timer(orgId).subscribe({ next: (entry) => this.set(entry ?? null), error: () => this.set(null) });
  }

  start(orgId: number, projectId: number, taskId?: number | null, description?: string): Observable<TimeEntry> {
    return this.api.startTimer(orgId, { projectId, taskId, description }).pipe(
      tap((entry) => {
        this.set(entry);
        this.announcer.announce(`Timer started on ${entry.taskTitle || entry.projectName}.`);
      }),
    );
  }

  stop(orgId: number): Observable<TimeEntry> {
    return this.api.stopTimer(orgId).pipe(
      tap((entry) => {
        this.set(null);
        this.announcer.announce(`Timer stopped: ${formatMinutes(entry.minutes)} on ${entry.taskTitle || entry.projectName}.`);
      }),
    );
  }

  private set(entry: TimeEntry | null): void {
    this.running.set(entry);
    this.now.set(Date.now());
    if (entry) {
      this.ticker ??= setInterval(() => this.now.set(Date.now()), 1000);
    } else {
      this.stopTicking();
    }
  }

  private stopTicking(): void {
    if (this.ticker) {
      clearInterval(this.ticker);
      this.ticker = null;
    }
  }
}

/** 3725 -> "1:02:05". */
export function formatElapsed(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
