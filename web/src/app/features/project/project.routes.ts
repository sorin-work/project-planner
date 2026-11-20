import { Routes } from '@angular/router';

export const projectRoutes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'overview' },
  { path: 'overview', title: 'Overview', loadComponent: () => import('./overview.tab').then((m) => m.OverviewTab) },
  { path: 'list', title: 'List', loadComponent: () => import('./list.tab').then((m) => m.ListTab) },
  { path: 'board', title: 'Board', loadComponent: () => import('./board.tab').then((m) => m.BoardTab) },
  { path: 'timeline', title: 'Timeline', loadComponent: () => import('./timeline.tab').then((m) => m.TimelineTab) },
  { path: 'activity', title: 'Activity', loadComponent: () => import('./activity.tab').then((m) => m.ActivityTab) },
  { path: 'settings', title: 'Project settings', loadComponent: () => import('./settings.tab').then((m) => m.SettingsTab) },
];
