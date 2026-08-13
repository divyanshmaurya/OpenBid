package com.openbid.db;

/**
 * Thrown when an {@code UPDATE ... WHERE version = ?} matches zero rows.
 * Caught by {@link Database#inTransaction} so the whole unit of work rolls back.
 */
public class OptimisticLockException extends RuntimeException {

    public OptimisticLockException(String message) {
        super(message);
    }
}
