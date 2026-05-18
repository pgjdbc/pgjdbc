/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Classifies an API element (typically a {@link org.postgresql.PGProperty}
 * value) into one or more topical buckets. The documentation generator
 * uses these tags to render topical sub-tables on dedicated pages, for
 * example: an SSL/TLS page can embed only the SSL-tagged properties, a
 * fetch-tuning page only the fetch-tagged ones.
 *
 * <p>An element may carry multiple tags when it legitimately belongs in
 * several sections (for example, {@code channelBinding} is both
 * {@link Tag#SSL} and {@link Tag#AUTHENTICATION}).
 *
 * <p>{@code @Retention(CLASS)}: not visible at runtime; preserved in
 * bytecode for the docs generator.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @PgTags({PgTags.Tag.SSL, PgTags.Tag.AUTHENTICATION})
 * CHANNEL_BINDING("channelBinding", "prefer", ...);
 * }</pre>
 *
 * @see PgApi
 * @see PgPropertyType
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface PgTags {

  /**
   * One or more topical tags applicable to the annotated element.
   */
  Tag[] value();

  /**
   * Canonical set of topical buckets used to classify connection
   * properties and other documented elements. The set is intentionally
   * small and curated; adding a new value is a deliberate change to the
   * project's information architecture.
   */
  enum Tag {

    /** SSL/TLS transport. */
    SSL,

    /** Authentication mechanism selection and configuration. */
    AUTHENTICATION,

    /** Kerberos / GSSAPI / SSPI authentication. */
    KERBEROS_GSS,

    /** Connection identity and startup parameters. */
    CONNECTION,

    /** Time-bounded operations. */
    TIMEOUT,

    /** TCP and socket tuning. */
    NETWORK,

    /** Multi-host fail-over and load balancing. */
    FAILOVER,

    /** ResultSet fetching behavior. */
    FETCH,

    /** Server-side prepared statement caching and query mode. */
    PREPARED_STATEMENTS,

    /** Batch execution. */
    BATCH,

    /** Binary protocol transfer. */
    BINARY_TRANSFER,

    /** {@code DatabaseMetaData} caching and shape. */
    METADATA,

    /** PostgreSQL type-handling tweaks. */
    TYPE_HANDLING,

    /** Streaming / logical replication. */
    REPLICATION,

    /** Driver logging knobs. */
    LOGGING,

    /** Transaction semantics and savepoint handling. */
    TRANSACTION,

    /** Backwards-compatibility shims. */
    COMPATIBILITY,

    /**
     * Cross-cutting marker for properties that platform / SRE / security
     * teams tune at deployment time, as distinct from properties an
     * application developer picks while writing code.
     *
     * <p>This is a SECONDARY tag — a property carries it in addition to
     * its primary categorisation, not instead of it. Topical pages
     * (timeouts, ssl, etc.) still surface these properties through
     * their primary tag; the OPERATIONS tag exists so a single
     * "operations cheat sheet" page can render the full operational
     * surface in one place without duplicating content elsewhere.
     */
    OPERATIONS,
  }
}
