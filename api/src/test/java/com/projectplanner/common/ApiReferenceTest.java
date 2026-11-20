package com.projectplanner.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static final Set<String> PUBLIC = Set.of("AuthResource_signup", "AuthResource_login");
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
    void everythingButSignupAndLoginAsksForTheToken() {
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
     * The codes the reference promises are the codes the code answers with: every code
     * named by {@code @Fails} is thrown somewhere, every code that is thrown is named by
     * an operation, and the ones any operation can answer with are in the introduction.
     */
    @Test
    void theErrorCodesAreTheOnesInTheCode() throws IOException {
        Set<String> thrown = new TreeSet<>();
        Set<String> common = new TreeSet<>();
        Pattern ownCode = Pattern.compile("(?:badRequest|conflict)\\(\"([a-z_]+)\"|new ApiException\\(\\d+, \"([a-z_]+)\"");
        Pattern commonCode = Pattern.compile("new Api(?:Error|Exception)\\(\\d+, \"([a-z_]+)\"|-> \"([a-z_]+)\";");
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(file -> file.toString().endsWith(".java")).toList()) {
                boolean shared = source.endsWith("ErrorMappers.java") || source.endsWith("ApiException.java");
                Matcher matcher = (shared ? commonCode : ownCode).matcher(Files.readString(source));
                while (matcher.find()) {
                    (shared ? common : thrown).add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
                }
            }
        }
        assertThat(thrown).as("codes found in the sources").contains("dependency_cycle", "timesheet_locked", "code_taken");
        assertThat(common).as("codes found in ErrorMappers").contains("validation_failed", "not_found", "internal_error");

        Set<String> named = new TreeSet<>();
        resourceMethods().forEach((id, method) -> {
            for (Fails fails : method.getAnnotationsByType(Fails.class)) {
                named.add(fails.code());
                assertThat(operations().get(id).at("/responses/" + fails.status() + "/description").asText())
                        .as("%s documents %s", id, fails.code()).contains("`" + fails.code() + "`: " + fails.when());
            }
        });
        assertThat(named).as("codes named with @Fails against codes thrown in the code").isEqualTo(thrown);

        String introduction = document().at("/info/description").asText();
        assertThat(common).allSatisfy(code -> assertThat(introduction).contains("`" + code + "`"));
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
            fail("The API has changed shape.\n\nNo longer there:\n  " + String.join("\n  ", gone)
                    + "\n\nNew:\n  " + String.join("\n  ", added)
                    + "\n\nIf that is what you meant to do, refresh " + PINNED_SHAPE + " and commit it:\n  " + REFRESH + "\n");
        }
    }

    /** Text for people. It can be reworded without the API changing. */
    private static final Set<String> PROSE = Set.of("description", "summary", "example", "examples", "title");

    /**
     * The document as sorted lines of "where = what", without the introduction, the tag
     * list and the prose. One fact per line keeps two changes to different endpoints from
     * touching the same line.
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
            node.properties().forEach(field -> {
                if (!PROSE.contains(field.getKey()) || field.getValue().isObject()) {
                    flatten(where + " " + field.getKey(), field.getValue(), lines);
                }
            });
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
            // required, enum, tags: the order says nothing.
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(value.asText()));
            values.sort(null);
            lines.add(where + " = " + String.join(", ", values));
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
