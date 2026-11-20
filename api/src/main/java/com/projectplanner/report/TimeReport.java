package com.projectplanner.report;

import com.projectplanner.common.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Logged time added up per project, client, person or task.
 *
 * <p>Time is kept in whole minutes and money in cents, and nothing is rounded
 * after it has been added up: a total is the sum of its rows, to the minute and
 * to the cent. Money is priced the way the budget and the CSV export of entries
 * price it, one entry at a time: an entry costs its minutes at the hourly rate,
 * rounded half up to a cent ({@link Money#cost}). Three entries of twenty minutes
 * at 100 an hour are 99.99, not 100.00. Only billable time has an amount.
 */
public final class TimeReport {

    private TimeReport() {
    }

    public enum GroupBy {
        PROJECT, CLIENT, MEMBER, TASK
    }

    /**
     * Entries that have project, person, task, billable flag and length in common:
     * {@code count} entries of {@code minutes} minutes each. They share a rate, so
     * pricing one of them prices them all, which is why the database can do the
     * grouping without changing the result.
     */
    public record Bucket(long projectId, long userId, Long taskId, boolean billable, int minutes, long count, BigDecimal rate) {
    }

    /** The row a bucket belongs to. */
    public record Group(String key, String label) {
    }

    public record Row(String key, String label, long minutes, long billableMinutes, long billableCents) {

        /** The amount as a decimal with two places. */
        public BigDecimal billableAmount() {
            return BigDecimal.valueOf(billableCents, 2);
        }
    }

    public record Result(List<Row> rows, Row total) {
    }

    /** What one entry of a bucket costs, in cents. Zero when it is not billable. */
    static long centsPerEntry(Bucket bucket) {
        return bucket.billable() ? Money.cost(bucket.minutes(), bucket.rate()).unscaledValue().longValueExact() : 0;
    }

    /** The rows, the one with the most time first, and their total. */
    public static Result group(Collection<Bucket> buckets, Function<Bucket, Group> groupOf) {
        Map<String, long[]> sums = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        long[] total = new long[3];
        for (Bucket bucket : buckets) {
            Group group = groupOf.apply(bucket);
            labels.putIfAbsent(group.key(), group.label());
            long[] sum = sums.computeIfAbsent(group.key(), k -> new long[3]);
            long minutes = bucket.minutes() * bucket.count();
            long billableMinutes = bucket.billable() ? minutes : 0;
            long cents = centsPerEntry(bucket) * bucket.count();
            sum[0] += minutes;
            sum[1] += billableMinutes;
            sum[2] += cents;
            total[0] += minutes;
            total[1] += billableMinutes;
            total[2] += cents;
        }
        List<Row> rows = new ArrayList<>();
        sums.forEach((key, sum) -> rows.add(new Row(key, labels.get(key), sum[0], sum[1], sum[2])));
        rows.sort(Comparator.comparingLong(Row::minutes).reversed()
                .thenComparing(Row::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Row::key));
        return new Result(rows, new Row("total", "Total", total[0], total[1], total[2]));
    }
}
