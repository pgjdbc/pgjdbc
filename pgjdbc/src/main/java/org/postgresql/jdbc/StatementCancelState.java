/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

/**
 * Represents {@link PgStatement#cancel()} state.
 */
enum StatementCancelState {
    IDLE,
    IN_QUERY,
    CANCELING,
    CANCELLED
}
