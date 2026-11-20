package com.projectplanner.time;

/**
 * The time logged on a project has changed: an entry was added, changed, moved
 * away, deleted, or a timer was stopped. Fired inside the transaction of the
 * change, with the project's row locked ({@code Project.locked}).
 */
public record TimeChanged(long projectId) {
}
