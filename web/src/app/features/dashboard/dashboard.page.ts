import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { messageOf } from '../../core/errors';
import { InboxApi } from '../../core/inbox.api';
import { Dashboard } from '../../core/models';
import { OrgContext } from '../../core/org-context.service';
import { formatDate } from '../../shared/dates';
import { formatMinutes, formatPercent } from '../../shared/format';
import { HealthBadge } from '../../shared/health-badge';
import { ProgressBar } from '../../shared/progress-bar';
import { StatusPill } from '../../shared/status-pill';

@Component({
  selector: 'app-dashboard-page',
  imports: [RouterLink, HealthBadge, ProgressBar, StatusPill],
  templateUrl: './dashboard.page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardPage {
  private readonly api = inject(InboxApi);
  protected readonly org = inject(OrgContext);

  protected readonly data = signal<Dashboard | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly formatDate = formatDate;
  protected readonly formatMinutes = formatMinutes;
  protected readonly formatPercent = formatPercent;

  constructor() {
    effect(() => {
      const orgId = this.org.orgId();
      if (orgId === null) {
        return;
      }
      this.api.dashboard(orgId).subscribe({
        next: (d) => this.data.set(d),
        error: (err: unknown) => this.error.set(messageOf(err)),
      });
    });
  }
}
