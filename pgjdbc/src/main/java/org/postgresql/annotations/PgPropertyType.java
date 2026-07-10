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
 * Declares the semantic data type of a connection-property value. The
 * documentation generator emits this in the property reference table so
 * that readers can see at a glance whether a numeric default like
 * {@code "5000"} represents milliseconds, seconds, or bytes — distinctions
 * that pgjdbc has historically left implicit and that have caused real
 * user errors.
 *
 * <p>For many properties the type is inferrable from the default value or
 * the {@code choices} array (e.g. default {@code "true"} implies
 * {@link Kind#BOOLEAN}, non-empty choices imply {@link Kind#ENUM}). The
 * documentation generator uses inference as a hint and only requires an
 * explicit {@code @PgPropertyType} when inference is ambiguous — notably for
 * numeric properties whose unit must be explicit
 * ({@link Kind#DURATION_SECONDS} vs {@link Kind#DURATION_MILLIS},
 * {@link Kind#SIZE_BYTES} vs {@link Kind#SIZE_EXPRESSION}).
 *
 * <p>{@code @Retention(CLASS)}: not visible at runtime; preserved in
 * bytecode for the docs generator.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * @PgPropertyType(PgPropertyType.Kind.DURATION_SECONDS)
 * CONNECT_TIMEOUT("connectTimeout", "10", ...);
 *
 * @PgPropertyType(PgPropertyType.Kind.SIZE_BYTES)
 * MAX_SEND_BUFFER_SIZE("maxSendBufferSize", "8192", ...);
 * }</pre>
 *
 * @see PgApi
 * @see PgTags
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface PgPropertyType {

  /**
   * Semantic kind of the value carried by the annotated property.
   */
  Kind value();

  /**
   * Value-shape of a connection property. Distinguishes Java types
   * where they map cleanly, and adds unit-bearing variants for numeric
   * values whose interpretation requires more than a raw type.
   */
  enum Kind {

    /** {@code "true"} or {@code "false"}. */
    BOOLEAN,

    /** Plain integer, no unit. */
    INT,

    /** Plain long, no unit. */
    LONG,

    /** Free-form string. */
    STRING,

    /** One of a fixed set of string literals; the legal set is given
     *  by the {@code choices} parameter of the property constructor. */
    ENUM,

    /** Fully-qualified class name resolved via {@link Class#forName}. */
    CLASS,

    /** Integer count of seconds. The driver internally converts to
     *  milliseconds where the underlying API demands it. */
    DURATION_SECONDS,

    /** Integer count of milliseconds. */
    DURATION_MILLIS,

    /** Integer count of bytes, no unit suffix. */
    SIZE_BYTES,

    /** Size expression with an optional unit suffix
     *  (e.g. {@code "150M"}, {@code "1G"}). Distinct from
     *  {@link #SIZE_BYTES} because the value is parsed as a string at
     *  runtime, not as a raw integer. */
    SIZE_EXPRESSION,
  }
}
