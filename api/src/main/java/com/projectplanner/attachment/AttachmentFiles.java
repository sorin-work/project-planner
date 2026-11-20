package com.projectplanner.attachment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;

import java.util.List;

/**
 * Ties files on disk to the transaction that writes or deletes their rows. A file whose
 * row is deleted goes once the deletion is committed, and not before: a request that
 * fails half way keeps its files with its rows. A file written for a row that is never
 * committed goes when the transaction is rolled back. Whatever is missed, because the
 * process stopped in between, is left to the sweep.
 */
@ApplicationScoped
public class AttachmentFiles {

    private final TransactionSynchronizationRegistry transactions;
    private final AttachmentStore store;

    public AttachmentFiles(TransactionSynchronizationRegistry transactions, AttachmentStore store) {
        this.transactions = transactions;
        this.store = store;
    }

    /** Deletes the files when the current transaction commits. */
    public void deleteAfterCommit(List<String> keys) {
        whenDone(keys, true);
    }

    /** Deletes the files when the current transaction does not commit. */
    public void deleteUnlessCommitted(List<String> keys) {
        whenDone(keys, false);
    }

    private void whenDone(List<String> keys, boolean onCommit) {
        if (keys.isEmpty()) {
            return;
        }
        List<String> copy = List.copyOf(keys);
        transactions.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
            }

            @Override
            public void afterCompletion(int status) {
                if ((status == Status.STATUS_COMMITTED) == onCommit) {
                    store.delete(copy);
                }
            }
        });
    }
}
