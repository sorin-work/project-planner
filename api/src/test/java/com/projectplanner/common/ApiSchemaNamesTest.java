package com.projectplanner.common;

import com.projectplanner.common.ApiSchemaNames.Known;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiSchemaNamesTest {

    private static final Known CRITICAL = new Known("com.projectplanner.schedule.CriticalPath$Result", Set.of("start", "finish", "tasks"));
    private static final Known REPORT = new Known("com.projectplanner.report.Utilisation$Result", Set.of("from", "to", "people"));
    private static final Known OTHER_REPORT = new Known("com.projectplanner.report.TimeReport$Result", Set.of("from", "to", "rows"));
    private static final Known TASK = new Known("com.projectplanner.task.TaskDtos$TaskResponse", Set.of("id", "title"));
    private static final Map<String, String> GIVEN = Map.of(CRITICAL.className(), "CriticalPath");

    @Test
    void aSchemaGetsTheNameGivenForItsClass() {
        Map<String, Set<String>> schemas = Map.of("Result", CRITICAL.members(), "TaskResponse", TASK.members(), "Instant", Set.of());

        assertThat(ApiSchemaNames.renames(schemas, List.of(CRITICAL, TASK), GIVEN)).isEqualTo(Map.of("Result", "CriticalPath"));
    }

    @Test
    void aSecondClassOfTheSameNameDoesNotTakeTheNameOfTheFirst() {
        // The scanner numbers them in an order of its own. Either way each one ends up as itself.
        assertThat(ApiSchemaNames.renames(Map.of("Result", CRITICAL.members(), "Result1", REPORT.members()), List.of(CRITICAL, REPORT), GIVEN))
                .isEqualTo(Map.of("Result", "CriticalPath", "Result1", "Result"));
        assertThat(ApiSchemaNames.renames(Map.of("Result", REPORT.members(), "Result1", CRITICAL.members()), List.of(CRITICAL, REPORT), GIVEN))
                .isEqualTo(Map.of("Result1", "CriticalPath"));
    }

    @Test
    void twoSchemasThatWouldShareANameStopTheBuildAndTheMessageNamesTheClasses() {
        Map<String, Set<String>> schemas = Map.of("Result", CRITICAL.members(), "Result1", REPORT.members(), "Result2", OTHER_REPORT.members());

        assertThatThrownBy(() -> ApiSchemaNames.renames(schemas, List.of(CRITICAL, REPORT, OTHER_REPORT), GIVEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("com.projectplanner.report.TimeReport$Result and com.projectplanner.report.Utilisation$Result")
                .hasMessageContaining("would all be called Result")
                .hasMessageContaining("SCHEMA_NAMES");

        Map<String, String> apart = Map.of(CRITICAL.className(), "CriticalPath", REPORT.className(), "UtilisationReport");
        assertThat(ApiSchemaNames.renames(schemas, List.of(CRITICAL, REPORT, OTHER_REPORT), apart))
                .isEqualTo(Map.of("Result", "CriticalPath", "Result1", "UtilisationReport", "Result2", "Result"));
    }

    @Test
    void aGivenNameThatAnotherSchemaAlreadyHasStopsTheBuild() {
        Map<String, Set<String>> schemas = Map.of("Result", CRITICAL.members(), "TaskResponse", TASK.members());

        assertThatThrownBy(() -> ApiSchemaNames.renames(schemas, List.of(CRITICAL, TASK), Map.of(CRITICAL.className(), "TaskResponse")))
                .hasMessageContaining("would all be called TaskResponse");
        assertThatThrownBy(() -> ApiSchemaNames.renames(Map.of("Result", CRITICAL.members(), "Instant", Set.of()), List.of(CRITICAL),
                Map.of(CRITICAL.className(), "Instant")))
                .hasMessageContaining("that name is taken");
    }

    @Test
    void twoClassesThatLookTheSameCannotBeToldApart() {
        Known twin = new Known("com.projectplanner.report.Twin$Result", CRITICAL.members());

        assertThatThrownBy(() -> ApiSchemaNames.renames(Map.of("Result", CRITICAL.members(), "Result1", CRITICAL.members()),
                List.of(CRITICAL, twin), GIVEN))
                .hasMessageContaining("cannot tell which class the schema Result");
    }

    @Test
    void aNameGivenForAClassThatIsGoneStopsTheBuild() {
        assertThatThrownBy(() -> ApiSchemaNames.renames(Map.of("TaskResponse", TASK.members()), List.of(TASK), GIVEN))
                .hasMessageContaining("there is no such class");
    }
}
