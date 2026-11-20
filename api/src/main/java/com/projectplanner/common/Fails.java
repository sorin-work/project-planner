package com.projectplanner.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An error an endpoint answers with for a reason of its own, written down for the API
 * reference. {@link ApiReferenceFilter} turns these into the documented responses of
 * the operation. The errors that any endpoint can answer with ({@code validation_failed},
 * {@code unauthorized}, {@code forbidden}, {@code not_found}) are described once, in
 * {@code META-INF/openapi.yaml}.
 */
@Repeatable(Fails.List.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Fails {

    int status();

    /** The {@link ApiError#code()} of the answer. */
    String code();

    /** When it happens, as the rest of a sentence: "the link would close a loop". */
    String when();

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {
        Fails[] value();
    }
}
