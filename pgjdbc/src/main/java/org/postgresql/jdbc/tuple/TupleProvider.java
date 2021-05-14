/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.tuple;

import org.postgresql.core.Provider;
import org.postgresql.core.Tuple;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Returns {@code non-null} tuples or {@code null} when no more rows exist.
 */
public interface TupleProvider extends Provider<@Nullable Tuple> {
}
