package com.projectplanner.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * How much of what people could work was billable time. The share is billable
 * minutes over capacity minutes, as a percentage with one decimal. Capacity
 * comes from the workload feature (weekly hours over the working days counted),
 * so there is one definition of it. The total is worked out from the summed
 * minutes, not from the rows' percentages.
 */
public final class Utilisation {

    private Utilisation() {
    }

    public record Person(long userId, String name, long minutes, long billableMinutes, long capacityMinutes) {
    }

    /** {@code utilisationPct} is null when there is no capacity to compare with. */
    public record Row(Long userId, String name, long minutes, long billableMinutes, long capacityMinutes, Double utilisationPct) {
    }

    public record Result(List<Row> rows, Row total) {
    }

    public static Result of(Collection<Person> people) {
        List<Row> rows = new ArrayList<>();
        long minutes = 0;
        long billable = 0;
        long capacity = 0;
        for (Person p : people) {
            rows.add(row(p.userId(), p.name(), p.minutes(), p.billableMinutes(), p.capacityMinutes()));
            minutes += p.minutes();
            billable += p.billableMinutes();
            capacity += p.capacityMinutes();
        }
        rows.sort(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Row::userId));
        return new Result(rows, row(null, "Total", minutes, billable, capacity));
    }

    private static Row row(Long userId, String name, long minutes, long billableMinutes, long capacityMinutes) {
        return new Row(userId, name, minutes, billableMinutes, capacityMinutes,
                BudgetCalculator.percent(BigDecimal.valueOf(billableMinutes), BigDecimal.valueOf(capacityMinutes)));
    }
}
