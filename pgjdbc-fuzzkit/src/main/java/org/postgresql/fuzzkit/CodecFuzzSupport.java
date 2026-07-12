/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveBinaryEncoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.PrimitiveTextEncoder;
import org.postgresql.api.codec.RawValue;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.coercion.ArrayDescriptor;
import org.postgresql.fuzzkit.coercion.Fidelity;
import org.postgresql.fuzzkit.coercion.LeafRepr;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.codec.BackpatchByteArrayOutputStream;
import org.postgresql.jdbc.codec.FallbackCodec;
import org.postgresql.util.PGRange;
import org.postgresql.util.PGmultirange;
import org.postgresql.util.internal.Nullness;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    return OfflineCodecContexts.offlineBuilder().build();
  }

  /** A context that also resolves one user-defined type by OID (composites, custom arrays). */
  public static CodecContext with(PgType type) {
    return OfflineCodecContexts.offlineBuilder().type(type).build();
  }

  // --- Streaming-parity property: stream(v) == materialise(v) ---------------------------------

  /**
   * Asserts the streaming encode form agrees with the materialising one for whichever paths the
   * resolved codec opts into. A {@link StreamingBinaryCodec} must write the same bytes into a
   * {@link BackpatchingBinarySink} as its {@code byte[]} {@link BinaryCodec#encodeBinary} returns; a
   * {@link StreamingTextCodec} must append the same characters as its {@code String}
   * {@link TextCodec#encodeText} returns.
   *
   * <p>The top-level {@link Codecs#encode} routes a scalar through the {@code byte[]}/{@code String}
   * form, so the streaming form of a leaf codec (int4, date, uuid, ...) is otherwise reached only when
   * that leaf is an array or composite element. Calling this alongside the round-trip and cross-format
   * properties covers the leaf streaming form directly, across every generated value.
   *
   * <p>Guarded by {@code instanceof}, so it is a no-op for a codec that streams neither path and safe
   * to call for any type. For a delegating codec whose {@code byte[]} form merely buffers its own
   * streaming form (range, multirange, domain, PGobject), the two paths are equal by construction --
   * this property has teeth only for the leaf codecs that hand-write both. The element-branch parity
   * of the delegating codecs is a separate property.
   *
   * @param value the value to encode both ways
   * @param type the backend type whose codec the context resolves
   * @param ctx the offline codec context, which must resolve {@code type}
   */
  public static void encodeParity(Object value, PgType type, CodecContext ctx) throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    if (codec instanceof StreamingBinaryCodec) {
      byte[] materialised = ((BinaryCodec) codec).encodeBinary(value, type, ctx);
      BackpatchByteArrayOutputStream sink = new BackpatchByteArrayOutputStream();
      try {
        ((StreamingBinaryCodec) codec).encodeBinary(value, type, ctx, sink);
      } catch (IOException e) {
        // BackpatchByteArrayOutputStream never performs I/O; a throw here is a codec contract breach.
        throw new AssertionError(type.getTypeName() + " streaming-binary threw IOException", e);
      }
      assertArrayEquals(materialised, sink.toByteArray(),
          type.getTypeName() + " streaming-binary vs byte[]");
    }
    if (codec instanceof StreamingTextCodec) {
      String materialised = ((TextCodec) codec).encodeText(value, type, ctx);
      StringBuilder sink = new StringBuilder();
      try {
        ((StreamingTextCodec) codec).encodeText(value, type, ctx, sink);
      } catch (IOException e) {
        // StringBuilder.append never throws IOException; a throw here is a codec contract breach.
        throw new AssertionError(type.getTypeName() + " streaming-text threw IOException", e);
      }
      assertEquals(materialised, sink.toString(),
          () -> type.getTypeName() + " streaming-text vs String");
    }
  }

  // --- Stream-vs-materialise property for delegating codecs -----------------------------------

  /**
   * Asserts a delegating codec (range, multirange, domain, PGobject, array, composite) writes the
   * same wire bytes whether it streams each child straight into the sink or materialises the child to
   * a {@code byte[]}/{@code String} first. It encodes {@code value} twice: once through {@code ctx},
   * and once through a context whose {@link CodecContext#resolveCodec} returns a non-streaming view of
   * every codec, forcing the delegate's materialising fallback branch. The two wire forms must match
   * in both formats.
   *
   * <p>This is the property {@link #encodeParity} cannot reach for {@link org.postgresql.jdbc.codec
   * .RangeCodec} and {@link org.postgresql.jdbc.codec.MultirangeCodec}, whose {@code byte[]} form
   * merely buffers their own streaming form -- so their materialising child branch is otherwise never
   * taken, since every registered child codec streams. The de-streamed run exercises that branch and
   * pins its length framing against the back-patched streaming one.
   *
   * @param value the value to encode both ways
   * @param type the delegating backend type
   * @param ctx the offline codec context, which must resolve {@code type} and its children
   */
  public static void streamVsMaterializeParity(Object value, PgType type, CodecContext ctx)
      throws SQLException {
    CodecContext materialising = new NonStreamingContext(ctx);
    // Non-vacuity guard: if the delegate streams, the de-streamed view must hide its streaming faces,
    // or the two runs would encode through the same branch and the property would prove nothing. The
    // same wrapping drives the children, so hiding it on the top type witnesses the mechanism works.
    Codec real = ctx.resolveCodec(type.getOid());
    if (real instanceof StreamingBinaryCodec || real instanceof StreamingTextCodec) {
      Codec deStreamed = materialising.resolveCodec(type.getOid());
      assertFalse(deStreamed instanceof StreamingBinaryCodec
              || deStreamed instanceof StreamingTextCodec,
          () -> type.getTypeName() + " de-streamed codec still advertises a streaming face");
    }
    for (Format format : Format.values()) {
      byte[] streamed = Codecs.encode(value, type, ctx, format).toByteArray();
      byte[] materialised = Codecs.encode(value, type, materialising, format).toByteArray();
      assertArrayEquals(streamed, materialised,
          type.getTypeName() + " " + format + " streamed vs materialised child path");
    }
  }

  // --- Primitive-capability parity: no-box path has the same OUTCOME as the boxing path ----------
  //
  // PrimitiveBinaryEncoder/PrimitiveTextEncoder/PrimitiveBinaryDecoder/PrimitiveTextDecoder each
  // override a boxing default; the whole point is that the override produces the same OUTCOME -- the
  // same bytes/value on success, AND the same failure on a bad input. These oracles compare outcomes
  // (see assertSameOutcome), so encoding a value out of the codec's range must throw on both the
  // primitive and the boxing path, with the same SQLState, or the property fails. The generators feed
  // the range-checked codecs their wider type (an int to int2, a long to int4) so the overflow path
  // is actually reached, and each text decode parses toString(value) so an out-of-range value yields
  // out-of-range text both paths must reject alike.
  //
  // Only the codec's own width is checked (int2/int4 -> int, int8 -> long, ...). A narrowing accessor
  // (int8.decodeAsInt, float8.decodeAsLong) is deliberately NOT compared here: its whole reason to
  // exist is to add a range check the boxing default lacks -- Long.intValue() truncates, the override
  // throws -- so there the two are meant to differ. The decoders are also fed the value at a non-zero
  // offset into a padded buffer, and a truncated wire, so a slice off-by-one or a missing length check
  // surfaces.

  /**
   * Parity oracle for the primitive capabilities on an {@code int}-valued scalar codec (int2, int4).
   *
   * @param value the value to check
   * @param oid the scalar type OID whose codec is under test
   * @param ctx the offline codec context, which must resolve {@code oid}
   */
  public static void intPrimitiveParity(int value, int oid, CodecContext ctx) throws SQLException {
    intPrimitiveParity(value, PgTypeDescriptors.scalar(oid).pgType(), ctx);
  }

  /** Same, against an explicit type -- lets a domain forward the accessors to its base codec. */
  public static void intPrimitiveParity(int value, PgType type, CodecContext ctx) throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    Integer boxed = value;
    String name = type.getTypeName().toString();
    if (codec instanceof PrimitiveBinaryEncoder) {
      PrimitiveBinaryEncoder enc = (PrimitiveBinaryEncoder) codec;
      assertSameOutcome(name + " encodeInt(binary) vs encodeBinary",
          Outcome.capture(() -> ((BinaryCodec) codec).encodeBinary(boxed, type, ctx)),
          Outcome.capture(() -> encodeToBytes(sink -> enc.encodeInt(value, type, ctx, sink), type)));
    }
    if (codec instanceof PrimitiveTextEncoder) {
      PrimitiveTextEncoder enc = (PrimitiveTextEncoder) codec;
      assertSameOutcome(name + " encodeInt(text) vs encodeText",
          Outcome.capture(() -> ((TextCodec) codec).encodeText(boxed, type, ctx)),
          Outcome.capture(() -> encodeToText(out -> enc.encodeInt(value, type, ctx, out), type)));
    }
    if (codec instanceof PrimitiveBinaryDecoder) {
      PrimitiveBinaryDecoder dec = (PrimitiveBinaryDecoder) codec;
      byte[] wire = tryEncodeBinary((BinaryCodec) codec, boxed, type, ctx);
      if (wire != null) {
        assertSameOutcome(name + " decodeAsInt(byte[]) vs decodeBinary",
            Outcome.capture(() -> intOf(((BinaryCodec) codec).decodeBinary(wire, 0, wire.length, type, ctx))),
            Outcome.capture(() -> dec.decodeAsInt(wire, 0, wire.length, type, ctx)));
        assertEquals(dec.decodeAsInt(wire, 0, wire.length, type, ctx),
            dec.decodeAsInt(pad(wire), 3, wire.length, type, ctx),
            name + " decodeAsInt(byte[]) honours offset");
        if (wire.length > 1) {
          byte[] shortWire = Arrays.copyOf(wire, wire.length - 1);
          assertSameOutcome(name + " decodeAsInt(byte[]) rejects a short wire",
              Outcome.capture(() -> intOf(((BinaryCodec) codec).decodeBinary(shortWire, 0, shortWire.length, type, ctx))),
              Outcome.capture(() -> dec.decodeAsInt(shortWire, 0, shortWire.length, type, ctx)));
        }
      }
    }
    if (codec instanceof PrimitiveTextDecoder) {
      PrimitiveTextDecoder dec = (PrimitiveTextDecoder) codec;
      String text = Integer.toString(value);
      char[] chars = text.toCharArray();
      Outcome viaText = Outcome.capture(() -> intOf(((TextCodec) codec).decodeText(text, type, ctx)));
      assertSameOutcome(name + " decodeAsInt(String) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsInt(text, type, ctx)));
      assertSameOutcome(name + " decodeAsInt(char[]) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsInt(chars, 0, chars.length, type, ctx)));
      assertSameOutcome(name + " decodeAsInt(char[]) at offset vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsInt(pad(chars), 3, chars.length, type, ctx)));
      assertSameOutcome(name + " decodeTextBytesAsInt vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeTextBytesAsInt(text.getBytes(ctx.getCharset()), type, ctx)));
    }
  }

  /**
   * Parity oracle for the primitive capabilities on a {@code long}-valued scalar codec (int8, oid).
   *
   * @param value the value to check
   * @param oid the scalar type OID whose codec is under test
   * @param ctx the offline codec context, which must resolve {@code oid}
   */
  public static void longPrimitiveParity(long value, int oid, CodecContext ctx) throws SQLException {
    longPrimitiveParity(value, PgTypeDescriptors.scalar(oid).pgType(), ctx);
  }

  /** Same, against an explicit type -- lets a domain forward the accessors to its base codec. */
  public static void longPrimitiveParity(long value, PgType type, CodecContext ctx) throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    Long boxed = value;
    String name = type.getTypeName().toString();
    if (codec instanceof PrimitiveBinaryEncoder) {
      PrimitiveBinaryEncoder enc = (PrimitiveBinaryEncoder) codec;
      assertSameOutcome(name + " encodeLong(binary) vs encodeBinary",
          Outcome.capture(() -> ((BinaryCodec) codec).encodeBinary(boxed, type, ctx)),
          Outcome.capture(() -> encodeToBytes(sink -> enc.encodeLong(value, type, ctx, sink), type)));
    }
    if (codec instanceof PrimitiveTextEncoder) {
      PrimitiveTextEncoder enc = (PrimitiveTextEncoder) codec;
      assertSameOutcome(name + " encodeLong(text) vs encodeText",
          Outcome.capture(() -> ((TextCodec) codec).encodeText(boxed, type, ctx)),
          Outcome.capture(() -> encodeToText(out -> enc.encodeLong(value, type, ctx, out), type)));
    }
    if (codec instanceof PrimitiveBinaryDecoder) {
      PrimitiveBinaryDecoder dec = (PrimitiveBinaryDecoder) codec;
      byte[] wire = tryEncodeBinary((BinaryCodec) codec, boxed, type, ctx);
      if (wire != null) {
        assertSameOutcome(name + " decodeAsLong(byte[]) vs decodeBinary",
            Outcome.capture(() -> longOf(((BinaryCodec) codec).decodeBinary(wire, 0, wire.length, type, ctx))),
            Outcome.capture(() -> dec.decodeAsLong(wire, 0, wire.length, type, ctx)));
        assertEquals(dec.decodeAsLong(wire, 0, wire.length, type, ctx),
            dec.decodeAsLong(pad(wire), 3, wire.length, type, ctx),
            name + " decodeAsLong(byte[]) honours offset");
        if (wire.length > 1) {
          byte[] shortWire = Arrays.copyOf(wire, wire.length - 1);
          assertSameOutcome(name + " decodeAsLong(byte[]) rejects a short wire",
              Outcome.capture(() -> longOf(((BinaryCodec) codec).decodeBinary(shortWire, 0, shortWire.length, type, ctx))),
              Outcome.capture(() -> dec.decodeAsLong(shortWire, 0, shortWire.length, type, ctx)));
        }
      }
    }
    if (codec instanceof PrimitiveTextDecoder) {
      PrimitiveTextDecoder dec = (PrimitiveTextDecoder) codec;
      String text = Long.toString(value);
      char[] chars = text.toCharArray();
      Outcome viaText = Outcome.capture(() -> longOf(((TextCodec) codec).decodeText(text, type, ctx)));
      assertSameOutcome(name + " decodeAsLong(String) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsLong(text, type, ctx)));
      assertSameOutcome(name + " decodeAsLong(char[]) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsLong(chars, 0, chars.length, type, ctx)));
      assertSameOutcome(name + " decodeAsLong(char[]) at offset vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsLong(pad(chars), 3, chars.length, type, ctx)));
      assertSameOutcome(name + " decodeTextBytesAsLong vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeTextBytesAsLong(text.getBytes(ctx.getCharset()), type, ctx)));
    }
  }

  /**
   * Same as {@link #longPrimitiveParity(long, PgType, CodecContext)}, for a scalar codec whose text
   * form is the <em>unsigned</em> decimal of {@code value}'s bit pattern (currently {@code oid8}, an
   * unsigned 64-bit object identifier) rather than {@code Long.toString}. A value at or above
   * 2<sup>63</sup> is exactly the case {@code longPrimitiveParity} cannot check: its decode-side text
   * probe would build a leading-minus string the codec's own {@code decodeText} rejects, so the two
   * oracles cannot share one method.
   */
  public static void unsignedLongPrimitiveParity(long value, PgType type, CodecContext ctx) throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    Long boxed = value;
    String name = type.getTypeName().toString();
    if (codec instanceof PrimitiveBinaryEncoder) {
      PrimitiveBinaryEncoder enc = (PrimitiveBinaryEncoder) codec;
      assertSameOutcome(name + " encodeLong(binary) vs encodeBinary",
          Outcome.capture(() -> ((BinaryCodec) codec).encodeBinary(boxed, type, ctx)),
          Outcome.capture(() -> encodeToBytes(sink -> enc.encodeLong(value, type, ctx, sink), type)));
    }
    if (codec instanceof PrimitiveTextEncoder) {
      PrimitiveTextEncoder enc = (PrimitiveTextEncoder) codec;
      assertSameOutcome(name + " encodeLong(text) vs encodeText",
          Outcome.capture(() -> ((TextCodec) codec).encodeText(boxed, type, ctx)),
          Outcome.capture(() -> encodeToText(out -> enc.encodeLong(value, type, ctx, out), type)));
    }
    if (codec instanceof PrimitiveBinaryDecoder) {
      PrimitiveBinaryDecoder dec = (PrimitiveBinaryDecoder) codec;
      byte[] wire = tryEncodeBinary((BinaryCodec) codec, boxed, type, ctx);
      if (wire != null) {
        assertSameOutcome(name + " decodeAsLong(byte[]) vs decodeBinary",
            Outcome.capture(() -> longOf(((BinaryCodec) codec).decodeBinary(wire, 0, wire.length, type, ctx))),
            Outcome.capture(() -> dec.decodeAsLong(wire, 0, wire.length, type, ctx)));
        assertEquals(dec.decodeAsLong(wire, 0, wire.length, type, ctx),
            dec.decodeAsLong(pad(wire), 3, wire.length, type, ctx),
            name + " decodeAsLong(byte[]) honours offset");
        if (wire.length > 1) {
          byte[] shortWire = Arrays.copyOf(wire, wire.length - 1);
          assertSameOutcome(name + " decodeAsLong(byte[]) rejects a short wire",
              Outcome.capture(() -> longOf(((BinaryCodec) codec).decodeBinary(shortWire, 0, shortWire.length, type, ctx))),
              Outcome.capture(() -> dec.decodeAsLong(shortWire, 0, shortWire.length, type, ctx)));
        }
      }
    }
    if (codec instanceof PrimitiveTextDecoder) {
      PrimitiveTextDecoder dec = (PrimitiveTextDecoder) codec;
      String text = Long.toUnsignedString(value);
      char[] chars = text.toCharArray();
      Outcome viaText = Outcome.capture(() -> longOf(((TextCodec) codec).decodeText(text, type, ctx)));
      assertSameOutcome(name + " decodeAsLong(String) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsLong(text, type, ctx)));
      assertSameOutcome(name + " decodeAsLong(char[]) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsLong(chars, 0, chars.length, type, ctx)));
      assertSameOutcome(name + " decodeAsLong(char[]) at offset vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsLong(pad(chars), 3, chars.length, type, ctx)));
      assertSameOutcome(name + " decodeTextBytesAsLong vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeTextBytesAsLong(text.getBytes(ctx.getCharset()), type, ctx)));
    }
  }

  /**
   * Parity oracle for the primitive capabilities on a {@code float}-valued scalar codec (float4).
   *
   * @param value the value to check
   * @param oid the scalar type OID whose codec is under test
   * @param ctx the offline codec context, which must resolve {@code oid}
   */
  public static void floatPrimitiveParity(float value, int oid, CodecContext ctx) throws SQLException {
    floatPrimitiveParity(value, PgTypeDescriptors.scalar(oid).pgType(), ctx);
  }

  /** Same, against an explicit type -- lets a domain forward the accessors to its base codec. */
  public static void floatPrimitiveParity(float value, PgType type, CodecContext ctx) throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    Float boxed = value;
    String name = type.getTypeName().toString();
    if (codec instanceof PrimitiveBinaryEncoder) {
      PrimitiveBinaryEncoder enc = (PrimitiveBinaryEncoder) codec;
      assertSameOutcome(name + " encodeFloat(binary) vs encodeBinary",
          Outcome.capture(() -> ((BinaryCodec) codec).encodeBinary(boxed, type, ctx)),
          Outcome.capture(() -> encodeToBytes(sink -> enc.encodeFloat(value, type, ctx, sink), type)));
    }
    if (codec instanceof PrimitiveTextEncoder) {
      PrimitiveTextEncoder enc = (PrimitiveTextEncoder) codec;
      assertSameOutcome(name + " encodeFloat(text) vs encodeText",
          Outcome.capture(() -> ((TextCodec) codec).encodeText(boxed, type, ctx)),
          Outcome.capture(() -> encodeToText(out -> enc.encodeFloat(value, type, ctx, out), type)));
    }
    if (codec instanceof PrimitiveBinaryDecoder) {
      PrimitiveBinaryDecoder dec = (PrimitiveBinaryDecoder) codec;
      byte[] wire = tryEncodeBinary((BinaryCodec) codec, boxed, type, ctx);
      if (wire != null) {
        assertSameOutcome(name + " decodeAsFloat(byte[]) vs decodeBinary",
            Outcome.capture(() -> floatOf(((BinaryCodec) codec).decodeBinary(wire, 0, wire.length, type, ctx))),
            Outcome.capture(() -> dec.decodeAsFloat(wire, 0, wire.length, type, ctx)));
        assertEquals(dec.decodeAsFloat(wire, 0, wire.length, type, ctx),
            dec.decodeAsFloat(pad(wire), 3, wire.length, type, ctx),
            name + " decodeAsFloat(byte[]) honours offset");
        if (wire.length > 1) {
          byte[] shortWire = Arrays.copyOf(wire, wire.length - 1);
          assertSameOutcome(name + " decodeAsFloat(byte[]) rejects a short wire",
              Outcome.capture(() -> floatOf(((BinaryCodec) codec).decodeBinary(shortWire, 0, shortWire.length, type, ctx))),
              Outcome.capture(() -> dec.decodeAsFloat(shortWire, 0, shortWire.length, type, ctx)));
        }
      }
    }
    if (codec instanceof PrimitiveTextDecoder) {
      PrimitiveTextDecoder dec = (PrimitiveTextDecoder) codec;
      String text = Float.toString(value);
      char[] chars = text.toCharArray();
      Outcome viaText = Outcome.capture(() -> floatOf(((TextCodec) codec).decodeText(text, type, ctx)));
      assertSameOutcome(name + " decodeAsFloat(String) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsFloat(text, type, ctx)));
      assertSameOutcome(name + " decodeAsFloat(char[]) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsFloat(chars, 0, chars.length, type, ctx)));
      assertSameOutcome(name + " decodeAsFloat(char[]) at offset vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsFloat(pad(chars), 3, chars.length, type, ctx)));
    }
  }

  /**
   * Parity oracle for the primitive capabilities on a {@code double}-valued scalar codec (float8).
   *
   * @param value the value to check
   * @param oid the scalar type OID whose codec is under test
   * @param ctx the offline codec context, which must resolve {@code oid}
   */
  public static void doublePrimitiveParity(double value, int oid, CodecContext ctx) throws SQLException {
    doublePrimitiveParity(value, PgTypeDescriptors.scalar(oid).pgType(), ctx);
  }

  /** Same, against an explicit type -- lets a domain forward the accessors to its base codec. */
  public static void doublePrimitiveParity(double value, PgType type, CodecContext ctx) throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    Double boxed = value;
    String name = type.getTypeName().toString();
    if (codec instanceof PrimitiveBinaryEncoder) {
      PrimitiveBinaryEncoder enc = (PrimitiveBinaryEncoder) codec;
      assertSameOutcome(name + " encodeDouble(binary) vs encodeBinary",
          Outcome.capture(() -> ((BinaryCodec) codec).encodeBinary(boxed, type, ctx)),
          Outcome.capture(() -> encodeToBytes(sink -> enc.encodeDouble(value, type, ctx, sink), type)));
    }
    if (codec instanceof PrimitiveTextEncoder) {
      PrimitiveTextEncoder enc = (PrimitiveTextEncoder) codec;
      assertSameOutcome(name + " encodeDouble(text) vs encodeText",
          Outcome.capture(() -> ((TextCodec) codec).encodeText(boxed, type, ctx)),
          Outcome.capture(() -> encodeToText(out -> enc.encodeDouble(value, type, ctx, out), type)));
    }
    if (codec instanceof PrimitiveBinaryDecoder) {
      PrimitiveBinaryDecoder dec = (PrimitiveBinaryDecoder) codec;
      byte[] wire = tryEncodeBinary((BinaryCodec) codec, boxed, type, ctx);
      if (wire != null) {
        assertSameOutcome(name + " decodeAsDouble(byte[]) vs decodeBinary",
            Outcome.capture(() -> doubleOf(((BinaryCodec) codec).decodeBinary(wire, 0, wire.length, type, ctx))),
            Outcome.capture(() -> dec.decodeAsDouble(wire, 0, wire.length, type, ctx)));
        assertEquals(dec.decodeAsDouble(wire, 0, wire.length, type, ctx),
            dec.decodeAsDouble(pad(wire), 3, wire.length, type, ctx),
            name + " decodeAsDouble(byte[]) honours offset");
        if (wire.length > 1) {
          byte[] shortWire = Arrays.copyOf(wire, wire.length - 1);
          assertSameOutcome(name + " decodeAsDouble(byte[]) rejects a short wire",
              Outcome.capture(() -> doubleOf(((BinaryCodec) codec).decodeBinary(shortWire, 0, shortWire.length, type, ctx))),
              Outcome.capture(() -> dec.decodeAsDouble(shortWire, 0, shortWire.length, type, ctx)));
        }
      }
    }
    if (codec instanceof PrimitiveTextDecoder) {
      PrimitiveTextDecoder dec = (PrimitiveTextDecoder) codec;
      String text = Double.toString(value);
      char[] chars = text.toCharArray();
      Outcome viaText = Outcome.capture(() -> doubleOf(((TextCodec) codec).decodeText(text, type, ctx)));
      assertSameOutcome(name + " decodeAsDouble(String) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsDouble(text, type, ctx)));
      assertSameOutcome(name + " decodeAsDouble(char[]) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsDouble(chars, 0, chars.length, type, ctx)));
      assertSameOutcome(name + " decodeAsDouble(char[]) at offset vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsDouble(pad(chars), 3, chars.length, type, ctx)));
    }
  }

  /**
   * Parity oracle for the primitive decode capabilities on a {@code boolean}-valued scalar codec
   * (bool). There is no primitive boolean encode -- neither {@code PrimitiveBinaryEncoder} nor
   * {@code PrimitiveTextEncoder} carries a boolean writer, so a boolean always encodes through the
   * boxing {@code encodeBinary(Boolean)} / {@code encodeText(Boolean)} path.
   *
   * @param value the value to check
   * @param oid the scalar type OID whose codec is under test
   * @param ctx the offline codec context, which must resolve {@code oid}
   */
  public static void booleanPrimitiveParity(boolean value, int oid, CodecContext ctx) throws SQLException {
    booleanPrimitiveParity(value, PgTypeDescriptors.scalar(oid).pgType(), ctx);
  }

  /** Same, against an explicit type -- lets a domain forward the accessors to its base codec. */
  public static void booleanPrimitiveParity(boolean value, PgType type, CodecContext ctx) throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    Boolean boxed = value;
    String name = type.getTypeName().toString();
    if (codec instanceof PrimitiveBinaryDecoder) {
      PrimitiveBinaryDecoder dec = (PrimitiveBinaryDecoder) codec;
      byte[] wire = ((BinaryCodec) codec).encodeBinary(boxed, type, ctx);
      assertSameOutcome(name + " decodeAsBoolean(byte[]) vs decodeBinary",
          Outcome.capture(() -> boolOf(((BinaryCodec) codec).decodeBinary(wire, 0, wire.length, type, ctx))),
          Outcome.capture(() -> dec.decodeAsBoolean(wire, 0, wire.length, type, ctx)));
      assertEquals(dec.decodeAsBoolean(wire, 0, wire.length, type, ctx),
          dec.decodeAsBoolean(pad(wire), 3, wire.length, type, ctx),
          name + " decodeAsBoolean(byte[]) honours offset");
    }
    if (codec instanceof PrimitiveTextDecoder) {
      PrimitiveTextDecoder dec = (PrimitiveTextDecoder) codec;
      String text = ((TextCodec) codec).encodeText(boxed, type, ctx);
      char[] chars = text.toCharArray();
      Outcome viaText = Outcome.capture(() -> boolOf(((TextCodec) codec).decodeText(text, type, ctx)));
      assertSameOutcome(name + " decodeAsBoolean(String) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsBoolean(text, type, ctx)));
      assertSameOutcome(name + " decodeAsBoolean(char[]) vs decodeText", viaText,
          Outcome.capture(() -> dec.decodeAsBoolean(chars, 0, chars.length, type, ctx)));
    }
  }

  // --- Domain parity: a domain forwards the primitive accessors to its base codec's no-box path,
  // so a domain over a codec with a pure accessor inherits the same outcome parity. Built here because
  // a domain type is not a registered scalar descriptor -- it needs its own offline context.

  /** Outcome parity for a domain over an {@code int}-valued base (for example {@code int4}). */
  public static void domainIntPrimitiveParity(int value, int baseOid, String baseName, char baseCat)
      throws SQLException {
    PgType domain = domainOver(baseOid, baseName, baseCat);
    intPrimitiveParity(value, domain, OfflineCodecContexts.offlineBuilder().type(domain).build());
  }

  /** Outcome parity for a domain over a {@code long}-valued base (for example {@code int8}). */
  public static void domainLongPrimitiveParity(long value, int baseOid, String baseName, char baseCat)
      throws SQLException {
    PgType domain = domainOver(baseOid, baseName, baseCat);
    longPrimitiveParity(value, domain, OfflineCodecContexts.offlineBuilder().type(domain).build());
  }

  /** Outcome parity for a domain over a {@code double}-valued base (for example {@code float8}). */
  public static void domainDoublePrimitiveParity(double value, int baseOid, String baseName, char baseCat)
      throws SQLException {
    PgType domain = domainOver(baseOid, baseName, baseCat);
    doublePrimitiveParity(value, domain, OfflineCodecContexts.offlineBuilder().type(domain).build());
  }

  /** Outcome parity for a domain over a {@code boolean}-valued base ({@code bool}). */
  public static void domainBooleanPrimitiveParity(boolean value, int baseOid, String baseName, char baseCat)
      throws SQLException {
    PgType domain = domainOver(baseOid, baseName, baseCat);
    booleanPrimitiveParity(value, domain, OfflineCodecContexts.offlineBuilder().type(domain).build());
  }

  private static PgType domainOver(int baseOid, String baseName, char baseCat) {
    return new PgType(new ObjectName("public", "dom_" + baseName), "public.dom_" + baseName,
        DOMAIN_OID, 'd', baseCat, -1, 0, 0, baseOid);
  }

  /**
   * Guards the narrowing {@code int8 -> int} accessor: reading a {@code bigint} as an {@code int} must
   * return the exact value when it fits and THROW when it does not -- never silently truncate the way
   * the boxing fallback's {@code Long.intValue()} would. This is the narrowing-accessor property the
   * outcome-parity oracle deliberately does not check, because there the override is meant to differ.
   *
   * @param value the {@code int8} value; the generator ranges well past int, so overflow is exercised
   * @param ctx the offline codec context resolving {@code int8}
   */
  public static void int8NarrowingRejectsOverflow(long value, CodecContext ctx) throws SQLException {
    PgType type = PgTypeDescriptors.scalar(Oid.INT8).pgType();
    BinaryCodec codec = (BinaryCodec) ctx.resolveCodec(Oid.INT8);
    byte[] wire = codec.encodeBinary((Long) value, type, ctx);
    PrimitiveBinaryDecoder dec = (PrimitiveBinaryDecoder) codec;
    Outcome out = Outcome.capture(() -> dec.decodeAsInt(wire, 0, wire.length, type, ctx));
    if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
      assertFalse(out.threw, () -> "int8 " + value + " fits int but decodeAsInt threw " + out.state);
      assertEquals((int) value, out.value, "int8 decodeAsInt returns the exact value when it fits");
    } else {
      assertTrue(out.threw, () -> "int8 " + value + " overflows int; decodeAsInt must reject it, not"
          + " truncate to " + out.value);
    }
  }

  // --- Getter-consistency: the primitive accessors of ONE codec agree via the standard casts ---------
  //
  // The parity oracles above pin each codec's NATIVE-width accessor to its boxing default (int8 ->
  // decodeAsLong, float8 -> decodeAsDouble, ...), but never the cross-width accessors (float8's
  // decodeAsInt, int8's decodeAsFloat) nor numeric/money/bit at all. This oracle fills that gap: on one
  // fuzzed wire it asserts the accessors of a single codec are mutually consistent.
  //
  // Two layers. Layer 1 (every primitive-decoder codec, numeric or not): the overloads of one accessor
  // -- String vs char[] vs char[]@offset vs decodeTextBytesAs* on the text side, byte[]@0 vs byte[]@offset
  // on the binary side -- return the same value whenever both succeed. Layer 2 (Number-valued codecs):
  // the int/long/float/double views of the same value agree through the standard widening/narrowing.
  //
  // Integer accessors TRUNCATE toward zero and THROW on range overflow (never saturate); so the naive
  // "decodeAsLong == decodeAsFloat" is false for a fractional value, and the lattice is stated only where
  // it is exact. long -> double -> float is correctly rounded (innocuous double rounding, 53 >= 2*24+2),
  // so (float) l == (float)((double) l) bit for bit and the two float paths agree.

  /**
   * The numeric family a codec belongs to, chosen by its type OID and {@link Codec#getDefaultJavaType()}.
   * {@code oid}/{@code oid8}/{@code xid8} are unsigned, so their {@code int} view is the raw low bits of
   * their {@code long} view rather than a signed narrowing -- a separate family from signed integrals.
   */
  private enum NumericFamily { INTEGRAL, UNSIGNED, FLOATING, DECIMAL, OTHER }

  private static NumericFamily familyOf(int oid, Class<?> javaType) {
    if (oid == Oid.OID || oid == Oid.OID8 || oid == Oid.XID8) {
      return NumericFamily.UNSIGNED;
    }
    if (javaType == Integer.class || javaType == Long.class || javaType == Short.class) {
      return NumericFamily.INTEGRAL;
    }
    if (javaType == Float.class || javaType == Double.class) {
      return NumericFamily.FLOATING;
    }
    if (javaType == BigDecimal.class) {
      return NumericFamily.DECIMAL;
    }
    // String, byte[], PGobject (bit, money, fallback), Boolean: Layer 1 only, no numeric lattice.
    return NumericFamily.OTHER;
  }

  // A synthetic unknown scalar that resolves to the unpinned FallbackCodec: its OID is not pinned and
  // its typtype 'b' / typcategory 'X' route to no container, while a scalar() type carries no text-like
  // typsend, so getByOid falls all the way through to the fallback. It lets the fuzzer cover
  // FallbackCodec's primitive text/byte accessors, which the OID-driven enumeration would otherwise
  // skip; consistencyContext() registers it so it resolves offline.
  private static final int FALLBACK_PROBE_OID = 999_999;
  private static final PgType FALLBACK_PROBE_TYPE = scalar(FALLBACK_PROBE_OID, "fuzz_unknown", 'X');

  private static final List<PgType> PRIMITIVE_DECODER_TYPES = buildPrimitiveDecoderTypes();

  /**
   * A representative scalar {@link PgType} for every built-in codec that opts into
   * {@link PrimitiveBinaryDecoder} or {@link PrimitiveTextDecoder}, derived from the pinned OID map so a
   * newly pinned primitive-decoder codec joins the getter-consistency fuzzer automatically, followed by
   * a synthetic unknown type for the unpinned {@link FallbackCodec}. Each pinned type is a generic scalar
   * ({@code typmod -1}) resolved by its canonical OID; the {@code typcategory} does not steer resolution
   * of a pinned OID (see {@link #scalar}). Resolve these through {@link #consistencyContext()}, which
   * registers the synthetic type.
   *
   * @return the representative types, in OID order, then the Fallback probe
   */
  public static List<PgType> primitiveDecoderTypes() {
    return PRIMITIVE_DECODER_TYPES;
  }

  /**
   * The offline context for {@link #primitiveDecoderTypes()}: the shared built-ins plus the synthetic
   * unknown type registered, so both the pinned types and the {@link FallbackCodec} probe resolve.
   *
   * @return the offline codec context that drives the getter-consistency oracle
   */
  public static CodecContext consistencyContext() {
    return with(FALLBACK_PROBE_TYPE);
  }

  private static List<PgType> buildPrimitiveDecoderTypes() {
    List<PgType> types = new ArrayList<>();
    for (Map.Entry<Integer, Codec> entry : new CodecRegistry().builtinCodecsByOid().entrySet()) {
      Codec codec = entry.getValue();
      if (codec instanceof PrimitiveBinaryDecoder || codec instanceof PrimitiveTextDecoder) {
        types.add(scalar(entry.getKey(), codec.getTypeName(), 'X'));
      }
    }
    // The synthetic unknown type must resolve to the unpinned FallbackCodec (not TextLikeCodec or a
    // container), or the intended Fallback coverage would silently exercise the wrong codec.
    try {
      Codec probe = with(FALLBACK_PROBE_TYPE).resolveCodec(FALLBACK_PROBE_OID);
      if (probe != FallbackCodec.INSTANCE) {
        throw new ExceptionInInitializerError(
            "Fallback probe resolved to " + probe.getClass().getName() + ", not FallbackCodec");
      }
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
    types.add(FALLBACK_PROBE_TYPE);
    return Collections.unmodifiableList(types);
  }

  /**
   * Asserts the binary primitive accessors of {@code type}'s codec are mutually consistent on
   * {@code wire}. A no-op when the codec is not a {@link PrimitiveBinaryDecoder}, so it is safe for any
   * type. See the section comment for the properties.
   *
   * @param type the backend type whose codec the context resolves
   * @param wire the (fuzzed) binary wire bytes fed to every accessor
   * @param ctx the offline codec context, which must resolve {@code type}
   */
  public static void binaryGetterConsistency(PgType type, byte[] wire, CodecContext ctx)
      throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    if (!(codec instanceof PrimitiveBinaryDecoder)) {
      return;
    }
    PrimitiveBinaryDecoder dec = (PrimitiveBinaryDecoder) codec;
    String name = codec.getTypeName();
    int len = wire.length;
    Outcome oi = Outcome.capture(() -> dec.decodeAsInt(wire, 0, len, type, ctx));
    Outcome ol = Outcome.capture(() -> dec.decodeAsLong(wire, 0, len, type, ctx));
    Outcome of = Outcome.capture(() -> dec.decodeAsFloat(wire, 0, len, type, ctx));
    Outcome od = Outcome.capture(() -> dec.decodeAsDouble(wire, 0, len, type, ctx));
    Outcome ob = Outcome.capture(() -> dec.decodeAsBoolean(wire, 0, len, type, ctx));

    // Layer 1: every accessor honours the offset (same value read at offset 0 and at offset 3 of a pad).
    byte[] pad = pad(wire);
    assertValueAgrees(name + " decodeAsInt(byte[]) offset", oi,
        Outcome.capture(() -> dec.decodeAsInt(pad, 3, len, type, ctx)));
    assertValueAgrees(name + " decodeAsLong(byte[]) offset", ol,
        Outcome.capture(() -> dec.decodeAsLong(pad, 3, len, type, ctx)));
    assertValueAgrees(name + " decodeAsFloat(byte[]) offset", of,
        Outcome.capture(() -> dec.decodeAsFloat(pad, 3, len, type, ctx)));
    assertValueAgrees(name + " decodeAsDouble(byte[]) offset", od,
        Outcome.capture(() -> dec.decodeAsDouble(pad, 3, len, type, ctx)));
    assertValueAgrees(name + " decodeAsBoolean(byte[]) offset", ob,
        Outcome.capture(() -> dec.decodeAsBoolean(pad, 3, len, type, ctx)));

    // Layer 2 + getObject cross-check.
    numericLattice(name + " binary", familyOf(type.getOid(), codec.getDefaultJavaType()), oi, ol, of, od);
    getObjectConsistency(name + " getObject(binary)", type, RawValue.binary(wire), ctx, oi, ol, of, od);
  }

  /**
   * Asserts the text primitive accessors of {@code type}'s codec are mutually consistent on
   * {@code text}. A no-op when the codec is not a {@link PrimitiveTextDecoder}. See the section comment.
   *
   * @param type the backend type whose codec the context resolves
   * @param text the (fuzzed) text input fed to every accessor
   * @param ctx the offline codec context, which must resolve {@code type}
   */
  public static void textGetterConsistency(PgType type, String text, CodecContext ctx)
      throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    if (!(codec instanceof PrimitiveTextDecoder)) {
      return;
    }
    PrimitiveTextDecoder dec = (PrimitiveTextDecoder) codec;
    String name = codec.getTypeName();
    char[] chars = text.toCharArray();
    char[] pad = pad(chars);
    int clen = chars.length;
    byte[] textBytes = text.getBytes(ctx.getCharset());
    Outcome oi = Outcome.capture(() -> dec.decodeAsInt(text, type, ctx));
    Outcome ol = Outcome.capture(() -> dec.decodeAsLong(text, type, ctx));
    Outcome of = Outcome.capture(() -> dec.decodeAsFloat(text, type, ctx));
    Outcome od = Outcome.capture(() -> dec.decodeAsDouble(text, type, ctx));
    Outcome ob = Outcome.capture(() -> dec.decodeAsBoolean(text, type, ctx));

    // Layer 1: the String, char[], char[]@offset and (int/long) ASCII-bytes forms agree with the String
    // form whenever both succeed.
    assertValueAgrees(name + " decodeAsInt(char[])", oi,
        Outcome.capture(() -> dec.decodeAsInt(chars, 0, clen, type, ctx)));
    assertValueAgrees(name + " decodeAsInt(char[]) offset", oi,
        Outcome.capture(() -> dec.decodeAsInt(pad, 3, clen, type, ctx)));
    assertValueAgrees(name + " decodeTextBytesAsInt", oi,
        Outcome.capture(() -> dec.decodeTextBytesAsInt(textBytes, type, ctx)));
    assertValueAgrees(name + " decodeAsLong(char[])", ol,
        Outcome.capture(() -> dec.decodeAsLong(chars, 0, clen, type, ctx)));
    assertValueAgrees(name + " decodeAsLong(char[]) offset", ol,
        Outcome.capture(() -> dec.decodeAsLong(pad, 3, clen, type, ctx)));
    assertValueAgrees(name + " decodeTextBytesAsLong", ol,
        Outcome.capture(() -> dec.decodeTextBytesAsLong(textBytes, type, ctx)));
    assertValueAgrees(name + " decodeAsFloat(char[])", of,
        Outcome.capture(() -> dec.decodeAsFloat(chars, 0, clen, type, ctx)));
    assertValueAgrees(name + " decodeAsFloat(char[]) offset", of,
        Outcome.capture(() -> dec.decodeAsFloat(pad, 3, clen, type, ctx)));
    assertValueAgrees(name + " decodeAsDouble(char[])", od,
        Outcome.capture(() -> dec.decodeAsDouble(chars, 0, clen, type, ctx)));
    assertValueAgrees(name + " decodeAsDouble(char[]) offset", od,
        Outcome.capture(() -> dec.decodeAsDouble(pad, 3, clen, type, ctx)));
    assertValueAgrees(name + " decodeAsBoolean(char[])", ob,
        Outcome.capture(() -> dec.decodeAsBoolean(chars, 0, clen, type, ctx)));

    numericLattice(name + " text", familyOf(type.getOid(), codec.getDefaultJavaType()), oi, ol, of, od);
  }

  // --- Reproducible failures: turn a bare assertion into "which type, on what data, how to repro" -----

  /**
   * Wraps a getter-consistency {@link AssertionError} from a binary check with the exact fuzzed wire and
   * a reproduction pointer, so a fuzz failure names the data it happened on rather than only the
   * accessor. The {@code @FuzzTest} calls this per type; the wrapped message keeps the original
   * expected/actual as its tail and cause.
   *
   * @param failure the original assertion failure
   * @param type the backend type whose codec failed
   * @param wire the binary wire the fuzzer fed
   * @return an {@link AssertionError} whose message leads with the type, the wire and a repro recipe
   */
  public static AssertionError binaryReproError(AssertionError failure, PgType type, byte[] wire) {
    return reproError(failure, type, "binary wire", describeWire(wire), "binaryGetterConsistency");
  }

  /**
   * Wraps a getter-consistency {@link AssertionError} from a text check with the exact fuzzed text and a
   * reproduction pointer. See {@link #binaryReproError}.
   *
   * @param failure the original assertion failure
   * @param type the backend type whose codec failed
   * @param text the text the fuzzer fed
   * @return an {@link AssertionError} whose message leads with the type, the text and a repro recipe
   */
  public static AssertionError textReproError(AssertionError failure, PgType type, String text) {
    return reproError(failure, type, "text", describeText(text), "textGetterConsistency");
  }

  private static AssertionError reproError(AssertionError failure, PgType type, String inputKind,
      String inputDesc, String method) {
    String header = "getter-consistency failure on oid " + type.getOid() + " -- " + inputKind + " "
        + inputDesc + "\n  reproduce: CodecFuzzSupport." + method + "(type for oid " + type.getOid()
        + ", " + inputKind + " above, CodecFuzzSupport.consistencyContext())\n  cause: ";
    return new AssertionError(header + failure.getMessage(), failure);
  }

  /** Renders a fuzzed wire as {@code 0x<hex> (N bytes)}, capped, for a copy-pasteable failure message. */
  private static String describeWire(byte[] wire) {
    int shown = Math.min(wire.length, 256);
    StringBuilder sb = new StringBuilder(2 + shown * 2 + 16).append("0x");
    for (int i = 0; i < shown; i++) {
      sb.append(Character.forDigit((wire[i] >> 4) & 0xF, 16)).append(Character.forDigit(wire[i] & 0xF, 16));
    }
    if (wire.length > shown) {
      sb.append("...");
    }
    return sb.append(" (").append(wire.length).append(" bytes)").toString();
  }

  /** Renders fuzzed text as a Java-escaped, capped literal {@code "..." (N chars)}. */
  private static String describeText(String text) {
    int shown = Math.min(text.length(), 256);
    StringBuilder sb = new StringBuilder(shown + 16).append('"');
    for (int i = 0; i < shown; i++) {
      char c = text.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c == '\n') {
        sb.append("\\n");
      } else if (c == '\r') {
        sb.append("\\r");
      } else if (c == '\t') {
        sb.append("\\t");
      } else if (c < 0x20 || c > 0x7e) {
        sb.append(String.format("\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    sb.append('"');
    if (text.length() > shown) {
      sb.append("...");
    }
    return sb.append(" (").append(text.length()).append(" chars)").toString();
  }

  /**
   * The Layer-2 numeric lattice: the {@code int}/{@code long}/{@code float}/{@code double} views of one
   * value agree through the standard casts. Applied per {@link NumericFamily}; a no-op for
   * {@link NumericFamily#OTHER}.
   */
  private static void numericLattice(String label, NumericFamily family,
      Outcome oi, Outcome ol, Outcome of, Outcome od) {
    // Success-monotonicity, EVERY family (before the numeric-only lattice below): float and double are
    // equi-permissive floating reads -- float saturates and never range-overflows, so a value readable
    // as one is readable as the other -- and long is wider than int. A codec that reads a value as one
    // of a pair must read it as the other, or its accessor set is half-overridden. This is the check
    // that a decodeAsDouble override paired with a defaulting decodeAsFloat (which boxes a non-Number
    // and refuses) fails -- the getDouble/getFloat gap FallbackCodec had.
    assertTrue(od.threw == of.threw,
        () -> label + " getDouble/getFloat success must agree (getDouble " + describe(od)
            + ", getFloat " + describe(of) + ")");
    if (!oi.threw) {
      assertFalse(ol.threw, () -> label + " getInt read " + oi.value + " but getLong refused it");
    }
    if (family == NumericFamily.OTHER) {
      return;
    }
    if (family == NumericFamily.UNSIGNED) {
      // getInt is the raw low 32 bits of getLong (a signed reinterpretation), not a range-checked
      // narrowing, so the signed INV-1/INV-2/INV-3a-b do not apply. The sound relations are the
      // truncating one and the float-narrowing one.
      if (!oi.threw && !ol.threw) {
        assertEquals((int) longOf(ol.value), intOf(oi.value),
            label + " INV-U1: decodeAsInt == (int) decodeAsLong");
      }
      if (!od.threw && !of.threw) {
        assertFloatBits(label + " INV-U3c: decodeAsFloat == (float) decodeAsDouble",
            (float) doubleOf(od.value), floatOf(of.value));
      }
      return;
    }
    if (family == NumericFamily.INTEGRAL) {
      // INV-1: int fits => long succeeds and is the same integer.
      if (!oi.threw) {
        assertFalse(ol.threw, () -> label + " INV-1: decodeAsInt succeeded but decodeAsLong threw");
        assertEquals((long) intOf(oi.value), longOf(ol.value),
            label + " INV-1: decodeAsLong == (long) decodeAsInt");
      }
      // INV-3a/3b: reading the integer as double/float is the exact cast of the long.
      if (!ol.threw) {
        long l = longOf(ol.value);
        assertFalse(od.threw, () -> label + " INV-3a: decodeAsLong succeeded but decodeAsDouble threw");
        assertDoubleBits(label + " INV-3a: decodeAsDouble == (double) decodeAsLong",
            (double) l, doubleOf(od.value));
        assertFalse(of.threw, () -> label + " INV-3b: decodeAsLong succeeded but decodeAsFloat threw");
        assertFloatBits(label + " INV-3b: decodeAsFloat == (float) decodeAsLong",
            (float) l, floatOf(of.value));
      }
    }
    if (!od.threw) {
      double d = doubleOf(od.value);
      // INV-3c: reading as float is the narrowing cast of reading as double (integral + floating).
      if (family == NumericFamily.INTEGRAL || family == NumericFamily.FLOATING) {
        assertFalse(of.threw, () -> label + " INV-3c: decodeAsDouble succeeded but decodeAsFloat threw");
        assertFloatBits(label + " INV-3c: decodeAsFloat == (float) decodeAsDouble",
            (float) d, floatOf(of.value));
      }
      // INV-2: a value outside int range must be REJECTED by decodeAsInt, never truncated/saturated.
      if (Double.isFinite(d) && (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)) {
        assertTrue(oi.threw, () -> label + " INV-2: value " + d
            + " is out of int range but decodeAsInt did not throw (returned " + oi.value + ")");
      }
    }
  }

  /**
   * The getObject cross-check: when both {@code Codecs.decode(.., X.class)} and the primitive accessor
   * of that width produce a value, the two values agree. It has teeth on the codec's own default type
   * ({@code decode(int8, Long.class)} vs {@code decodeAsLong}); for a target the boxing cast rejects,
   * {@code decode} throws and there is nothing to compare. The success-ness itself is not compared: the
   * boxing path ({@code decodeBinaryAs}) and the primitive path may validate a malformed-length wire
   * with different strictness, which is not the value consistency this checks.
   */
  private static void getObjectConsistency(String label, PgType type, RawValue raw, CodecContext ctx,
      Outcome oi, Outcome ol, Outcome of, Outcome od) {
    assertGetObject(label + " Integer", type, raw, ctx, Integer.class, oi);
    assertGetObject(label + " Long", type, raw, ctx, Long.class, ol);
    assertGetObject(label + " Float", type, raw, ctx, Float.class, of);
    assertGetObject(label + " Double", type, raw, ctx, Double.class, od);
  }

  private static void assertGetObject(String label, PgType type, RawValue raw, CodecContext ctx,
      Class<?> target, Outcome primitive) {
    @Nullable Object decoded;
    try {
      decoded = Codecs.decode(raw, type, ctx, target);
    } catch (SQLException | RuntimeException e) {
      // "либо падает": a target the codec cannot produce (or a bad wire) is an allowed outcome.
      return;
    }
    if (decoded == null || primitive.threw) {
      return;
    }
    assertEquals(primitive.value, decoded, label + ": getObject == primitive accessor");
  }

  /** Layer-1 overload agreement: two forms of one accessor return the same value when both succeed. */
  private static void assertValueAgrees(String label, Outcome a, Outcome b) {
    if (!a.threw && !b.threw) {
      // Boxed Float/Double equality is bit-canonical (NaN == NaN, -0.0 != 0.0), which is what we want.
      assertEquals(a.value, b.value, label + " overloads agree");
    }
  }

  /** Bit-canonical float equality (all NaNs equal, -0.0f distinct from 0.0f). */
  private static void assertFloatBits(String label, float expected, float actual) {
    assertEquals(Float.floatToIntBits(expected), Float.floatToIntBits(actual), label);
  }

  /** Bit-canonical double equality (all NaNs equal, -0.0 distinct from 0.0). */
  private static void assertDoubleBits(String label, double expected, double actual) {
    assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual), label);
  }

  // The result of one codec call: the value it returned, or the SQLState it failed with. Two calls
  // have "the same outcome" when they both returned equal values or both failed with the same state.
  private static final class Outcome {
    private final boolean threw;
    private final @Nullable String state;
    private final @Nullable Object value;

    private Outcome(boolean threw, @Nullable String state, @Nullable Object value) {
      this.threw = threw;
      this.state = state;
      this.value = value;
    }

    static Outcome capture(ThrowingSupplier call) {
      try {
        return new Outcome(false, null, call.get());
      } catch (SQLException e) {
        return new Outcome(true, e.getSQLState(), null);
      }
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier {
    @Nullable Object get() throws SQLException;
  }

  private static void assertSameOutcome(String label, Outcome boxed, Outcome primitive) {
    if (boxed.threw || primitive.threw) {
      assertEquals(boxed.threw, primitive.threw, () -> label
          + ": boxed " + describe(boxed) + " but primitive " + describe(primitive));
      assertEquals(boxed.state, primitive.state, () -> label + ": both threw, but with a different"
          + " SQLState (boxed " + boxed.state + ", primitive " + primitive.state + ")");
      return;
    }
    if (boxed.value instanceof byte[] && primitive.value instanceof byte[]) {
      assertArrayEquals((byte[]) boxed.value, (byte[]) primitive.value, label);
    } else {
      assertEquals(boxed.value, primitive.value, label);
    }
  }

  private static String describe(Outcome o) {
    return o.threw ? "threw SQLState " + o.state : "returned " + o.value;
  }

  /** Encodes {@code boxed}, or {@code null} when the codec rejects it as out of range. */
  private static byte @Nullable [] tryEncodeBinary(BinaryCodec codec, Object boxed, TypeDescriptor type,
      CodecContext ctx) {
    Outcome enc = Outcome.capture(() -> codec.encodeBinary(boxed, type, ctx));
    // enc.threw == false guarantees encodeBinary returned a value, a correlation the checker cannot
    // see through the Outcome fields.
    return enc.threw ? null
        : (byte[]) Nullness.castNonNull(enc.value, "encodeBinary returned null without throwing");
  }

  private static int intOf(@Nullable Object value) {
    return ((Number) Nullness.castNonNull(value)).intValue();
  }

  private static long longOf(@Nullable Object value) {
    return ((Number) Nullness.castNonNull(value)).longValue();
  }

  private static float floatOf(@Nullable Object value) {
    return ((Number) Nullness.castNonNull(value)).floatValue();
  }

  private static double doubleOf(@Nullable Object value) {
    return ((Number) Nullness.castNonNull(value)).doubleValue();
  }

  private static boolean boolOf(@Nullable Object value) {
    return (Boolean) Nullness.castNonNull(value);
  }

  /** A primitive binary encode driven into a fresh sink, as the raw value bytes. */
  private interface BinaryEncode {
    void writeTo(BackpatchByteArrayOutputStream sink) throws SQLException, IOException;
  }

  /** A primitive text encode driven into a fresh buffer. */
  private interface TextEncode {
    void writeTo(StringBuilder out) throws SQLException, IOException;
  }

  private static byte[] encodeToBytes(BinaryEncode encode, PgType type) throws SQLException {
    BackpatchByteArrayOutputStream sink = new BackpatchByteArrayOutputStream();
    try {
      encode.writeTo(sink);
    } catch (IOException e) {
      throw new AssertionError(type.getTypeName() + " primitive binary encode threw IOException", e);
    }
    return sink.toByteArray();
  }

  private static String encodeToText(TextEncode encode, PgType type) throws SQLException {
    StringBuilder out = new StringBuilder();
    try {
      encode.writeTo(out);
    } catch (IOException e) {
      throw new AssertionError(type.getTypeName() + " primitive text encode threw IOException", e);
    }
    return out.toString();
  }

  /** Copies {@code data} to offset 3 of a 5-byte-larger buffer, so a slice decode must skip the pad. */
  private static byte[] pad(byte[] data) {
    byte[] padded = new byte[3 + data.length + 2];
    System.arraycopy(data, 0, padded, 3, data.length);
    return padded;
  }

  /** Copies {@code data} to offset 3 of a buffer padded with a non-digit, catching an over-read. */
  private static char[] pad(char[] data) {
    char[] padded = new char[3 + data.length + 2];
    Arrays.fill(padded, 'x');
    System.arraycopy(data, 0, padded, 3, data.length);
    return padded;
  }

  /**
   * A {@link CodecContext} that resolves every codec through {@link NonStreamingCodec}, so a
   * delegating codec sees no child that opts into the streaming interfaces and takes its
   * materialising fallback branch. Every other setting is delegated unchanged.
   */
  private static final class NonStreamingContext implements CodecContext {
    private final CodecContext delegate;

    NonStreamingContext(CodecContext delegate) {
      this.delegate = delegate;
    }

    @Override
    public Codec resolveCodec(int oid) throws SQLException {
      return NonStreamingCodec.wrap(delegate.resolveCodec(oid));
    }

    @Override
    public TypeDescriptor resolveType(int oid) throws SQLException {
      return delegate.resolveType(oid);
    }

    @Override
    public CodecContext withoutJavaTimePreferences() {
      CodecContext inner = delegate.withoutJavaTimePreferences();
      return inner == delegate ? this : new NonStreamingContext(inner);
    }

    @Override
    public Charset getCharset() {
      return delegate.getCharset();
    }

    @Override
    public boolean usesDoubleDateTime() {
      return delegate.usesDoubleDateTime();
    }

    @Override
    public TimeZone getClientTimeZone() {
      return delegate.getClientTimeZone();
    }

    @Override
    public TimeZone getDefaultTimeZone() {
      return delegate.getDefaultTimeZone();
    }

    @Override
    public @Nullable Calendar getCalendar() {
      return delegate.getCalendar();
    }

    @Override
    public boolean prefersJavaTimeForDate() {
      return delegate.prefersJavaTimeForDate();
    }

    @Override
    public boolean prefersJavaTimeForTime() {
      return delegate.prefersJavaTimeForTime();
    }

    @Override
    public boolean prefersJavaTimeForTimetz() {
      return delegate.prefersJavaTimeForTimetz();
    }

    @Override
    public boolean prefersJavaTimeForTimestamp() {
      return delegate.prefersJavaTimeForTimestamp();
    }

    @Override
    public boolean prefersJavaTimeForTimestamptz() {
      return delegate.prefersJavaTimeForTimestamptz();
    }

    @Override
    public boolean getConvertBooleanToNumeric() {
      return delegate.getConvertBooleanToNumeric();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() {
      return delegate.getTypeMap();
    }

    @Override
    public @Nullable Class<?> getMappedClass(String typeName) {
      return delegate.getMappedClass(typeName);
    }
  }

  /**
   * A {@link BinaryCodec} and {@link TextCodec} that forwards every call to a wrapped codec but does
   * not implement {@link StreamingBinaryCodec} or {@link StreamingTextCodec}, so a delegating codec
   * resolving it as a child takes the materialising branch. Capability probes
   * ({@code supportsBinaryEncoding}, {@code canEncodeBinary}, {@code mayRequireQuoting}) are delegated
   * too, so the wrapped codec still steers format selection exactly as it would unwrapped.
   */
  private static final class NonStreamingCodec implements BinaryCodec, TextCodec {
    private final BinaryCodec bin;
    private final TextCodec txt;

    private NonStreamingCodec(BinaryCodec bin, TextCodec txt) {
      this.bin = bin;
      this.txt = txt;
    }

    /**
     * Wraps {@code codec} only when it streams and carries both wire faces (every delegating codec
     * and its registered children do). A codec that streams neither face needs no de-streaming, and
     * one that carries a single face is returned unchanged rather than gaining a face it lacked.
     */
    static Codec wrap(Codec codec) {
      boolean streams = codec instanceof StreamingBinaryCodec || codec instanceof StreamingTextCodec;
      if (streams && codec instanceof BinaryCodec && codec instanceof TextCodec) {
        return new NonStreamingCodec((BinaryCodec) codec, (TextCodec) codec);
      }
      return codec;
    }

    @Override
    public String getTypeName() {
      return bin.getTypeName();
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return bin.getDefaultJavaType();
    }

    @Override
    public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
      return bin.encodeBinary(value, type, ctx);
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
        CodecContext ctx) throws SQLException {
      return bin.decodeBinary(data, offset, length, type, ctx);
    }

    @Override
    public boolean supportsBinaryEncoding() {
      return bin.supportsBinaryEncoding();
    }

    @Override
    public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
      return bin.canEncodeBinary(value, type, ctx);
    }

    @Override
    public boolean supportsBinaryRead() {
      return bin.supportsBinaryRead();
    }

    @Override
    public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
      return txt.encodeText(value, type, ctx);
    }

    @Override
    public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
      return txt.decodeText(data, type, ctx);
    }

    @Override
    public @Nullable Object decodeText(char[] data, int offset, int length, TypeDescriptor type,
        CodecContext ctx) throws SQLException {
      return txt.decodeText(data, offset, length, type, ctx);
    }

    @Override
    public boolean mayRequireQuoting() {
      return txt.mayRequireQuoting();
    }

    @Override
    public boolean supportsTextRead() {
      return txt.supportsTextRead();
    }
  }

  // --- Round-trip properties: decode(encode(v)) == v -----------------------------------------

  /**
   * Encodes then decodes {@code value} in both wire formats and asserts the result equals the
   * input. For a lossless codec this must hold for every generated value.
   */
  public static <T> void roundTrip(Object value, PgType type, Class<T> target, CodecContext ctx)
      throws SQLException {
    encodeParity(value, type, ctx);
    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, type, ctx, format);
      @Nullable T back = Codecs.decode(raw, type, ctx, target);
      assertEquals(value, back, () -> type.getTypeName() + " " + format + " round-trip");
    }
  }

  /**
   * Round-trips {@code value} through the text format alone and asserts the result equals the input.
   * The two-format {@link #roundTrip} would fail for a codec that carries only a text path; use this
   * variant for such a codec instead.
   *
   * @param value the value to encode then decode as text
   * @param type the offline scalar type
   * @param target the class to decode into
   * @param ctx the offline codec context
   * @param <T> the target type
   */
  public static <T> void roundTripText(Object value, PgType type, Class<T> target, CodecContext ctx)
      throws SQLException {
    encodeParity(value, type, ctx);
    RawValue raw = Codecs.encode(value, type, ctx, Format.TEXT);
    @Nullable T back = Codecs.decode(raw, type, ctx, target);
    assertEquals(value, back, () -> type.getTypeName() + " text round-trip");
  }

  /** numeric round-trip compared by value ({@code compareTo}) so trailing-zero scale is ignored. */
  public static void numericRoundTrip(BigDecimal value, CodecContext ctx) throws SQLException {
    PgType numeric = PgTypeDescriptors.scalar(Oid.NUMERIC).pgType();
    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, numeric, ctx, format);
      BigDecimal back = Nullness.castNonNull(Codecs.decode(raw, numeric, ctx, BigDecimal.class),
          "numeric round-trip decoded null for a non-null written value");
      assertEquals(0, value.compareTo(back),
          () -> "numeric " + format + ": " + value + " != " + back);
    }
  }

  /** bytea round-trip: the decoded bytes must equal the input bytes. */
  public static void byteaRoundTrip(byte[] value, CodecContext ctx) throws SQLException {
    PgType bytea = PgTypeDescriptors.scalar(Oid.BYTEA).pgType();
    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, bytea, ctx, format);
      byte @Nullable [] back = Codecs.decode(raw, bytea, ctx, byte[].class);
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
      @Nullable Object back = Codecs.decode(raw, arrayType, ctx, target);
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
      Struct back = Nullness.castNonNull(Codecs.decode(raw, type, ctx, Struct.class),
          "struct round-trip decoded null for a non-null written struct");
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
    Struct back = Nullness.castNonNull(Codecs.decode(raw, recordType, ctx, Struct.class),
        "record round-trip decoded null for a non-null written record");
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
    encodeParity(value, type, ctx);
    @Nullable T viaText = Codecs.decode(Codecs.encode(value, type, ctx, Format.TEXT), type, ctx, target);
    @Nullable T viaBinary =
        Codecs.decode(Codecs.encode(value, type, ctx, Format.BINARY), type, ctx, target);
    assertEquals(viaText, viaBinary, () -> type.getTypeName() + " text vs binary");
  }

  public static void numericCrossFormat(BigDecimal value, CodecContext ctx) throws SQLException {
    PgType numeric = PgTypeDescriptors.scalar(Oid.NUMERIC).pgType();
    BigDecimal viaText = Nullness.castNonNull(Codecs.decode(
        Codecs.encode(value, numeric, ctx, Format.TEXT), numeric, ctx, BigDecimal.class),
        "numeric cross-format decoded null for a non-null written value");
    BigDecimal viaBinary = Nullness.castNonNull(Codecs.decode(
        Codecs.encode(value, numeric, ctx, Format.BINARY), numeric, ctx, BigDecimal.class),
        "numeric cross-format decoded null for a non-null written value");
    assertEquals(0, viaText.compareTo(viaBinary),
        () -> "numeric text " + viaText + " vs binary " + viaBinary);
  }

  public static void byteaCrossFormat(byte[] value, CodecContext ctx) throws SQLException {
    PgType bytea = PgTypeDescriptors.scalar(Oid.BYTEA).pgType();
    byte @Nullable [] viaText = Codecs.decode(
        Codecs.encode(value, bytea, ctx, Format.TEXT), bytea, ctx, byte[].class);
    byte @Nullable [] viaBinary = Codecs.decode(
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
    @Nullable Object viaText = Codecs.decode(
        Codecs.encode(value, arrayType, ctx, Format.TEXT), arrayType, ctx, target);
    @Nullable Object viaBinary = Codecs.decode(
        Codecs.encode(value, arrayType, ctx, Format.BINARY), arrayType, ctx, target);
    if (!descriptor.fidelity().equal(viaText, viaBinary)) {
      throw new AssertionError(arrayType.getTypeName() + " text vs binary: " + leafRepr + " " + ndim
          + "D mismatch");
    }
  }

  public static void structCrossFormat(PgType type, Object[] attributes, CodecContext ctx)
      throws SQLException {
    Struct viaText = Nullness.castNonNull(Codecs.decode(
        Codecs.encode(new PgStruct(type, attributes, null), type, ctx, Format.TEXT),
        type, ctx, Struct.class), "struct cross-format decoded null for a non-null written struct");
    Struct viaBinary = Nullness.castNonNull(Codecs.decode(
        Codecs.encode(new PgStruct(type, attributes, null), type, ctx, Format.BINARY),
        type, ctx, Struct.class), "struct cross-format decoded null for a non-null written struct");
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
    @Nullable Object[] attributes = new Object[node.fields.size()];
    for (int i = 0; i < node.fields.size(); i++) {
      FuzzNode.Field field = node.fields.get(i);
      int fieldOid;
      if (field.isScalar()) {
        fieldOid = field.scalarOid;
        attributes[i] = field.scalarValue;
      } else {
        // field.isScalar() == false guarantees field.nested is set (see FuzzNode.Field), a
        // correlation the checker cannot see through the two nullable fields.
        Built child = build(Nullness.castNonNull(field.nested), counter, registry);
        fieldOid = child.type.getOid();
        attributes[i] = child.struct;
      }
      pgFields.add(new PgField("f" + (i + 1), fieldOid, i + 1, -1));
    }
    PgType type;
    if (node.anonymous) {
      // An anonymous RECORD is not resolvable by OID; the PgStruct carries its own type so the
      // encoder can read the fields (CompositeCodec.fieldTypeFor prefers the carried type for a
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
    PgCodecContext.OfflineBuilder builder = OfflineCodecContexts.offlineBuilder();
    for (PgType type : registry) {
      builder.type(type);
    }
    CodecContext ctx = builder.build();

    Format[] formats = root.anonymous ? new Format[]{Format.BINARY} : Format.values();
    for (Format format : formats) {
      RawValue raw = Codecs.encode(built.struct, built.type, ctx, format);
      Struct decoded = Nullness.castNonNull(Codecs.decode(raw, built.type, ctx, Struct.class),
          "nested composite round-trip decoded null for a non-null written struct");
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
        // field.isScalar() == false guarantees field.nested is set (see FuzzNode.Field).
        assertNodeEquals(Nullness.castNonNull(field.nested), (Struct) attributes[i]);
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
    CodecContext ctx = OfflineCodecContexts.offlineBuilder().type(element).type(arrayType).build();

    @Nullable PgStruct[] structs = new PgStruct[rows.length];
    for (int i = 0; i < rows.length; i++) {
      structs[i] = rows[i] == null ? null : new PgStruct(element, rows[i], null);
    }
    RawValue raw = Codecs.encode(structs, arrayType, ctx, Format.BINARY);
    Object[] decoded = (Object[]) Nullness.castNonNull(Codecs.decode(raw, arrayType, ctx, Object.class),
        "record[] round-trip decoded null for a non-null written array");

    assertEquals(rows.length, decoded.length, "record[] length");
    for (int i = 0; i < rows.length; i++) {
      if (rows[i] == null) {
        assertNull(decoded[i], "record[] null element");
      } else {
        assertArrayEquals(rows[i], ((Struct) decoded[i]).getAttributes(), "record[] element");
      }
    }
  }

  // --- Delegating codecs: range, multirange, domain ------------------------------------------

  // A fixed OID for the offline domain type. Codec resolution keys on typtype 'd', not the OID, but
  // the value must still be unique across the fuzz suite: OfflineCodecContexts shares one registry
  // whose OID cache outlives a single test, so reusing an OID that another target maps to a different
  // codec (SINGLE_FIELD_COMPOSITE_OID once did) leaks that codec here and breaks encode.
  private static final int DOMAIN_OID = 90_040;

  /**
   * Round-trips a domain value through the {@link org.postgresql.jdbc.codec.DomainCodec}, which
   * forwards to the base type's codec. The property runs the two-format round-trip and, through
   * {@link #encodeParity}, pins the domain's streaming form against its materialising one -- a
   * genuine check here, because the domain's {@code byte[]}/{@code String} form materialises the base
   * value while its streaming form streams the base, so the two agree only if the base codec's own
   * forms do.
   *
   * @param baseOid the base type OID the domain wraps (for example {@link Oid#INT4})
   * @param baseTypeName the base type name, used only to name the synthetic domain type
   * @param baseCategory the base type's {@code typcategory}
   * @param value the domain value (a value of the base type)
   * @param target the class to decode into
   */
  public static void domainRoundTrip(int baseOid, String baseTypeName, char baseCategory,
      Object value, Class<?> target) throws SQLException {
    PgType domain = new PgType(new ObjectName("public", "dom_" + baseTypeName),
        "public.dom_" + baseTypeName, DOMAIN_OID, 'd', baseCategory, -1, 0, 0, baseOid);
    CodecContext ctx = OfflineCodecContexts.offlineBuilder().type(domain).build();
    roundTrip(value, domain, target, ctx);
  }

  /**
   * Asserts a range value encodes to the same wire bytes whether its bound codec streams or
   * materialises, through {@link #streamVsMaterializeParity}. The range's {@code byte[]} form buffers
   * its own streaming form, so {@link #encodeParity} cannot tell the two bound paths apart; only the
   * de-streamed run reaches {@link org.postgresql.jdbc.codec.RangeCodec}'s materialising bound branch.
   *
   * @param rangeOid the range type OID (for example {@code 3904} for {@code int4range})
   * @param rangeTypeName the range type name
   * @param subtypeOid the range subtype OID (for example {@link Oid#INT4})
   * @param value the range value, whose bounds are of the subtype's Java class
   */
  public static void rangeStreamParity(int rangeOid, String rangeTypeName, int subtypeOid,
      PGRange<?> value) throws SQLException {
    PgType rangeType = new PgType(new ObjectName("pg_catalog", rangeTypeName), rangeTypeName,
        rangeOid, 'r', 'R', -1, 0, 0, 0).withRangeSubtype(subtypeOid);
    CodecContext ctx = OfflineCodecContexts.offlineBuilder().type(rangeType).build();
    streamVsMaterializeParity(value, rangeType, ctx);
  }

  /**
   * Asserts a multirange value encodes to the same wire bytes whether its element ranges stream or
   * materialise, through {@link #streamVsMaterializeParity}. As with {@link #rangeStreamParity}, the
   * multirange's {@code byte[]} form buffers its own streaming form, so only the de-streamed run
   * reaches {@link org.postgresql.jdbc.codec.MultirangeCodec}'s materialising element branch.
   *
   * @param multirangeOid the multirange type OID (for example {@code 4451} for {@code int4multirange})
   * @param multirangeTypeName the multirange type name
   * @param rangeOid the element range type OID
   * @param rangeTypeName the element range type name
   * @param subtypeOid the range subtype OID
   * @param value the multirange value
   */
  public static void multirangeStreamParity(int multirangeOid, String multirangeTypeName,
      int rangeOid, String rangeTypeName, int subtypeOid, PGmultirange<?> value) throws SQLException {
    PgType rangeType = new PgType(new ObjectName("pg_catalog", rangeTypeName), rangeTypeName,
        rangeOid, 'r', 'R', -1, 0, 0, 0).withRangeSubtype(subtypeOid);
    PgType multirangeType = new PgType(new ObjectName("pg_catalog", multirangeTypeName),
        multirangeTypeName, multirangeOid, 'm', 'R', -1, 0, 0, 0).withMultirangeRange(rangeOid);
    CodecContext ctx = OfflineCodecContexts.offlineBuilder().type(rangeType).type(multirangeType).build();
    streamVsMaterializeParity(value, multirangeType, ctx);
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
    CodecContext ctx = OfflineCodecContexts.offlineBuilder().type(type)
        .timeZone(TimeZone.getDefault()).build();

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, type, ctx, format);
      @Nullable FuzzSqlData back = Codecs.decode(raw, type, ctx, FuzzSqlData.class);
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
