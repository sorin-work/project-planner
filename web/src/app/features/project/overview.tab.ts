import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { messageOf } from '../../core/errors';
import { ProjectHealth } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { ProjectApi } from '../../core/project.api';
import { formatDate, formatRange } from '../../shared/dates';
import { formatHours, formatMoney, formatPercent } from '../../shared/format';
import { HealthBadge } from '../../shared/health-badge';
import { ProgressBar } from '../../shared/progress-bar';
import { countsText, endText, milestoneChanges, milestoneText } from './baseline-text';
import { injectOpenTask } from './open-task';
import { scheduleIssuesText } from './schedule-issues';
import { ProjectStore } from './project.store';

interface PhaseSummary {
  id: number | null;
  name: string;
  start: string | null;
  end: string | null;
  total: number;
  done: number;
  pct: number;
}

@Component({
  selector: 'app-overview-tab',
  imports: [HealthBadge, ProgressBar, RouterLink],
  templateUrl: './overview.tab.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OverviewTab {
  protected readonly store = inject(ProjectStore);
  protected readonly org = inject(OrgContext);
  private readonly api = inject(ProjectApi);
  protected readonly openTask = injectOpenTask();

  protected readonly health = signal<ProjectHealth | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly formatHours = formatHours;
  protected readonly formatPercent = formatPercent;
  protected readonly formatDate = formatDate;
  protected readonly formatRange = formatRange;
  protected readonly endText = endText;
  protected readonly scheduleIssuesText = scheduleIssuesText;
  protected readonly countsText = countsText;
  protected readonly milestoneChange = milestoneText;
  protected readonly baselineMilestones = computed(() => milestoneChanges(this.store.comparison()));

  protected readonly phaseSummaries = computed<PhaseSummary[]>(() => {
    const tasks = this.store.tasks();
    const parents = new Set(tasks.filter((t) => t.parentId).map((t) => t.parentId));
    const leaves = tasks.filter((t) => !parents.has(t.id) && !t.milestone);
    const groups: PhaseSummary[] = this.store.phases().map((p) => ({
      id: p.id, name: p.name, start: null, end: null, total: 0, done: 0, pct: 0,
    }));
    const noPhase: PhaseSummary = { id: null, name: 'No phase', start: null, end: null, total: 0, done: 0, pct: 0 };
    for (const t of leaves) {
      const group = groups.find((g) => g.id === t.phaseId) ?? noPhase;
      const estimate = t.estimateHours ?? 0;
      group.total += estimate;
      if (t.status === 'DONE') {
        group.done += estimate;
      }
      if (t.startDate && (!group.start || t.startDate < group.start)) {
        group.start = t.startDate;
      }
      if (t.dueDate && (!group.end || t.dueDate > group.end)) {
        group.end = t.dueDate;
      }
    }
    const all = noPhase.total > 0 ? [...groups, noPhase] : groups;
    for (const g of all) {
      g.pct = g.total > 0 ? (g.done / g.total) * 100 : 0;
    }
    return all;
  });

  protected readonly milestones = computed(() =>
    this.store
      .tasks()
      .filter((t) => t.milestone)
      .sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? '')),
  );

  constructor() {
    effect(() => {
      const project = this.store.project();
      this.store.version();
      if (!project) {
        return;
      }
      this.api.health(project.id).subscribe({
        next: (h) => this.health.set(h),
        error: (err: unknown) => this.error.set(messageOf(err)),
      });
    });
  }

  money(amount: number | null | undefined): string {
    return formatMoney(amount, this.org.currency());
  }

  memberName(userId: number | undefined): string {
    return this.store.members().find((m) => m.userId === userId)?.name ?? 'Unassigned';
  }
}
