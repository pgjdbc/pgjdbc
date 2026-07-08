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
 * Unit tests for {@link Int2ArrayLeafCodec}, the {@code int2} array fast leaf.
 * The boxed component type is {@code Short}, matching the legacy
 * {@code getArray()} return type for int2[] — distinct from the scalar codec's
 * {@code Integer} default.
 */
class Int2ArrayLeafCodecTest {

  private static final Int2ArrayLeafCodec LEAF = Int2ArrayLeafCodec.INSTANCE;

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
  void int2Codec_advertisesThisLeaf() {
    assertSame(LEAF, Int2Codec.INSTANCE.arrayLeaf());
    assertEquals(Oid.INT2, LEAF.getElementOid());
    assertEquals(short.class, LEAF.getPrimitiveComponentType());
    assertEquals(Short.class, LEAF.getBoxedComponentType());
  }

  @Test
  void encodeBinary_shortArray_writesPackedHeader() throws SQLException {
    short[] input = {1, -2, 3};
    byte[] bytes = encodeBinary(input);

    assertEquals(20 + (4 + 2) * input.length, bytes.length);
    assertEquals(1, ByteConverter.int4(bytes, 0));         // dimensions
    assertEquals(0, ByteConverter.int4(bytes, 4));         // hasNulls
    assertEquals(Oid.INT2, ByteConverter.int4(bytes, 8));  // element OID
    assertEquals(3, ByteConverter.int4(bytes, 12));        // length
    assertEquals(2, ByteConverter.int4(bytes, 20));        // first element length
    assertEquals((short) 1, ByteConverter.int2(bytes, 24));
    assertEquals((short) -2, ByteConverter.int2(bytes, 30));
  }

  @Test
  void decodeBinary_shortArray_packedRoundTrip() throws SQLException {
    short[] input = {7, -42, 0, Short.MAX_VALUE, Short.MIN_VALUE};
    short[] roundTrip = (short[]) decodeBinary(encodeBinary(input), short.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_ShortArrayWithNull_roundTrip() throws SQLException {
    Short[] input = {1, null, -5};
    Short[] roundTrip = (Short[]) decodeBinary(encodeBinary(input), Short.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_shortArray_rejectsNull() throws SQLException {
    byte[] withNull = encodeBinary(new Short[]{1, null, 3});
    assertThrows(SQLException.class, () -> decodeBinary(withNull, short.class));
  }

  @Test
  void decodeBinary_emptyArray() throws SQLException {
    Short[] decoded = (Short[]) decodeBinary(encodeBinary(new Short[]{}), Short.class);
    assertArrayEquals(new Short[]{}, decoded);
  }

  @Test
  void text_shortArray_roundTrip() throws SQLException {
    assertEquals("{1,2,3}", encodeText(new short[]{1, 2, 3}));
    short[] decoded = (short[]) decodeText("{1,2,3}", short.class);
    assertArrayEquals(new short[]{1, 2, 3}, decoded);
  }

  @Test
  void text_ShortArrayWithNull_roundTrip() throws SQLException {
    assertEquals("{1,NULL,3}", encodeText(new Short[]{1, null, 3}));
    Short[] decoded = (Short[]) decodeText("{1,NULL,3}", Short.class);
    assertArrayEquals(new Short[]{1, null, 3}, decoded);
  }

  @Test
  void text_rangeBounds() throws SQLException {
    short[] decoded = (short[]) decodeText("{32767,-32768}", short.class);
    assertArrayEquals(new short[]{Short.MAX_VALUE, Short.MIN_VALUE}, decoded);
  }

  @Test
  void multiDim_binaryAndText_roundTrip() throws SQLException {
    short[][] input = {{1, 2}, {3, 4}};
    short[][] viaBinary = (short[][]) decodeBinary(encodeBinary(input), short.class);
    assertArrayEquals(input[0], viaBinary[0]);
    assertArrayEquals(input[1], viaBinary[1]);

    assertEquals("{{1,2},{3,4}}", encodeText(input));
    short[][] viaText = (short[][]) decodeText("{{1,2},{3,4}}", short.class);
    assertArrayEquals(input[0], viaText[0]);
    assertArrayEquals(input[1], viaText[1]);
  }

  @Test
  void decodeBinary_nullSlotsPreserved() throws SQLException {
    Short[] decoded = (Short[]) decodeBinary(
        encodeBinary(new Short[]{null, 7, null}), Short.class);
    assertNull(decoded[0]);
    assertEquals((short) 7, decoded[1]);
    assertNull(decoded[2]);
  }

  @Test
  void registry_int2_isArrayElementCodec() {
    CodecRegistry registry = new CodecRegistry();
    assertInstanceOf(ArrayElementCodec.class, registry.getByName("int2"));
  }
}
