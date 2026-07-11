/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.sql.SQLException;
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
   * Returns the backend's {@code IntervalStyle} (a GUC_REPORT parameter), which the interval codec
   * uses to render a binary {@code interval} the way the server would in text mode, so that
   * {@code getString} is independent of the wire format. Defaults to {@link IntervalStyle#POSTGRES}
   * when no connection is available (offline decoding) or the server does not report the setting.
   *
   * @return the current interval style, never null
   */
  default IntervalStyle getIntervalStyle() {
    return IntervalStyle.POSTGRES;
  }

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

  /**
   * Resolves a child type by OID so a container codec (array, composite, domain, range) can decode
   * its elements without reaching into the driver's type cache.
   *
   * <p>The returned descriptor is self-contained: composite attributes and the range subtype
   * ({@code pg_range.rngsubtype}, which {@code typelem} does not carry) are loaded so that
   * {@link TypeDescriptor#getFields()} and {@link TypeDescriptor#getRangeSubtype()} are populated.
   * Unknown OIDs resolve to a descriptor for the unknown type rather than null.</p>
   *
   * @param oid the PostgreSQL type OID
   * @return the resolved type descriptor
   * @throws SQLException if the type metadata cannot be loaded
   */
  TypeDescriptor resolveType(int oid) throws SQLException;

  /**
   * Resolves the codec registered for a child type by OID. Returns the fallback codec for an
   * unknown OID, so the result is never null.
   *
   * @param oid the PostgreSQL type OID
   * @return the codec for the type
   * @throws SQLException if the type metadata cannot be loaded
   */
  Codec resolveCodec(int oid) throws SQLException;

  /**
   * Resolves the binary codec for a child type by OID, or null when the registered codec does not
   * support the binary wire format.
   *
   * @param oid the PostgreSQL type OID
   * @return the binary codec, or null if the type has no binary codec
   * @throws SQLException if the type metadata cannot be loaded
   */
  default @Nullable BinaryCodec resolveBinaryCodec(int oid) throws SQLException {
    Codec codec = resolveCodec(oid);
    return codec instanceof BinaryCodec ? (BinaryCodec) codec : null;
  }

  /**
   * Resolves the text codec for a child type by OID, or null when the registered codec does not
   * support the text wire format.
   *
   * @param oid the PostgreSQL type OID
   * @return the text codec, or null if the type has no text codec
   * @throws SQLException if the type metadata cannot be loaded
   */
  default @Nullable TextCodec resolveTextCodec(int oid) throws SQLException {
    Codec codec = resolveCodec(oid);
    return codec instanceof TextCodec ? (TextCodec) codec : null;
  }

  /**
   * Returns a context with the {@code getObject} java.time preferences cleared, so temporal codecs
   * yield the {@code java.sql} types ({@code Date}/{@code Time}/{@code Timestamp}) rather than
   * {@code LocalDate}/{@code LocalTime}/… The array codec uses it when decoding temporal array
   * elements, which return the SQL types regardless of the per-{@code getObject} preferences.
   *
   * @return a context that decodes temporal values as the {@code java.sql} types, or {@code this}
   *     when no preference is set
   */
  CodecContext withoutJavaTimePreferences();
}
