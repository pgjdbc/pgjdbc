/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

/**
 * pgjdbc-specific annotations carrying metadata about driver API elements.
 *
 * <p>The annotations describe properties of API elements — most commonly
 * individual {@link org.postgresql.PGProperty} values — that are not
 * naturally expressible in plain Java code: lifecycle status, topical
 * categorisation, and unit-aware data types.
 *
 * <ul>
 *   <li>{@link org.postgresql.annotations.PgApi} — stability, version
 *       introduced, and version of last status transition.
 *   <li>{@link org.postgresql.annotations.PgTags} — topical buckets used
 *       to group related properties.
 *   <li>{@link org.postgresql.annotations.PgPropertyType} — semantic value
 *       kind, including unit-bearing numeric variants (seconds, bytes,
 *       etc.).
 * </ul>
 *
 * <p>All annotations in this package use {@link
 * java.lang.annotation.RetentionPolicy#CLASS} so they are visible to
 * bytecode-level readers without being exposed at runtime through
 * reflection.
 *
 * <p>The Pg-prefixed names exist to avoid collisions with widely-used
 * annotation libraries that publish simpler names (notably JUnit Jupiter
 * via {@code org.apiguardian.api.API}). A user typing {@code @Pg…} in
 * their IDE sees only pgjdbc annotations.
 */
package org.postgresql.annotations;
