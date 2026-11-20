package com.projectplanner.support;

import com.projectplanner.support.ErrorCodeScan.Result;
import com.projectplanner.support.ErrorCodeScan.Thrown;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeScanTest {

    private static Result scan(String... filesAndSources) {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        for (int i = 0; i < filesAndSources.length; i += 2) {
            sources.put(filesAndSources[i], filesAndSources[i + 1]);
        }
        return ErrorCodeScan.scan(sources);
    }

    @Test
    void readsTheThreeWaysOfMakingAnErrorWithItsStatusAndPlace() {
        Result result = scan("time/TimeService.java", """
                class TimeService {
                    void a() { throw ApiException.badRequest("date_in_future", "Too far ahead"); }
                    void b() {
                        throw ApiException.conflict("timesheet_locked", "Locked");
                    }
                    void c() { throw new ApiException(401, "invalid_credentials", "No"); }
                }
                """);

        assertThat(result.unreadable()).isEmpty();
        assertThat(result.thrown()).containsExactlyInAnyOrder(
                new Thrown("date_in_future", 400, "time/TimeService.java", 2),
                new Thrown("timesheet_locked", 409, "time/TimeService.java", 4),
                new Thrown("invalid_credentials", 401, "time/TimeService.java", 6));
    }

    @Test
    void readsACodeOnTheNextLineAndOneWithADigitAndOneInALambda() {
        Result result = scan("A.java", """
                class A {
                    void a() {
                        throw ApiException.conflict(
                                "dependency_cycle",
                                message(a, b));
                    }
                    void b() { throw ApiException.badRequest("oauth2_failed", "x"); }
                    Object c() { return find().orElseThrow(() -> ApiException.badRequest("unknown_assignee", "x, (y)")); }
                }
                """);

        assertThat(result.unreadable()).isEmpty();
        assertThat(result.thrown()).extracting(Thrown::code, Thrown::line)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("dependency_cycle", 3),
                        org.assertj.core.groups.Tuple.tuple("oauth2_failed", 7),
                        org.assertj.core.groups.Tuple.tuple("unknown_assignee", 8));
    }

    @Test
    void doesNotCountWhatIsInAComment() {
        Result result = scan("A.java", """
                class A {
                    // throw ApiException.conflict("only_a_comment", "x");
                    /* and ApiException.badRequest("nor_this", "x")
                       or new ApiException(418, "teapot", "x") */
                    String url = "http://example.com/a//b"; // ApiException.conflict("trailing", "x")
                    void a() { throw ApiException.conflict("real_one", "says // nothing"); }
                }
                """);

        assertThat(result.thrown()).extracting(Thrown::code).containsExactly("real_one");
        assertThat(result.unreadable()).isEmpty();
    }

    @Test
    void readsAConstantWhereverItIsDefined() {
        Result result = scan(
                "Codes.java", """
                        final class Codes {
                            static final String KEY_REUSED = "client_key_reused";
                        }
                        """,
                "B.java", """
                        class B {
                            private static final String LOCKED = "timesheet_locked";
                            void a() { throw ApiException.conflict(LOCKED, "x"); }
                            void b() { throw ApiException.conflict(Codes.KEY_REUSED, "x"); }
                        }
                        """);

        assertThat(result.unreadable()).isEmpty();
        assertThat(result.thrown()).extracting(Thrown::code).containsExactlyInAnyOrder("timesheet_locked", "client_key_reused");
    }

    @Test
    void saysWhereItCannotReadACodeOrAStatus() {
        Result result = scan("C.java", """
                class C {
                    void a(String code) { throw ApiException.conflict(code, "x"); }
                    void b() { throw ApiException.badRequest("invalid_" + kind(), "x"); }
                    void c(int status) { throw new ApiException(status, "some_code", "x"); }
                    void d() { throw ApiException.conflict(MISSING, "x"); }
                }
                """);

        assertThat(result.thrown()).isEmpty();
        assertThat(result.unreadable()).extracting(u -> u.file() + ":" + u.line() + " " + u.why()).containsExactly(
                "C.java:2 the code is `code`, and no string constant of that name is in the sources",
                "C.java:3 the code is `\"invalid_\" + kind()`, not a string literal or a constant",
                "C.java:5 the code is `MISSING`, and no string constant of that name is in the sources",
                "C.java:4 the status is `status`, not a number");
    }

    @Test
    void readsCallsWithoutTheClassNameOnlyWhereTheyAreImported() {
        String body = """
                class D {
                    void a() { throw conflict("imported_code", "x"); }
                    void b() { merge.conflict("not_ours", "x"); }
                }
                """;
        assertThat(scan("D.java", body).thrown()).isEmpty();
        assertThat(scan("D.java", "import static com.projectplanner.common.ApiException.conflict;\n" + body).thrown())
                .extracting(Thrown::code, Thrown::line).containsExactly(org.assertj.core.groups.Tuple.tuple("imported_code", 3));
    }

    @Test
    void knowsWhichClassesAClassCanReach() {
        Map<String, java.util.Set<String>> reach = ErrorCodeScan.reach(Map.of(
                "time/TimesheetResource.java", "class TimesheetResource { TimesheetService timesheets; }",
                "time/TimesheetService.java", "class TimesheetService { void a() { TimeService.checkUnlocked(); } }",
                "time/TimeService.java", "class TimeService { /* uses ExportResource, says a comment */ String s = \"TaskService\"; }",
                "time/ExportResource.java", "class ExportResource { }",
                "task/TaskService.java", "class TaskService { }"));

        assertThat(reach.get("TimesheetResource")).containsExactlyInAnyOrder("TimesheetResource", "TimesheetService", "TimeService");
        assertThat(reach.get("ExportResource")).containsExactly("ExportResource");
    }
}
