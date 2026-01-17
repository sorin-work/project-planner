package com.projectplanner.report;

import java.time.LocalDate;

/** What the health calculations need to know about a task. */
public record TaskFacts(long id, Long parentId, double estimateHours, boolean done,
                        LocalDate start, LocalDate due, boolean milestone) {
}
