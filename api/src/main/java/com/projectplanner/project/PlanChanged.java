package com.projectplanner.project;

/**
 * Something that the health of a project's plan depends on has changed: a task was added,
 * changed, moved or deleted, a link moved tasks, or the project's own dates or status changed.
 * Fired inside the transaction of the change, after it.
 */
public record PlanChanged(long projectId) {
}
