/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * Unit tests for {@link Float8ArrayLeafCodec}, the {@code float8} array fast leaf.
 * Boxed component type is {@code Double} (the legacy {@code getArray()} type for
 * float8[]); {@code NaN}/{@code Infinity}/{@code -Infinity} are pinned in both
 * binary and text forms.
 */
class Float8ArrayLeafCodecTest {

  private static final Float8ArrayLeafCodec LEAF = Float8ArrayLeafCodec.INSTANCE;

  private static byte[] encodeBinary(Object array) throws SQLException {
    return MultiDimArrayBinary.encode(array, null, LEAF);
  }

  private static Object decodeBinary(byte[] data, Class<?> leafComponentType) throws SQLException {
    return MultiDimArrayBinary.decode(data, 0, data.length, leafComponentType, null, LEAF);
  }

  private static String encodeText(Object array) throws SQLException {
    return MultiDimArrayText.encode(array, ',', null, LEAF);
  }

  private static Object decodeText(String literal, Class<?> leafComponentType) throws SQLException {
    return MultiDimArrayText.decode(literal, leafComponentType, ',', null, LEAF);
  }

  @Test
  void float8Codec_advertisesThisLeaf() {
    assertSame(LEAF, Float8Codec.INSTANCE.arrayLeaf());
    assertEquals(Oid.FLOAT8, LEAF.getElementOid());
    assertEquals(double.class, LEAF.getPrimitiveComponentType());
    assertEquals(Double.class, LEAF.getBoxedComponentType());
  }

  @Test
  void encodeBinary_doubleArray_writesPackedHeader() throws SQLException {
    double[] input = {1.5, -2.5, 3.5};
    byte[] bytes = encodeBinary(input);

    assertEquals(20 + (4 + 8) * input.length, bytes.length);
    assertEquals(1, ByteConverter.int4(bytes, 0));          // dimensions
    assertEquals(0, ByteConverter.int4(bytes, 4));          // hasNulls
    assertEquals(Oid.FLOAT8, ByteConverter.int4(bytes, 8)); // element OID
    assertEquals(3, ByteConverter.int4(bytes, 12));         // length
    assertEquals(8, ByteConverter.int4(bytes, 20));         // first element length
    assertEquals(1.5, ByteConverter.float8(bytes, 24), 0.0);
  }

  @Test
  void decodeBinary_doubleArray_packedRoundTrip() throws SQLException {
    double[] input = {3.5, -4.5, 0.0, 10.0 / 3, 77};
    double[] roundTrip = (double[]) decodeBinary(encodeBinary(input), double.class);
    assertArrayEquals(input, roundTrip, 0.0);
  }

  @Test
  void decodeBinary_DoubleArrayWithNull_roundTrip() throws SQLException {
    Double[] input = {1.5, null, -5.5};
    Double[] roundTrip = (Double[]) decodeBinary(encodeBinary(input), Double.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_doubleArray_rejectsNull() throws SQLException {
    byte[] withNull = encodeBinary(new Double[]{1.5, null});
    assertThrows(SQLException.class, () -> decodeBinary(withNull, double.class));
  }

  @Test
  void decodeBinary_emptyArray() throws SQLException {
    Double[] decoded = (Double[]) decodeBinary(encodeBinary(new Double[]{}), Double.class);
    assertArrayEquals(new Double[]{}, decoded);
  }

  @Test
  void text_doubleArray_roundTrip() throws SQLException {
    assertEquals("{1.5,-2.5,3.5}", encodeText(new double[]{1.5, -2.5, 3.5}));
    double[] decoded = (double[]) decodeText("{1.5,-2.5,3.5}", double.class);
    assertArrayEquals(new double[]{1.5, -2.5, 3.5}, decoded, 0.0);
  }

  @Test
  void text_DoubleArrayWithNull_roundTrip() throws SQLException {
    assertEquals("{1.5,NULL,3.5}", encodeText(new Double[]{1.5, null, 3.5}));
    Double[] decoded = (Double[]) decodeText("{1.5,NULL,3.5}", Double.class);
    assertArrayEquals(new Double[]{1.5, null, 3.5}, decoded);
  }

  @Test
  void text_nanAndInfinity_matchPostgresSpelling() throws SQLException {
    Double[] input = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.5};
    assertEquals("{NaN,Infinity,-Infinity,1.5}", encodeText(input));
    Double[] decoded = (Double[]) decodeText("{NaN,Infinity,-Infinity,1.5}", Double.class);
    assertArrayEquals(input, decoded);
  }

  @Test
  void binary_nanAndInfinity_roundTrip() throws SQLException {
    Double[] input = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    Double[] decoded = (Double[]) decodeBinary(encodeBinary(input), Double.class);
    assertArrayEquals(input, decoded);
  }

  @Test
  void multiDim_binaryAndText_roundTrip() throws SQLException {
    double[][] input = {{1.5, 2.5}, {3.5, 4.5}};
    double[][] viaBinary = (double[][]) decodeBinary(encodeBinary(input), double.class);
    assertArrayEquals(input[0], viaBinary[0], 0.0);
    assertArrayEquals(input[1], viaBinary[1], 0.0);

    assertEquals("{{1.5,2.5},{3.5,4.5}}", encodeText(input));
    double[][] viaText = (double[][]) decodeText("{{1.5,2.5},{3.5,4.5}}", double.class);
    assertArrayEquals(input[0], viaText[0], 0.0);
    assertArrayEquals(input[1], viaText[1], 0.0);
  }

  @Test
  void decodeBinary_nullSlotsPreserved() throws SQLException {
    Double[] decoded = (Double[]) decodeBinary(
        encodeBinary(new Double[]{null, 7.5, null}), Double.class);
    assertNull(decoded[0]);
    assertEquals(7.5, decoded[1]);
    assertNull(decoded[2]);
  }

  @Test
  void registry_float8_isArrayElementCodec() {
    CodecRegistry registry = new CodecRegistry();
    assertInstanceOf(ArrayElementCodec.class, registry.getByName("float8"));
  }
}
