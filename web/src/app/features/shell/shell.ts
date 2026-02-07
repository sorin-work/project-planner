import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { messageOf } from '../../core/errors';
import { OrgContext } from '../../core/org-context.service';
import { Avatar } from '../../shared/avatar';
import { ROLE_LABELS } from '../../shared/format';


@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Avatar],
  templateUrl: './shell.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContext);
  private readonly router = inject(Router);

  protected readonly loadError = signal<string | null>(null);
  protected readonly menuOpen = signal(false);
  protected readonly roleLabels = ROLE_LABELS;

  constructor() {
    this.auth.loadMe().subscribe({ error: (err: unknown) => this.loadError.set(messageOf(err)) });

    const navigation = this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => {
      this.menuOpen.set(false);
    });
    inject(DestroyRef).onDestroy(() => {
      navigation.unsubscribe();
    });
  }

  logout(): void {
    this.auth.logout();
  }
}
