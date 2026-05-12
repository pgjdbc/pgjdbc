/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.Experimental;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Encoding;
import org.postgresql.core.TypeInfo;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;

/**
 * Immutable context passed to all codec operations.
 *
 * <p>CodecContext provides access to connection-scoped resources needed for encoding
 * and decoding PostgreSQL values:</p>
 * <ul>
 *   <li>{@link TypeInfo} - PostgreSQL type metadata cache</li>
 *   <li>{@link CodecRegistry} - Codec lookup and registration</li>
 *   <li>{@link JavaTypeRegistry} - Java class to PostgreSQL type mappings</li>
 *   <li>Connection settings (timezone, encoding)</li>
 *   <li>Type map for custom type mappings</li>
 * </ul>
 *
 * <p>CodecContext is immutable. Use {@link #withTypeMap(Map)} to create a new
 * context with a different type map for per-call customization.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class CodecContext {

  private final @Nullable BaseConnection connection;
  private final @Nullable TypeInfo typeInfo;
  private final @Nullable CodecRegistry codecs;
  private final @Nullable JavaTypeRegistry javaTypes;
  private final Map<String, Class<?>> typeMap;
  private final @Nullable Encoding encoding;
  private final Charset charset;
  private final @Nullable TimestampUtils timestampUtils;

  // Date/time type preferences (from connection properties)
  private final boolean prefersJavaTimeForDate;
  private final boolean prefersJavaTimeForTime;
  private final boolean prefersJavaTimeForTimetz;
  private final boolean prefersJavaTimeForTimestamp;
  private final boolean prefersJavaTimeForTimestamptz;

  // When true, getInt/Long/Float/Double/BigDecimal on a BOOL column converts
  // 't'/'f' (or binary 0/1) to 1/0 instead of throwing.
  private final boolean convertBooleanToNumeric;

  /**
   * Creates a new CodecContext from a connection with default preferences.
   *
   * @param connection the database connection
   * @param codecs the codec registry
   * @param javaTypes the Java type registry
   * @throws SQLException if the encoding cannot be retrieved
   */
  public CodecContext(BaseConnection connection, CodecRegistry codecs,
      JavaTypeRegistry javaTypes) throws SQLException {
    this(connection, codecs, javaTypes, Collections.emptyMap(),
        false, false, false, false, false, false);
  }

  /**
   * Creates a new CodecContext with date/time preferences.
   *
   * @param connection the database connection
   * @param codecs the codec registry
   * @param javaTypes the Java type registry
   * @param prefersJavaTimeForDate true if getObject(DATE) should return LocalDate
   * @param prefersJavaTimeForTime true if getObject(TIME) should return LocalTime
   * @param prefersJavaTimeForTimetz true if getObject(TIMETZ) should return OffsetTime
   * @param prefersJavaTimeForTimestamp true if getObject(TIMESTAMP) should return LocalDateTime
   * @param prefersJavaTimeForTimestamptz true if getObject(TIMESTAMPTZ) should return OffsetDateTime
   * @throws SQLException if the encoding cannot be retrieved
   */
  public CodecContext(BaseConnection connection, CodecRegistry codecs,
      JavaTypeRegistry javaTypes,
      boolean prefersJavaTimeForDate,
      boolean prefersJavaTimeForTime,
      boolean prefersJavaTimeForTimetz,
      boolean prefersJavaTimeForTimestamp,
      boolean prefersJavaTimeForTimestamptz) throws SQLException {
    this(connection, codecs, javaTypes, Collections.emptyMap(),
        prefersJavaTimeForDate, prefersJavaTimeForTime, prefersJavaTimeForTimetz,
        prefersJavaTimeForTimestamp, prefersJavaTimeForTimestamptz, false);
  }

  /**
   * Creates a new CodecContext with a specific type map and date/time preferences.
   *
   * @param connection the database connection
   * @param codecs the codec registry
   * @param javaTypes the Java type registry
   * @param typeMap the type map for custom mappings
   * @param prefersJavaTimeForDate true if getObject(DATE) should return LocalDate
   * @param prefersJavaTimeForTime true if getObject(TIME) should return LocalTime
   * @param prefersJavaTimeForTimetz true if getObject(TIMETZ) should return OffsetTime
   * @param prefersJavaTimeForTimestamp true if getObject(TIMESTAMP) should return LocalDateTime
   * @param prefersJavaTimeForTimestamptz true if getObject(TIMESTAMPTZ) should return OffsetDateTime
   * @param convertBooleanToNumeric true if numeric getters on a BOOL column convert 't'/'f' to 1/0
   * @throws SQLException if the encoding cannot be retrieved
   */
  public CodecContext(BaseConnection connection, CodecRegistry codecs,
      JavaTypeRegistry javaTypes, Map<String, Class<?>> typeMap,
      boolean prefersJavaTimeForDate,
      boolean prefersJavaTimeForTime,
      boolean prefersJavaTimeForTimetz,
      boolean prefersJavaTimeForTimestamp,
      boolean prefersJavaTimeForTimestamptz,
      boolean convertBooleanToNumeric) throws SQLException {
    this.connection = connection;
    this.typeInfo = connection.getTypeInfo();
    this.codecs = codecs;
    this.javaTypes = javaTypes;
    this.typeMap = typeMap == null || typeMap.isEmpty()
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(typeMap);
    this.encoding = connection.getEncoding();
    this.charset = Charset.forName(encoding.name());
    this.timestampUtils = null;
    this.prefersJavaTimeForDate = prefersJavaTimeForDate;
    this.prefersJavaTimeForTime = prefersJavaTimeForTime;
    this.prefersJavaTimeForTimetz = prefersJavaTimeForTimetz;
    this.prefersJavaTimeForTimestamp = prefersJavaTimeForTimestamp;
    this.prefersJavaTimeForTimestamptz = prefersJavaTimeForTimestamptz;
    this.convertBooleanToNumeric = convertBooleanToNumeric;
  }

  /**
   * Package-private constructor for unit testing without a database connection.
   *
   * @param timestampUtils the timestamp utilities
   * @param charset the character set
   * @param prefersJavaTimeForDate true if getObject(DATE) should return LocalDate
   * @param prefersJavaTimeForTime true if getObject(TIME) should return LocalTime
   * @param prefersJavaTimeForTimetz true if getObject(TIMETZ) should return OffsetTime
   * @param prefersJavaTimeForTimestamp true if getObject(TIMESTAMP) should return LocalDateTime
   * @param prefersJavaTimeForTimestamptz true if getObject(TIMESTAMPTZ) should return OffsetDateTime
   */
  CodecContext(TimestampUtils timestampUtils, Charset charset,
      boolean prefersJavaTimeForDate,
      boolean prefersJavaTimeForTime,
      boolean prefersJavaTimeForTimetz,
      boolean prefersJavaTimeForTimestamp,
      boolean prefersJavaTimeForTimestamptz) {
    this(timestampUtils, charset,
        prefersJavaTimeForDate, prefersJavaTimeForTime, prefersJavaTimeForTimetz,
        prefersJavaTimeForTimestamp, prefersJavaTimeForTimestamptz, false);
  }

  /**
   * Package-private constructor for unit testing without a database connection,
   * allowing the {@code convertBooleanToNumeric} flag to be configured.
   */
  CodecContext(TimestampUtils timestampUtils, Charset charset,
      boolean prefersJavaTimeForDate,
      boolean prefersJavaTimeForTime,
      boolean prefersJavaTimeForTimetz,
      boolean prefersJavaTimeForTimestamp,
      boolean prefersJavaTimeForTimestamptz,
      boolean convertBooleanToNumeric) {
    this.connection = null;
    this.typeInfo = null;
    this.codecs = null;
    this.javaTypes = null;
    this.typeMap = Collections.emptyMap();
    this.encoding = null;
    this.charset = charset;
    this.timestampUtils = timestampUtils;
    this.prefersJavaTimeForDate = prefersJavaTimeForDate;
    this.prefersJavaTimeForTime = prefersJavaTimeForTime;
    this.prefersJavaTimeForTimetz = prefersJavaTimeForTimetz;
    this.prefersJavaTimeForTimestamp = prefersJavaTimeForTimestamp;
    this.prefersJavaTimeForTimestamptz = prefersJavaTimeForTimestamptz;
    this.convertBooleanToNumeric = convertBooleanToNumeric;
  }

  /**
   * Returns a new CodecContext with the specified type map.
   *
   * <p>This is used for operations that accept a type map parameter,
   * such as {@code getArray(Map)} or {@code getObject(int, Map)}.</p>
   *
   * @param typeMap the new type map
   * @return a new CodecContext with the specified type map
   * @throws SQLException if the encoding cannot be retrieved
   */
  public CodecContext withTypeMap(Map<String, Class<?>> typeMap) throws SQLException {
    if (typeMap == null || typeMap.isEmpty()) {
      if (this.typeMap.isEmpty()) {
        return this;
      }
      return new CodecContext(connection, codecs, javaTypes, Collections.emptyMap(),
          prefersJavaTimeForDate, prefersJavaTimeForTime, prefersJavaTimeForTimetz,
          prefersJavaTimeForTimestamp, prefersJavaTimeForTimestamptz, convertBooleanToNumeric);
    }
    return new CodecContext(connection, codecs, javaTypes, typeMap,
        prefersJavaTimeForDate, prefersJavaTimeForTime, prefersJavaTimeForTimetz,
        prefersJavaTimeForTimestamp, prefersJavaTimeForTimestamptz, convertBooleanToNumeric);
  }

  /**
   * Returns the underlying database connection.
   *
   * <p>Note: Prefer using specific accessors like {@link #getTypeInfo()},
   * {@link #getEncoding()}, etc. Direct connection access should be limited
   * to operations not available through CodecContext.</p>
   *
   * @return the database connection
   */
  public BaseConnection getConnection() {
    return connection;
  }

  /**
   * Returns the type information cache.
   *
   * @return the type info cache
   */
  public TypeInfo getTypeInfo() {
    return typeInfo;
  }

  /**
   * Returns the codec registry.
   *
   * @return the codec registry
   */
  public CodecRegistry getCodecs() {
    return codecs;
  }

  /**
   * Returns the Java type registry.
   *
   * @return the Java type registry
   */
  public JavaTypeRegistry getJavaTypes() {
    return javaTypes;
  }

  /**
   * Returns the current type map for custom type mappings.
   *
   * <p>The type map associates PostgreSQL type names with Java classes
   * (typically SQLData implementations).</p>
   *
   * @return the type map (never null, may be empty)
   */
  public Map<String, Class<?>> getTypeMap() {
    return typeMap;
  }

  /**
   * Returns the connection's character encoding.
   *
   * @return the character encoding
   */
  public Encoding getEncoding() {
    return encoding;
  }

  /**
   * Returns the connection's character set.
   *
   * @return the character set
   */
  public Charset getCharset() {
    return charset;
  }

  /**
   * Returns the timestamp utilities for date/time conversions.
   *
   * @return the timestamp utils
   */
  @SuppressWarnings("deprecation")
  public TimestampUtils getTimestampUtils() {
    if (timestampUtils != null) {
      return timestampUtils;
    }
    return connection.getTimestampUtils();
  }

  /**
   * Returns the mapped Java class for a PostgreSQL type name, if any.
   *
   * <p>Checks the current type map first, then falls back to any
   * connection-level type mappings.</p>
   *
   * @param typeName the PostgreSQL type name
   * @return the mapped Java class, or null if not mapped
   */
  public @Nullable Class<?> getMappedClass(String typeName) {
    Class<?> clazz = typeMap.get(typeName);
    if (clazz != null) {
      return clazz;
    }
    return javaTypes.getPGobject(typeName);
  }

  /**
   * Returns whether getObject() for DATE columns should return java.time.LocalDate.
   *
   * @return true if java.time is preferred for DATE
   */
  public boolean prefersJavaTimeForDate() {
    return prefersJavaTimeForDate;
  }

  /**
   * Returns whether getObject() for TIME columns should return java.time.LocalTime.
   *
   * @return true if java.time is preferred for TIME
   */
  public boolean prefersJavaTimeForTime() {
    return prefersJavaTimeForTime;
  }

  /**
   * Returns whether getObject() for TIMETZ columns should return java.time.OffsetTime.
   *
   * @return true if java.time is preferred for TIMETZ
   */
  public boolean prefersJavaTimeForTimetz() {
    return prefersJavaTimeForTimetz;
  }

  /**
   * Returns whether getObject() for TIMESTAMP columns should return java.time.LocalDateTime.
   *
   * @return true if java.time is preferred for TIMESTAMP
   */
  public boolean prefersJavaTimeForTimestamp() {
    return prefersJavaTimeForTimestamp;
  }

  /**
   * Returns whether getObject() for TIMESTAMPTZ columns should return java.time.OffsetDateTime.
   *
   * @return true if java.time is preferred for TIMESTAMPTZ
   */
  public boolean prefersJavaTimeForTimestamptz() {
    return prefersJavaTimeForTimestamptz;
  }

  /**
   * Returns whether numeric getters on a BOOL column should convert {@code 't'}/{@code 'f'}
   * (or binary {@code 0}/{@code 1}) to {@code 1}/{@code 0} instead of throwing.
   *
   * <p>Controlled by the {@code convertBooleanToNumeric} connection property.</p>
   *
   * @return true if BOOL→numeric conversion is enabled
   */
  public boolean getConvertBooleanToNumeric() {
    return convertBooleanToNumeric;
  }
}
