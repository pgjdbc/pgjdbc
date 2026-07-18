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
 * Unit tests for {@link Oid8ArrayLeafCodec}, the {@code oid8} array fast leaf. {@code oid8} is an
 * unsigned 64-bit object identifier: 8 bytes on the wire, read as the raw bit pattern (matching
 * {@link Oid8Codec}), with text elements formatted and parsed as unsigned decimal.
 */
class Oid8ArrayLeafCodecTest {

  private static final Oid8ArrayLeafCodec LEAF = Oid8ArrayLeafCodec.INSTANCE;
  private static final long MAX_UNSIGNED = -1L; // bit pattern of 2^64 - 1

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
  void oid8Codec_advertisesThisLeaf() {
    assertSame(LEAF, Oid8Codec.INSTANCE.arrayLeaf());
    assertEquals(Oid.OID8, LEAF.getElementOid());
    assertEquals(long.class, LEAF.getPrimitiveComponentType());
    assertEquals(Long.class, LEAF.getBoxedComponentType());
  }

  @Test
  void encodeBinary_longArray_writesPackedHeader() throws SQLException {
    long[] input = {0, 16384, MAX_UNSIGNED};
    byte[] bytes = encodeBinary(input);

    assertEquals(20 + (4 + 8) * input.length, bytes.length);
    assertEquals(Oid.OID8, ByteConverter.int4(bytes, 8)); // element OID
    assertEquals(8, ByteConverter.int4(bytes, 20));       // first element length
    assertEquals(0, ByteConverter.int8(bytes, 24));       // 0
    assertEquals(16384, ByteConverter.int8(bytes, 36));   // 16384
  }

  @Test
  void decodeBinary_unsignedRoundTrip() throws SQLException {
    long[] input = {0, 1, 16384, Long.MIN_VALUE, MAX_UNSIGNED};
    long[] roundTrip = (long[]) decodeBinary(encodeBinary(input), long.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_LongArrayWithNull_roundTrip() throws SQLException {
    Long[] input = {1L, null, MAX_UNSIGNED};
    Long[] roundTrip = (Long[]) decodeBinary(encodeBinary(input), Long.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_longArray_rejectsNull() throws SQLException {
    byte[] withNull = encodeBinary(new Long[]{1L, null});
    assertThrows(SQLException.class, () -> decodeBinary(withNull, long.class));
  }

  @Test
  void decodeBinary_emptyArray() throws SQLException {
    Long[] decoded = (Long[]) decodeBinary(encodeBinary(new Long[]{}), Long.class);
    assertArrayEquals(new Long[]{}, decoded);
  }

  @Test
  void text_unsignedRoundTrip() throws SQLException {
    assertEquals("{0,16384,18446744073709551615}", encodeText(new long[]{0, 16384, MAX_UNSIGNED}));
    long[] decoded = (long[]) decodeText("{0,16384,18446744073709551615}", long.class);
    assertArrayEquals(new long[]{0, 16384, MAX_UNSIGNED}, decoded);
  }

  @Test
  void text_LongArrayWithNull() throws SQLException {
    Long[] decoded = (Long[]) decodeText("{1,NULL,18446744073709551615}", Long.class);
    assertArrayEquals(new Long[]{1L, null, MAX_UNSIGNED}, decoded);
  }

  @Test
  void multiDim_binaryAndText_roundTrip() throws SQLException {
    long[][] input = {{1, 2}, {3, MAX_UNSIGNED}};
    long[][] viaBinary = (long[][]) decodeBinary(encodeBinary(input), long.class);
    assertArrayEquals(input[0], viaBinary[0]);
    assertArrayEquals(input[1], viaBinary[1]);

    long[][] viaText = (long[][]) decodeText(encodeText(input), long.class);
    assertArrayEquals(input[0], viaText[0]);
    assertArrayEquals(input[1], viaText[1]);
  }

  @Test
  void registry_oid8_isArrayElementCodec() {
    CodecRegistry registry = new CodecRegistry();
    assertInstanceOf(ArrayElementCodec.class, registry.getByName("oid8"));
  }
}
