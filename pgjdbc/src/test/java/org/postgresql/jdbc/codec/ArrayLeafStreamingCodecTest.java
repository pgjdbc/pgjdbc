/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * Unit tests for {@link ArrayLeafStreamingCodec#INT4}, the POC specialized codec for
 * {@code _int4} arrays.
 */
class ArrayLeafStreamingCodecTest {

  private ArrayLeafStreamingCodec codec;
  private PgType int4ArrayType;

  @BeforeEach
  void setUp() {
    codec = ArrayLeafStreamingCodec.INT4;
    int4ArrayType = new PgType(
        new ObjectName("pg_catalog", "_int4"),
        "integer[]",
        Oid.INT4_ARRAY,
        'b',
        'A',           // array category
        -1,
        Oid.INT4,      // typelem
        0,
        0
    );
  }

  @Test
  void typeName_is_int4() {
    assertEquals("_int4", codec.getTypeName());
  }

  @Test
  void defaultJavaType_isIntegerArray() {
    assertEquals(Integer[].class, codec.getDefaultJavaType());
  }

  // ---------------- binary encode ----------------

  @Test
  void encodeBinary_intArray_writesPackedHeader() throws SQLException {
    int[] input = {1, -2, 3};
    byte[] bytes = codec.encodeBinary(input, int4ArrayType, null);

    assertEquals(20 + 8 * input.length, bytes.length);
    assertEquals(1, ByteConverter.int4(bytes, 0));         // dimensions
    assertEquals(0, ByteConverter.int4(bytes, 4));         // hasNulls
    assertEquals(Oid.INT4, ByteConverter.int4(bytes, 8));  // element OID
    assertEquals(3, ByteConverter.int4(bytes, 12));        // length
    assertEquals(1, ByteConverter.int4(bytes, 16));        // lower bound

    assertEquals(4, ByteConverter.int4(bytes, 20));
    assertEquals(1, ByteConverter.int4(bytes, 24));
    assertEquals(4, ByteConverter.int4(bytes, 28));
    assertEquals(-2, ByteConverter.int4(bytes, 32));
    assertEquals(4, ByteConverter.int4(bytes, 36));
    assertEquals(3, ByteConverter.int4(bytes, 40));
  }

  @Test
  void encodeBinary_integerArrayWithNull_setsHasNullsAndEmitsMinusOne() throws SQLException {
    Integer[] input = {10, null, 30};
    byte[] bytes = codec.encodeBinary(input, int4ArrayType, null);

    // 20-byte header + per-element (4 bytes len + 4 bytes data for non-null, 4 bytes only for null)
    assertEquals(20 + (4 * 3) + (4 * 2), bytes.length);
    assertEquals(1, ByteConverter.int4(bytes, 4));   // hasNulls = 1
    assertEquals(3, ByteConverter.int4(bytes, 12));  // length

    int pos = 20;
    assertEquals(4, ByteConverter.int4(bytes, pos));
    assertEquals(10, ByteConverter.int4(bytes, pos + 4));
    pos += 8;
    assertEquals(-1, ByteConverter.int4(bytes, pos)); // null marker
    pos += 4;
    assertEquals(4, ByteConverter.int4(bytes, pos));
    assertEquals(30, ByteConverter.int4(bytes, pos + 4));
  }

  // ---------------- binary decode ----------------

  @Test
  void decodeBinary_returnsLazyPgArray() throws SQLException {
    // We can't easily build a CodecContext here, but decodeBinaryAs covers the
    // fast unpack path which is what the POC really replaces.
    Integer[] roundTrip = (Integer[]) codec.decodeBinaryAs(
        codec.encodeBinary(new Integer[]{1, null, -5}, int4ArrayType, null),
        int4ArrayType, Integer[].class, null);
    assertArrayEquals(new Integer[]{1, null, -5}, roundTrip);
  }

  @Test
  void decodeBinaryAs_intArray_rejectsNulls() throws SQLException {
    byte[] withNull = codec.encodeBinary(new Integer[]{1, null, 3}, int4ArrayType, null);
    assertThrows(SQLException.class,
        () -> codec.decodeBinaryAs(withNull, int4ArrayType, int[].class, null));
  }

  @Test
  void decodeBinaryAs_intArray_packedRoundTrip() throws SQLException {
    int[] input = {7, -42, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};
    byte[] bytes = codec.encodeBinary(input, int4ArrayType, null);
    int[] roundTrip = (int[]) codec.decodeBinaryAs(bytes, int4ArrayType, int[].class, null);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinaryAs_rejectsInvalidElementLength() throws SQLException {
    byte[] bytes = codec.encodeBinary(new int[]{1}, int4ArrayType, null);
    ByteConverter.int4(bytes, 20, 8);
    assertThrows(SQLException.class,
        () -> codec.decodeBinaryAs(bytes, int4ArrayType, int[].class, null));
  }

  @Test
  void decodeBinaryAs_emptyArray() throws SQLException {
    byte[] bytes = codec.encodeBinary(new Integer[]{}, int4ArrayType, null);
    Integer[] decoded = (Integer[]) codec.decodeBinaryAs(bytes, int4ArrayType, Integer[].class, null);
    assertArrayEquals(new Integer[]{}, decoded);
  }

  // ---------------- text encode ----------------

  @Test
  void encodeText_intArray_emitsUnquotedNumbers() throws SQLException {
    assertEquals("{1,2,3}", codec.encodeText(new int[]{1, 2, 3}, int4ArrayType, null));
  }

  @Test
  void encodeText_integerArrayWithNull_emitsNullLiteral() throws SQLException {
    assertEquals("{1,NULL,3}",
        codec.encodeText(new Integer[]{1, null, 3}, int4ArrayType, null));
  }

  @Test
  void encodeText_emptyArray() throws SQLException {
    assertEquals("{}", codec.encodeText(new int[]{}, int4ArrayType, null));
  }

  @Test
  void encodeBinary_rejectsUnsupportedType() {
    assertThrows(Exception.class,
        () -> codec.encodeBinary("not-an-array", int4ArrayType, null));
  }

  @Test
  void decodeBinaryAs_nullValueLeavesSlot() throws SQLException {
    byte[] bytes = codec.encodeBinary(new Integer[]{null, null, 7, null}, int4ArrayType, null);
    Integer[] decoded = (Integer[]) codec.decodeBinaryAs(bytes, int4ArrayType, Integer[].class, null);
    assertNull(decoded[0]);
    assertNull(decoded[1]);
    assertEquals(7, decoded[2]);
    assertNull(decoded[3]);
  }

  // ---------------- multi-dim ----------------

  @Test
  void multiDim_intArrayThreeDim_binaryRoundTrip() throws SQLException {
    int[][][] input = new int[2][3][2];
    int n = 0;
    for (int x = 0; x < 2; x++) {
      for (int y = 0; y < 3; y++) {
        for (int z = 0; z < 2; z++) {
          input[x][y][z] = n++;
        }
      }
    }
    byte[] bytes = codec.encodeBinary(input, int4ArrayType, null);

    // Header: 3 × int4 (dim/hasNulls/oid) + 3 × (length+lower) = 12 + 24 = 36
    assertEquals(3, ByteConverter.int4(bytes, 0));
    assertEquals(0, ByteConverter.int4(bytes, 4));
    assertEquals(Oid.INT4, ByteConverter.int4(bytes, 8));
    assertEquals(2, ByteConverter.int4(bytes, 12));
    assertEquals(3, ByteConverter.int4(bytes, 20));
    assertEquals(2, ByteConverter.int4(bytes, 28));

    int[][][] roundTrip = (int[][][]) codec.decodeBinaryAs(
        bytes, int4ArrayType, int[][][].class, null);
    for (int x = 0; x < 2; x++) {
      for (int y = 0; y < 3; y++) {
        assertArrayEquals(input[x][y], roundTrip[x][y]);
      }
    }
  }

  @Test
  void multiDim_integerTwoDimWithNulls_binaryRoundTrip() throws SQLException {
    Integer[][] input = {
        {1, null, 3},
        {null, 5, 6}
    };
    byte[] bytes = codec.encodeBinary(input, int4ArrayType, null);
    assertEquals(2, ByteConverter.int4(bytes, 0));          // ndim
    assertEquals(1, ByteConverter.int4(bytes, 4));          // hasNulls
    assertEquals(Oid.INT4, ByteConverter.int4(bytes, 8));

    Integer[][] roundTrip = (Integer[][]) codec.decodeBinaryAs(
        bytes, int4ArrayType, Integer[][].class, null);
    assertArrayEquals(input[0], roundTrip[0]);
    assertArrayEquals(input[1], roundTrip[1]);
  }

  @Test
  void multiDim_text_emitsNestedBraces() throws SQLException {
    int[][] input = {{1, 2}, {3, 4}};
    assertEquals("{{1,2},{3,4}}", codec.encodeText(input, int4ArrayType, null));
  }

  @Test
  void multiDim_text_withNulls() throws SQLException {
    Integer[][] input = {{1, null}, {null, 4}};
    assertEquals("{{1,NULL},{NULL,4}}", codec.encodeText(input, int4ArrayType, null));
  }

  @Test
  void multiDim_intArray_rejectsNullForPrimitiveTarget() throws SQLException {
    Integer[][] input = {{1, null}};
    byte[] bytes = codec.encodeBinary(input, int4ArrayType, null);
    assertThrows(SQLException.class,
        () -> codec.decodeBinaryAs(bytes, int4ArrayType, int[][].class, null));
  }

  @Test
  void multiDim_binaryRejectsJaggedArray() {
    int[][] input = {{1, 2}, {3}};
    assertThrows(SQLException.class,
        () -> codec.encodeBinary(input, int4ArrayType, null));
  }

  @Test
  void multiDim_textRejectsJaggedArray() {
    int[][] input = {{1, 2}, {3}};
    assertThrows(SQLException.class,
        () -> codec.encodeText(input, int4ArrayType, null));
  }

  @Test
  void registry_resolves_int4_arrayName_toThisCodec() {
    org.postgresql.jdbc.CodecRegistry registry = new org.postgresql.jdbc.CodecRegistry();
    // _int4 is the PostgreSQL canonical type name for the int4 array type; the
    // codec must be picked up by name-based lookup, ahead of the generic
    // ArrayCodec fallback that resolveByTyptype returns for unrecognized array
    // names.
    assertEquals(ArrayLeafStreamingCodec.INT4, registry.getByName("_int4"));
  }
}
