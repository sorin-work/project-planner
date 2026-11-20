package com.projectplanner.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
            @Schema(description = "For a project its client. For a task or a comment the text around the match, when the match is not in the title.") String snippet) {
    }

    public record SearchResponse(
            @Schema(description = "The text the hits are for, without the space around it.") String query,
            List<SearchHit> projects,
            List<SearchHit> tasks,
            List<SearchHit> comments) {

        static SearchResponse nothing(String query) {
            return new SearchResponse(query, List.of(), List.of(), List.of());
        }
    }
}
