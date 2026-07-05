/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.coercion.ArrayDescriptor;
import org.postgresql.fuzzkit.coercion.Fidelity;
import org.postgresql.fuzzkit.coercion.LeafRepr;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Offline building blocks shared by the codec fuzz targets: the connectionless
 * {@link CodecContext}, the {@link PgType} descriptors the fuzzers resolve from
 * {@link PgTypeDescriptors}, and the round-trip and cross-format properties the {@code @FuzzTest}
 * methods assert. Nothing here opens a connection, so every property runs from the guided byte stream
 * alone.
 */
public final class CodecFuzzSupport {

  private CodecFuzzSupport() {
  }

  // --- Type descriptors ----------------------------------------------------------------------

  // Every PgType comes from the descriptor registry: scalars from ScalarDescriptor.pgType(), arrays
  // from ArrayDescriptor.pgType(), and the point composite from CompositeDescriptor.pgType(). Nothing
  // is built inline here any more, except the PGobject/PGInterval scalars below, which carry a codec
  // but no coercion-dictionary row and so are deliberately kept out of the descriptor registry (a
  // ScalarDescriptor requires a ReadCoercions row that guard G3 checks for).

  /**
   * Builds an offline scalar {@link PgType} the codec context resolves by OID, for the PGobject and
   * PGInterval scalars ({@code json}, {@code jsonb}, {@code bit}, {@code varbit}, {@code interval})
   * the codec round-trip targets exercise. These carry a pinned built-in codec but no coercion
   * dictionary row, so they are not {@link org.postgresql.fuzzkit.coercion.ScalarDescriptor}s; the
   * PgType is built inline here the way the codec unit tests build theirs -- a base type
   * ({@code typtype='b'}) in {@code pg_catalog} with no element, array, or base type. The codec is
   * resolved from the pinned built-in OID, so {@code typcategory} only records the type's category and
   * does not steer resolution.
   *
   * @param oid the pinned built-in OID (for example {@link Oid#JSON})
   * @param name the {@code pg_catalog} type name (for example {@code "json"})
   * @param typcategory the {@code pg_type.typcategory}
   * @return the offline scalar type
   */
  public static PgType scalar(int oid, String name, char typcategory) {
    return new PgType(new ObjectName("pg_catalog", name), name, oid, 'b', typcategory, -1, 0, 0, 0);
  }

  // The SQLData composite's field OIDs, in wire order (f1..f12). The membership is derived: exactly
  // the scalar descriptors that carry both a typed writer and a typed reader (which excludes timetz and
  // timestamptz, whose identity runs through the object axis), matching the twelve scalar SQLInput /
  // SQLOutput methods FuzzSqlData reads and writes. The order is NOT the registry order -- it is pinned
  // here so the composite's wire layout, and therefore every pinned repro, stays fixed. A static guard
  // fails class init if this explicit order ever drifts from the derived membership.
  private static final int[] SQL_DATA_FIELD_OIDS = {
      Oid.INT4,       // f1  intValue    (Integer)
      Oid.INT8,       // f2  longValue   (Long)
      Oid.INT2,       // f3  shortValue  (Short)
      Oid.BOOL,       // f4  boolValue   (Boolean)
      Oid.FLOAT4,     // f5  floatValue  (Float)
      Oid.FLOAT8,     // f6  doubleValue (Double)
      Oid.TEXT,       // f7  text        (String)
      Oid.NUMERIC,    // f8  numeric     (BigDecimal)
      Oid.BYTEA,      // f9  bytes       (byte[])
      Oid.DATE,       // f10 date        (java.sql.Date)
      Oid.TIME,       // f11 time        (java.sql.Time)
      Oid.TIMESTAMP,  // f12 timestamp   (java.sql.Timestamp)
  };

  private static final List<PgField> SQL_DATA_FIELDS = sqlDataFields();

  /**
   * Builds the SQLData composite's field list from {@link #SQL_DATA_FIELD_OIDS}, deriving each field's
   * OID through the descriptor registry. The pinned order names the wire layout; the registry decides
   * membership, so the two are cross-checked here: the OID set must equal the scalar descriptors whose
   * typed writer and reader are both present, or class init fails rather than let the derived schema
   * silently disagree with the twelve typed SQLData methods.
   */
  private static List<PgField> sqlDataFields() {
    Set<Integer> typed = new LinkedHashSet<>();
    for (ScalarDescriptor scalar : PgTypeDescriptors.scalars()) {
      if (scalar.typedWriter() != null && scalar.typedReader() != null) {
        typed.add(scalar.oid());
      }
    }
    Set<Integer> pinned = new LinkedHashSet<>();
    for (int oid : SQL_DATA_FIELD_OIDS) {
      pinned.add(oid);
    }
    if (!typed.equals(pinned)) {
      throw new ExceptionInInitializerError("FuzzSqlData field OIDs " + pinned
          + " diverge from the typed (writer+reader) scalar descriptors " + typed);
    }
    List<PgField> fields = new ArrayList<>(SQL_DATA_FIELD_OIDS.length);
    for (int i = 0; i < SQL_DATA_FIELD_OIDS.length; i++) {
      // The descriptor resolves the OID (and asserts it is a registered scalar); the field value class
      // is the descriptor's naturalClass -- for int2 that is Short, matching FuzzSqlData.shortValue.
      ScalarDescriptor scalar = PgTypeDescriptors.scalar(SQL_DATA_FIELD_OIDS[i]);
      fields.add(new PgField("f" + (i + 1), scalar.oid(), i + 1, -1));
    }
    return fields;
  }

  // --- Contexts ------------------------------------------------------------------------------

  /** A context with only the built-in types (scalars and built-in arrays resolve offline). */
  public static CodecContext builtins() {
    return PgCodecContext.offlineBuilder().build();
  }

  /** A context that also resolves one user-defined type by OID (composites, custom arrays). */
  public static CodecContext with(PgType type) {
    return PgCodecContext.offlineBuilder().type(type).build();
  }

  // --- Round-trip properties: decode(encode(v)) == v -----------------------------------------

  /**
   * Encodes then decodes {@code value} in both wire formats and asserts the result equals the
   * input. For a lossless codec this must hold for every generated value.
   */
  public static <T> void roundTrip(Object value, PgType type, Class<T> target, CodecContext ctx)
      throws SQLException {
    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, type, ctx, format);
      T back = Codecs.decode(raw, type, ctx, target);
      assertEquals(value, back, () -> type.getTypeName() + " " + format + " round-trip");
    }
  }

  /** numeric round-trip compared by value ({@code compareTo}) so trailing-zero scale is ignored. */
  public static void numericRoundTrip(BigDecimal value, CodecContext ctx) throws SQLException {
    PgType numeric = PgTypeDescriptors.scalar(Oid.NUMERIC).pgType();
    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, numeric, ctx, format);
      BigDecimal back = Codecs.decode(raw, numeric, ctx, BigDecimal.class);
      assertEquals(0, value.compareTo(back),
          () -> "numeric " + format + ": " + value + " != " + back);
    }
  }

  /** bytea round-trip: the decoded bytes must equal the input bytes. */
  public static void byteaRoundTrip(byte[] value, CodecContext ctx) throws SQLException {
    PgType bytea = PgTypeDescriptors.scalar(Oid.BYTEA).pgType();
    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, bytea, ctx, format);
      byte[] back = Codecs.decode(raw, bytea, ctx, byte[].class);
      assertArrayEquals(value, back, "bytea " + format + " round-trip");
    }
  }

  /**
   * Round-trips a (possibly multi-dimensional) array in both formats, deriving the backend type and
   * the {@code ndim}-by-{@code leafRepr} target class from the descriptor and comparing with
   * {@link Fidelity#DEEP_EQUALS} -- which unwraps every dimension and both {@code Integer[]} and
   * {@code int[]} leaves. The value is a nested array of the descriptor's leaf class (boxed
   * {@code Integer[][]} or primitive {@code int[][]}); the two representations differ only in whether
   * the leaf carries {@code null} elements, decided by the generator.
   *
   * @param value the array value, an {@code ndim}-dimensional array of the leaf class
   * @param descriptor the array descriptor
   * @param leafRepr the leaf representation the value uses
   * @param ndim the number of dimensions
   * @param ctx the offline codec context
   */
  public static void arrayRoundTrip(Object value, ArrayDescriptor descriptor, LeafRepr leafRepr, int ndim,
      CodecContext ctx) throws SQLException {
    PgType arrayType = descriptor.pgType();
    Class<?> target = descriptor.targetClass(leafRepr, ndim);
    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, arrayType, ctx, format);
      Object back = Codecs.decode(raw, arrayType, ctx, target);
      if (!descriptor.fidelity().equal(value, back)) {
        throw new AssertionError(arrayType.getTypeName() + " " + format + " round-trip: " + leafRepr
            + " " + ndim + "D mismatch");
      }
    }
  }

  /** Round-trips a struct in both formats and compares its attributes element by element. */
  public static void structRoundTrip(PgType type, Object[] attributes, CodecContext ctx)
      throws SQLException {
    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(new PgStruct(type, attributes, null), type, ctx, format);
      Struct back = Codecs.decode(raw, type, ctx, Struct.class);
      assertArrayEquals(attributes, back.getAttributes(),
          type.getTypeName() + " " + format + " struct round-trip");
    }
  }

  /**
   * Round-trips an anonymous {@code RECORD} over the binary wire. The record's field types are
   * synthesized from the binary self-description on decode, so this only runs in binary -- the
   * text literal carries no OIDs.
   */
  public static void anonymousRecordRoundTrip(int[] fieldOids, Object[] values) throws SQLException {
    List<PgField> fields = new ArrayList<>(fieldOids.length);
    for (int i = 0; i < fieldOids.length; i++) {
      fields.add(new PgField("f" + (i + 1), fieldOids[i], i + 1, -1));
    }
    // OID is RECORD so decode synthesizes fields from the wire; typtype 'c' steers codec
    // resolution to CompositeCodec. The descriptor already carries the fields encode needs.
    PgType recordType = new PgType(new ObjectName("pg_catalog", "record"), "record", Oid.RECORD,
        'c', 'C', -1, 0, 0, 0, ',', fields);
    CodecContext ctx = with(recordType);

    RawValue raw = Codecs.encode(new PgStruct(recordType, values, null), recordType, ctx,
        Format.BINARY);
    Struct back = Codecs.decode(raw, recordType, ctx, Struct.class);
    assertArrayEquals(values, back.getAttributes(), "anonymous record binary round-trip");
  }

  // --- Cross-format properties: decode(encodeText(v)) == decode(encodeBinary(v)) -------------

  /**
   * Asserts the text and binary paths agree: encoding {@code value} each way and decoding both
   * yields equal results. Independent of the round-trip property -- it catches a case where the two
   * formats disagree even if each is self-consistent.
   */
  public static <T> void crossFormat(Object value, PgType type, Class<T> target, CodecContext ctx)
      throws SQLException {
    T viaText = Codecs.decode(Codecs.encode(value, type, ctx, Format.TEXT), type, ctx, target);
    T viaBinary = Codecs.decode(Codecs.encode(value, type, ctx, Format.BINARY), type, ctx, target);
    assertEquals(viaText, viaBinary, () -> type.getTypeName() + " text vs binary");
  }

  public static void numericCrossFormat(BigDecimal value, CodecContext ctx) throws SQLException {
    PgType numeric = PgTypeDescriptors.scalar(Oid.NUMERIC).pgType();
    BigDecimal viaText = Codecs.decode(
        Codecs.encode(value, numeric, ctx, Format.TEXT), numeric, ctx, BigDecimal.class);
    BigDecimal viaBinary = Codecs.decode(
        Codecs.encode(value, numeric, ctx, Format.BINARY), numeric, ctx, BigDecimal.class);
    assertEquals(0, viaText.compareTo(viaBinary),
        () -> "numeric text " + viaText + " vs binary " + viaBinary);
  }

  public static void byteaCrossFormat(byte[] value, CodecContext ctx) throws SQLException {
    PgType bytea = PgTypeDescriptors.scalar(Oid.BYTEA).pgType();
    byte[] viaText = Codecs.decode(
        Codecs.encode(value, bytea, ctx, Format.TEXT), bytea, ctx, byte[].class);
    byte[] viaBinary = Codecs.decode(
        Codecs.encode(value, bytea, ctx, Format.BINARY), bytea, ctx, byte[].class);
    assertArrayEquals(viaText, viaBinary, "bytea text vs binary");
  }

  /**
   * Asserts a (possibly multi-dimensional) array agrees across the text and binary paths, with the
   * type and target class derived from the descriptor and the two decoded arrays compared by
   * {@link Fidelity#DEEP_EQUALS}.
   *
   * @param value the array value
   * @param descriptor the array descriptor
   * @param leafRepr the leaf representation the value uses
   * @param ndim the number of dimensions
   * @param ctx the offline codec context
   */
  public static void arrayCrossFormat(Object value, ArrayDescriptor descriptor, LeafRepr leafRepr, int ndim,
      CodecContext ctx) throws SQLException {
    PgType arrayType = descriptor.pgType();
    Class<?> target = descriptor.targetClass(leafRepr, ndim);
    Object viaText = Codecs.decode(
        Codecs.encode(value, arrayType, ctx, Format.TEXT), arrayType, ctx, target);
    Object viaBinary = Codecs.decode(
        Codecs.encode(value, arrayType, ctx, Format.BINARY), arrayType, ctx, target);
    if (!descriptor.fidelity().equal(viaText, viaBinary)) {
      throw new AssertionError(arrayType.getTypeName() + " text vs binary: " + leafRepr + " " + ndim
          + "D mismatch");
    }
  }

  public static void structCrossFormat(PgType type, Object[] attributes, CodecContext ctx)
      throws SQLException {
    Struct viaText = Codecs.decode(
        Codecs.encode(new PgStruct(type, attributes, null), type, ctx, Format.TEXT),
        type, ctx, Struct.class);
    Struct viaBinary = Codecs.decode(
        Codecs.encode(new PgStruct(type, attributes, null), type, ctx, Format.BINARY),
        type, ctx, Struct.class);
    assertArrayEquals(viaText.getAttributes(), viaBinary.getAttributes(),
        type.getTypeName() + " text vs binary");
  }

  // --- Recursive nested composites -----------------------------------------------------------

  /** A built composite node: its registered type and a value struct of that type. */
  private static final class Built {
    final PgType type;
    final PgStruct struct;

    Built(PgType type, PgStruct struct) {
      this.type = type;
      this.struct = struct;
    }
  }

  private static Built build(FuzzNode node, int[] counter, List<PgType> registry) {
    List<PgField> pgFields = new ArrayList<>(node.fields.size());
    Object[] attributes = new Object[node.fields.size()];
    for (int i = 0; i < node.fields.size(); i++) {
      FuzzNode.Field field = node.fields.get(i);
      int fieldOid;
      if (field.isScalar()) {
        fieldOid = field.scalarOid;
        attributes[i] = field.scalarValue;
      } else {
        Built child = build(field.nested, counter, registry);
        fieldOid = child.type.getOid();
        attributes[i] = child.struct;
      }
      pgFields.add(new PgField("f" + (i + 1), fieldOid, i + 1, -1));
    }
    PgType type;
    if (node.anonymous) {
      // An anonymous RECORD is not resolvable by OID; the PgStruct carries its own type so the
      // encoder can read the fields (CompositeCodec.binaryFieldType prefers the carried type for a
      // RECORD field).
      type = new PgType(new ObjectName("pg_catalog", "record"), "record", Oid.RECORD, 'c', 'C', -1,
          0, 0, 0, ',', pgFields);
    } else {
      int oid = counter[0]++;
      type = new PgType(new ObjectName("public", "fuzz_c" + oid), "public.fuzz_c" + oid, oid, 'c',
          'C', -1, 0, 0, 0, ',', pgFields);
      registry.add(type);
    }
    return new Built(type, new PgStruct(type, attributes, null));
  }

  /**
   * Round-trips a recursively nested composite through the codec, covering named, anonymous, and the
   * mixed anonymous-holding-named shapes. Named nodes become distinct registered composite types;
   * anonymous nodes carry their own {@code RECORD} type on the struct. A named top-level composite
   * round-trips in text and binary; an anonymous {@code RECORD} in binary only, since its text
   * literal carries no field OIDs to recover the attribute types from.
   */
  public static void structRoundTrip(FuzzNode root) throws SQLException {
    List<PgType> registry = new ArrayList<>();
    Built built = build(root, new int[]{90_000}, registry);
    PgCodecContext.OfflineBuilder builder = PgCodecContext.offlineBuilder();
    for (PgType type : registry) {
      builder.type(type);
    }
    CodecContext ctx = builder.build();

    Format[] formats = root.anonymous ? new Format[]{Format.BINARY} : Format.values();
    for (Format format : formats) {
      RawValue raw = Codecs.encode(built.struct, built.type, ctx, format);
      Struct decoded = Codecs.decode(raw, built.type, ctx, Struct.class);
      assertNodeEquals(root, decoded);
    }
  }

  private static void assertNodeEquals(FuzzNode expected, Struct actual) throws SQLException {
    Object[] attributes = actual.getAttributes();
    assertEquals(expected.fields.size(), attributes.length, "nested record field count");
    for (int i = 0; i < expected.fields.size(); i++) {
      FuzzNode.Field field = expected.fields.get(i);
      if (field.isScalar()) {
        assertEquals(field.scalarValue, attributes[i], "nested record scalar field");
      } else {
        assertNodeEquals(field.nested, (Struct) attributes[i]);
      }
    }
  }

  /**
   * Round-trips an array of the {@code (x int4, y int4, label text)} composite -- the array-of-record
   * shape -- over the binary wire, with occasional SQL NULL elements.
   */
  public static void recordArrayRoundTrip(Object[][] rows) throws SQLException {
    // The element is the registered point composite; the array-of-composite over it is not a scalar
    // array, so it stays a local RECORD[]-shaped PgType (typelem = the composite OID) rather than a
    // registered ArrayDescriptor.
    PgType element = PgTypeDescriptors.composite(PgTypeDescriptors.POINT_OID).pgType();
    PgType arrayType = new PgType(new ObjectName("public", "_fuzz_point"), "public.fuzz_point[]",
        90_010, 'b', 'A', -1, PgTypeDescriptors.POINT_OID, 0, 0);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(element).type(arrayType).build();

    PgStruct[] structs = new PgStruct[rows.length];
    for (int i = 0; i < rows.length; i++) {
      structs[i] = rows[i] == null ? null : new PgStruct(element, rows[i], null);
    }
    RawValue raw = Codecs.encode(structs, arrayType, ctx, Format.BINARY);
    Object[] decoded = (Object[]) Codecs.decode(raw, arrayType, ctx, Object.class);

    assertEquals(rows.length, decoded.length, "record[] length");
    for (int i = 0; i < rows.length; i++) {
      if (rows[i] == null) {
        assertNull(decoded[i], "record[] null element");
      } else {
        assertArrayEquals(rows[i], ((Struct) decoded[i]).getAttributes(), "record[] element");
      }
    }
  }

  // --- SQLData (PgSQLInput / PgSQLOutput) ----------------------------------------------------

  /**
   * Round-trips a {@link FuzzSqlData} through both SQLData wire paths -- text
   * ({@code PgSQLOutputText} / {@code PgSQLInputText}) and binary ({@code PgSQLOutputBinary} /
   * {@code PgSQLInputBinary}) -- exercising every scalar writer and reader, the composite literal
   * quoting on the text side, and the NULL path behind {@code wasNull}. The offline context uses the
   * JVM time zone so the {@code java.sql} temporal values agree with the codec on the wall clock.
   */
  public static void sqlDataRoundTrip(FuzzSqlData value) throws SQLException {
    PgType type = new PgType(new ObjectName("public", "fuzz_sqldata"), "public.fuzz_sqldata",
        90_020, 'c', 'C', -1, 0, 0, 0, ',', SQL_DATA_FIELDS);
    CodecContext ctx = PgCodecContext.offlineBuilder().type(type)
        .timeZone(TimeZone.getDefault()).build();

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, type, ctx, format);
      FuzzSqlData back = Codecs.decode(raw, type, ctx, FuzzSqlData.class);
      assertEquals(value, back, () -> "SQLData " + format + " round-trip");
    }
  }

  // --- Adversarial text-literal decode -------------------------------------------------------

  /**
   * The weak decode-robustness invariant for adversarial text literals, shared by both fuzz
   * front-ends: decodes {@code literal} as {@code type} in the text format and asserts the decoder
   * either returns a value or refuses with a clean {@link SQLException}. It must never leak an
   * unchecked {@link RuntimeException}.
   *
   * <p>The property targets the recursive, quoting- and escape-aware text-literal parsers -- the
   * array literal grammar ({@code MultiDimArrayText} over {@code LiteralCursor}), the composite
   * literal grammar ({@code CompositeCodec} over {@code LiteralCursor}), and the scalar text decoders
   * -- which the canonical-wire fuzzers leave almost entirely cold, since every literal those fuzzers
   * decode is one the codec itself just wrote. A malformed literal (unbalanced braces, a truncated
   * element, a stray quote) is expected to refuse per value, not crash.
   *
   * <p>A leaked unchecked exception is rethrown as an {@link AssertionError} that names the type OID,
   * the leaked exception class, and the offending literal, so the same finding surfaces under both the
   * JQF and Jazzer front-ends. An {@link Error} -- notably the {@link StackOverflowError} the recursive
   * array grammar can hit on a pathologically deep literal -- is left to propagate: it is a known
   * limitation of the parser, not a per-value contract breach for this oracle to translate (see
   * FUZZ_ROADMAP.md).
   *
   * @param literal the arbitrary literal to decode
   * @param type the backend type to decode the literal as
   * @param ctx the offline codec context, which must resolve {@code type}
   */
  public static void decodeTextExpectingNoLeak(String literal, PgType type, CodecContext ctx) {
    RawValue raw = RawValue.text(literal.getBytes(StandardCharsets.UTF_8));
    try {
      Codecs.decode(raw, type, ctx, Object.class);
    } catch (SQLException refused) {
      // Expected: a malformed literal refuses per value.
    } catch (RuntimeException leak) {
      throw new AssertionError("text decode of t" + type.getOid() + " leaked "
          + leak.getClass().getName() + " (expected only SQLException) on literal "
          + quoteLiteral(literal), leak);
    }
  }

  /**
   * The weak decode-robustness invariant for adversarial binary wire, the binary sibling of
   * {@link #decodeTextExpectingNoLeak}: decodes {@code data} as {@code type} in the binary format and
   * asserts the decoder either returns a value or refuses with a clean {@link SQLException}. It must
   * never leak an unchecked {@link RuntimeException}.
   *
   * <p>The property targets the binary container decoders -- the array header and per-element framing
   * ({@code MultiDimArrayBinary}), the composite field framing ({@code CompositeCodec}), and the range
   * bound framing ({@code RangeCodec}) -- whose length- and count-guarded error branches the
   * canonical-wire round-trip fuzzers never reach, since every buffer those fuzzers decode is one a
   * matching encoder just produced. A hostile or corrupt server can send a truncated header, a
   * negative length, or an over-large element count; the decoder is expected to refuse per value, not
   * crash. The allocation guards phase F1 added to those decoders are what let a guided campaign run
   * this without exhausting the heap.
   *
   * <p>A leaked unchecked exception is rethrown as an {@link AssertionError} naming the type OID, the
   * leaked class, and a hex prefix of the offending bytes, so the finding surfaces the same way under
   * both fuzz front-ends. An {@link Error} -- an {@link OutOfMemoryError} from an unbounded allocation,
   * or a {@link StackOverflowError} from unbounded recursion -- is left to propagate: it is a driver
   * robustness gap for the campaign to report, not a per-value contract breach for this oracle to
   * translate.
   *
   * @param data the arbitrary bytes to decode as a binary wire value
   * @param type the backend type to decode the bytes as
   * @param ctx the offline codec context, which must resolve {@code type}
   */
  public static void decodeBinaryExpectingNoLeak(byte[] data, PgType type, CodecContext ctx) {
    try {
      Codecs.decode(RawValue.binary(data), type, ctx, Object.class);
    } catch (SQLException refused) {
      // Expected: malformed bytes refuse per value.
    } catch (RuntimeException leak) {
      throw new AssertionError("binary decode of t" + type.getOid() + " leaked "
          + leak.getClass().getName() + " (expected only SQLException) on bytes "
          + hexPrefix(data), leak);
    }
  }

  private static String quoteLiteral(String s) {
    return s.length() > 80
        ? '"' + s.substring(0, 80) + "\"... (" + s.length() + " chars)"
        : '"' + s + '"';
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static String hexPrefix(byte[] data) {
    int shown = Math.min(data.length, 40);
    StringBuilder sb = new StringBuilder(shown * 2 + 24);
    for (int i = 0; i < shown; i++) {
      sb.append(HEX[(data[i] >> 4) & 0xf]).append(HEX[data[i] & 0xf]);
    }
    if (data.length > shown) {
      sb.append("... (").append(data.length).append(" bytes)");
    }
    return sb.toString();
  }
}
