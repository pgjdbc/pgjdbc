/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgStruct;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.Collections;

/**
 * Exercises the typmod-carrying descriptor offline: a {@code numeric} value decodes to its declared
 * scale — including a negative scale such as {@code numeric(2,-2)} that the wire {@code dscale} does
 * not convey — when, and only when, the descriptor reports that modifier. Covers the scalar column
 * path plus the composite-field and domain paths that stamp the modifier from the catalog.
 *
 * <p>All connectionless, so it runs without a server. The equivalent connected assertion is
 * {@code PreparedStatementTest.testNegativeNumericScale}.</p>
 */
class NumericTypmodDecodeTest {

  private static final PgType NUMERIC = new PgType(
      new ObjectName("pg_catalog", "numeric"), "numeric", Oid.NUMERIC, 'b', 'N', -1, 0, 0, 0);

  /** Encodes a {@code numeric} typmod: precision in the high 16 bits, scale (sign-extended) below. */
  private static int numericTypmod(int precision, int scale) {
    return ((precision << 16) | (scale & 0xffff)) + 4;
  }

  private static BigDecimal scaled(String value, int scale) {
    return new BigDecimal(value).setScale(scale, RoundingMode.HALF_EVEN);
  }

  @Test
  void decodeBinaryRescalesToNegativeTypmodScale() throws SQLException {
    CodecContext ctx = OfflineCodecs.builder().build();
    byte[] wire = NumericCodec.INSTANCE.encodeBinary(new BigDecimal("1500"), NUMERIC, ctx);
    PgType scaledType = NUMERIC.withTypmod(numericTypmod(2, -2));

    // getObject contract: the value carries the column's declared scale, so numeric(2,-2) reports
    // scale -2 even though the wire dscale is 0.
    assertEquals(scaled("1500", -2),
        NumericCodec.INSTANCE.decodeBinary(wire, 0, wire.length, scaledType, ctx),
        "decodeBinary numeric(2,-2)");

    // Without a modifier the decode stays wire-faithful (scale 0).
    assertEquals(new BigDecimal("1500"),
        NumericCodec.INSTANCE.decodeBinary(wire, 0, wire.length, NUMERIC, ctx),
        "decodeBinary numeric (no typmod)");
  }

  @Test
  void decodeBinaryPadsToPositiveTypmodScale() throws SQLException {
    CodecContext ctx = OfflineCodecs.builder().build();
    byte[] wire = NumericCodec.INSTANCE.encodeBinary(new BigDecimal("5"), NUMERIC, ctx);
    PgType scaledType = NUMERIC.withTypmod(numericTypmod(10, 2));

    assertEquals(scaled("5", 2),
        NumericCodec.INSTANCE.decodeBinary(wire, 0, wire.length, scaledType, ctx),
        "decodeBinary numeric(10,2) pads to 5.00");
  }

  @Test
  void decodeHonoursDescriptorModifierAndIsWireFaithfulWithout() throws SQLException {
    CodecContext ctx = OfflineCodecs.builder().build();
    PgType scaledType = NUMERIC.withTypmod(numericTypmod(2, -2));
    byte[] wire = NumericCodec.INSTANCE.encodeBinary(new BigDecimal("1500"), NUMERIC, ctx);

    // getObject on a text numeric rescales, mirroring the binary path.
    assertEquals(scaled("1500", -2),
        NumericCodec.INSTANCE.decodeText("1500", scaledType, ctx), "decodeText numeric(2,-2)");

    // decodeAsBigDecimal honours the descriptor's modifier: a stamped descriptor (what a
    // numeric(p,s)[] element passes) rescales, while a mod-less one (what getBigDecimal on a plain
    // column passes) stays wire-faithful. This is what keeps getBigDecimal wire-faithful while
    // getArray() rescales.
    BigDecimal stamped = NumericCodec.INSTANCE.decodeAsBigDecimal(wire, 0, wire.length, scaledType, ctx);
    assertNotNull(stamped, "decodeAsBigDecimal stamped");
    assertEquals(-2, stamped.scale(), "decodeAsBigDecimal honours a stamped modifier");
    BigDecimal wireFaithful = NumericCodec.INSTANCE.decodeAsBigDecimal(wire, 0, wire.length, NUMERIC, ctx);
    assertNotNull(wireFaithful, "decodeAsBigDecimal mod-less");
    assertEquals(0, wireFaithful.scale(), "decodeAsBigDecimal stays wire-faithful without a modifier");

    // getString renders the plain server text either way; the rescale does not change it.
    assertEquals("1500",
        NumericCodec.INSTANCE.decodeAsString(wire, 0, wire.length, scaledType, ctx),
        "decodeAsString numeric(2,-2)");
  }

  private static PgType numericArray(int precision, int scale) {
    return new PgType(new ObjectName("pg_catalog", "_numeric"), "numeric[]",
        Oid.NUMERIC_ARRAY, 'b', 'A', -1, Oid.NUMERIC, 0, 0).withTypmod(numericTypmod(precision, scale));
  }

  @Test
  void arrayColumnModifierRescalesEachElement() throws SQLException {
    // numeric(2,-2)[]: the array column modifier is the element modifier, so decoding through a
    // modifier-carrying descriptor yields each element at the declared scale -2.
    PgType type = numericArray(2, -2);
    CodecContext ctx = OfflineCodecs.builder().build();
    BigDecimal[] value = {new BigDecimal("1500"), new BigDecimal("2500")};

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, type, ctx, format);
      Object[] back = (Object[]) Codecs.decode(raw, type, ctx, Object.class);
      assertNotNull(back, () -> "numeric(2,-2)[] " + format);
      assertEquals(scaled("1500", -2), back[0], () -> "element 0 " + format);
      assertEquals(scaled("2500", -2), back[1], () -> "element 1 " + format);
    }
  }

  @Test
  void codecHonoursArrayModifierAcrossTypedTargets() throws SQLException {
    // The codec honours the descriptor's modifier for every element target, not only the default
    // Object/BigDecimal[] one that falls through to the generic walker. numeric(10,4) with a wire
    // scale of 1 makes the difference observable: rescaling pads to 5.5000, wire-faithful keeps 5.5.
    PgType stamped = numericArray(10, 4);
    PgType modLess = new PgType(new ObjectName("pg_catalog", "_numeric"), "numeric[]",
        Oid.NUMERIC_ARRAY, 'b', 'A', -1, Oid.NUMERIC, 0, 0);
    CodecContext ctx = OfflineCodecs.builder().build();
    BigDecimal[] value = {new BigDecimal("5.5")};

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, stamped, ctx, format);

      Object[] asObject = (Object[]) Codecs.decode(raw, stamped, ctx, Object.class);
      assertEquals(scaled("5.5", 4), asObject[0], () -> "Object.class " + format);

      BigDecimal[] asBigDecimals = Codecs.decode(raw, stamped, ctx, BigDecimal[].class);
      assertNotNull(asBigDecimals, () -> "BigDecimal[].class " + format);
      assertEquals(scaled("5.5", 4), asBigDecimals[0], () -> "BigDecimal[].class " + format);

      String[] asStrings = Codecs.decode(raw, stamped, ctx, String[].class);
      assertNotNull(asStrings, () -> "String[].class " + format);
      assertEquals("5.5000", asStrings[0], () -> "String[].class " + format);

      // A mod-less descriptor stays wire-faithful for every target — the ResultSet hands typed
      // getObject(col, T[].class) this shape, which is why typed array access is wire-faithful.
      BigDecimal[] wireFaithful = Codecs.decode(
          Codecs.encode(value, modLess, ctx, format), modLess, ctx, BigDecimal[].class);
      assertNotNull(wireFaithful, () -> "mod-less BigDecimal[].class " + format);
      assertEquals(1, wireFaithful[0].scale(), () -> "mod-less stays wire-faithful " + format);
    }
  }

  @Test
  void compositeFieldModifierRescalesStructAttribute() throws SQLException {
    // A composite with a numeric(2,-2) attribute; the atttypmod reaches the field codec via the
    // stamped descriptor, so the decoded struct attribute carries scale -2.
    PgField numericField = new PgField("p", Oid.NUMERIC, 1, numericTypmod(2, -2));
    PgType composite = new PgType(new ObjectName("public", "typmod_t"), "public.typmod_t", 90_100,
        'c', 'C', -1, 0, 0, 0, ',', Collections.singletonList(numericField));
    CodecContext ctx = OfflineCodecs.builder().type(composite).build();
    PgStruct value = new PgStruct(composite, new Object[]{new BigDecimal("1500")}, null);

    for (Format format : Format.values()) {
      RawValue raw = Codecs.encode(value, composite, ctx, format);
      Struct decoded = Codecs.decode(raw, composite, ctx, Struct.class);
      assertNotNull(decoded, () -> "composite decode " + format);
      assertEquals(scaled("1500", -2), decoded.getAttributes()[0],
          () -> "composite numeric(2,-2) field " + format);
    }
  }

  @Test
  void domainModifierRescalesBaseTypeDecode() throws SQLException {
    // CREATE DOMAIN price AS numeric(2,-2): the modifier lives in the domain's typtypmod, and
    // DomainCodec forwards it to the base numeric codec.
    PgType domain = new PgType(new ObjectName("public", "price"), "public.price", 90_101,
        'd', 'N', numericTypmod(2, -2), 0, 0, Oid.NUMERIC);
    CodecContext ctx = OfflineCodecs.builder().type(domain).build();
    byte[] wire = NumericCodec.INSTANCE.encodeBinary(new BigDecimal("1500"), NUMERIC, ctx);

    assertEquals(scaled("1500", -2),
        DomainCodec.INSTANCE.decodeBinary(wire, 0, wire.length, domain, ctx),
        "domain over numeric(2,-2)");
  }
}
