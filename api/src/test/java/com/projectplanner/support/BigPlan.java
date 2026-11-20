package com.projectplanner.support;

import com.projectplanner.support.Api.Account;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskDependency;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans that are too big to build through the API in a test, and a way to count
 * what the database is asked to do with them.
 */
public final class BigPlan {

    /** The first Monday of every plan made here. */
    public static final LocalDate START = LocalDate.of(2026, 3, 2);

    private BigPlan() {
    }

    /**
     * A project with {@code size} tasks, written straight into the database. One task in
     * six is a parent that takes a week, Monday to Friday, with five one-day subtasks
     * under it. The parents wait for each other in a chain, and so do the subtasks of
     * one parent. Every link holds. Titles are "{code} task {n}" and are unique.
     */
    public static long create(Account owner, String code, int size) {
        long projectId = Api.createProject(owner, code);
        QuarkusTransaction.requiringNew().run(() -> {
            Instant now = Instant.now();
            List<Task> parents = new ArrayList<>();
            List<Task> subtasks = new ArrayList<>();
            Task parent = null;
            for (int i = 0; i < size; i++) {
                Task task = new Task();
                task.projectId = projectId;
                task.title = code + " task " + i;
                task.description = "Step " + i;
                task.estimateHours = BigDecimal.valueOf(i % 9 + 1);
                task.assigneeId = owner.userId();
                task.position = i;
                task.createdAt = now;
                task.updatedAt = now;
                task.startDate = START.plusWeeks(i / 6);
                task.dueDate = task.startDate.plusDays(4);
                if (i % 6 == 0) {
                    parent = task;
                    parents.add(task);
                } else {
                    task.parentId = parent.id;
                    task.startDate = task.startDate.plusDays(i % 6 - 1);
                    task.dueDate = task.startDate;
                    subtasks.add(task);
                }
                task.persist();
            }
            for (int i = 1; i < parents.size(); i++) {
                link(parents.get(i - 1).id, parents.get(i).id, now);
            }
            for (int i = 1; i < subtasks.size(); i++) {
                if (subtasks.get(i).parentId.equals(subtasks.get(i - 1).parentId)) {
                    link(subtasks.get(i - 1).id, subtasks.get(i).id, now);
                }
            }
        });
        return projectId;
    }

    private static void link(long predecessor, long successor, Instant now) {
        TaskDependency dependency = new TaskDependency();
        dependency.predecessorId = predecessor;
        dependency.successorId = successor;
        dependency.createdAt = now;
        dependency.persist();
    }

    /**
     * How many statements the database ran for {@code request}, by its own count (H2 keeps
     * one when asked to). It includes everything the request needs, permission checks too.
     */
    public static long statementsFor(EntityManager em, Runnable request) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("set query_statistics true").executeUpdate());
        try {
            long before = executed(em);
            request.run();
            return executed(em) - before;
        } finally {
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("set query_statistics false").executeUpdate());
        }
    }

    private static long executed(EntityManager em) {
        return QuarkusTransaction.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "select coalesce(sum(execution_count), 0) from information_schema.query_statistics").getSingleResult()).longValue());
    }
}
