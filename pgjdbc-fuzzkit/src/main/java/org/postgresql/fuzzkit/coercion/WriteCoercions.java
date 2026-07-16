/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import static org.postgresql.fuzzkit.coercion.CoercionOutcome.CANNOT_COERCE;
import static org.postgresql.fuzzkit.coercion.CoercionOutcome.INVALID_PARAMETER_TYPE;
import static org.postgresql.fuzzkit.coercion.CoercionOutcome.OK;
import static org.postgresql.fuzzkit.coercion.CoercionOutcome.OK_OR_COERCE;

import org.postgresql.core.Oid;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Write-side JDBC coercion legality: which Java class a PostgreSQL type accepts when encoding.
 *
 * <p>This is the mirror of {@link ReadCoercions#readObjectAs}. Every driver write funnels through the
 * codec's {@code encodeText(Object, ...)} / {@code encodeBinary(Object, ...)}, so the outcome depends
 * only on the target type OID and the runtime Java class of the value; the {@code SQLOutput} /
 * {@code PreparedStatement} method matters only in which class it presents. That collapses the whole
 * write matrix to one lookup, {@link #encode}, keyed by {@code (oid, sourceClass)} -- no typed-vs-free
 * split like the read side needs.
 *
 * <p>The refusal states differ from the read side. A populated type is <b>default-deny</b>: an
 * unlisted source class refuses with {@code INVALID_PARAMETER_TYPE} (the state {@code Codec.cannotEncode}
 * raises), the write-side mirror of {@code DATA_TYPE_MISMATCH}. A listed cell is {@code OK} (accepted
 * for every value) or {@code OK_OR_COERCE} (value-dependent: overflow, parse failure, or a non-0/1
 * boolean refuses with a value-level state). An unpopulated type stays {@code null} (unspecified).
 *
 * <p>The {@link Surface} axis is carried for parity with {@link ReadCoercions}: {@code setInt} and
 * {@code writeInt} run the same codec encoder, so value coercions are surface-independent. Two write
 * concerns are <em>not</em> in this table: the {@code NOT_IMPLEMENTED} writers ({@code writeRef} and
 * friends), which the driver rejects before reaching a codec and so are keyed by method, not class;
 * and the {@code setObject(x, SQLType)} conversion legality, which is a separate {@code (class, SQLType)}
 * axis (the JDBC setObject table) and belongs in its own registry.
 */
public final class WriteCoercions {

  /** The API a value is written through. Value coercions behave identically across all four. */
  public enum Surface {
    SQL_OUTPUT, PREPARED_STATEMENT, UPDATABLE_RESULT_SET, CALLABLE_STATEMENT
  }

  /**
   * The canonical set of {@code SQLOutput} write methods, the write-side mirror of
   * {@link ReadCoercions.Accessor}. Each method carries the three facts a write oracle and its value
   * generator need without knowing which surface runs it: the Java class the method accepts at its
   * entry point ({@code inputClass}), the class it presents to the codec's encoder (what
   * {@link WriteCoercions#encode} keys on), and whether it refuses with {@code NOT_IMPLEMENTED} before
   * a codec sees a value.
   *
   * <p>Input and presented class differ for the widening and reducing methods. {@code writeByte} and
   * {@code writeShort} accept {@code Byte}/{@code Short} but widen to {@code Integer}; {@code writeURL}
   * accepts a {@code URL} but reduces to its {@code String} form; the character streams accept a
   * {@code String}, the binary stream a {@code byte[]}. {@link #WRITE_OBJECT_AS} is the free-class
   * axis -- it accepts and presents the value's own class, the only way to reach a class no typed
   * writer produces, so its input class is unfixed. The {@code NOT_IMPLEMENTED} methods
   * ({@code writeRef} and friends) reject before a codec runs, so they accept and present no class.
   *
   * <p>The invocation itself -- the actual {@code java.sql} call -- is not held here; it lives in the
   * surface binding, just as the read-side calls live in the reader binding rather than in
   * {@link ReadCoercions.Accessor}.
   */
  public enum Method {
    WRITE_INT(Integer.class, Integer.class, false),
    WRITE_LONG(Long.class, Long.class, false),
    WRITE_FLOAT(Float.class, Float.class, false),
    WRITE_DOUBLE(Double.class, Double.class, false),
    WRITE_BOOLEAN(Boolean.class, Boolean.class, false),
    WRITE_STRING(String.class, String.class, false),
    WRITE_N_STRING(String.class, String.class, false),
    WRITE_CHARACTER_STREAM(String.class, String.class, false),
    WRITE_ASCII_STREAM(String.class, String.class, false),
    WRITE_BIG_DECIMAL(BigDecimal.class, BigDecimal.class, false),
    WRITE_BYTES(byte[].class, byte[].class, false),
    WRITE_BINARY_STREAM(byte[].class, byte[].class, false),
    WRITE_DATE(Date.class, Date.class, false),
    WRITE_TIME(Time.class, Time.class, false),
    WRITE_TIMESTAMP(Timestamp.class, Timestamp.class, false),
    // Accept Byte/Short but widen to Integer via encodeInt, so the presented class is Integer.
    WRITE_BYTE(Byte.class, Integer.class, false),
    WRITE_SHORT(Short.class, Integer.class, false),
    // Accept a URL but reduce to its string form, so the presented class is String.
    WRITE_URL(URL.class, String.class, false),
    // The free-class axis: accepts and presents the value's own class, the mirror of readObject(Class).
    WRITE_OBJECT_AS(null, null, false),
    WRITE_REF(null, null, true),
    WRITE_BLOB(null, null, true),
    WRITE_CLOB(null, null, true),
    WRITE_NCLOB(null, null, true),
    WRITE_ROWID(null, null, true);

    private final @Nullable Class<?> inputClass;
    private final @Nullable Class<?> fixedPresentedClass;
    private final boolean notImplemented;

    Method(@Nullable Class<?> inputClass, @Nullable Class<?> fixedPresentedClass,
        boolean notImplemented) {
      this.inputClass = inputClass;
      this.fixedPresentedClass = fixedPresentedClass;
      this.notImplemented = notImplemented;
    }

    /**
     * The Java class this method accepts at its {@code SQLOutput}/{@code PreparedStatement} entry
     * point -- what a value generator must produce to drive it. Differs from
     * {@link #presentedClass(Object)} for the widening and reducing methods ({@code writeByte} accepts
     * {@code Byte} but presents {@code Integer}; {@code writeURL} accepts {@code URL} but presents
     * {@code String}). {@code null} for {@link #WRITE_OBJECT_AS}, whose input class is the value's own,
     * and for the {@code NOT_IMPLEMENTED} methods, which read no value.
     */
    public @Nullable Class<?> inputClass() {
      return inputClass;
    }

    /**
     * The Java class this method hands to the codec's encoder, which is what
     * {@link WriteCoercions#encode} keys on. Fixed for the typed methods; the value's own class for
     * {@link #WRITE_OBJECT_AS}.
     */
    public Class<?> presentedClass(Object value) {
      return fixedPresentedClass != null ? fixedPresentedClass : value.getClass();
    }

    /** Whether this method rejects with {@code NOT_IMPLEMENTED} before reaching a codec. */
    public boolean notImplemented() {
      return notImplemented;
    }
  }

  private static final Map<Integer, Map<Class<?>, CoercionOutcome>> ENCODE = new HashMap<>();
  private static final Map<Integer, CoercionOutcome> DEFAULT_REFUSAL = new HashMap<>();
  private static final Map<String, String> EMPTY_CONFIG = Collections.emptyMap();

  static {
    defineNumericFamily();
    defineSmallNumericFamily();
    defineFloatFamily();
    defineText();
    defineTextLikeFamily();
    defineBool();
    defineBytea();
    defineTemporalFamily();
  }

  private WriteCoercions() {
  }

  /**
   * Returns the outcome pgjdbc produces when encoding a value of {@code sourceClass} into the given
   * type. Returns the listed cell, {@code INVALID_PARAMETER_TYPE} by default-deny for an unlisted class
   * once the type is populated, and {@code null} for an unpopulated type.
   *
   * @param surface the writing API
   * @param oid the target PostgreSQL type OID
   * @param sourceClass the runtime class of the value handed to the codec
   * @param config the connection properties (unused today; carried for parity with the read side)
   * @return the expected outcome, or {@code null} if unspecified
   */
  public static @Nullable CoercionOutcome encode(Surface surface, int oid, Class<?> sourceClass,
      Map<String, String> config) {
    Map<Class<?>, CoercionOutcome> row = ENCODE.get(oid);
    if (row == null) {
      return null;
    }
    CoercionOutcome listed = row.get(sourceClass);
    return listed != null ? listed : DEFAULT_REFUSAL.get(oid);
  }

  /** Encodes under the default connection config. */
  public static @Nullable CoercionOutcome encode(Surface surface, int oid, Class<?> sourceClass) {
    return encode(surface, oid, sourceClass, EMPTY_CONFIG);
  }

  /**
   * The source classes the encode registry lists for a type (an unmodifiable view of its encode row),
   * or an empty set when the type is not populated. Backs {@link PgTypeDescriptor#acceptedClasses()},
   * so the descriptor can derive its accepted-class axis without copying the table.
   *
   * @param oid the target PostgreSQL type OID
   * @return the accepted source classes, never {@code null}
   */
  static Set<Class<?>> acceptedClasses(int oid) {
    Map<Class<?>, CoercionOutcome> row = ENCODE.get(oid);
    return row == null ? Collections.<Class<?>>emptySet()
        : Collections.unmodifiableSet(row.keySet());
  }

  /**
   * The OIDs the encode registry has populated. Backs the descriptor type guard (G3): every
   * write-populated OID must have a {@link PgTypeDescriptor}.
   *
   * @return the populated OIDs, never {@code null}
   */
  static Set<Integer> populatedOids() {
    return Collections.unmodifiableSet(ENCODE.keySet());
  }

  private static void type(int oid, Consumer<Accepts> populate) {
    Accepts accepts = new Accepts();
    populate.accept(accepts);
    ENCODE.put(oid, accepts.row);
    DEFAULT_REFUSAL.put(oid, accepts.defaultRefusal);
  }

  /** Chainable DSL to list the accepted source classes of one type and its default refusal. */
  private static final class Accepts {
    private final Map<Class<?>, CoercionOutcome> row = new HashMap<>();
    private CoercionOutcome defaultRefusal = INVALID_PARAMETER_TYPE;

    /** Accepted for every value. */
    Accepts ok(Class<?> sourceClass) {
      row.put(sourceClass, OK);
      return this;
    }

    /** Accepted, but value-dependent: overflow / parse / non-0-1 boolean refuses with a value state. */
    Accepts coerce(Class<?> sourceClass) {
      row.put(sourceClass, OK_OR_COERCE);
      return this;
    }

    /** Overrides the default-deny state for an unlisted class (default {@code INVALID_PARAMETER_TYPE}). */
    Accepts defaultRefusal(CoercionOutcome outcome) {
      this.defaultRefusal = outcome;
      return this;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Numeric family: int4, int8, numeric. Sourced from Int4Codec.toInt / Int8Codec.toLong /
  // NumericCodec.toBigDecimal. Integer/Long/Short/Byte and Boolean map cleanly; the wider numbers and
  // String are range- or parse-checked (NUMERIC_VALUE_OUT_OF_RANGE); temporal, byte[] and UUID refuse.
  // ---------------------------------------------------------------------------------------------

  private static void defineNumericFamily() {
    // int4: Integer/Short/Byte always fit; Long and the wider numbers range-check; String parses.
    type(Oid.INT4, a -> a
        .ok(Integer.class).ok(Short.class).ok(Byte.class).ok(Boolean.class)
        .coerce(Long.class).coerce(Float.class).coerce(Double.class)
        .coerce(BigDecimal.class).coerce(BigInteger.class).coerce(String.class));

    // int8: every integral box fits; the floating numbers and String are value-dependent.
    type(Oid.INT8, a -> a
        .ok(Integer.class).ok(Long.class).ok(Short.class).ok(Byte.class).ok(Boolean.class)
        .coerce(Float.class).coerce(Double.class).coerce(BigDecimal.class).coerce(BigInteger.class)
        .coerce(String.class));

    // numeric: integral boxes and BigDecimal are exact; Float/Double go through doubleValue and String
    // parses, so those are value-dependent.
    type(Oid.NUMERIC, a -> a
        .ok(Integer.class).ok(Long.class).ok(Short.class).ok(Byte.class).ok(BigDecimal.class)
        .ok(Boolean.class)
        .coerce(Float.class).coerce(Double.class).coerce(BigInteger.class).coerce(String.class));
  }

  // ---------------------------------------------------------------------------------------------
  // Small integer: int2 and oid. Both funnel through a codec toShort/toLong that widens a Number,
  // parses a String, and refuses everything else.
  //
  // int2 (Int2Codec.toShort): Short and Byte always fit the int2 range; Boolean maps to 0/1 (in range).
  // Integer and the wider numbers go through longValue() and are range-checked (NUMERIC_VALUE_OUT_OF_
  // RANGE), so they are value-dependent, as is String (Short.parseShort). Everything else refuses with
  // INVALID_PARAMETER_TYPE.
  //
  // oid (OidCodec.toLong): every Number widens through longValue(), which never overflows (it truncates
  // a floating or big value rather than raising), so all the boxed numbers are OK. String parses through
  // Long.parseLong and is value-dependent. Boolean is not a Number and is not handled, so it refuses with
  // INVALID_PARAMETER_TYPE -- unlike int2/int4, which accept it as 0/1. The oid codec truncates the long
  // to unsigned 32 bits only in the binary encoder; that is a round-trip fidelity concern, not an encode
  // legality one, so it does not change the accepted-class set here.
  // ---------------------------------------------------------------------------------------------

  private static void defineSmallNumericFamily() {
    type(Oid.INT2, a -> a
        .ok(Short.class).ok(Byte.class).ok(Boolean.class)
        .coerce(Integer.class).coerce(Long.class).coerce(Float.class).coerce(Double.class)
        .coerce(BigDecimal.class).coerce(BigInteger.class).coerce(String.class));

    type(Oid.OID, a -> a
        .ok(Integer.class).ok(Long.class).ok(Short.class).ok(Byte.class).ok(Float.class)
        .ok(Double.class).ok(BigDecimal.class).ok(BigInteger.class)
        .coerce(String.class));
  }

  // ---------------------------------------------------------------------------------------------
  // Floating point: float4 and float8. Sourced from Float4Codec.toFloat / Float8Codec.toDouble: a
  // Number widens through floatValue()/doubleValue() (which saturate to +/-Infinity rather than raise,
  // so every boxed number is OK), Boolean maps to 1.0/0.0, and String parses through parseFloat/
  // parseDouble and is value-dependent. Everything else refuses with INVALID_PARAMETER_TYPE.
  // ---------------------------------------------------------------------------------------------

  private static void defineFloatFamily() {
    for (int oid : new int[]{Oid.FLOAT4, Oid.FLOAT8}) {
      type(oid, a -> a
          .ok(Integer.class).ok(Long.class).ok(Short.class).ok(Byte.class).ok(Float.class)
          .ok(Double.class).ok(BigDecimal.class).ok(BigInteger.class).ok(Boolean.class)
          .coerce(String.class));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // text. Sourced from AbstractTextCodec.toString: every scalar (Number, Boolean, String) stringifies;
  // byte[], temporal and UUID refuse with INVALID_PARAMETER_TYPE.
  // ---------------------------------------------------------------------------------------------

  private static void defineText() {
    type(Oid.TEXT, a -> a
        .ok(Integer.class).ok(Long.class).ok(Short.class).ok(Byte.class).ok(Float.class)
        .ok(Double.class).ok(BigDecimal.class).ok(BigInteger.class).ok(Boolean.class).ok(String.class));
  }

  // ---------------------------------------------------------------------------------------------
  // Text-like family: varchar, bpchar, name. Each delegates its encoder to AbstractTextCodec (through
  // VarcharCodec/BpcharCodec/NameCodec), so the accepted-class set is identical to text: every Number,
  // Boolean and String stringifies; byte[], temporal and UUID refuse with INVALID_PARAMETER_TYPE. The
  // fixed-width blank padding of bpchar is a server-side effect; the offline single-field codec neither
  // pads nor trims, so it does not touch encode legality here.
  //
  // "char" (OID 18) shares this accepted-class set but is deliberately left out: its encoder mirrors
  // charin -- a String is truncated to a single byte -- so it is a lossy write that cannot satisfy the
  // value-fidelity round-trip (coercionScalars -> JqfCoercionRoundTripFuzzTest). It stays read-only: the
  // decode-robustness and ReadCoercions surfaces still cover it, and its byte->String->byte decode is
  // idempotent, but it is off the write/round-trip axis.
  // ---------------------------------------------------------------------------------------------

  private static void defineTextLikeFamily() {
    for (int oid : new int[]{Oid.VARCHAR, Oid.BPCHAR, Oid.NAME}) {
      type(oid, a -> a
          .ok(Integer.class).ok(Long.class).ok(Short.class).ok(Byte.class).ok(Float.class)
          .ok(Double.class).ok(BigDecimal.class).ok(BigInteger.class).ok(Boolean.class)
          .ok(String.class));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // bool. Sourced from BoolCodec.toBoolean / BooleanTypeUtil: Boolean maps cleanly; every number and
  // String is accepted but only 0/1 (or a recognised bool literal) succeed, else CANNOT_COERCE.
  // ---------------------------------------------------------------------------------------------

  private static void defineBool() {
    // BooleanTypeUtil.castToBoolean refuses every non-boolean-coercible value with CANNOT_COERCE, so
    // that is bool's default-deny state, not INVALID_PARAMETER_TYPE (the mirror of readBoolean on read).
    type(Oid.BOOL, a -> a
        .defaultRefusal(CANNOT_COERCE)
        .ok(Boolean.class)
        .coerce(Integer.class).coerce(Long.class).coerce(Short.class).coerce(Byte.class)
        .coerce(Float.class).coerce(Double.class).coerce(BigDecimal.class).coerce(BigInteger.class)
        .coerce(String.class));
  }

  // ---------------------------------------------------------------------------------------------
  // bytea. Sourced from ByteaCodec.toBytes: byte[] is taken as-is and always encodes, so it is OK. A
  // String is decoded through PGbytea.toBytes as the hex ('\x...') or octal-escape text form: a valid
  // literal encodes and a malformed one (an odd hex length, a non-hex digit, a trailing/lone backslash,
  // a truncated or out-of-range octal escape) refuses per value, so String is value-dependent
  // (OK_OR_COERCE). Everything else refuses with INVALID_PARAMETER_TYPE.
  //
  // The String value generator feeds bytea free printable-ASCII, backslashes included, so it exercises
  // both the valid and the malformed literal. A clean per-value refusal is the modelled outcome: a bad
  // escape/octal literal raises INVALID_TEXT_REPRESENTATION (22P02) and a bad hex literal raises
  // INVALID_PARAMETER_VALUE (22023), both in OutcomeContract.WRITE_VALUE_LEVEL_STATES. These match the
  // server; the driver's PGbytea.toBytes was hardened to raise them instead of leaking an
  // ArrayIndexOutOfBoundsException on a malformed literal.
  // ---------------------------------------------------------------------------------------------

  private static void defineBytea() {
    type(Oid.BYTEA, a -> a.ok(byte[].class).coerce(String.class));
  }

  // ---------------------------------------------------------------------------------------------
  // Temporal family: date, time, timetz, timestamp, timestamptz. Sourced from DateCodec and the
  // TemporalCodecs helpers. Each helper ends in an `instanceof java.util.Date` catch-all, so all of
  // java.sql.Date/Time/Timestamp and java.util.Date are accepted by every temporal type (a java.sql.*
  // value is-a java.util.Date).
  //
  // Date-carrying targets are value-dependent for the date types (date, timestamp, timestamptz): a
  // value outside PostgreSQL's timestamp range overflows to a clean DATETIME_OVERFLOW, so they are
  // OK_OR_COERCE. java.sql.Time is the exception -- it is a time-of-day anchored to 1970-01-01, always
  // in range, so it stays OK. The time types (time, timetz) extract the time-of-day from any input, so
  // every accepted class is always in range and stays OK. String parses (value-dependent) everywhere.
  // ---------------------------------------------------------------------------------------------

  private static void defineTemporalFamily() {
    // date: the java.util.Date family plus LocalDate; date-carrying targets can overflow.
    type(Oid.DATE, a -> a
        .ok(Time.class)
        .coerce(Date.class).coerce(Timestamp.class).coerce(java.util.Date.class)
        .coerce(LocalDate.class).coerce(String.class));

    // time / timetz: the java.util.Date family plus LocalTime/OffsetTime; time-of-day is always in range.
    type(Oid.TIME, a -> a
        .ok(Date.class).ok(Time.class).ok(Timestamp.class).ok(java.util.Date.class)
        .ok(LocalTime.class).ok(OffsetTime.class).coerce(String.class));
    type(Oid.TIMETZ, a -> a
        .ok(Date.class).ok(Time.class).ok(Timestamp.class).ok(java.util.Date.class)
        .ok(LocalTime.class).ok(OffsetTime.class).coerce(String.class));

    // timestamp / timestamptz: the java.util.Date family plus the datetime java.time targets; all but
    // the time-of-day java.sql.Time can carry an out-of-range instant, so they are value-dependent.
    type(Oid.TIMESTAMP, a -> a
        .ok(Time.class)
        .coerce(Date.class).coerce(Timestamp.class).coerce(java.util.Date.class)
        .coerce(LocalDateTime.class).coerce(OffsetDateTime.class).coerce(ZonedDateTime.class)
        .coerce(Instant.class).coerce(String.class));
    type(Oid.TIMESTAMPTZ, a -> a
        .ok(Time.class)
        .coerce(Date.class).coerce(Timestamp.class).coerce(java.util.Date.class)
        .coerce(LocalDateTime.class).coerce(OffsetDateTime.class).coerce(ZonedDateTime.class)
        .coerce(Instant.class).coerce(String.class));
  }
}
