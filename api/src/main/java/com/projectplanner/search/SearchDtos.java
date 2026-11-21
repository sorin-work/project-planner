package com.projectplanner.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.projectplanner.comment.Comment;
import com.projectplanner.project.Project;
import com.projectplanner.task.Priority;
import com.projectplanner.task.Task;
import com.projectplanner.task.TaskStatus;

import java.time.LocalDate;
import java.util.List;

public final class SearchDtos {

    private SearchDtos() {
    }

    public enum SearchHitType {
        PROJECT,
        TASK,
        COMMENT
    }

    /** One thing that was found. It says where to go, and nothing about budgets or rates. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchHit(
            SearchHitType type,
            @Schema(description = "The id of the project, the task or the comment, depending on the type.") long id,
            long projectId,
            String projectCode,
            @Schema(description = "The task to open: the task itself, or the task a comment is on. Left out for a project.") Long taskId,
            @Schema(description = "The name of the project, or the title of the task. For a comment, the title of its task.") String title,
            @Schema(description = "For a project its client. For a task or a comment the text around the match, when the match is not in the title.") String snippet,
            @Schema(description = "For a task: when it is due. Left out without a due date, and for a project or a comment.") LocalDate dueDate,
            @Schema(description = "For a task: its status. Left out for a project or a comment.") TaskStatus status,
            @Schema(description = "For a task: its priority. Left out for a project or a comment.") Priority priority) {

        static SearchHit project(Project p, String clientName) {
            return new SearchHit(SearchHitType.PROJECT, p.id, p.id, p.code, null, p.name, clientName, null, null, null);
        }

        static SearchHit task(Task t, String projectCode, String snippet) {
            return new SearchHit(SearchHitType.TASK, t.id, t.projectId, projectCode, t.id, t.title, snippet, t.dueDate, t.status, t.priority);
        }

        static SearchHit comment(Comment c, Task task, String projectCode, String snippet) {
            return new SearchHit(SearchHitType.COMMENT, c.id, task.projectId, projectCode, task.id, task.title, snippet, null, null, null);
        }
    }

    @Schema(description = "A hit that was picked before, by its kind and id.")
    public record RecentPick(@NotNull SearchHitType type, long id) {
    }

    public record RecentRequest(@NotNull @Size(max = 20) List<@NotNull @Valid RecentPick> picks) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResponse(
            @Schema(description = "The text the hits are for, without the space around it.") String query,
            List<SearchHit> projects,
            List<SearchHit> tasks,
            List<SearchHit> comments,
            @Schema(description = "The kinds that had more hits than the limit: there is more to find than what is listed.") List<SearchHitType> more,
            @Schema(description = "The organisation's date, when only tasks were asked for (by assignee or status), so that they can be told apart as overdue or not.") LocalDate today) {

        static SearchResponse nothing(String query) {
            return new SearchResponse(query, List.of(), List.of(), List.of(), List.of(), null);
        }
    }
}
