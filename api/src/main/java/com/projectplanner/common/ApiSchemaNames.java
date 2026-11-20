package com.projectplanner.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides what the schemas of the API reference are called. The scanner names a schema
 * after its class without the package, and puts a number behind the name when a second
 * class is called the same ({@code Result}, {@code Result1}), in an order nobody controls.
 * Here every schema is traced back to its class, gets the name given for that class or
 * else the plain class name, and two schemas that would end up with the same name stop
 * the build with a message that says which classes they are.
 */
final class ApiSchemaNames {

    /** A class of the application, with the names of its record components, enum constants or fields. */
    record Known(String className, Set<String> members) {

        String simpleName() {
            return className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
        }
    }

    private ApiSchemaNames() {
    }

    /**
     * @param schemas the name of every scanned schema with its property names or enum values
     * @param classes the classes of the application
     * @param given   the name a schema gets, by the qualified name of its class
     * @return the new name of every schema that is renamed, by the name it was scanned under
     */
    static Map<String, String> renames(Map<String, Set<String>> schemas, Collection<Known> classes, Map<String, String> given) {
        Map<String, List<Known>> bySimpleName = new HashMap<>();
        classes.forEach(known -> bySimpleName.computeIfAbsent(known.simpleName(), name -> new ArrayList<>()).add(known));
        for (String className : given.keySet()) {
            if (classes.stream().noneMatch(known -> known.className().equals(className))) {
                throw new IllegalStateException("The API reference has a schema name for " + className
                        + ", and there is no such class. Was it renamed? See SCHEMA_NAMES in ApiReferenceFilter.");
            }
        }

        // The class behind every schema that comes from a class of the application.
        Map<String, Known> owners = new TreeMap<>();
        schemas.forEach((scanned, members) -> {
            List<Known> candidates = bySimpleName.get(scanned);
            if (candidates == null) {
                candidates = bySimpleName.getOrDefault(scanned.replaceFirst("\\d+$", ""), List.of());
            } else if (candidates.size() == 1) {
                owners.put(scanned, candidates.getFirst());
                return;
            }
            List<Known> fitting = candidates.stream().filter(known -> known.members().equals(members)).toList();
            if (fitting.size() == 1) {
                owners.put(scanned, fitting.getFirst());
            } else if (candidates.size() > 1) {
                throw new IllegalStateException("The API reference cannot tell which class the schema " + scanned + " comes from: "
                        + names(candidates) + " are called the same, and their fields do not settle it. Give them names of their own"
                        + " in SCHEMA_NAMES in ApiReferenceFilter.");
            }
        });

        Map<String, List<Known>> wanted = new TreeMap<>();
        owners.forEach((scanned, owner) -> wanted
                .computeIfAbsent(given.getOrDefault(owner.className(), owner.simpleName()), name -> new ArrayList<>()).add(owner));
        wanted.forEach((name, sharing) -> {
            if (sharing.size() > 1) {
                throw new IllegalStateException(names(sharing) + " would all be called " + name + " in the API reference."
                        + " Give all but one of them a name of their own: add the class with its package to SCHEMA_NAMES in"
                        + " ApiReferenceFilter, or put @Schema(name = ...) on a record that is only there for the API.");
            }
            if (schemas.containsKey(name) && !owners.containsKey(name)) {
                throw new IllegalStateException(names(sharing) + " would be called " + name
                        + " in the API reference, and that name is taken by a schema that does not come from a class of the application.");
            }
        });

        Map<String, String> renames = new TreeMap<>();
        owners.forEach((scanned, owner) -> {
            String name = given.getOrDefault(owner.className(), owner.simpleName());
            if (!name.equals(scanned)) {
                renames.put(scanned, name);
            }
        });
        return renames;
    }

    private static String names(List<Known> classes) {
        return String.join(" and ", classes.stream().map(Known::className).sorted().toList());
    }
}
