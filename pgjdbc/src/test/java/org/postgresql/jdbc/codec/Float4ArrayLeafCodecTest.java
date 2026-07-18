/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * Unit tests for {@link Float4ArrayLeafCodec}, the {@code float4} array fast leaf.
 * Boxed component type is {@code Float} (the legacy {@code getArray()} type for
 * float4[]); {@code NaN}/{@code Infinity}/{@code -Infinity} are pinned in both
 * binary and text forms.
 */
class Float4ArrayLeafCodecTest {

  private static final Float4ArrayLeafCodec LEAF = Float4ArrayLeafCodec.INSTANCE;

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
  void float4Codec_advertisesThisLeaf() {
    assertSame(LEAF, Float4Codec.INSTANCE.arrayLeaf());
    assertEquals(Oid.FLOAT4, LEAF.getElementOid());
    assertEquals(float.class, LEAF.getPrimitiveComponentType());
    assertEquals(Float.class, LEAF.getBoxedComponentType());
  }

  @Test
  void encodeBinary_floatArray_writesPackedHeader() throws SQLException {
    float[] input = {1.5f, -2.5f, 3.5f};
    byte[] bytes = encodeBinary(input);

    assertEquals(20 + (4 + 4) * input.length, bytes.length);
    assertEquals(1, ByteConverter.int4(bytes, 0));          // dimensions
    assertEquals(0, ByteConverter.int4(bytes, 4));          // hasNulls
    assertEquals(Oid.FLOAT4, ByteConverter.int4(bytes, 8)); // element OID
    assertEquals(3, ByteConverter.int4(bytes, 12));         // length
    assertEquals(4, ByteConverter.int4(bytes, 20));         // first element length
    assertEquals(1.5f, ByteConverter.float4(bytes, 24), 0.0f);
  }

  @Test
  void decodeBinary_floatArray_packedRoundTrip() throws SQLException {
    float[] input = {3.5f, -4.5f, 0.0f, 1.25f};
    float[] roundTrip = (float[]) decodeBinary(encodeBinary(input), float.class);
    assertArrayEquals(input, roundTrip, 0.0f);
  }

  @Test
  void decodeBinary_FloatArrayWithNull_roundTrip() throws SQLException {
    Float[] input = {1.5f, null, -5.5f};
    Float[] roundTrip = (Float[]) decodeBinary(encodeBinary(input), Float.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_floatArray_rejectsNull() throws SQLException {
    byte[] withNull = encodeBinary(new Float[]{1.5f, null});
    assertThrows(SQLException.class, () -> decodeBinary(withNull, float.class));
  }

  @Test
  void decodeBinary_emptyArray() throws SQLException {
    Float[] decoded = (Float[]) decodeBinary(encodeBinary(new Float[]{}), Float.class);
    assertArrayEquals(new Float[]{}, decoded);
  }

  @Test
  void text_floatArray_roundTrip() throws SQLException {
    assertEquals("{1.5,-2.5,3.5}", encodeText(new float[]{1.5f, -2.5f, 3.5f}));
    float[] decoded = (float[]) decodeText("{1.5,-2.5,3.5}", float.class);
    assertArrayEquals(new float[]{1.5f, -2.5f, 3.5f}, decoded, 0.0f);
  }

  @Test
  void text_nanAndInfinity_matchPostgresSpelling() throws SQLException {
    Float[] input = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.5f};
    assertEquals("{NaN,Infinity,-Infinity,1.5}", encodeText(input));
    Float[] decoded = (Float[]) decodeText("{NaN,Infinity,-Infinity,1.5}", Float.class);
    assertArrayEquals(input, decoded);
  }

  @Test
  void binary_nanAndInfinity_roundTrip() throws SQLException {
    Float[] input = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
    Float[] decoded = (Float[]) decodeBinary(encodeBinary(input), Float.class);
    assertArrayEquals(input, decoded);
  }

  @Test
  void multiDim_binaryAndText_roundTrip() throws SQLException {
    float[][] input = {{1.5f, 2.5f}, {3.5f, 4.5f}};
    float[][] viaBinary = (float[][]) decodeBinary(encodeBinary(input), float.class);
    assertArrayEquals(input[0], viaBinary[0], 0.0f);
    assertArrayEquals(input[1], viaBinary[1], 0.0f);

    assertEquals("{{1.5,2.5},{3.5,4.5}}", encodeText(input));
    float[][] viaText = (float[][]) decodeText("{{1.5,2.5},{3.5,4.5}}", float.class);
    assertArrayEquals(input[0], viaText[0], 0.0f);
    assertArrayEquals(input[1], viaText[1], 0.0f);
  }

  @Test
  void registry_float4_isArrayElementCodec() {
    CodecRegistry registry = new CodecRegistry();
    assertInstanceOf(ArrayElementCodec.class, registry.getByName("float4"));
  }
}
