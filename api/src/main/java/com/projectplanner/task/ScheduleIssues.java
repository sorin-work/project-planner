package com.projectplanner.task;

import com.projectplanner.schedule.Link;
import com.projectplanner.schedule.ScheduleCheck;
import com.projectplanner.schedule.WorkCalendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The schedule check over the tasks of a project as they are stored: the timeline's list
 * of issues and the count of them that the project list and the Overview show come from
 * here, so that the two always agree.
 */
public final class ScheduleIssues {

    private ScheduleIssues() {
    }

    public static List<ScheduleCheck.Issue> check(WorkCalendar calendar, List<Task> tasks, Collection<Link> links,
                                                  LocalDate projectEnd) {
        Map<Long, Long> parents = new HashMap<>();
        for (Task task : tasks) {
            if (task.parentId != null) {
                parents.put(task.id, task.parentId);
            }
        }
        return new ScheduleCheck(calendar).check(tasks.stream().map(Task::toScheduled).toList(), links, parents, done(tasks),
                projectEnd);
    }

    /** The ids of the finished tasks. */
    public static Set<Long> done(List<Task> tasks) {
        Set<Long> done = new HashSet<>();
        tasks.stream().filter(Task::isDone).forEach(t -> done.add(t.id));
        return done;
    }

    public static List<Link> linksOf(long projectId) {
        return TaskDependency.forProject(projectId).stream().map(TaskDependency::toLink).toList();
    }

    /** The links of several projects in one query, by project. A project without links is not in the map. */
    public static Map<Long, List<Link>> linksOf(Collection<Long> projectIds) {
        Map<Long, List<Link>> links = new HashMap<>();
        if (projectIds.isEmpty()) {
            return links;
        }
        TaskDependency.getEntityManager()
                .createQuery("select t.projectId, d from TaskDependency d, Task t where t.id = d.successorId"
                        + " and t.projectId in :projects order by d.id", Object[].class)
                .setParameter("projects", projectIds)
                .getResultList()
                .forEach(r -> links.computeIfAbsent((Long) r[0], id -> new ArrayList<>())
                        .add(((TaskDependency) r[1]).toLink()));
        return links;
    }
}
