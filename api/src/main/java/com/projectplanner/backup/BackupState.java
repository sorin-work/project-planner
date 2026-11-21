package com.projectplanner.backup;

/** What the status file of the backup service means at a moment. See {@link BackupStatus}. */
public enum BackupState {
    /** The newest good dump is at most twice the interval old, and the last run went well. */
    OK,
    /** The last run made its dump, but could not copy it off the machine. */
    COPY_FAILED,
    /** The newest good dump is older than twice the interval. */
    LATE,
    /**
     * The newest good dump is dated ahead of the clock, so how old it is cannot be told. It
     * stays so until a correctly dated dump has been made.
     */
    FUTURE_DATED,
    /** There has not been a good dump yet. */
    NO_GOOD_DUMP
}
