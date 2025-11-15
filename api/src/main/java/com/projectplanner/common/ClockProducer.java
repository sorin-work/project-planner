package com.projectplanner.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import io.quarkus.arc.DefaultBean;

import java.time.Clock;

/** A single clock so tests can pin "now" and "today". */
@ApplicationScoped
public class ClockProducer {

    @Produces
    @DefaultBean
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}
