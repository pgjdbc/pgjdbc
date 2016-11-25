/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.copy;

/**
 * Bidirectional via copy stream protocol. Via bidirectional copy protocol work PostgreSQL
 * replication.
 *
 * @see CopyIn
 * @see CopyOut
 */
public interface CopyDual extends CopyIn, CopyOut {
}
