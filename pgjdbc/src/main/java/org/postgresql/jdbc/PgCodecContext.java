/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.Experimental;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecContextBuilder;
import org.postgresql.api.codec.CodecLookup;
import org.postgresql.api.codec.IntervalStyle;
import org.postgresql.api.codec.PrefersJavaTime;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Encoding;
import org.postgresql.core.Oid;
import org.postgresql.core.TypeInfo;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Connection-bound implementation of {@link CodecContext}.
 *
 * <p>Beyond the public {@link CodecContext} surface, this class exposes the driver internals the
 * built-in container codecs and SQLData adapters need:</p>
 * <ul>
 *   <li>{@link TypeInfo} - PostgreSQL type metadata cache</li>
 *   <li>{@link CodecRegistry} - Codec lookup and registration</li>
 *   <li>{@link JavaTypeRegistry} - Java class to PostgreSQL type mappings</li>
 *   <li>{@link BaseConnection} and {@link Encoding}</li>
 * </ul>
 *
 * <p>Instances are immutable. Use {@link #withTypeMap(Map)} (and the other {@code with*} methods) to
 * derive a context with a different per-call setting.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class PgCodecContext implements CodecContext {

  /**
   * PostgreSQL's {@code FirstNormalObjectId}: OIDs below this are built-in catalog objects; at or
   * above it are user- or extension-defined.
   */
  private static final int FIRST_NORMAL_OBJECT_ID = 16384;

  private final @Nullable BaseConnection connection;
  private final @Nullable TypeInfo typeInfo;
  private final @Nullable CodecRegistry codecs;
  private final @Nullable JavaTypeRegistry javaTypes;
  // Offline (connectionless) type source: resolveType/resolveCodec consult it when typeInfo is null.
  // Empty for a connection-bound context, which resolves child types through typeInfo instead.
  private final Map<Integer, TypeDescriptor> typesByOid;
  private final Map<String, Class<?>> typeMap;
  private final @Nullable Encoding encoding;
  private final Charset charset;
  private final @Nullable TimestampUtils timestampUtils;

  // Per-call Calendar threaded by getDate/getTime/getTimestamp(col, Calendar) via
  // withCalendar(). Null means "use the connection default" (matching getObject,
  // which supplies no Calendar). Consumed synchronously within a single decode.
  private final @Nullable Calendar calendar;

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
   * Creates a new PgCodecContext from a connection with default preferences.
   *
   * @param connection the database connection
   * @param codecs the codec registry
   * @param javaTypes the Java type registry
   * @throws SQLException if the encoding cannot be retrieved
   */
  public PgCodecContext(BaseConnection connection, CodecRegistry codecs,
      JavaTypeRegistry javaTypes) throws SQLException {
    this(connection, codecs, javaTypes, Collections.emptyMap(),
        false, false, false, false, false, false);
  }

  /**
   * Creates a new PgCodecContext with date/time preferences.
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
  public PgCodecContext(BaseConnection connection, CodecRegistry codecs,
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
   * Creates a new PgCodecContext with a specific type map and date/time preferences.
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
  public PgCodecContext(BaseConnection connection, CodecRegistry codecs,
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
    this.typesByOid = Collections.emptyMap();
    this.typeMap = typeMap == null || typeMap.isEmpty()
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(
            IdentifierNormalizingTypeMap.of(typeMap, this.typeInfo));
    this.encoding = connection.getEncoding();
    this.charset = Charset.forName(encoding.name());
    this.timestampUtils = null;
    this.calendar = null;
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
  PgCodecContext(TimestampUtils timestampUtils, Charset charset,
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
  PgCodecContext(TimestampUtils timestampUtils, Charset charset,
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
    this.typesByOid = Collections.emptyMap();
    this.typeMap = Collections.emptyMap();
    this.encoding = null;
    this.charset = charset;
    this.timestampUtils = timestampUtils;
    this.calendar = null;
    this.prefersJavaTimeForDate = prefersJavaTimeForDate;
    this.prefersJavaTimeForTime = prefersJavaTimeForTime;
    this.prefersJavaTimeForTimetz = prefersJavaTimeForTimetz;
    this.prefersJavaTimeForTimestamp = prefersJavaTimeForTimestamp;
    this.prefersJavaTimeForTimestamptz = prefersJavaTimeForTimestamptz;
    this.convertBooleanToNumeric = convertBooleanToNumeric;
  }

  /**
   * Constructs a connectionless context for offline encoding and decoding. The wire settings come
   * from {@code timestampUtils} and {@code charset} rather than a live connection; {@code codecs}
   * resolves codecs by OID and {@code typesByOid} resolves child type descriptors. Built through
   * {@link OfflineBuilder}.
   */
  private PgCodecContext(TimestampUtils timestampUtils, Charset charset,
      CodecRegistry codecs, Map<Integer, TypeDescriptor> typesByOid,
      boolean prefersJavaTimeForDate,
      boolean prefersJavaTimeForTime,
      boolean prefersJavaTimeForTimetz,
      boolean prefersJavaTimeForTimestamp,
      boolean prefersJavaTimeForTimestamptz,
      boolean convertBooleanToNumeric) {
    this.connection = null;
    this.typeInfo = null;
    this.codecs = codecs;
    this.javaTypes = null;
    this.typesByOid = typesByOid;
    this.typeMap = Collections.emptyMap();
    // Derive a wire Encoding from the charset so the encoding readers (such as hstore) work offline;
    // getEncoding() would otherwise be null without a connection.
    this.encoding = Encoding.getJVMEncoding(charset.name());
    this.charset = charset;
    this.timestampUtils = timestampUtils;
    this.calendar = null;
    this.prefersJavaTimeForDate = prefersJavaTimeForDate;
    this.prefersJavaTimeForTime = prefersJavaTimeForTime;
    this.prefersJavaTimeForTimetz = prefersJavaTimeForTimetz;
    this.prefersJavaTimeForTimestamp = prefersJavaTimeForTimestamp;
    this.prefersJavaTimeForTimestamptz = prefersJavaTimeForTimestamptz;
    this.convertBooleanToNumeric = convertBooleanToNumeric;
  }

  /**
   * Returns a builder for a connectionless {@link CodecContext} that encodes and decodes offline.
   *
   * <p>Supply the wire settings (charset, time zone, integer-datetime mode), the
   * {@link CodecRegistry} that resolves codecs, and descriptors for any child types a container
   * would resolve. The result drives {@link org.postgresql.api.codec.Codecs#encode} and
   * {@link org.postgresql.api.codec.Codecs#decode} for scalar and temporal types with no
   * connection.</p>
   *
   * @return a new offline builder
   */
  static CodecContextBuilder offlineBuilder() {
    return new OfflineBuilder();
  }

  /**
   * Returns a fresh codec registry with the built-in codecs, viewed through the read-only
   * {@link CodecLookup} SPI. Package-private: {@link OfflineCodecs#defaultRegistry()} is the public
   * entry point.
   *
   * @return a new default codec registry
   */
  static CodecLookup newDefaultRegistry() {
    return new CodecRegistry();
  }

  /**
   * Returns a new PgCodecContext with the specified type map.
   *
   * <p>This is used for operations that accept a type map parameter,
   * such as {@code getArray(Map)} or {@code getObject(int, Map)}.</p>
   *
   * @param typeMap the new type map
   * @return a new PgCodecContext with the specified type map
   * @throws SQLException if the encoding cannot be retrieved
   */
  public PgCodecContext withTypeMap(Map<String, Class<?>> typeMap) throws SQLException {
    if (typeMap == null || typeMap.isEmpty()) {
      if (this.typeMap.isEmpty()) {
        return this;
      }
      typeMap = Collections.emptyMap();
    }
    // withTypeMap is only meaningful on a connection-backed context; the
    // test-only constructor produces a context with a null connection / null
    // registries. Reject calls on such a context rather than synthesizing a
    // partially-constructed copy.
    BaseConnection conn = connection;
    CodecRegistry registries = codecs;
    JavaTypeRegistry javaTypeReg = javaTypes;
    if (conn == null || registries == null || javaTypeReg == null) {
      throw Exceptions.withTypeMapNotSupportedConnectionless();
    }
    PgCodecContext copy = new PgCodecContext(conn, registries, javaTypeReg, typeMap,
        prefersJavaTimeForDate, prefersJavaTimeForTime, prefersJavaTimeForTimetz,
        prefersJavaTimeForTimestamp, prefersJavaTimeForTimestamptz, convertBooleanToNumeric);
    if (timestampUtils != null) {
      copy = copy.withTimestampUtils(timestampUtils);
    }
    return copy;
  }

  /**
   * Returns a new PgCodecContext that uses the given TimestampUtils instance
   * for date/time conversions. This is meant for callers like PgResultSet
   * that maintain a per-instance TimestampUtils (so timezone caching is
   * scoped to the result set rather than shared at the connection level).
   *
   * @param utils the TimestampUtils to use, or null to fall through to the
   *     connection-level default
   * @return a new PgCodecContext bound to {@code utils} for {@code usesDoubleDateTime()} and
   *     {@code getClientTimeZone()}
   */
  @SuppressWarnings("ReferenceEquality")
  public PgCodecContext withTimestampUtils(@Nullable TimestampUtils utils) {
    if (utils == this.timestampUtils) {
      return this;
    }
    return new PgCodecContext(this, utils);
  }

  /**
   * Copy constructor with a custom TimestampUtils.
   */
  private PgCodecContext(PgCodecContext source, @Nullable TimestampUtils utils) {
    this.connection = source.connection;
    this.typeInfo = source.typeInfo;
    this.codecs = source.codecs;
    this.javaTypes = source.javaTypes;
    this.typesByOid = source.typesByOid;
    this.typeMap = source.typeMap;
    this.encoding = source.encoding;
    this.charset = source.charset;
    this.timestampUtils = utils;
    this.calendar = source.calendar;
    this.prefersJavaTimeForDate = source.prefersJavaTimeForDate;
    this.prefersJavaTimeForTime = source.prefersJavaTimeForTime;
    this.prefersJavaTimeForTimetz = source.prefersJavaTimeForTimetz;
    this.prefersJavaTimeForTimestamp = source.prefersJavaTimeForTimestamp;
    this.prefersJavaTimeForTimestamptz = source.prefersJavaTimeForTimestamptz;
    this.convertBooleanToNumeric = source.convertBooleanToNumeric;
  }

  /**
   * Returns a new PgCodecContext carrying the supplied {@link Calendar} for the next
   * decode. {@code getDate/getTime/getTimestamp(col, Calendar)} use this to thread the
   * caller's Calendar to the temporal codecs without changing the codec method
   * signatures. The Calendar is borrowed, not copied: it is consumed synchronously
   * within a single decode, so the codecs stay stateless.
   *
   * @param cal the Calendar to use, or null for the connection default
   * @return a context that returns {@code cal} from {@link #getCalendar()}
   */
  @SuppressWarnings("ReferenceEquality")
  public PgCodecContext withCalendar(@Nullable Calendar cal) {
    if (cal == this.calendar) {
      return this;
    }
    return new PgCodecContext(this, cal);
  }

  /**
   * Returns a context with all {@code getObject} java.time preferences cleared, so the temporal
   * codecs yield {@code java.sql.Date}/{@code Time}/{@code Timestamp} rather than
   * {@code LocalDate}/{@code LocalTime}/… Used when decoding temporal <em>array</em> elements:
   * {@code getArray()} returns the SQL temporal types regardless of the per-getObject preferences,
   * matching the legacy array decoder. Returns {@code this} when no preference is set.
   *
   * @return a context that decodes temporal values as the {@code java.sql} types
   */
  @Override
  public PgCodecContext withoutJavaTimePreferences() {
    if (!prefersJavaTimeForDate && !prefersJavaTimeForTime && !prefersJavaTimeForTimetz
        && !prefersJavaTimeForTimestamp && !prefersJavaTimeForTimestamptz) {
      return this;
    }
    return new PgCodecContext(this);
  }

  /**
   * Copy constructor that clears the java.time {@code getObject} preferences.
   */
  private PgCodecContext(PgCodecContext source) {
    this.connection = source.connection;
    this.typeInfo = source.typeInfo;
    this.codecs = source.codecs;
    this.javaTypes = source.javaTypes;
    this.typesByOid = source.typesByOid;
    this.typeMap = source.typeMap;
    this.encoding = source.encoding;
    this.charset = source.charset;
    this.timestampUtils = source.timestampUtils;
    this.calendar = source.calendar;
    this.prefersJavaTimeForDate = false;
    this.prefersJavaTimeForTime = false;
    this.prefersJavaTimeForTimetz = false;
    this.prefersJavaTimeForTimestamp = false;
    this.prefersJavaTimeForTimestamptz = false;
    this.convertBooleanToNumeric = source.convertBooleanToNumeric;
  }

  /**
   * Copy constructor with a per-call Calendar.
   */
  private PgCodecContext(PgCodecContext source, @Nullable Calendar cal) {
    this.connection = source.connection;
    this.typeInfo = source.typeInfo;
    this.codecs = source.codecs;
    this.javaTypes = source.javaTypes;
    this.typesByOid = source.typesByOid;
    this.typeMap = source.typeMap;
    this.encoding = source.encoding;
    this.charset = source.charset;
    this.timestampUtils = source.timestampUtils;
    this.calendar = cal;
    this.prefersJavaTimeForDate = source.prefersJavaTimeForDate;
    this.prefersJavaTimeForTime = source.prefersJavaTimeForTime;
    this.prefersJavaTimeForTimetz = source.prefersJavaTimeForTimetz;
    this.prefersJavaTimeForTimestamp = source.prefersJavaTimeForTimestamp;
    this.prefersJavaTimeForTimestamptz = source.prefersJavaTimeForTimestamptz;
    this.convertBooleanToNumeric = source.convertBooleanToNumeric;
  }

  /**
   * Returns the underlying database connection.
   *
   * <p>Note: Prefer using specific accessors like {@link #getTypeInfo()},
   * {@link #getEncoding()}, etc. Direct connection access should be limited
   * to operations not available through PgCodecContext.</p>
   *
   * @return the database connection
   */
  public BaseConnection getConnection() {
    return castNonNull(connection,
        "PgCodecContext has no connection (constructed for unit testing only)");
  }

  /**
   * Returns the live connection, or fails with a clear message when this context is connectionless.
   *
   * <p>The container codecs build a connection-bound {@link PgArray} / {@link PgStruct} and call this
   * so an offline context reports the limitation instead of dereferencing a null connection (which
   * {@link #getConnection()} would do, since {@code castNonNull} is a no-op without assertions).
   * Offline encode and decode currently covers scalar and temporal types; materialising a container
   * value still needs a connection.</p>
   *
   * @param type the type being decoded, named in the error
   * @return the live connection
   * @throws SQLException if this context has no connection
   */
  public BaseConnection requireConnection(TypeDescriptor type) throws SQLException {
    BaseConnection conn = connection;
    if (conn == null) {
      throw Exceptions.cannotDecodeOffline(type.getFullName());
    }
    return conn;
  }

  /**
   * Returns whether this context is bound to a live connection, and therefore has
   * a {@link TypeInfo} and {@link CodecRegistry}. The unit-testing constructor
   * produces a context that is not connection-bound; callers that need the
   * registry or type cache must fall back when this returns {@code false}.
   *
   * @return true if connection-bound
   */
  public boolean isConnectionBound() {
    return connection != null;
  }

  /**
   * Returns the type information cache.
   *
   * @return the type info cache
   */
  public TypeInfo getTypeInfo() {
    return castNonNull(typeInfo,
        "PgCodecContext has no TypeInfo (constructed for unit testing only)");
  }

  /**
   * Returns the codec registry.
   *
   * @return the codec registry
   */
  public CodecRegistry getCodecs() {
    return castNonNull(codecs,
        "PgCodecContext has no CodecRegistry (constructed for unit testing only)");
  }

  /**
   * Returns the Java type registry.
   *
   * @return the Java type registry
   */
  public JavaTypeRegistry getJavaTypes() {
    return castNonNull(javaTypes,
        "PgCodecContext has no JavaTypeRegistry (constructed for unit testing only)");
  }

  /**
   * Resolves a child type by OID, loading the lazily-cached structure the container codecs read off
   * the descriptor: composite attributes ({@code pg_attribute}), the range subtype
   * ({@code pg_range.rngsubtype}), and the multirange's range type ({@code pg_range.rngtypid}) —
   * none of which {@code pg_type.typelem} carries. Other types resolve to the plain
   * {@link TypeInfo#getPgTypeByOid(int)} lookup.
   */
  @Override
  public TypeDescriptor resolveType(int oid) throws SQLException {
    TypeInfo ti = typeInfo;
    if (ti != null) {
      PgType type = ti.getPgTypeByOid(oid);
      if (type.isComposite() && type.getFields() == null) {
        // getFields caches the attributes on a new PgType; re-fetch so the descriptor carries them.
        ti.getFields(oid);
        type = ti.getPgTypeByOid(oid);
      } else if (type.getTyptype() == 'r' && type.getRangeSubtype() == Oid.UNSPECIFIED) {
        ti.getRangeSubtype(oid);
        type = ti.getPgTypeByOid(oid);
      } else if (type.getTyptype() == 'm' && type.getMultirangeRange() == Oid.UNSPECIFIED) {
        ti.getMultirangeRange(oid);
        type = ti.getPgTypeByOid(oid);
      }
      return type;
    }
    // Offline: consult the caller-supplied map first, then the driver's built-in type catalog (so
    // built-in scalar, temporal and array OIDs resolve without registration), then fail clearly.
    TypeDescriptor offline = typesByOid.get(oid);
    if (offline != null) {
      return offline;
    }
    PgType builtin = TypeInfoCache.getDefaultType(oid);
    if (builtin != null) {
      return builtin;
    }
    throw Exceptions.noOfflineTypeDescriptor(oid);
  }

  /**
   * Resolves the codec for a child type by OID. The lookup dispatches by type name and, failing
   * that, by {@code typtype}/{@code typcategory}; it does not need the descriptor's structure, so it
   * uses the plain {@link TypeInfo#getPgTypeByOid(int)} lookup rather than {@link #resolveType(int)}.
   */
  @Override
  public Codec resolveCodec(int oid) throws SQLException {
    TypeInfo ti = typeInfo;
    if (ti != null) {
      return getCodecs().getByOid(oid, ti.getPgTypeByOid(oid));
    }
    CodecRegistry registry = codecs;
    if (registry == null) {
      throw Exceptions.noCodecRegistry(oid);
    }
    // Offline: pass the caller-supplied descriptor, falling back to the built-in catalog, so the
    // registry can dispatch a container codec by typtype/typcategory for a built-in array/composite.
    TypeDescriptor pgType = typesByOid.get(oid);
    if (pgType == null) {
      pgType = TypeInfoCache.getDefaultType(oid);
    }
    return registry.getByOid(oid, pgType);
  }

  /**
   * Returns the current type map for custom type mappings.
   *
   * <p>The type map associates PostgreSQL type names with Java classes
   * (typically SQLData implementations).</p>
   *
   * @return the type map (never null, may be empty)
   */
  @Override
  public Map<String, Class<?>> getTypeMap() {
    return typeMap;
  }

  /**
   * Returns the connection's character encoding.
   *
   * @return the character encoding
   */
  public Encoding getEncoding() {
    return castNonNull(encoding,
        "PgCodecContext has no Encoding (constructed for unit testing only)");
  }

  /**
   * Returns the connection's character set.
   *
   * @return the character set
   */
  @Override
  public Charset getCharset() {
    return charset;
  }

  /**
   * Returns the backend's {@code IntervalStyle} (reported as a GUC_REPORT parameter status), so the
   * interval codec can render a binary {@code interval} the way the server would in text mode. An
   * offline (connectionless) context reports {@link IntervalStyle#POSTGRES}, the server default.
   *
   * @return the current interval style, never null
   */
  @Override
  public IntervalStyle getIntervalStyle() {
    BaseConnection c = connection;
    if (c == null) {
      return IntervalStyle.POSTGRES;
    }
    return IntervalStyle.fromServerValue(c.getParameterStatus("IntervalStyle"));
  }

  /**
   * Returns whether the backend uses doubles (rather than longs) for time values. Temporal codecs
   * read this to decode binary {@code time}/{@code timestamp} payloads.
   *
   * @return true if the backend uses {@code float8} timestamps
   */
  @Override
  public boolean usesDoubleDateTime() {
    TimestampUtils tu = timestampUtils;
    if (tu != null) {
      return tu.usesDouble();
    }
    return !getConnection().getQueryExecutor().getIntegerDateTimes();
  }

  /**
   * Returns the JVM default time zone used to decode/encode {@code date}/{@code time}/
   * {@code timestamp} (without time zone) when no per-call {@link Calendar} is supplied.
   *
   * @return the default time zone
   */
  @Override
  public TimeZone getDefaultTimeZone() {
    return TimeZone.getDefault();
  }

  /**
   * Returns the client/session time zone (the backend's {@code TimeZone} setting). Temporal codecs
   * use it to render binary {@code timetz}/{@code timestamptz} values as text the same way text
   * mode does.
   *
   * @return the client time zone
   */
  @Override
  public TimeZone getClientTimeZone() {
    TimestampUtils tu = timestampUtils;
    if (tu != null) {
      return tu.getClientTimeZone();
    }
    return castNonNull(getConnection().getQueryExecutor().getTimeZone(),
        "Backend timezone is not known");
  }

  /**
   * Returns the per-call Calendar set via {@link #withCalendar(Calendar)}, or null when
   * none was supplied (the connection default applies). Temporal codecs read this to honor the
   * {@code Calendar} passed to {@code getDate/getTime/getTimestamp(col, Calendar)}.
   *
   * @return the per-call Calendar, or null
   */
  @Override
  public @Nullable Calendar getCalendar() {
    return calendar;
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
  @Override
  public @Nullable Class<?> getMappedClass(String typeName) {
    Class<?> clazz = typeMap.get(typeName);
    if (clazz != null) {
      return clazz;
    }
    return getRegisteredClass(typeName);
  }

  /**
   * Returns the class the JDBC connection type map ({@link java.sql.Connection#setTypeMap}) assigns
   * to {@code type}, consulting the fully qualified name first and then the bare name.
   *
   * <p>Returns {@code null} when there is no entry, and always for a built-in type
   * (oid &lt; {@code FirstNormalObjectId}): the JDBC type map customizes only user-defined types, so
   * a stale or mistaken entry such as {@code {"varchar" -> Foo}} cannot hijack a built-in column.
   * The pgjdbc {@code addDataType} registry is not consulted here; see
   * {@link #getRegisteredClass(String)}.</p>
   *
   * @param type the type to look up
   * @return the mapped class, or {@code null} for no entry or a built-in type
   */
  @Nullable Class<?> getTypeMapClass(TypeDescriptor type) {
    if (type.getOid() < FIRST_NORMAL_OBJECT_ID) {
      return null;
    }
    Class<?> mapped = typeMap.get(type.getFullName());
    if (mapped == null) {
      mapped = typeMap.get(type.getTypeName().getName());
    }
    return mapped;
  }

  /**
   * Returns the class registered for {@code typeName} through {@code addDataType} (or the default
   * {@link JavaTypeRegistry} mapping), independent of the JDBC connection type map. Applies to
   * built-in and user-defined types alike.
   *
   * @param typeName the PostgreSQL type name
   * @return the registered class, or {@code null} if none
   */
  @Nullable Class<?> getRegisteredClass(String typeName) {
    JavaTypeRegistry javaTypeReg = javaTypes;
    return javaTypeReg == null ? null : javaTypeReg.getPGobject(typeName);
  }

  /**
   * Returns whether getObject() for DATE columns should return java.time.LocalDate.
   *
   * @return true if java.time is preferred for DATE
   */
  @Override
  public boolean prefersJavaTimeForDate() {
    return prefersJavaTimeForDate;
  }

  /**
   * Returns whether getObject() for TIME columns should return java.time.LocalTime.
   *
   * @return true if java.time is preferred for TIME
   */
  @Override
  public boolean prefersJavaTimeForTime() {
    return prefersJavaTimeForTime;
  }

  /**
   * Returns whether getObject() for TIMETZ columns should return java.time.OffsetTime.
   *
   * @return true if java.time is preferred for TIMETZ
   */
  @Override
  public boolean prefersJavaTimeForTimetz() {
    return prefersJavaTimeForTimetz;
  }

  /**
   * Returns whether getObject() for TIMESTAMP columns should return java.time.LocalDateTime.
   *
   * @return true if java.time is preferred for TIMESTAMP
   */
  @Override
  public boolean prefersJavaTimeForTimestamp() {
    return prefersJavaTimeForTimestamp;
  }

  /**
   * Returns whether getObject() for TIMESTAMPTZ columns should return java.time.OffsetDateTime.
   *
   * @return true if java.time is preferred for TIMESTAMPTZ
   */
  @Override
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
  @Override
  public boolean getConvertBooleanToNumeric() {
    return convertBooleanToNumeric;
  }

  /**
   * Builds a connectionless {@link CodecContext} for offline encoding and decoding. Obtain one from
   * {@link PgCodecContext#offlineBuilder()}.
   *
   * <p>Defaults: UTF-8, UTC, integer datetimes, a fresh {@link CodecRegistry} with the built-in
   * codecs, no {@code getObject} java.time preferences, and no boolean-to-numeric coercion.</p>
   */
  @Experimental("Codec API is experimental and may change in future releases")
  public static final class OfflineBuilder implements CodecContextBuilder {
    private Charset charset = StandardCharsets.UTF_8;
    private TimeZone timeZone = TimeZone.getTimeZone("UTC");
    private boolean integerDateTimes = true;
    private @Nullable CodecRegistry registry;
    private final Map<Integer, TypeDescriptor> typesByOid = new HashMap<>();
    private boolean prefersJavaTimeForDate;
    private boolean prefersJavaTimeForTime;
    private boolean prefersJavaTimeForTimetz;
    private boolean prefersJavaTimeForTimestamp;
    private boolean prefersJavaTimeForTimestamptz;
    private boolean convertBooleanToNumeric;

    private OfflineBuilder() {
    }

    /**
     * Sets the character set for text values. Defaults to UTF-8.
     *
     * @param charset the character set
     * @return this builder
     */
    @Override
    public OfflineBuilder charset(Charset charset) {
      this.charset = charset;
      return this;
    }

    /**
     * Sets the session time zone temporal codecs render {@code timetz}/{@code timestamptz} against.
     * Defaults to UTC.
     *
     * @param timeZone the session time zone
     * @return this builder
     */
    @Override
    public OfflineBuilder timeZone(TimeZone timeZone) {
      this.timeZone = timeZone;
      return this;
    }

    /**
     * Sets whether the backend encodes binary {@code time}/{@code timestamp} payloads as 64-bit
     * integers ({@code true}, the modern default) rather than doubles.
     *
     * @param integerDateTimes true for integer datetimes
     * @return this builder
     */
    @Override
    public OfflineBuilder integerDateTimes(boolean integerDateTimes) {
      this.integerDateTimes = integerDateTimes;
      return this;
    }

    /**
     * Sets the codec registry that resolves codecs by OID and name. Defaults to a fresh
     * {@link CodecRegistry} with the built-in codecs.
     *
     * @param registry the codec registry
     * @return this builder
     */
    @Override
    public OfflineBuilder registry(CodecLookup registry) {
      if (!(registry instanceof CodecRegistry)) {
        throw new IllegalArgumentException(
            "registry must be obtained from OfflineCodecs.defaultRegistry(); got "
                + (registry == null ? "null" : registry.getClass().getName()));
      }
      this.registry = (CodecRegistry) registry;
      return this;
    }

    /**
     * Registers {@code type} under its own OID so a container can resolve it as a child type.
     *
     * @param type the type descriptor
     * @return this builder
     */
    @Override
    public OfflineBuilder type(TypeDescriptor type) {
      this.typesByOid.put(type.getOid(), type);
      return this;
    }

    /**
     * Registers every descriptor in {@code types}, keyed by OID.
     *
     * @param types the type descriptors by OID
     * @return this builder
     */
    @Override
    public OfflineBuilder types(Map<Integer, ? extends TypeDescriptor> types) {
      this.typesByOid.putAll(types);
      return this;
    }

    /**
     * Sets the {@code getObject} java.time preferences, matching the per-type connection properties.
     * Each flag makes {@code decode(..., Object.class)} on that type yield the java.time class rather
     * than the {@code java.sql} one.
     *
     * @param prefers the per-type java.time preferences; build one with {@link PrefersJavaTime#builder()}
     * @return this builder
     */
    @Override
    public OfflineBuilder prefersJavaTime(PrefersJavaTime prefers) {
      this.prefersJavaTimeForDate = prefers.forDate();
      this.prefersJavaTimeForTime = prefers.forTime();
      this.prefersJavaTimeForTimetz = prefers.forTimetz();
      this.prefersJavaTimeForTimestamp = prefers.forTimestamp();
      this.prefersJavaTimeForTimestamptz = prefers.forTimestamptz();
      return this;
    }

    /**
     * Sets whether numeric getters on a {@code bool} value coerce it to {@code 1}/{@code 0} instead
     * of throwing.
     *
     * @param convertBooleanToNumeric true to enable the coercion
     * @return this builder
     */
    @Override
    public OfflineBuilder convertBooleanToNumeric(boolean convertBooleanToNumeric) {
      this.convertBooleanToNumeric = convertBooleanToNumeric;
      return this;
    }

    /**
     * Builds the connectionless context.
     *
     * @return a {@link CodecContext} that encodes and decodes without a connection
     */
    @Override
    public CodecContext build() {
      CodecRegistry codecs = registry != null ? registry : new CodecRegistry();
      TimeZone tz = timeZone;
      TimestampUtils timestampUtils = new TimestampUtils(!integerDateTimes, () -> tz);
      Map<Integer, TypeDescriptor> types = typesByOid.isEmpty()
          ? Collections.<Integer, TypeDescriptor>emptyMap()
          : Collections.unmodifiableMap(new HashMap<>(typesByOid));
      return new PgCodecContext(timestampUtils, charset, codecs, types,
          prefersJavaTimeForDate, prefersJavaTimeForTime, prefersJavaTimeForTimetz,
          prefersJavaTimeForTimestamp, prefersJavaTimeForTimestamptz, convertBooleanToNumeric);
    }
  }
}
