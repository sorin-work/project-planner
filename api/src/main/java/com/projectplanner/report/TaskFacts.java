package com.projectplanner.report;

import java.time.LocalDate;

/**
 * What the health calculations need to know about a task.
 *
 * @param started work has begun (in progress or in review) but it isn't done
 */
public record TaskFacts(long id, Long parentId, double estimateHours, boolean done, boolean started,
                        LocalDate start, LocalDate due, boolean milestone) {

    public TaskFacts(long id, Long parentId, double estimateHours, boolean done, LocalDate start, LocalDate due, boolean milestone) {
        this(id, parentId, estimateHours, done, false, start, due, milestone);
    }
}
