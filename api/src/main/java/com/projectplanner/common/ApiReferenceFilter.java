package com.projectplanner.common;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Finishes the OpenAPI document when the API is built, after the resources have been
 * scanned. It puts in what the scanner gets wrong or cannot know, once for all operations:
 *
 * <ul>
 * <li>the status of a successful call. The scanner does not read {@code @ResponseStatus},
 * so by itself it documents a created resource as 200 and a POST without an answer as 201;</li>
 * <li>the errors: the ones a method names with {@link Fails}, 401 wherever a token is
 * needed, and for everything else the one shared response from
 * {@code META-INF/openapi.yaml}. The body is an {@link ApiError} every time;</li>
 * <li>the names of the schemas, see {@link ApiSchemaNames}. That is also where the records
 * of the rule classes get a name that says something outside their class, without the rule
 * classes having to know about OpenAPI.</li>
 * </ul>
 */
@OpenApiFilter(OpenApiFilter.RunStage.BUILD)
public class ApiReferenceFilter implements OASFilter {

    static final String ERROR_SCHEMA = "#/components/schemas/ApiError";
    static final String ERROR_RESPONSE = "#/components/responses/Error";

    private static final DotName PATH = DotName.createSimple("jakarta.ws.rs.Path");
    private static final DotName RESPONSE_STATUS = DotName.createSimple("org.jboss.resteasy.reactive.ResponseStatus");
    private static final DotName RESPONSE = DotName.createSimple(Response.class.getName());
    private static final DotName FAILS = DotName.createSimple(Fails.class.getName());

    private static final String SCHEMAS = "#/components/schemas/";
    private static final String APPLICATION = "com.projectplanner.";
    /**
     * A schema is named after its class, without the package. For these that says too
     * little, or the same as another class. By the full name of the class, since only that
     * tells two classes apart.
     */
    private static final Map<String, String> SCHEMA_NAMES = Map.of(
            "com.projectplanner.schedule.CriticalPath$Result", "CriticalPath",
            "com.projectplanner.schedule.ScheduleCheck$IssueType", "ScheduleIssueType",
            "com.projectplanner.report.TimeReport$GroupBy", "TimeReportGroupBy",
            "com.projectplanner.time.MissingTimesheets$Missing", "MissingTimesheet",
            "com.projectplanner.time.MissingTimesheets$Skip", "ReminderSkipReason");

    private final IndexView index;

    public ApiReferenceFilter(IndexView index) {
        this.index = index;
    }

    @Override
    public void filterOpenAPI(OpenAPI document) {
        if (document.getPaths() == null || document.getPaths().getPathItems() == null) {
            return;
        }
        nameSchemas(document);
        Map<String, MethodInfo> methods = resourceMethods();
        for (PathItem path : document.getPaths().getPathItems().values()) {
            for (Operation operation : path.getOperations().values()) {
                MethodInfo method = methods.get(operation.getOperationId());
                if (method != null) {
                    documentSuccess(operation, method);
                    documentFailures(operation, method);
                }
                documentCommonErrors(operation);
            }
        }
    }

    /** Gives every schema the name {@link ApiSchemaNames} decides on, wherever the document refers to it. */
    private void nameSchemas(OpenAPI document) {
        Map<String, Schema> schemas = document.getComponents().getSchemas();
        Map<String, Set<String>> scanned = new TreeMap<>();
        schemas.forEach((name, schema) -> scanned.put(name, schema.getProperties() != null ? schema.getProperties().keySet()
                : schema.getEnumeration() != null ? schema.getEnumeration().stream().map(String::valueOf).collect(Collectors.toSet())
                : Set.of()));
        // By name: the index is made of several, and a class can be in more than one of them.
        Map<String, ApiSchemaNames.Known> classes = new TreeMap<>();
        index.getKnownClasses().stream()
                .filter(info -> info.name().toString().startsWith(APPLICATION))
                .forEach(info -> classes.put(info.name().toString(), known(info)));

        Map<String, String> renames = ApiSchemaNames.renames(scanned, classes.values(), SCHEMA_NAMES);
        if (renames.isEmpty()) {
            return;
        }
        Map<String, Schema> named = new TreeMap<>();
        schemas.forEach((name, schema) -> named.put(renames.getOrDefault(name, name), schema));
        document.getComponents().setSchemas(named);

        Map<String, String> references = new HashMap<>();
        renames.forEach((from, to) -> references.put(SCHEMAS + from, SCHEMAS + to));
        named.values().forEach(schema -> refer(schema, references));
        if (document.getComponents().getResponses() != null) {
            document.getComponents().getResponses().values().forEach(response -> refer(response.getContent(), references));
        }
        for (PathItem path : document.getPaths().getPathItems().values()) {
            for (Operation operation : path.getOperations().values()) {
                if (operation.getParameters() != null) {
                    operation.getParameters().forEach(parameter -> refer(parameter.getSchema(), references));
                }
                if (operation.getRequestBody() != null) {
                    refer(operation.getRequestBody().getContent(), references);
                }
                operation.getResponses().getAPIResponses().values().forEach(response -> refer(response.getContent(), references));
            }
        }
    }

    private static ApiSchemaNames.Known known(ClassInfo info) {
        Set<String> members = new TreeSet<>();
        if (info.isEnum()) {
            info.enumConstants().forEach(constant -> members.add(constant.name()));
        } else if (info.isRecord()) {
            info.recordComponents().forEach(component -> members.add(component.name()));
        } else {
            info.fields().stream().filter(field -> !Modifier.isStatic(field.flags())).forEach(field -> members.add(field.name()));
        }
        return new ApiSchemaNames.Known(info.name().toString(), members);
    }

    private static void refer(Content content, Map<String, String> references) {
        if (content != null && content.getMediaTypes() != null) {
            content.getMediaTypes().values().forEach(media -> refer(media.getSchema(), references));
        }
    }

    /** Points a schema, and every schema inside it, at the new names. */
    private static void refer(Schema schema, Map<String, String> references) {
        if (schema == null) {
            return;
        }
        if (schema.getRef() != null && references.containsKey(schema.getRef())) {
            schema.setRef(references.get(schema.getRef()));
        }
        refer(schema.getItems(), references);
        refer(schema.getNot(), references);
        refer(schema.getAdditionalPropertiesSchema(), references);
        for (Collection<Schema> inside : Arrays.asList(
                schema.getProperties() == null ? null : schema.getProperties().values(),
                schema.getAllOf(), schema.getAnyOf(), schema.getOneOf())) {
            if (inside != null) {
                inside.forEach(part -> refer(part, references));
            }
        }
    }

    /** Resource methods by the operation id that the "class-method" strategy gives them. */
    private Map<String, MethodInfo> resourceMethods() {
        Map<String, MethodInfo> methods = new HashMap<>();
        for (AnnotationInstance path : index.getAnnotations(PATH)) {
            if (path.target().kind() != AnnotationTarget.Kind.CLASS) {
                continue;
            }
            for (MethodInfo method : path.target().asClass().methods()) {
                methods.put(path.target().asClass().simpleName() + "_" + method.name(), method);
            }
        }
        return methods;
    }

    /** Moves the one success response the scanner made to the status the method really answers with. */
    private static void documentSuccess(Operation operation, MethodInfo method) {
        if (method.returnType().name().equals(RESPONSE)) {
            // The method picks the status itself and says so with @APIResponse.
            return;
        }
        AnnotationInstance responseStatus = method.annotation(RESPONSE_STATUS);
        int status = responseStatus != null ? responseStatus.value().asInt()
                : method.returnType().kind() == Type.Kind.VOID ? 204 : 200;
        String code = String.valueOf(status);

        APIResponses responses = operation.getResponses();
        List<String> scanned = responses.getAPIResponses().keySet().stream().filter(c -> c.startsWith("2")).toList();
        if (scanned.size() != 1 || scanned.getFirst().equals(code)) {
            return;
        }
        APIResponse success = responses.getAPIResponse(scanned.getFirst());
        responses.removeAPIResponse(scanned.getFirst());
        success.setDescription(Response.Status.fromStatusCode(status).getReasonPhrase());
        if (status == 204) {
            success.setContent(null);
        }
        responses.addAPIResponse(code, success);
    }

    /** One response per status that the method names with {@link Fails}, listing the codes. */
    private void documentFailures(Operation operation, MethodInfo method) {
        Map<Integer, List<String>> byStatus = new TreeMap<>();
        for (AnnotationInstance fails : method.annotationsWithRepeatable(FAILS, index)) {
            byStatus.computeIfAbsent(fails.value("status").asInt(), status -> new ArrayList<>())
                    .add("`" + fails.value("code").asString() + "`: " + fails.value("when").asString() + ".");
        }
        byStatus.forEach((status, lines) -> operation.getResponses().addAPIResponse(String.valueOf(status),
                OASFactory.createAPIResponse()
                        .description(lines.size() == 1 ? lines.getFirst() : "- " + String.join("\n- ", lines))
                        .content(errorBody())));
    }

    private static void documentCommonErrors(Operation operation) {
        APIResponses responses = operation.getResponses();
        // Quarkus puts these on every operation that takes a body or needs a login. They have
        // no body, and 400 and 403 are covered by the shared response.
        dropStock(responses, "400", "Bad Request");
        dropStock(responses, "401", "Not Authorized");
        dropStock(responses, "403", "Not Allowed");
        boolean needsToken = operation.getSecurity() != null && !operation.getSecurity().isEmpty();
        if (needsToken && !responses.hasAPIResponse("401")) {
            responses.addAPIResponse("401", OASFactory.createAPIResponse()
                    .description("`unauthorized`: no token was sent, or it has expired or is not valid.")
                    .content(errorBody()));
        }
        if (responses.getDefaultValue() == null) {
            responses.setDefaultValue(OASFactory.createAPIResponse().ref(ERROR_RESPONSE));
        }
        // By status, with the shared response last, whatever order they were put in.
        Map<String, APIResponse> ordered = new TreeMap<>(
                Comparator.comparing((String code) -> code.equals(APIResponses.DEFAULT)).thenComparing(Comparator.naturalOrder()));
        ordered.putAll(responses.getAPIResponses());
        responses.setAPIResponses(new LinkedHashMap<>(ordered));
    }

    private static void dropStock(APIResponses responses, String code, String description) {
        APIResponse response = responses.getAPIResponse(code);
        if (response != null && response.getContent() == null && description.equals(response.getDescription())) {
            responses.removeAPIResponse(code);
        }
    }

    private static Content errorBody() {
        return OASFactory.createContent().addMediaType("application/json",
                OASFactory.createMediaType().schema(OASFactory.createSchema().ref(ERROR_SCHEMA)));
    }
}
