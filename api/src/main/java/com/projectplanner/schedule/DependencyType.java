package com.projectplanner.schedule;

/**
 * How a successor waits for its predecessor. The first letter is the predecessor's
 * end that counts, the second the successor's: finish-to-start is "starts after the
 * predecessor finishes", start-to-start "starts when it starts", and so on.
 */
public enum DependencyType {
    /** Starts the working day after the predecessor is due, plus the lag. */
    FS,
    /** Starts no earlier than the predecessor starts, plus the lag. */
    SS,
    /** Is due no earlier than the predecessor is due, plus the lag. */
    FF,
    /** Is due no earlier than the predecessor starts, plus the lag. */
    SF;

    /** Whether the link holds the successor's finish rather than its start. */
    public boolean constrainsFinish() {
        return this == FF || this == SF;
    }

    /** Whether the link counts from the predecessor's start rather than its finish. */
    public boolean fromStart() {
        return this == SS || this == SF;
    }
}
