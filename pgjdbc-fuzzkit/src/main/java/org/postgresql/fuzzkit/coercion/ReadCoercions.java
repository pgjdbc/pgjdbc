/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import static org.postgresql.fuzzkit.coercion.CoercionOutcome.CANNOT_COERCE;
import static org.postgresql.fuzzkit.coercion.CoercionOutcome.DATA_TYPE_MISMATCH;
import static org.postgresql.fuzzkit.coercion.CoercionOutcome.INVALID_PARAMETER_VALUE;
import static org.postgresql.fuzzkit.coercion.CoercionOutcome.NOT_IMPLEMENTED;
import static org.postgresql.fuzzkit.coercion.CoercionOutcome.OK;
import static org.postgresql.fuzzkit.coercion.CoercionOutcome.OK_OR_COERCE;

import org.postgresql.core.Oid;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Read-side JDBC coercion legality: which reader a PostgreSQL type accepts, over three coordinates.
 *
 * <ul>
 *   <li><b>Source</b> -- the SQL type, keyed by {@code OID}. OID rather than {@code JDBCType}: the
 *       latter collapses distinct types ({@code uuid}, {@code json}, {@code hstore}, geometric all map
 *       to {@code OTHER}; {@code text} and {@code varchar} both to {@code VARCHAR}), which "whole
 *       driver" cannot lose. {@code JDBCType} instead serves population (group by
 *       {@code getSqlTypeCode(oid)}, then override exceptions) and an optional spec-parity overlay.</li>
 *   <li><b>Target</b> -- a fixed reader ({@link Accessor}) or {@code readObject(Class)}.</li>
 *   <li><b>Surface</b> -- {@link Surface#SQL_INPUT} (SQLData/composite), {@link Surface#RESULT_SET}
 *       (top-level rows), or {@link Surface#CALLABLE_STATEMENT} (OUT parameters).</li>
 * </ul>
 *
 * <p>Value coercions are surface-independent: {@code getInt} and {@code readInt} both run the same
 * codec, so the shared table covers every surface. Surfaces diverge only on structural accessors -- a
 * small, enumerable set -- carried as per-surface overrides plus the per-surface unimplemented sets.
 *
 * <p>The outcome table records what pgjdbc does today (legacy behaviour); a matrix test locks it
 * against drift. A {@link ServerParity} overlay separately marks where PostgreSQL's cast rules
 * disagree -- documentation and a candidate list, never an assertion.
 *
 * <p>A populated type is <b>default-deny</b>: list only the readers and classes that succeed
 * ({@code OK} / {@code OK_OR_COERCE}); any other value reader or class refuses with
 * {@code DATA_TYPE_MISMATCH} by default (the state {@code Codec.cannotDecode} raises), which absorbs
 * the unbounded class space ({@code Date[]}, user types, ...) and plain type mismatches without
 * enumerating them. {@code readBoolean} is the one reader that defaults to {@code CANNOT_COERCE}
 * instead (it runs through {@code BooleanCoercion}). Two more exceptions to the default:
 * readers a surface does not implement refuse with {@code NOT_IMPLEMENTED} (per-surface unimplemented
 * set), and {@link #STRUCTURAL} readers (array, LOB, URL, SQLXML, ...) are connection-bound or
 * type-specific, so they stay {@code null} (unspecified) unless listed. A {@code null} lookup means
 * the fuzzer keeps the weak "no unchecked leak" invariant. Record an explicit refusal only when it is
 * interesting -- a non-default {@code SQLState} (for example {@code INVALID_PARAMETER_VALUE}) or a
 * parity note.
 *
 * <p>A fourth dimension is the <b>connection config</b>. The base is one {@link View}; each
 * {@link #connectionParam} view holds the cells a property switches on ({@code convertBooleanToNumeric}
 * flips bool's numeric readers; {@code prefersJavaTime} will switch the temporal default classes), and
 * the lookups layer matching views over the default. {@link #defaultObjectClass(int)} exposes the
 * class the no-arg {@code getObject} returns, which a property view can also change.
 */
public final class ReadCoercions {

  /** The API a value is read through. Most coercions behave identically across all three. */
  public enum Surface {
    SQL_INPUT, RESULT_SET, CALLABLE_STATEMENT
  }

  /** A fixed {@code SQLInput}/{@code ResultSet} reader. {@code readObject(Class)} is a separate axis. */
  public enum Accessor {
    READ_STRING, READ_BOOLEAN, READ_BYTE, READ_SHORT, READ_INT, READ_LONG, READ_FLOAT,
    READ_DOUBLE, READ_BIG_DECIMAL, READ_BYTES, READ_DATE, READ_TIME, READ_TIMESTAMP,
    READ_CHARACTER_STREAM, READ_ASCII_STREAM, READ_BINARY_STREAM, READ_OBJECT, READ_REF,
    READ_BLOB, READ_CLOB, READ_ARRAY, READ_URL, READ_NCLOB, READ_NSTRING, READ_SQLXML, READ_ROWID
  }

  /** How the driver's coercion relates to PostgreSQL's cast rules for the same type pair. */
  public enum ServerParity {
    /** pgjdbc performs the coercion; PostgreSQL rejects the corresponding cast. */
    PG_ALLOWS_SERVER_FORBIDS,
    /** pgjdbc refuses the coercion; PostgreSQL accepts the corresponding cast. */
    PG_FORBIDS_SERVER_ALLOWS
  }

  /** A recorded divergence between the driver and the server, with a short reason. */
  public static final class ServerDivergence {
    private final ServerParity parity;
    private final String note;

    ServerDivergence(ServerParity parity, String note) {
      this.parity = parity;
      this.note = note;
    }

    public ServerParity parity() {
      return parity;
    }

    public String note() {
      return note;
    }
  }

  /**
   * Connection-bound or type-specific readers. Default-deny does not apply to them: an unlisted cell
   * stays {@code null} rather than {@code CANNOT_COERCE}, because their outcome depends on the surface
   * and the connection (an array codec, a large object, an offline limitation), not on a value
   * coercion.
   */
  private static final Set<Accessor> STRUCTURAL = EnumSet.of(
      Accessor.READ_REF, Accessor.READ_BLOB, Accessor.READ_CLOB, Accessor.READ_ARRAY,
      Accessor.READ_URL, Accessor.READ_NCLOB, Accessor.READ_SQLXML, Accessor.READ_ROWID);

  /** Readers a surface rejects with {@code NOT_IMPLEMENTED} for every type. */
  private static final Map<Surface, Set<Accessor>> UNIMPLEMENTED = new EnumMap<>(Surface.class);

  /**
   * One config view of the table: the coercion cells, overlays and default-object metadata that hold
   * for a given connection configuration. {@link #DEFAULT} carries the base; each
   * {@link #connectionParam} view carries only the deltas that a property switches on.
   */
  static final class View {
    final Map<Integer, Map<Accessor, CoercionOutcome>> methods = new HashMap<>();
    final Map<Integer, Map<Class<?>, CoercionOutcome>> objects = new HashMap<>();
    final Map<Surface, Map<Integer, Map<Accessor, CoercionOutcome>>> methodOverrides =
        new EnumMap<>(Surface.class);
    final Map<Surface, Map<Integer, Map<Class<?>, CoercionOutcome>>> objectOverrides =
        new EnumMap<>(Surface.class);
    final Map<Integer, Map<Accessor, ServerDivergence>> methodDivergences = new HashMap<>();
    final Map<Integer, Map<Class<?>, ServerDivergence>> objectDivergences = new HashMap<>();
    final Map<Integer, Class<?>> defaultObjects = new HashMap<>();

    /** Records the outcome of a fixed reader. Chainable DSL used by the population lambdas. */
    void method(int oid, Accessor accessor, CoercionOutcome outcome) {
      methods.computeIfAbsent(oid, k -> new EnumMap<>(Accessor.class)).put(accessor, outcome);
    }

    /** Records the outcome of a {@code readObject(Class)} target. */
    void objectAs(int oid, Class<?> targetClass, CoercionOutcome outcome) {
      objects.computeIfAbsent(oid, k -> new HashMap<>()).put(targetClass, outcome);
    }

    /** Records a per-surface override of a {@code readObject(Class)} target. */
    void objectAsOverride(Surface surface, int oid, Class<?> targetClass, CoercionOutcome outcome) {
      objectOverrides.computeIfAbsent(surface, k -> new HashMap<>())
          .computeIfAbsent(oid, k -> new HashMap<>()).put(targetClass, outcome);
    }

    /** Records a server-parity note for a fixed reader. */
    void divergence(int oid, Accessor accessor, ServerParity parity, String note) {
      methodDivergences.computeIfAbsent(oid, k -> new EnumMap<>(Accessor.class))
          .put(accessor, new ServerDivergence(parity, note));
    }

    /** Records a server-parity note for a {@code readObject(Class)} target. */
    void divergenceAs(int oid, Class<?> targetClass, ServerParity parity, String note) {
      objectDivergences.computeIfAbsent(oid, k -> new HashMap<>())
          .put(targetClass, new ServerDivergence(parity, note));
    }

    /** Records the class the no-arg {@code readObject()} / {@code getObject()} returns for a type. */
    void defaultObject(int oid, Class<?> javaClass) {
      defaultObjects.put(oid, javaClass);
    }

    /** Scopes population to one type so its cells omit the repeated OID. */
    void oid(int oid, Consumer<TypeScope> populate) {
      populate.accept(new TypeScope(this, oid));
    }

    @Nullable CoercionOutcome lookupMethod(Surface surface, int oid, Accessor accessor) {
      CoercionOutcome override = nested(methodOverrides.get(surface), oid, accessor);
      if (override != null) {
        return override;
      }
      Map<Accessor, CoercionOutcome> row = methods.get(oid);
      return row == null ? null : row.get(accessor);
    }

    @Nullable CoercionOutcome lookupObject(Surface surface, int oid, Class<?> targetClass) {
      CoercionOutcome override = nested(objectOverrides.get(surface), oid, targetClass);
      if (override != null) {
        return override;
      }
      Map<Class<?>, CoercionOutcome> row = objects.get(oid);
      return row == null ? null : row.get(targetClass);
    }
  }

  /**
   * A population handle scoped to one type OID. Its methods drop the OID argument, so a type reads as
   * {@code oid(INT4, t -> { t.read(READ_INT, OK); t.object(Integer.class, OK); })}.
   */
  static final class TypeScope {
    private final View view;
    private final int oid;

    TypeScope(View view, int oid) {
      this.view = view;
      this.oid = oid;
    }

    TypeScope read(Accessor accessor, CoercionOutcome outcome) {
      view.method(oid, accessor, outcome);
      return this;
    }

    TypeScope object(Class<?> targetClass, CoercionOutcome outcome) {
      view.objectAs(oid, targetClass, outcome);
      return this;
    }

    TypeScope objectOverride(Surface surface, Class<?> targetClass, CoercionOutcome outcome) {
      view.objectAsOverride(surface, oid, targetClass, outcome);
      return this;
    }

    TypeScope divergence(Accessor accessor, ServerParity parity, String note) {
      view.divergence(oid, accessor, parity, note);
      return this;
    }

    TypeScope divergenceAs(Class<?> targetClass, ServerParity parity, String note) {
      view.divergenceAs(oid, targetClass, parity, note);
      return this;
    }

    TypeScope defaultObject(Class<?> javaClass) {
      view.defaultObject(oid, javaClass);
      return this;
    }
  }

  /** A view that applies only when a connection property holds a given value. */
  private static final class ParamView {
    final String param;
    final String value;
    final View view;

    ParamView(String param, String value, View view) {
      this.param = param;
      this.value = value;
      this.view = view;
    }

    boolean matches(Map<String, String> config) {
      return value.equals(config.get(param));
    }
  }

  private static final View DEFAULT = new View();
  private static final List<ParamView> PARAM_VIEWS = new ArrayList<>();
  private static final Map<String, String> EMPTY_CONFIG = Collections.emptyMap();

  static {
    // PgSQLInput throws NOT_IMPLEMENTED for these regardless of type.
    UNIMPLEMENTED.put(Surface.SQL_INPUT, EnumSet.of(
        Accessor.READ_REF, Accessor.READ_BLOB, Accessor.READ_CLOB, Accessor.READ_NCLOB,
        Accessor.READ_ROWID));
    defineIntegerFamily();
    defineFloatFamily();
    defineNumeric();
    defineBool();
    defineBytea();
    defineTextFamily();
    defineTemporalFamily();
    defineUuid();
    defineJsonFamily();
    defineBitFamily();
    defineInterval();
    defineGeometricFamily();
    defineConfigViews();
  }

  private ReadCoercions() {
  }

  /**
   * Returns the outcome pgjdbc produces for a fixed reader under the given connection config. Returns
   * {@code NOT_IMPLEMENTED} for a reader the surface does not implement, {@code CANNOT_COERCE} by
   * default-deny for an unlisted value reader once the type is populated, and {@code null} for an
   * unlisted structural reader or an unpopulated type. A property view overrides the default cell when
   * its property matches {@code config}.
   *
   * @param surface the reading API
   * @param oid the PostgreSQL type OID
   * @param accessor the reader method
   * @param config the connection properties that scope config-dependent cells
   * @return the expected outcome, or {@code null} if unspecified
   */
  public static @Nullable CoercionOutcome read(Surface surface, int oid, Accessor accessor,
      Map<String, String> config) {
    if (unimplemented(surface).contains(accessor)) {
      return NOT_IMPLEMENTED;
    }
    CoercionOutcome listed = DEFAULT.lookupMethod(surface, oid, accessor);
    boolean populated = DEFAULT.methods.containsKey(oid);
    for (ParamView pv : PARAM_VIEWS) {
      if (pv.matches(config)) {
        CoercionOutcome o = pv.view.lookupMethod(surface, oid, accessor);
        if (o != null) {
          listed = o;
        }
        if (pv.view.methods.containsKey(oid)) {
          populated = true;
        }
      }
    }
    if (listed != null) {
      return listed;
    }
    if (!populated) {
      return null;
    }
    if (STRUCTURAL.contains(accessor)) {
      return null;
    }
    // A decode failure carries DATA_TYPE_MISMATCH (Codec.cannotDecode). readBoolean is the exception:
    // it runs through BooleanCoercion, which refuses with CANNOT_COERCE.
    return accessor == Accessor.READ_BOOLEAN ? CANNOT_COERCE : DATA_TYPE_MISMATCH;
  }

  /** Reads under the default connection config. */
  public static @Nullable CoercionOutcome read(Surface surface, int oid, Accessor accessor) {
    return read(surface, oid, accessor, EMPTY_CONFIG);
  }

  /**
   * Returns the outcome pgjdbc produces for {@code readObject(targetClass)} /
   * {@code getObject(targetClass)} under the given connection config. Default-deny once the type is
   * populated, {@code null} only when the type is not populated at all.
   *
   * @param surface the reading API
   * @param oid the PostgreSQL type OID
   * @param targetClass the requested Java target class
   * @param config the connection properties that scope config-dependent cells
   * @return the expected outcome, or {@code null} if unspecified
   */
  public static @Nullable CoercionOutcome readObjectAs(Surface surface, int oid,
      Class<?> targetClass, Map<String, String> config) {
    CoercionOutcome listed = DEFAULT.lookupObject(surface, oid, targetClass);
    boolean populated = DEFAULT.objects.containsKey(oid);
    for (ParamView pv : PARAM_VIEWS) {
      if (pv.matches(config)) {
        CoercionOutcome o = pv.view.lookupObject(surface, oid, targetClass);
        if (o != null) {
          listed = o;
        }
        if (pv.view.objects.containsKey(oid)) {
          populated = true;
        }
      }
    }
    if (listed != null) {
      return listed;
    }
    // readObject(Class) always refuses through Codec.cannotDecode, so the default is DATA_TYPE_MISMATCH.
    return populated ? DATA_TYPE_MISMATCH : null;
  }

  /** Reads {@code readObject(Class)} under the default connection config. */
  public static @Nullable CoercionOutcome readObjectAs(Surface surface, int oid,
      Class<?> targetClass) {
    return readObjectAs(surface, oid, targetClass, EMPTY_CONFIG);
  }

  /**
   * Returns how the driver's fixed-reader coercion diverges from PostgreSQL's cast rules under the
   * default config, or {@code null} when it matches (or is unspecified). Divergences are recorded for
   * the default config; a property that flips a coercion also flips its parity.
   *
   * @param oid the PostgreSQL type OID
   * @param accessor the reader method
   * @return the recorded divergence, or {@code null} if the driver matches the server
   */
  public static @Nullable ServerDivergence serverDivergence(int oid, Accessor accessor) {
    Map<Accessor, ServerDivergence> row = DEFAULT.methodDivergences.get(oid);
    return row == null ? null : row.get(accessor);
  }

  /**
   * Returns how the driver's {@code readObject(Class)} coercion diverges from PostgreSQL's cast rules
   * under the default config, or {@code null} when it matches (or is unspecified).
   *
   * @param oid the PostgreSQL type OID
   * @param targetClass the requested Java target class
   * @return the recorded divergence, or {@code null} if the driver matches the server
   */
  public static @Nullable ServerDivergence serverDivergenceAs(int oid, Class<?> targetClass) {
    Map<Class<?>, ServerDivergence> row = DEFAULT.objectDivergences.get(oid);
    return row == null ? null : row.get(targetClass);
  }

  /**
   * Returns the class the no-arg {@code readObject()} / {@code getObject()} returns for a type under
   * the given config (the codec's default Java type), or {@code null} when unspecified. A property
   * view can change it -- for example {@code prefersJavaTime} switches the temporal types from
   * {@code java.sql.*} to {@code java.time.*}.
   *
   * @param oid the PostgreSQL type OID
   * @param config the connection properties that scope config-dependent metadata
   * @return the default Java class, or {@code null} if unspecified
   */
  public static @Nullable Class<?> defaultObjectClass(int oid, Map<String, String> config) {
    Class<?> result = DEFAULT.defaultObjects.get(oid);
    for (ParamView pv : PARAM_VIEWS) {
      if (pv.matches(config)) {
        Class<?> override = pv.view.defaultObjects.get(oid);
        if (override != null) {
          result = override;
        }
      }
    }
    return result;
  }

  /** Returns the default {@code getObject} class under the default connection config. */
  public static @Nullable Class<?> defaultObjectClass(int oid) {
    return defaultObjectClass(oid, EMPTY_CONFIG);
  }

  /**
   * The {@code readObject(Class)} target classes the default view lists for a type (an unmodifiable
   * view of its object row), or an empty set when the type has no object row. Backs
   * {@link PgTypeDescriptor#producedClasses()}, so the descriptor can derive its produced-class axis
   * without copying the table.
   *
   * @param oid the PostgreSQL type OID
   * @return the {@code readObject(Class)} target classes, never {@code null}
   */
  static Set<Class<?>> objectTargets(int oid) {
    Map<Class<?>, CoercionOutcome> row = DEFAULT.objects.get(oid);
    return row == null ? Collections.<Class<?>>emptySet()
        : Collections.unmodifiableSet(row.keySet());
  }

  /**
   * The union of every read-populated type's {@code readObject(Class)} targets under the default view --
   * the whole {@code readObject} target-class axis the read fuzzers exercise. Derived from the object
   * rows, so the fuzzer no longer keeps a hand-written class list; a type joining the read dictionary
   * widens the axis automatically. The result is unordered; the caller imposes a stable order.
   *
   * @return the union of all {@code readObject(Class)} target classes, never {@code null}
   */
  public static Set<Class<?>> allObjectTargets() {
    Set<Class<?>> union = new HashSet<>();
    for (Map<Class<?>, CoercionOutcome> row : DEFAULT.objects.values()) {
      union.addAll(row.keySet());
    }
    return Collections.unmodifiableSet(union);
  }

  /**
   * Whether the default view has any read row -- a fixed reader or a {@code readObject(Class)} target
   * -- for a type. Backs the descriptor type guard (G3): a descriptor whose OID is not populated here
   * could never be read back.
   *
   * @param oid the PostgreSQL type OID
   * @return whether the type is populated on the read side
   */
  static boolean isPopulated(int oid) {
    return DEFAULT.methods.containsKey(oid) || DEFAULT.objects.containsKey(oid);
  }

  /**
   * Registers a view scoped to a connection property. The {@code populate} lambda fills the view's
   * cells with the deltas that {@code param = value} switches on, via the same DSL the default
   * population uses ({@code v.method(...)}, {@code v.objectAs(...)}, {@code v.defaultObject(...)}).
   */
  private static void connectionParam(String param, String value, Consumer<View> populate) {
    View view = new View();
    populate.accept(view);
    PARAM_VIEWS.add(new ParamView(param, value, view));
  }

  private static Set<Accessor> unimplemented(Surface surface) {
    Set<Accessor> set = UNIMPLEMENTED.get(surface);
    return set == null ? Collections.emptySet() : set;
  }

  private static <K extends @NonNull Object> @Nullable CoercionOutcome nested(
      @Nullable Map<Integer, Map<K, CoercionOutcome>> bySurface, int oid, K key) {
    if (bySurface == null) {
      return null;
    }
    Map<K, CoercionOutcome> row = bySurface.get(oid);
    return row == null ? null : row.get(key);
  }

  /** Scopes default-view population to one type; the families below write through the {@link TypeScope}. */
  private static void oid(int oid, Consumer<TypeScope> populate) {
    DEFAULT.oid(oid, populate);
  }

  // ---------------------------------------------------------------------------------------------
  // Integer family: int2, int4, int8, oid. Sourced from Int2Codec/Int4Codec/Int8Codec/OidCodec,
  // NumberDecoders, and the BinaryCodec default readers. Temporal readers, byte[], and binary streams
  // are left to default-deny (DATA_TYPE_MISMATCH); the actual int->java.sql.Date leak is a known deviation.
  // ---------------------------------------------------------------------------------------------

  private static void defineIntegerFamily() {
    // int2: values fit int and short, so readInt/readShort never overflow; readByte truncates.
    integralValueReaders(Oid.INT2, Integer.class, OK, OK);
    integralObjectAs(Oid.INT2, OK, OK, OK_OR_COERCE);

    // int4: readInt fits; readShort/readByte truncate silently (lossy but never throw).
    integralValueReaders(Oid.INT4, Integer.class, OK, OK);
    integralObjectAs(Oid.INT4, OK, OK_OR_COERCE, OK_OR_COERCE);
    // Representative surface override: ResultSet.getObject(Blob.class) on a non-LOB column throws
    // INVALID_PARAMETER_VALUE instead of the default CANNOT_COERCE.
    oid(Oid.INT4, t -> t.objectOverride(Surface.RESULT_SET, Blob.class, INVALID_PARAMETER_VALUE));

    // int8: readInt/readShort/readByte range-check the long first, so they are value-dependent.
    integralValueReaders(Oid.INT8, Long.class, OK_OR_COERCE, OK_OR_COERCE);
    integralObjectAs(Oid.INT8, OK_OR_COERCE, OK_OR_COERCE, OK_OR_COERCE);
    oid(Oid.INT8, t -> t.object(BigInteger.class, OK));

    // oid: unsigned 32-bit as long. decodeAsInt reinterprets the 4 bytes without a range check, so
    // readInt never throws; the object-as whitelist is narrower than the signed integers.
    integralValueReaders(Oid.OID, Long.class, OK, OK);
    oidObjectAs(Oid.OID);
  }

  /**
   * Fixed value readers shared by the integer family. {@code intOutcome} covers {@code readInt},
   * {@code shortByteOutcome} covers {@code readShort}/{@code readByte} (they differ from
   * {@code readInt} only for {@code int8}, whose long is range-checked before the narrowing cast).
   */
  private static void integralValueReaders(int typeOid, Class<?> defaultClass,
      CoercionOutcome intOutcome, CoercionOutcome shortByteOutcome) {
    oid(typeOid, t -> {
      t.defaultObject(defaultClass);
      t.read(Accessor.READ_LONG, OK);
      t.read(Accessor.READ_INT, intOutcome);
      t.read(Accessor.READ_SHORT, shortByteOutcome);
      t.read(Accessor.READ_BYTE, shortByteOutcome);
      t.read(Accessor.READ_FLOAT, OK);
      t.read(Accessor.READ_DOUBLE, OK);
      t.read(Accessor.READ_BIG_DECIMAL, OK);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      // BooleanCoercion maps 0 and 1 and refuses the rest.
      t.read(Accessor.READ_BOOLEAN, OK_OR_COERCE);
      t.divergence(Accessor.READ_BOOLEAN, ServerParity.PG_ALLOWS_SERVER_FORBIDS,
          "PostgreSQL has no int-to-boolean cast; pgjdbc maps 0 and 1 and refuses the rest");
    });
  }

  /**
   * {@code readObject(Class)} whitelist for int2/int4/int8 via {@code NumberDecoders}. {@code Long},
   * {@code Double}, {@code Float}, {@code BigDecimal}, {@code String} and {@code Boolean} always
   * succeed; {@code Integer}/{@code Short}/{@code Byte} succeed or overflow with
   * {@code NUMERIC_VALUE_OUT_OF_RANGE}, so the caller passes their per-type outcome.
   */
  private static void integralObjectAs(int typeOid, CoercionOutcome integerOutcome,
      CoercionOutcome shortOutcome, CoercionOutcome byteOutcome) {
    oid(typeOid, t -> {
      t.object(Long.class, OK);
      t.object(Object.class, OK);
      t.object(Integer.class, integerOutcome);
      t.object(Short.class, shortOutcome);
      t.object(Byte.class, byteOutcome);
      t.object(Double.class, OK);
      t.object(Float.class, OK);
      t.object(BigDecimal.class, OK);
      t.object(String.class, OK);
      // Unlike readBoolean, decodeIntegralAs maps any non-zero value to true and never refuses.
      t.object(Boolean.class, OK);
      t.divergenceAs(Boolean.class, ServerParity.PG_ALLOWS_SERVER_FORBIDS,
          "PostgreSQL has no int-to-boolean cast; readObject(Boolean.class) maps value != 0");
    });
  }

  /**
   * {@code oid}'s {@code readObject(Class)} whitelist ({@code decodeOidAs}) is narrower than the
   * signed integers: no {@code Short}, {@code Byte}, {@code Float} or {@code Boolean}, and
   * {@code Integer} truncates without a range check. Because {@code readObject(Boolean.class)} refuses,
   * it matches the server here even though {@code readBoolean} does not.
   */
  private static void oidObjectAs(int typeOid) {
    oid(typeOid, t -> {
      t.object(Long.class, OK);
      t.object(Object.class, OK);
      t.object(Integer.class, OK);
      t.object(String.class, OK);
      t.object(Double.class, OK);
      t.object(BigDecimal.class, OK);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Float family: float4, float8. Sourced from Float4Codec/Float8Codec, NumberDecoders, and the
  // BinaryCodec default readers. Both types share one outcome table: the integer readers range-check
  // and the BigDecimal reader refuses NaN/Infinity, so every narrowing reader is value-dependent.
  // ---------------------------------------------------------------------------------------------

  private static void defineFloatFamily() {
    floatValueReaders(Oid.FLOAT4, Float.class);
    floatObjectAs(Oid.FLOAT4);
    floatValueReaders(Oid.FLOAT8, Double.class);
    floatObjectAs(Oid.FLOAT8);
  }

  /**
   * Fixed value readers shared by float4 and float8. {@code readFloat}/{@code readDouble} always
   * succeed; the integer readers range-check the value ({@code NUMERIC_VALUE_OUT_OF_RANGE} on
   * overflow) and {@code readBigDecimal} refuses NaN and Infinity, so all of them are value-dependent.
   */
  private static void floatValueReaders(int typeOid, Class<?> defaultClass) {
    oid(typeOid, t -> {
      t.defaultObject(defaultClass);
      t.read(Accessor.READ_FLOAT, OK);
      t.read(Accessor.READ_DOUBLE, OK);
      t.read(Accessor.READ_INT, OK_OR_COERCE);
      t.read(Accessor.READ_LONG, OK_OR_COERCE);
      t.read(Accessor.READ_SHORT, OK_OR_COERCE);
      t.read(Accessor.READ_BYTE, OK_OR_COERCE);
      t.read(Accessor.READ_BIG_DECIMAL, OK_OR_COERCE);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      t.read(Accessor.READ_BOOLEAN, OK_OR_COERCE);
      t.divergence(Accessor.READ_BOOLEAN, ServerParity.PG_ALLOWS_SERVER_FORBIDS,
          "PostgreSQL has no float-to-boolean cast; pgjdbc maps 0 and 1 and refuses the rest");
    });
  }

  /**
   * {@code readObject(Class)} whitelist for float4 and float8 ({@code decodeFloatAs} /
   * {@code decodeDoubleAs} plus {@code NumberDecoders.decodeFloatingAs}). Both floats and doubles
   * succeed; the integer targets and {@code Long} range-check, and {@code BigDecimal} refuses
   * NaN/Infinity, so those are value-dependent. {@code Boolean} always succeeds ({@code value != 0}).
   */
  private static void floatObjectAs(int typeOid) {
    oid(typeOid, t -> {
      t.object(Float.class, OK);
      t.object(Double.class, OK);
      t.object(Object.class, OK);
      t.object(String.class, OK);
      t.object(Long.class, OK_OR_COERCE);
      t.object(Integer.class, OK_OR_COERCE);
      t.object(Short.class, OK_OR_COERCE);
      t.object(Byte.class, OK_OR_COERCE);
      t.object(BigDecimal.class, OK_OR_COERCE);
      t.object(Boolean.class, OK);
      t.divergenceAs(Boolean.class, ServerParity.PG_ALLOWS_SERVER_FORBIDS,
          "PostgreSQL has no float-to-boolean cast; readObject(Boolean.class) maps value != 0");
    });
  }

  // ---------------------------------------------------------------------------------------------
  // numeric. Sourced from NumericCodec and the BinaryCodec default readers. NaN and +/-Infinity are
  // valid numeric values (PG14+) with no BigDecimal or integer form, which makes most readers
  // value-dependent and splits the no-arg readObject (keeps the Double sentinel) from the typed
  // readObject(Object.class)/readObject(String.class) (route through decodeAsBigDecimal, which refuses
  // NaN/Infinity with NUMERIC_VALUE_OUT_OF_RANGE).
  // ---------------------------------------------------------------------------------------------

  private static void defineNumeric() {
    oid(Oid.NUMERIC, t -> {
      t.defaultObject(BigDecimal.class);
      t.read(Accessor.READ_DOUBLE, OK);
      t.read(Accessor.READ_FLOAT, OK);
      t.read(Accessor.READ_INT, OK_OR_COERCE);
      t.read(Accessor.READ_LONG, OK_OR_COERCE);
      t.read(Accessor.READ_SHORT, OK_OR_COERCE);
      t.read(Accessor.READ_BYTE, OK_OR_COERCE);
      t.read(Accessor.READ_BIG_DECIMAL, OK_OR_COERCE);
      // decodeAsString formats the Double sentinel, so even NaN reads as the string "NaN".
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      // decodeBinary surfaces NaN/Infinity as a Double sentinel, unlike readObject(Object.class) below.
      t.read(Accessor.READ_OBJECT, OK);
      t.read(Accessor.READ_BOOLEAN, OK_OR_COERCE);
      t.divergence(Accessor.READ_BOOLEAN, ServerParity.PG_ALLOWS_SERVER_FORBIDS,
          "PostgreSQL has no numeric-to-boolean cast; pgjdbc maps 0 and 1 and refuses the rest");

      // Double and Float keep NaN/Infinity; every other target routes through decodeAsBigDecimal,
      // which refuses NaN/Infinity, so Object and String are value-dependent here (readObject is not).
      t.object(Double.class, OK);
      t.object(Float.class, OK);
      t.object(BigDecimal.class, OK_OR_COERCE);
      t.object(Object.class, OK_OR_COERCE);
      t.object(Long.class, OK_OR_COERCE);
      t.object(Integer.class, OK_OR_COERCE);
      t.object(Short.class, OK_OR_COERCE);
      t.object(Byte.class, OK_OR_COERCE);
      t.object(String.class, OK_OR_COERCE);
      t.object(Boolean.class, OK_OR_COERCE);
      t.divergenceAs(Boolean.class, ServerParity.PG_ALLOWS_SERVER_FORBIDS,
          "PostgreSQL has no numeric-to-boolean cast; readObject(Boolean.class) maps a finite value != 0");
    });
  }

  // ---------------------------------------------------------------------------------------------
  // bool. Sourced from BoolCodec. The numeric readers are gated by the convertBooleanToNumeric
  // property: with the default (off) they refuse with DATA_TYPE_MISMATCH; enabled, they return 1/0.
  // readObject(Class) has no such gate, so it maps bool to every numeric box unconditionally -- a
  // real asymmetry between the two axes.
  // ---------------------------------------------------------------------------------------------

  private static void defineBool() {
    oid(Oid.BOOL, t -> {
      t.defaultObject(Boolean.class);
      t.read(Accessor.READ_BOOLEAN, OK);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      // Numeric readers default-deny to DATA_TYPE_MISMATCH (default config); the convertBooleanToNumeric
      // view flips them to OK.
      t.divergence(Accessor.READ_INT, ServerParity.PG_FORBIDS_SERVER_ALLOWS,
          "PostgreSQL casts bool to int4 (0/1); pgjdbc refuses unless convertBooleanToNumeric is set");

      // No convertBooleanToNumeric gate here: decodeBoolAs maps bool to every numeric box and String.
      // (These broadly diverge from the server, which has only a bool->int4 cast; not per-class noted.)
      t.object(Boolean.class, OK);
      t.object(Object.class, OK);
      t.object(Integer.class, OK);
      t.object(Long.class, OK);
      t.object(Short.class, OK);
      t.object(Byte.class, OK);
      t.object(Double.class, OK);
      t.object(Float.class, OK);
      t.object(BigDecimal.class, OK);
      t.object(String.class, OK);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // bytea. Sourced from ByteaCodec and the BinaryCodec default readers. The byte and string readers
  // succeed; the numeric readers refuse with DATA_TYPE_MISMATCH (not the default CANNOT_COERCE), so
  // they are listed. readObject(Class) refuses other classes with CANNOT_COERCE -- a different state
  // from the fixed numeric readers.
  // ---------------------------------------------------------------------------------------------

  private static void defineBytea() {
    oid(Oid.BYTEA, t -> {
      t.defaultObject(byte[].class);
      t.read(Accessor.READ_BYTES, OK);
      t.read(Accessor.READ_BINARY_STREAM, OK);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      // Numeric readers default-deny to DATA_TYPE_MISMATCH; readBoolean to CANNOT_COERCE (BooleanCoercion).

      t.object(byte[].class, OK);
      t.object(Object.class, OK);
      t.object(String.class, OK);
      t.object(InputStream.class, OK);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Text family: text, varchar, bpchar, name, char. varchar/bpchar/name/char delegate their readers to
  // AbstractTextCodec, so all five share one matrix. String readers succeed; every numeric, boolean and
  // temporal reader parses the text, so it is value-dependent. The typed readObject(Date/Time/
  // Timestamp) parses cleanly via TemporalCodecs, while the fixed readDate/readTime/readTimestamp go
  // through java.sql.*.valueOf and leak an unchecked exception on malformed input (a known deviation).
  // ---------------------------------------------------------------------------------------------

  private static void defineTextFamily() {
    for (int typeOid : new int[]{Oid.TEXT, Oid.VARCHAR, Oid.BPCHAR, Oid.NAME, Oid.CHAR}) {
      textReaders(typeOid);
      textObjectAs(typeOid);
    }
  }

  private static void textReaders(int typeOid) {
    oid(typeOid, t -> {
      t.defaultObject(String.class);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      t.read(Accessor.READ_INT, OK_OR_COERCE);
      t.read(Accessor.READ_LONG, OK_OR_COERCE);
      t.read(Accessor.READ_SHORT, OK_OR_COERCE);
      t.read(Accessor.READ_BYTE, OK_OR_COERCE);
      t.read(Accessor.READ_FLOAT, OK_OR_COERCE);
      t.read(Accessor.READ_DOUBLE, OK_OR_COERCE);
      t.read(Accessor.READ_BIG_DECIMAL, OK_OR_COERCE);
      t.read(Accessor.READ_BOOLEAN, OK_OR_COERCE);
      t.read(Accessor.READ_DATE, OK_OR_COERCE);
      t.read(Accessor.READ_TIME, OK_OR_COERCE);
      t.read(Accessor.READ_TIMESTAMP, OK_OR_COERCE);
    });
  }

  private static void textObjectAs(int typeOid) {
    oid(typeOid, t -> {
      t.object(String.class, OK);
      t.object(Object.class, OK);
      t.object(Integer.class, OK_OR_COERCE);
      t.object(Long.class, OK_OR_COERCE);
      t.object(Short.class, OK_OR_COERCE);
      t.object(Byte.class, OK_OR_COERCE);
      t.object(Double.class, OK_OR_COERCE);
      t.object(Float.class, OK_OR_COERCE);
      t.object(BigDecimal.class, OK_OR_COERCE);
      t.object(Boolean.class, OK_OR_COERCE);
      t.object(Date.class, OK_OR_COERCE);
      t.object(Time.class, OK_OR_COERCE);
      t.object(Timestamp.class, OK_OR_COERCE);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Temporal family: date, time, timetz, timestamp, timestamptz. Sourced from DateCodec/TimeCodec/
  // TimetzCodec/TimestampCodec/TimestamptzCodec. The numeric readers refuse with DATA_TYPE_MISMATCH
  // (no time/date -> number coercion); readString formats the value. The readObject(Class) whitelist
  // is config-independent -- the codec accepts both the java.sql and the java.time targets regardless
  // of prefersJavaTime; only the no-arg default class changes (see the prefersJavaTime views). One
  // known deviation, not modelled as an outcome: the off-diagonal fixed readDate/readTime/readTimestamp
  // go through java.sql.*.valueOf and leak on the cross-type they are listed OK for.
  // ---------------------------------------------------------------------------------------------

  private static void defineTemporalFamily() {
    // date: getObject returns java.sql.Date. readDate is the diagonal; getTimestamp yields midnight.
    oid(Oid.DATE, t -> {
      temporalCommonReaders(t, Date.class);
      t.read(Accessor.READ_DATE, OK);
      t.read(Accessor.READ_TIMESTAMP, OK);
      t.object(Date.class, OK);
      t.object(Object.class, OK);
      t.object(LocalDate.class, OK);
      t.object(Timestamp.class, OK);
      t.object(java.util.Date.class, OK);
      t.object(Long.class, OK);
      t.object(String.class, OK);
    });

    // time: getObject returns java.sql.Time. getTimestamp anchors the time to 1970-01-01.
    oid(Oid.TIME, t -> {
      temporalCommonReaders(t, Time.class);
      t.read(Accessor.READ_TIME, OK);
      t.read(Accessor.READ_TIMESTAMP, OK);
      t.object(Time.class, OK);
      t.object(Object.class, OK);
      t.object(LocalTime.class, OK);
      t.object(Timestamp.class, OK);
      t.object(java.util.Date.class, OK);
      t.object(Long.class, OK);
      t.object(String.class, OK);
    });

    // timetz: getObject returns java.sql.Time. The Local* targets are rejected (they drop the zone).
    oid(Oid.TIMETZ, t -> {
      temporalCommonReaders(t, Time.class);
      t.read(Accessor.READ_TIME, OK);
      t.read(Accessor.READ_TIMESTAMP, OK);
      t.object(Time.class, OK);
      t.object(Object.class, OK);
      t.object(OffsetTime.class, OK);
      t.object(OffsetDateTime.class, OK);
      t.object(Timestamp.class, OK);
      t.object(java.util.Date.class, OK);
      t.object(Long.class, OK);
      t.object(String.class, OK);
    });

    // timestamp: getObject returns java.sql.Timestamp; getDate/getTime truncate to the date/time part.
    oid(Oid.TIMESTAMP, t -> {
      temporalCommonReaders(t, Timestamp.class);
      t.read(Accessor.READ_TIMESTAMP, OK);
      t.read(Accessor.READ_DATE, OK);
      t.read(Accessor.READ_TIME, OK);
      t.object(Timestamp.class, OK);
      t.object(Object.class, OK);
      t.object(LocalDateTime.class, OK);
      t.object(LocalDate.class, OK);
      t.object(OffsetDateTime.class, OK);
      t.object(ZonedDateTime.class, OK);
      t.object(Instant.class, OK);
      t.object(Date.class, OK);
      t.object(Time.class, OK);
      t.object(java.util.Date.class, OK);
      t.object(Long.class, OK);
      t.object(String.class, OK);
    });

    // timestamptz: getObject returns java.sql.Timestamp. The Local* targets are rejected (they drop
    // the zone the column carries).
    oid(Oid.TIMESTAMPTZ, t -> {
      temporalCommonReaders(t, Timestamp.class);
      t.read(Accessor.READ_TIMESTAMP, OK);
      t.read(Accessor.READ_DATE, OK);
      t.read(Accessor.READ_TIME, OK);
      t.object(Timestamp.class, OK);
      t.object(Object.class, OK);
      t.object(OffsetDateTime.class, OK);
      t.object(ZonedDateTime.class, OK);
      t.object(Instant.class, OK);
      t.object(Date.class, OK);
      t.object(Time.class, OK);
      t.object(java.util.Date.class, OK);
      t.object(Long.class, OK);
      t.object(String.class, OK);
    });
  }

  /** Numeric and string readers shared by every temporal type, plus the default {@code getObject} class. */
  private static void temporalCommonReaders(TypeScope t, Class<?> defaultClass) {
    t.defaultObject(defaultClass);
    // All numeric readers default-deny to DATA_TYPE_MISMATCH: the temporal codecs do not coerce to a
    // number, matching master and the server (there is no time/date -> number cast).
    t.read(Accessor.READ_STRING, OK);
    t.read(Accessor.READ_NSTRING, OK);
    t.read(Accessor.READ_CHARACTER_STREAM, OK);
    t.read(Accessor.READ_ASCII_STREAM, OK);
    t.read(Accessor.READ_OBJECT, OK);
  }

  // ---------------------------------------------------------------------------------------------
  // uuid, json, jsonb. Sourced from UuidCodec/JsonCodec/JsonbCodec. All three surface as a String or
  // their native object (UUID / PGobject) and refuse the numeric readers with DATA_TYPE_MISMATCH.
  // ---------------------------------------------------------------------------------------------

  private static void defineUuid() {
    oid(Oid.UUID, t -> {
      t.defaultObject(UUID.class);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      // Numeric readers default-deny to DATA_TYPE_MISMATCH.
      t.object(UUID.class, OK);
      t.object(Object.class, OK);
      t.object(String.class, OK);
    });
  }

  private static void defineJsonFamily() {
    jsonLike(Oid.JSON);
    jsonLike(Oid.JSONB);
  }

  private static void jsonLike(int typeOid) {
    oid(typeOid, t -> {
      t.defaultObject(PGobject.class);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      // Numeric readers default-deny to DATA_TYPE_MISMATCH.
      // decodeAsBoolean parses the JSON text as a boolean literal (true/false/1/0), else CANNOT_COERCE.
      t.read(Accessor.READ_BOOLEAN, OK_OR_COERCE);
      t.object(String.class, OK);
      t.object(PGobject.class, OK);
      t.object(Object.class, OK);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // bit, varbit (both BitCodec) and interval. bit/varbit surface as a PGobject bit string; readBoolean
  // parses bit(1) via BooleanTypeUtil and refuses a wider string. Their numeric readers are not
  // overridden, so they fall through the BinaryCodec defaults to DATA_TYPE_MISMATCH (default-deny).
  // interval surfaces as PGInterval and refuses the numeric readers with DATA_TYPE_MISMATCH too.
  // ---------------------------------------------------------------------------------------------

  private static void defineBitFamily() {
    bitLike(Oid.BIT);
    bitLike(Oid.VARBIT);
  }

  private static void bitLike(int typeOid) {
    oid(typeOid, t -> {
      t.defaultObject(PGobject.class);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      // BooleanTypeUtil parses bit(1) "1"/"0"; a wider bit string refuses with CANNOT_COERCE.
      t.read(Accessor.READ_BOOLEAN, OK_OR_COERCE);
      t.object(String.class, OK);
      t.object(Boolean.class, OK_OR_COERCE);
      t.object(PGobject.class, OK);
      t.object(Object.class, OK);
    });
  }

  private static void defineInterval() {
    oid(Oid.INTERVAL, t -> {
      t.defaultObject(PGInterval.class);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      // Numeric readers default-deny to DATA_TYPE_MISMATCH.
      t.object(PGInterval.class, OK);
      t.object(Object.class, OK);
      t.object(String.class, OK);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Geometric family: point, box, circle, line, lseg, path, polygon (PointCodec, BoxCodec, etc.).
  // Each surfaces as its PG* object or a String; readObject(Class) accepts only that object,
  // Object and PGobject.
  // Numeric readers default-deny to DATA_TYPE_MISMATCH, readBoolean to CANNOT_COERCE.
  // ---------------------------------------------------------------------------------------------

  private static void defineGeometricFamily() {
    geometricType(Oid.POINT, PGpoint.class);
    geometricType(Oid.BOX, PGbox.class);
    geometricType(Oid.CIRCLE, PGcircle.class);
    geometricType(Oid.LINE, PGline.class);
    geometricType(Oid.LSEG, PGlseg.class);
    geometricType(Oid.PATH, PGpath.class);
    geometricType(Oid.POLYGON, PGpolygon.class);
  }

  private static void geometricType(int typeOid, Class<?> javaType) {
    oid(typeOid, t -> {
      t.defaultObject(javaType);
      t.read(Accessor.READ_STRING, OK);
      t.read(Accessor.READ_NSTRING, OK);
      t.read(Accessor.READ_CHARACTER_STREAM, OK);
      t.read(Accessor.READ_ASCII_STREAM, OK);
      t.read(Accessor.READ_OBJECT, OK);
      t.object(javaType, OK);
      t.object(Object.class, OK);
      t.object(PGobject.class, OK);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Connection-property views. Each connectionParam block holds only the cells a property switches
  // on, layered over the default view at lookup time.
  // ---------------------------------------------------------------------------------------------

  private static void defineConfigViews() {
    // convertBooleanToNumeric: BoolCodec's numeric readers return 1/0 instead of refusing with
    // DATA_TYPE_MISMATCH. readObject(Class) has no such gate, so it is unchanged from the default.
    connectionParam("convertBooleanToNumeric", "true", v -> v.oid(Oid.BOOL, t -> {
      t.read(Accessor.READ_INT, OK);
      t.read(Accessor.READ_LONG, OK);
      t.read(Accessor.READ_SHORT, OK);
      t.read(Accessor.READ_BYTE, OK);
      t.read(Accessor.READ_FLOAT, OK);
      t.read(Accessor.READ_DOUBLE, OK);
      t.read(Accessor.READ_BIG_DECIMAL, OK);
    }));

    // prefersJavaTime switches the no-arg getObject default for a temporal type from java.sql.* to
    // java.time.*. The readObject(Class) whitelist is unchanged (the codec accepts both either way).
    connectionParam("prefersJavaTimeForDate", "true",
        v -> v.oid(Oid.DATE, t -> t.defaultObject(LocalDate.class)));
    connectionParam("prefersJavaTimeForTime", "true",
        v -> v.oid(Oid.TIME, t -> t.defaultObject(LocalTime.class)));
    connectionParam("prefersJavaTimeForTimetz", "true",
        v -> v.oid(Oid.TIMETZ, t -> t.defaultObject(OffsetTime.class)));
    connectionParam("prefersJavaTimeForTimestamp", "true",
        v -> v.oid(Oid.TIMESTAMP, t -> t.defaultObject(LocalDateTime.class)));
    connectionParam("prefersJavaTimeForTimestamptz", "true",
        v -> v.oid(Oid.TIMESTAMPTZ, t -> t.defaultObject(OffsetDateTime.class)));
  }
}
