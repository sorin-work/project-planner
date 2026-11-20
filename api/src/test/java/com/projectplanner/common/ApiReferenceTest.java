package com.projectplanner.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projectplanner.support.ErrorCodeScan;
import com.projectplanner.support.ErrorCodeScan.Thrown;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The OpenAPI document is built from the resources. These tests hold it to the code:
 * nothing undocumented, nothing documented that the code does not do, and no change of
 * shape that nobody meant.
 */
@QuarkusTest
class ApiReferenceTest {

    /** The paths, parameters, statuses and schemas of the document, one fact per line. See {@link #shape}. */
    private static final Path PINNED_SHAPE = Path.of("src/test/resources/api-shape.txt");
    private static final String REFRESH = "./mvnw -q test -Dtest=ApiReferenceTest -Dapi.shape=update";

    /**
     * Sign-up and login, the forgotten password and invitation link endpoints, and the bytes of
     * an attachment, which a signed link stands in for the token for.
     */
    private static final Set<String> PUBLIC = Set.of("AuthResource_signup", "AuthResource_login", "AuthResource_options",
            "AuthResource_forgot", "AuthResource_reset", "AuthResource_invitation", "AuthResource_unsubscribe",
            "AttachmentContentResource_content");
    private static final String ERROR_BODY = "#/components/schemas/ApiError";
    private static final String ERROR_RESPONSE = "#/components/responses/Error";

    private static JsonNode document;

    private static JsonNode document() {
        if (document == null) {
            String json = given().accept("application/json").get("/api/openapi").then().statusCode(200).extract().asString();
            try {
                document = new ObjectMapper().readTree(json);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return document;
    }

    /** Every operation of the document by its id, which is "class_method". */
    private static Map<String, JsonNode> operations() {
        Map<String, JsonNode> operations = new TreeMap<>();
        document().get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            String id = operation.getValue().path("operationId").asText();
            assertThat(operations.put(id, operation.getValue()))
                    .as("operation id %s is used once (%s %s)", id, operation.getKey(), path.getKey()).isNull();
        }));
        return operations;
    }

    /** Every resource method that answers a request, by the id its operation has. */
    private static Map<String, Method> resourceMethods() {
        Map<String, Method> methods = new TreeMap<>();
        Arc.container().beanManager().getBeans(Object.class, Any.Literal.INSTANCE).stream()
                .map(Bean::getBeanClass)
                .filter(type -> type.isAnnotationPresent(jakarta.ws.rs.Path.class))
                .distinct()
                .forEach(type -> Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> Arrays.stream(method.getAnnotations())
                                .anyMatch(a -> a.annotationType().isAnnotationPresent(HttpMethod.class)))
                        .forEach(method -> methods.put(type.getSimpleName() + "_" + method.getName(), method)));
        return methods;
    }

    @Test
    void theDocumentAnswersWithoutAToken() {
        given().get("/api/openapi").then().statusCode(200).contentType("application/yaml");
        assertThat(document().get("openapi").asText()).startsWith("3.");
    }

    @Test
    void everyResourceMethodIsInTheDocumentAndNothingElse() {
        assertThat(resourceMethods()).isNotEmpty();
        assertThat(operations().keySet()).isEqualTo(resourceMethods().keySet());
    }

    @Test
    void everyOperationHasASummaryAndATagAndItsOwnId() {
        Set<String> declared = new TreeSet<>();
        document().get("tags").forEach(tag -> declared.add(tag.get("name").asText()));
        Set<String> used = new TreeSet<>();
        operations().forEach((id, operation) -> {
            assertThat(operation.path("summary").asText()).as("summary of %s", id).isNotBlank();
            assertThat(operation.path("tags")).as("tags of %s", id).isNotEmpty();
            operation.get("tags").forEach(tag -> used.add(tag.asText()));
        });
        // Each tag is introduced once, in META-INF/openapi.yaml, and none is left over there.
        assertThat(used).isEqualTo(declared);
    }

    @Test
    void everythingButSigningInAsksForTheToken() {
        JsonNode scheme = document().at("/components/securitySchemes/bearer");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");

        operations().forEach((id, operation) -> {
            if (PUBLIC.contains(id)) {
                assertThat(operation.has("security")).as("%s is public", id).isFalse();
            } else {
                assertThat(operation.path("security")).as("security of %s", id).hasSize(1);
                assertThat(operation.at("/security/0").has("bearer")).as("%s asks for the bearer token", id).isTrue();
                assertThat(operation.at("/responses/401").isObject()).as("%s documents 401", id).isTrue();
            }
        });
        assertThat(operations().keySet()).containsAll(PUBLIC);
    }

    /**
     * A successful call is documented with the status the method really answers with, and
     * with what it sends. The status comes from the same place Quarkus takes it from.
     */
    @Test
    void everyOperationDocumentsWhatASuccessfulCallAnswers() {
        resourceMethods().forEach((id, method) -> {
            JsonNode responses = operations().get(id).get("responses");
            List<String> successes = new ArrayList<>();
            responses.fieldNames().forEachRemaining(code -> {
                if (code.startsWith("2")) {
                    successes.add(code);
                }
            });
            if (method.getReturnType() == Response.class) {
                // The method builds the answer itself, so it has to describe it itself.
                assertThat(successes).as("%s returns a Response and says with @APIResponse what it can be", id).isNotEmpty();
            } else {
                ResponseStatus status = method.getAnnotation(ResponseStatus.class);
                int expected = status != null ? status.value() : method.getReturnType() == void.class ? 204 : 200;
                assertThat(successes).as("success status of %s", id).containsExactly(String.valueOf(expected));
            }
            for (String code : successes) {
                JsonNode response = responses.get(code);
                assertThat(response.path("description").asText()).as("description of %s %s", id, code).isNotBlank();
                if (code.equals("204")) {
                    assertThat(response.has("content")).as("%s 204 has no body", id).isFalse();
                } else {
                    assertThat(response.path("content")).as("body of %s %s", id, code).hasSize(1);
                    JsonNode schema = response.get("content").elements().next().path("schema");
                    assertThat(schema.has("$ref") || schema.has("type")).as("schema of %s %s", id, code).isTrue();
                }
            }
        });
    }

    @Test
    void everyErrorIsAnApiError() {
        assertThat(document().at("/components/responses/Error/content/application~1json/schema/$ref").asText()).isEqualTo(ERROR_BODY);
        assertThat(fieldNames(document().at("/components/schemas/ApiError/properties")))
                .containsExactlyInAnyOrder("status", "code", "message", "errors");

        operations().forEach((id, operation) -> {
            JsonNode responses = operation.get("responses");
            assertThat(responses.at("/default/$ref").asText()).as("any other error of %s", id).isEqualTo(ERROR_RESPONSE);
            responses.properties().forEach(response -> {
                if (response.getKey().startsWith("4") || response.getKey().startsWith("5")) {
                    assertThat(response.getValue().at("/content/application~1json/schema/$ref").asText())
                            .as("body of %s %s", id, response.getKey()).isEqualTo(ERROR_BODY);
                }
            });
        });
    }

    /**
     * The rule for error codes. An operation names, with {@code @Fails}, every code it can
     * answer with for a reason of its own, and no other.
     *
     * <p>"Can answer with" is read from the sources, per resource class: the codes thrown in
     * the resource itself and in the classes its source reaches (the classes it mentions,
     * and the ones those mention). So a code has to be named on an operation of a resource
     * that reaches the place where it is thrown, with the status it is thrown with, and a
     * resource cannot name a code that nothing it reaches throws.
     */
    @Test
    void everyOperationNamesTheErrorCodesItsResourceCanAnswerWith() throws IOException {
        Map<String, String> sources = sources();
        // These two make the errors that every endpoint shares. They are checked below.
        Map<String, String> own = new TreeMap<>(sources);
        own.keySet().removeIf(file -> file.endsWith("/ApiException.java") || file.endsWith("/ErrorMappers.java"));
        ErrorCodeScan.Result scan = ErrorCodeScan.scan(own);
        Map<String, Set<String>> reach = ErrorCodeScan.reach(own);
        assertThat(scan.thrown()).as("codes found in the sources").extracting(Thrown::code)
                .contains("dependency_cycle", "timesheet_locked", "code_taken");

        List<String> problems = new ArrayList<>();
        scan.unreadable().forEach(place -> problems.add("Cannot read the error at " + place.file() + ":" + place.line() + ": "
                + place.why() + ".\n    Write the code as a string literal or a string constant, and the status as a number,"
                + " so that the API reference can say what the endpoint answers with."));

        Map<String, List<Method>> resources = new TreeMap<>();
        resourceMethods().values().forEach(method -> resources
                .computeIfAbsent(method.getDeclaringClass().getSimpleName(), name -> new ArrayList<>()).add(method));

        for (Thrown thrown : scan.thrown()) {
            List<String> reaching = resources.keySet().stream()
                    .filter(resource -> reach.getOrDefault(resource, Set.of()).contains(ErrorCodeScan.className(thrown.file()))).toList();
            List<Fails> naming = reaching.stream().flatMap(resource -> resources.get(resource).stream())
                    .flatMap(method -> Arrays.stream(method.getAnnotationsByType(Fails.class)))
                    .filter(fails -> fails.code().equals(thrown.code())).toList();
            String line = "@Fails(status = " + thrown.status() + ", code = \"" + thrown.code() + "\", when = \"...\")";
            if (reaching.isEmpty()) {
                problems.add("`" + thrown.code() + "` is thrown at " + thrown.place() + ", in a class that no resource reaches."
                        + "\n    If an endpoint can answer with it, the resource has to mention that class. If none can, remove it.");
            } else if (naming.isEmpty()) {
                problems.add("`" + thrown.code() + "` is thrown at " + thrown.place() + " and no operation names it."
                        + "\n    Add " + line + " to the method of " + String.join(" or ", reaching) + " that can answer with it.");
            } else if (naming.stream().noneMatch(fails -> fails.status() == thrown.status())) {
                problems.add("`" + thrown.code() + "` is thrown with status " + thrown.status() + " at " + thrown.place()
                        + " and named with status " + naming.getFirst().status() + "."
                        + "\n    Make the @Fails line say " + thrown.status() + ", or the code throw what the line says.");
            }
        }
        resources.forEach((resource, methods) -> methods.forEach(method -> {
            for (Fails fails : method.getAnnotationsByType(Fails.class)) {
                boolean thrownInReach = scan.thrown().stream().anyMatch(thrown -> thrown.code().equals(fails.code())
                        && reach.getOrDefault(resource, Set.of()).contains(ErrorCodeScan.className(thrown.file())));
                if (!thrownInReach) {
                    problems.add(resource + "." + method.getName() + " names `" + fails.code() + "` with @Fails, and nothing that "
                            + resource + " reaches throws it.\n    Remove the line, or check the spelling of the code.");
                }
                assertThat(operations().get(resource + "_" + method.getName()).at("/responses/" + fails.status() + "/description").asText())
                        .as("%s.%s documents %s", resource, method.getName(), fails.code()).contains("`" + fails.code() + "`: " + fails.when());
            }
        }));

        if (!problems.isEmpty()) {
            fail("The error codes in the code and in the API reference differ in " + problems.size() + " place(s).\n\n  "
                    + String.join("\n\n  ", problems) + "\n\nAn operation names every code it can answer with for a reason of its own,"
                    + " with a @Fails line on the resource method. The API reference is built from those lines.\n");
        }
    }

    /** The errors every endpoint shares are made in two classes, and the introduction lists their codes. */
    @Test
    void theIntroductionListsTheCodesEveryOperationCanAnswerWith() throws IOException {
        Set<String> common = new TreeSet<>();
        Pattern commonCode = Pattern.compile("new Api(?:Error|Exception)\\(\\d+, \"([a-z0-9_]+)\"|-> \"([a-z0-9_]+)\";");
        sources().forEach((file, text) -> {
            if (file.endsWith("/ApiException.java") || file.endsWith("/ErrorMappers.java")) {
                Matcher matcher = commonCode.matcher(ErrorCodeScan.withoutComments(text));
                while (matcher.find()) {
                    common.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
                }
            }
        });
        assertThat(common).contains("validation_failed", "unauthorized", "forbidden", "not_found", "internal_error");

        String introduction = document().at("/info/description").asText();
        assertThat(common).allSatisfy(code -> assertThat(introduction)
                .as("the introduction in META-INF/openapi.yaml lists `%s`", code).contains("`" + code + "`"));
    }

    /** Every source file of the API, by its path, with forward slashes. */
    private static Map<String, String> sources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.put(file.toString().replace('\\', '/'), Files.readString(file));
            }
        }
        return sources;
    }

    @Test
    void onlyTheApiIsDescribed() {
        assertThat(fieldNames(document().get("paths"))).isNotEmpty().allSatisfy(path -> assertThat(path).startsWith("/api/"));
        assertThat(fieldNames(document().get("paths"))).noneMatch(path -> path.contains("openapi") || path.contains("/docs"));
        // No server names, and nothing of the entities: only the records that the resources send.
        assertThat(document().has("servers")).isFalse();
        assertThat(document().toString()).doesNotContainIgnoringCase("passwordHash");
        assertThat(fieldNames(document().at("/components/schemas")))
                .doesNotContain("User", "Membership", "Organisation", "Invitation", "Task", "TimeEntry", "Timesheet");
        // The default places are gone, and so is everything Quarkus serves next to the API.
        given().get("/q/openapi").then().statusCode(404);
        given().get("/q/swagger-ui").then().statusCode(404);
    }

    @Test
    void everyReferenceLeadsSomewhere() {
        Set<String> references = new TreeSet<>();
        collect(document(), "$ref", references);
        assertThat(references).isNotEmpty().allSatisfy(reference ->
                assertThat(document().at(reference.substring(1)).isMissingNode()).as(reference).isFalse());
    }

    /** Without the setting, the interactive page is not served. ApiDocsPageTest has the other half. */
    @Test
    void theInteractivePageIsOffUnlessAskedFor() {
        given().get("/api/docs").then().statusCode(404);
        given().get("/api/docs/").then().statusCode(404);
    }

    /**
     * Fails when the document changes shape, so that a path, a parameter, a status or a
     * field of the API cannot change by accident. When the change is meant, {@link #REFRESH}
     * rewrites the pinned copy, and the difference goes into the same commit.
     */
    @Test
    void theShapeOfTheApiIsTheOneThatIsPinned() throws IOException {
        List<String> shape = shape(document());
        if ("update".equals(System.getProperty("api.shape"))) {
            Files.createDirectories(PINNED_SHAPE.getParent());
            Files.write(PINNED_SHAPE, shape);
            return;
        }
        List<String> pinned = Files.readAllLines(PINNED_SHAPE);
        if (!shape.equals(pinned)) {
            List<String> gone = new ArrayList<>(pinned);
            gone.removeAll(new HashSet<>(shape));
            List<String> added = new ArrayList<>(shape);
            added.removeAll(new HashSet<>(pinned));
            fail("The API has changed shape.\n\nNo longer there:\n  " + (gone.isEmpty() ? "(nothing)" : String.join("\n  ", gone))
                    + "\n\nNew:\n  " + (added.isEmpty() ? "(nothing)" : String.join("\n  ", added))
                    + "\n\nIf that is what you meant to do, refresh " + PINNED_SHAPE + " and commit it:\n  " + REFRESH + "\n");
        }
    }

    /** Text for people. It can be reworded without the API changing. */
    private static final Set<String> PROSE = Set.of("description", "summary", "example", "examples", "title");

    /**
     * The document as sorted lines of "where = what", without the introduction, the tag
     * list and the prose. One fact per line keeps two changes to different endpoints from
     * touching the same line: a list of values (an enum, the required fields) has a line
     * per value. Whatever has no fact of its own still gets a line, so that a response
     * that is only a status and a description, as every 204 is, is pinned as well.
     */
    static List<String> shape(JsonNode document) {
        List<String> lines = new ArrayList<>();
        document.properties().forEach(part -> {
            if (!part.getKey().equals("info") && !part.getKey().equals("tags")) {
                flatten(part.getKey(), part.getValue(), lines);
            }
        });
        lines.sort(null);
        return lines;
    }

    private static void flatten(String where, JsonNode node, List<String> lines) {
        if (node.isObject()) {
            int before = lines.size();
            node.properties().forEach(field -> {
                if (!PROSE.contains(field.getKey()) || field.getValue().isObject()) {
                    flatten(where + " " + field.getKey(), field.getValue(), lines);
                }
            });
            if (lines.size() == before) {
                lines.add(where + " = {}");
            }
        } else if (node.isArray() && where.endsWith(" parameters")) {
            // By place and name instead of by position. The two then need no lines of their own.
            node.forEach(parameter -> {
                ObjectNode rest = parameter.deepCopy();
                rest.remove(List.of("in", "name"));
                flatten(where + " " + parameter.get("in").asText() + ":" + parameter.get("name").asText(), rest, lines);
            });
        } else if (node.isArray() && where.endsWith(" security")) {
            node.forEach(requirement -> lines.add(where + " = " + String.join(", ", fieldNames(requirement))));
        } else if (node.isArray() && (node.isEmpty() || node.get(0).isValueNode())) {
            // required, enum, tags: a line per value, and the order says nothing.
            node.forEach(value -> lines.add(where + " = " + value.asText()));
            if (node.isEmpty()) {
                lines.add(where + " = []");
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flatten(where + " " + i, node.get(i), lines);
            }
        } else {
            lines.add(where + " = " + node.asText());
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void collect(JsonNode node, String field, Collection<String> values) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (entry.getKey().equals(field) && entry.getValue().isTextual()) {
                    values.add(entry.getValue().asText());
                } else {
                    collect(entry.getValue(), field, values);
                }
            });
        } else if (node.isArray()) {
            node.forEach(element -> collect(element, field, values));
        }
    }
}
