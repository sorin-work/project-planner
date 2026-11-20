import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';

/**
 * The title of the tab, the history and what a screen reader says on navigation: the
 * route's own title, then the titles of the routes above it, then the app's name, as in
 * "Timeline · Project · project-planner". A project page puts the project's name in
 * place of "Project" once it is loaded ({@link projectTitle}).
 */
@Injectable()
export class PageTitle extends TitleStrategy {
  private readonly title = inject(Title);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const parts: string[] = [];
    let route = snapshot.root;
    while (route) {
      const own = route.routeConfig?.title;
      if (typeof own === 'string' && own) {
        parts.unshift(own);
      }
      route = route.firstChild!;
    }
    this.title.setTitle([...parts, 'project-planner'].join(' · '));
  }
}

/** Puts the project's name in the title of a project page: "Timeline · Website · project-planner". */
export function projectTitle(current: string, projectName: string): string {
  return current.replace(/(^|· )Project( ·|$)/, `$1${projectName}$2`);
}
