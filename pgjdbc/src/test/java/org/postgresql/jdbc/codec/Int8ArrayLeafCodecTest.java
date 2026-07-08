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
 * Unit tests for {@link Int8ArrayLeafCodec}, the {@code int8} array fast leaf,
 * exercised through the shared {@link MultiDimArrayBinary} / {@link MultiDimArrayText}
 * walkers. Mirrors {@code Int4ArrayLeafCodecTest}; the boxed component type is
 * {@code Long}, matching the legacy {@code getArray()} return type for int8[].
 */
class Int8ArrayLeafCodecTest {

  private static final Int8ArrayLeafCodec LEAF = Int8ArrayLeafCodec.INSTANCE;

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
  void int8Codec_advertisesThisLeaf() {
    assertSame(LEAF, Int8Codec.INSTANCE.arrayLeaf());
    assertEquals(Oid.INT8, LEAF.getElementOid());
    assertEquals(long.class, LEAF.getPrimitiveComponentType());
    assertEquals(Long.class, LEAF.getBoxedComponentType());
  }

  @Test
  void encodeBinary_longArray_writesPackedHeader() throws SQLException {
    long[] input = {1, -2, 3};
    byte[] bytes = encodeBinary(input);

    assertEquals(20 + (4 + 8) * input.length, bytes.length);
    assertEquals(1, ByteConverter.int4(bytes, 0));         // dimensions
    assertEquals(0, ByteConverter.int4(bytes, 4));         // hasNulls
    assertEquals(Oid.INT8, ByteConverter.int4(bytes, 8));  // element OID
    assertEquals(3, ByteConverter.int4(bytes, 12));        // length
    assertEquals(8, ByteConverter.int4(bytes, 20));        // first element length
    assertEquals(1L, ByteConverter.int8(bytes, 24));
    assertEquals(-2L, ByteConverter.int8(bytes, 36));
  }

  @Test
  void decodeBinary_longArray_packedRoundTrip() throws SQLException {
    long[] input = {7, -42, 0, Long.MAX_VALUE, Long.MIN_VALUE};
    long[] roundTrip = (long[]) decodeBinary(encodeBinary(input), long.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_LongArrayWithNull_roundTrip() throws SQLException {
    Long[] input = {1L, null, -5L};
    Long[] roundTrip = (Long[]) decodeBinary(encodeBinary(input), Long.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_longArray_rejectsNull() throws SQLException {
    byte[] withNull = encodeBinary(new Long[]{1L, null, 3L});
    assertThrows(SQLException.class, () -> decodeBinary(withNull, long.class));
  }

  @Test
  void decodeBinary_emptyArray() throws SQLException {
    Long[] decoded = (Long[]) decodeBinary(encodeBinary(new Long[]{}), Long.class);
    assertArrayEquals(new Long[]{}, decoded);
  }

  @Test
  void text_longArray_roundTrip() throws SQLException {
    assertEquals("{1,2,3}", encodeText(new long[]{1, 2, 3}));
    long[] decoded = (long[]) decodeText("{1,2,3}", long.class);
    assertArrayEquals(new long[]{1, 2, 3}, decoded);
  }

  @Test
  void text_LongArrayWithNull_roundTrip() throws SQLException {
    assertEquals("{1,NULL,3}", encodeText(new Long[]{1L, null, 3L}));
    Long[] decoded = (Long[]) decodeText("{1,NULL,3}", Long.class);
    assertArrayEquals(new Long[]{1L, null, 3L}, decoded);
  }

  @Test
  void text_quotedNumbersAndBigValues() throws SQLException {
    assertArrayEquals(new long[]{1, 2}, (long[]) decodeText("{\"1\",\"2\"}", long.class));
    long[] big = (long[]) decodeText("{9223372036854775807,-9223372036854775808}", long.class);
    assertArrayEquals(new long[]{Long.MAX_VALUE, Long.MIN_VALUE}, big);
  }

  @Test
  void multiDim_binaryAndText_roundTrip() throws SQLException {
    long[][] input = {{1, 2}, {3, 4}};
    long[][] viaBinary = (long[][]) decodeBinary(encodeBinary(input), long.class);
    assertArrayEquals(input[0], viaBinary[0]);
    assertArrayEquals(input[1], viaBinary[1]);

    assertEquals("{{1,2},{3,4}}", encodeText(input));
    long[][] viaText = (long[][]) decodeText("{{1,2},{3,4}}", long.class);
    assertArrayEquals(input[0], viaText[0]);
    assertArrayEquals(input[1], viaText[1]);
  }

  @Test
  void decodeBinary_nullSlotsPreserved() throws SQLException {
    Long[] decoded = (Long[]) decodeBinary(encodeBinary(new Long[]{null, 7L, null}), Long.class);
    assertNull(decoded[0]);
    assertEquals(7L, decoded[1]);
    assertNull(decoded[2]);
  }

  @Test
  void registry_int8_isArrayElementCodec() {
    CodecRegistry registry = new CodecRegistry();
    assertInstanceOf(ArrayElementCodec.class, registry.getByName("int8"));
  }
}
