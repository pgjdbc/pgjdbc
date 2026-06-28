/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

/**
 * Per-operation settings a codec needs to encode or decode a value: wire encoding, the time zones
 * and {@link Calendar} that drive temporal conversion, the {@code getObject} type preferences, and
 * the custom type map.
 *
 * <p>A context is immutable and is supplied to every codec call. The surface here is the read-only
 * state a codec consumes; it deliberately exposes no connection, type cache, or codec registry, so
 * a codec written against this interface does not depend on the driver's internals.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface CodecContext {

  /**
   * Returns the connection's character set, used to encode and decode text values.
   *
   * @return the character set
   */
  Charset getCharset();

  /**
   * Returns whether the backend encodes binary {@code time}/{@code timestamp} payloads as doubles
   * rather than 64-bit integers. Temporal codecs read this to decode the binary form.
   *
   * @return true if the backend uses {@code float8} timestamps
   */
  boolean usesDoubleDateTime();

  /**
   * Returns the client/session time zone (the backend's {@code TimeZone} setting). Temporal codecs
   * use it to render binary {@code timetz}/{@code timestamptz} values the way text mode does.
   *
   * @return the client time zone
   */
  TimeZone getClientTimeZone();

  /**
   * Returns the JVM default time zone, used for {@code date}/{@code time}/{@code timestamp} (without
   * time zone) when no per-call {@link Calendar} is supplied.
   *
   * @return the default time zone
   */
  TimeZone getDefaultTimeZone();

  /**
   * Returns the per-call {@link Calendar} threaded from {@code getDate/getTime/getTimestamp(col,
   * Calendar)}, or null when none was supplied and the connection default applies. The Calendar is
   * borrowed and consumed within a single decode, so codecs stay stateless.
   *
   * @return the per-call Calendar, or null
   */
  @Nullable Calendar getCalendar();

  /**
   * Returns whether {@code getObject} on a DATE column should return {@link java.time.LocalDate}.
   *
   * @return true if java.time is preferred for DATE
   */
  boolean prefersJavaTimeForDate();

  /**
   * Returns whether {@code getObject} on a TIME column should return {@link java.time.LocalTime}.
   *
   * @return true if java.time is preferred for TIME
   */
  boolean prefersJavaTimeForTime();

  /**
   * Returns whether {@code getObject} on a TIMETZ column should return {@link java.time.OffsetTime}.
   *
   * @return true if java.time is preferred for TIMETZ
   */
  boolean prefersJavaTimeForTimetz();

  /**
   * Returns whether {@code getObject} on a TIMESTAMP column should return
   * {@link java.time.LocalDateTime}.
   *
   * @return true if java.time is preferred for TIMESTAMP
   */
  boolean prefersJavaTimeForTimestamp();

  /**
   * Returns whether {@code getObject} on a TIMESTAMPTZ column should return
   * {@link java.time.OffsetDateTime}.
   *
   * @return true if java.time is preferred for TIMESTAMPTZ
   */
  boolean prefersJavaTimeForTimestamptz();

  /**
   * Returns whether numeric getters on a BOOL column convert {@code 't'}/{@code 'f'} (or binary
   * {@code 0}/{@code 1}) to {@code 1}/{@code 0} instead of throwing. Controlled by the
   * {@code convertBooleanToNumeric} connection property.
   *
   * @return true if BOOL-to-numeric conversion is enabled
   */
  boolean getConvertBooleanToNumeric();

  /**
   * Returns the current type map, which associates PostgreSQL type names with Java classes
   * (typically {@link java.sql.SQLData} implementations).
   *
   * @return the type map (never null, may be empty)
   */
  Map<String, Class<?>> getTypeMap();

  /**
   * Returns the mapped Java class for a PostgreSQL type name, checking the type map first and then
   * any connection-level mappings.
   *
   * @param typeName the PostgreSQL type name
   * @return the mapped Java class, or null if not mapped
   */
  @Nullable Class<?> getMappedClass(String typeName);
}
