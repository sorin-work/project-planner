import { Routes } from '@angular/router';

export const projectRoutes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'overview' },
  { path: 'overview', loadComponent: () => import('./overview.tab').then((m) => m.OverviewTab) },
  { path: 'list', loadComponent: () => import('./list.tab').then((m) => m.ListTab) },
  { path: 'settings', loadComponent: () => import('./settings.tab').then((m) => m.SettingsTab) },
];
