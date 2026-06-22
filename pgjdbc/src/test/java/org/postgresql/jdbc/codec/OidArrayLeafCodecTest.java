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
 * Unit tests for {@link OidArrayLeafCodec}, the {@code oid} array fast leaf.
 * OID is an unsigned 32-bit integer: 4 bytes on the wire, read as an unsigned
 * {@code long}, with {@code Long[]} as the boxed array type (matching legacy
 * {@code getArray()} for oid[]).
 */
class OidArrayLeafCodecTest {

  private static final OidArrayLeafCodec LEAF = OidArrayLeafCodec.INSTANCE;
  private static final long MAX_OID = 0xFFFFFFFFL; // 4294967295

  private static byte[] encodeBinary(Object array) throws SQLException {
    return MultiDimArrayBinary.encode(array, null, LEAF);
  }

  private static Object decodeBinary(byte[] data, Class<?> leafComponentType) throws SQLException {
    return MultiDimArrayBinary.decode(data, leafComponentType, null, LEAF);
  }

  private static String encodeText(Object array) throws SQLException {
    return MultiDimArrayText.encode(array, ',', null, LEAF);
  }

  private static Object decodeText(String literal, Class<?> leafComponentType) throws SQLException {
    return MultiDimArrayText.decode(literal, leafComponentType, ',', null, LEAF);
  }

  @Test
  void oidCodec_advertisesThisLeaf() {
    assertSame(LEAF, OidCodec.INSTANCE.arrayLeaf());
    assertEquals(Oid.OID, LEAF.getElementOid());
    assertEquals(long.class, LEAF.getPrimitiveComponentType());
    assertEquals(Long.class, LEAF.getBoxedComponentType());
  }

  @Test
  void encodeBinary_longArray_writesPackedHeader() throws SQLException {
    long[] input = {0, 16384, MAX_OID};
    byte[] bytes = encodeBinary(input);

    assertEquals(20 + (4 + 4) * input.length, bytes.length);
    assertEquals(Oid.OID, ByteConverter.int4(bytes, 8)); // element OID
    assertEquals(4, ByteConverter.int4(bytes, 20));      // first element length
    assertEquals(0, ByteConverter.int4(bytes, 24));      // 0
    assertEquals(16384, ByteConverter.int4(bytes, 32));  // 16384
  }

  @Test
  void decodeBinary_unsignedRoundTrip() throws SQLException {
    long[] input = {0, 1, 16384, 2147483648L, MAX_OID};
    long[] roundTrip = (long[]) decodeBinary(encodeBinary(input), long.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_LongArrayWithNull_roundTrip() throws SQLException {
    Long[] input = {1L, null, MAX_OID};
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
    assertEquals("{0,16384,4294967295}", encodeText(new long[]{0, 16384, MAX_OID}));
    long[] decoded = (long[]) decodeText("{0,16384,4294967295}", long.class);
    assertArrayEquals(new long[]{0, 16384, MAX_OID}, decoded);
  }

  @Test
  void text_LongArrayWithNull() throws SQLException {
    Long[] decoded = (Long[]) decodeText("{1,NULL,4294967295}", Long.class);
    assertArrayEquals(new Long[]{1L, null, MAX_OID}, decoded);
  }

  @Test
  void multiDim_binaryAndText_roundTrip() throws SQLException {
    long[][] input = {{1, 2}, {3, MAX_OID}};
    long[][] viaBinary = (long[][]) decodeBinary(encodeBinary(input), long.class);
    assertArrayEquals(input[0], viaBinary[0]);
    assertArrayEquals(input[1], viaBinary[1]);

    long[][] viaText = (long[][]) decodeText(encodeText(input), long.class);
    assertArrayEquals(input[0], viaText[0]);
    assertArrayEquals(input[1], viaText[1]);
  }

  @Test
  void registry_oid_isArrayElementCodec() {
    CodecRegistry registry = new CodecRegistry();
    assertInstanceOf(ArrayElementCodec.class, registry.getByName("oid"));
  }
}
