import { inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

/** Returns a function that opens a task in the side panel by setting ?task=. */
export function injectOpenTask(): (taskId: number) => void {
  const router = inject(Router);
  const route = inject(ActivatedRoute);
  return (taskId: number) =>
    void router.navigate([], { relativeTo: route, queryParams: { task: taskId }, queryParamsHandling: 'merge' });
}
