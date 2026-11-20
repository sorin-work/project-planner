package com.projectplanner.project;

/**
 * A project's hours budget, money budget or own hourly rate has changed. Fired
 * inside the transaction of the change, with the project's row locked.
 */
public record BudgetChanged(long projectId) {
}
