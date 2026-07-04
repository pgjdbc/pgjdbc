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
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * Unit tests for {@link Int4ArrayLeafCodec}, the {@code int4} array fast leaf,
 * exercised through the shared {@link MultiDimArrayBinary} / {@link MultiDimArrayText}
 * walkers — the same path the single {@link ArrayCodec} runs once an element
 * codec advertises it via {@link ArrayElementCodec}.
 *
 * <p>These cover encode/decode shape without a live connection. End-to-end
 * coverage through {@code ArrayCodec} with a real {@code CodecContext} lives in
 * the integration suites (for example {@code ArrayTest}).</p>
 */
class Int4ArrayLeafCodecTest {

  private static final Int4ArrayLeafCodec LEAF = Int4ArrayLeafCodec.INSTANCE;

  private static byte[] encodeBinary(Object array) throws SQLException {
    return MultiDimArrayBinary.encode(array, null, LEAF);
  }

  private static Object decodeBinary(byte[] data, Class<?> leafComponentType) throws SQLException {
    return MultiDimArrayBinary.decode(data, leafComponentType, null, LEAF);
  }

  private static String encodeText(Object array) throws SQLException {
    return MultiDimArrayText.encode(array, ',', null, LEAF);
  }

  // ---------------- leaf identity ----------------

  @Test
  void int4Codec_advertisesThisLeaf() {
    assertSame(LEAF, Int4Codec.INSTANCE.arrayLeaf());
    assertEquals(Oid.INT4, LEAF.getElementOid());
    assertEquals(int.class, LEAF.getPrimitiveComponentType());
    assertEquals(Integer.class, LEAF.getBoxedComponentType());
  }

  // ---------------- binary encode ----------------

  @Test
  void encodeBinary_intArray_writesPackedHeader() throws SQLException {
    int[] input = {1, -2, 3};
    byte[] bytes = encodeBinary(input);

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
    byte[] bytes = encodeBinary(input);

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
  void decodeBinary_integerArray_roundTrip() throws SQLException {
    Integer[] roundTrip = (Integer[]) decodeBinary(
        encodeBinary(new Integer[]{1, null, -5}), Integer.class);
    assertArrayEquals(new Integer[]{1, null, -5}, roundTrip);
  }

  @Test
  void decodeBinary_intArray_rejectsNulls() throws SQLException {
    byte[] withNull = encodeBinary(new Integer[]{1, null, 3});
    assertThrows(SQLException.class, () -> decodeBinary(withNull, int.class));
  }

  @Test
  void decodeBinary_intArray_packedRoundTrip() throws SQLException {
    int[] input = {7, -42, 0, Integer.MAX_VALUE, Integer.MIN_VALUE};
    int[] roundTrip = (int[]) decodeBinary(encodeBinary(input), int.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_rejectsInvalidElementLength() throws SQLException {
    byte[] bytes = encodeBinary(new int[]{1});
    ByteConverter.int4(bytes, 20, 8);
    assertThrows(SQLException.class, () -> decodeBinary(bytes, int.class));
  }

  @Test
  void decodeBinary_emptyArray() throws SQLException {
    Integer[] decoded = (Integer[]) decodeBinary(encodeBinary(new Integer[]{}), Integer.class);
    assertArrayEquals(new Integer[]{}, decoded);
  }

  @Test
  void decodeBinary_nullValueLeavesSlot() throws SQLException {
    byte[] bytes = encodeBinary(new Integer[]{null, null, 7, null});
    Integer[] decoded = (Integer[]) decodeBinary(bytes, Integer.class);
    assertNull(decoded[0]);
    assertNull(decoded[1]);
    assertEquals(7, decoded[2]);
    assertNull(decoded[3]);
  }

  // ---------------- text encode ----------------

  @Test
  void encodeText_intArray_emitsUnquotedNumbers() throws SQLException {
    assertEquals("{1,2,3}", encodeText(new int[]{1, 2, 3}));
  }

  @Test
  void encodeText_integerArrayWithNull_emitsNullLiteral() throws SQLException {
    assertEquals("{1,NULL,3}", encodeText(new Integer[]{1, null, 3}));
  }

  @Test
  void encodeText_emptyArray() throws SQLException {
    assertEquals("{}", encodeText(new int[]{}));
  }

  @Test
  void encodeBinary_rejectsUnsupportedType() {
    assertThrows(Exception.class, () -> encodeBinary("not-an-array"));
  }

  // ---------------- empty-array normalisation ----------------

  @Test
  void encodeBinary_emptyMultiDim_normalisesToZeroDimHeader() throws SQLException {
    // An empty array (a zero in any dimension) is the zero-dimension array on the wire, like the
    // server's own empty array, not a positive-dimension header.
    for (int[][] empty : new int[][][]{new int[0][2], new int[2][0]}) {
      byte[] bytes = encodeBinary(empty);
      assertEquals(12, bytes.length);
      assertEquals(0, ByteConverter.int4(bytes, 0));         // dimensions
      assertEquals(0, ByteConverter.int4(bytes, 4));         // hasNulls
      assertEquals(Oid.INT4, ByteConverter.int4(bytes, 8));  // element OID
    }
  }

  @Test
  void encodeText_emptyMultiDim_rendersEmptyLiteral() throws SQLException {
    // PostgreSQL rejects a literal such as {{},{}}; every empty array renders as {}.
    assertEquals("{}", encodeText(new int[0][2]));
    assertEquals("{}", encodeText(new int[2][0]));
    assertEquals("{}", encodeText(new int[0][0][0]));
  }

  @Test
  void emptyMultiDim_textAndBinaryDecodeToSameShape() throws SQLException {
    // F2 regression: an empty outer dimension collapses the text literal to {}, which decodes 1-D.
    // The binary path must collapse to the same shape rather than keep a multi-dimensional header,
    // so the two formats agree.
    int[][] value = new int[0][2];
    Object viaText = decodeText(encodeText(value), int.class);
    Object viaBinary = decodeBinary(encodeBinary(value), int.class);
    assertEquals(viaText.getClass(), viaBinary.getClass());
    assertArrayEquals((int[]) viaText, (int[]) viaBinary);
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
    byte[] bytes = encodeBinary(input);

    // Header: 3 × int4 (dim/hasNulls/oid) + 3 × (length+lower) = 12 + 24 = 36
    assertEquals(3, ByteConverter.int4(bytes, 0));
    assertEquals(0, ByteConverter.int4(bytes, 4));
    assertEquals(Oid.INT4, ByteConverter.int4(bytes, 8));
    assertEquals(2, ByteConverter.int4(bytes, 12));
    assertEquals(3, ByteConverter.int4(bytes, 20));
    assertEquals(2, ByteConverter.int4(bytes, 28));

    int[][][] roundTrip = (int[][][]) decodeBinary(bytes, int.class);
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
    byte[] bytes = encodeBinary(input);
    assertEquals(2, ByteConverter.int4(bytes, 0));          // ndim
    assertEquals(1, ByteConverter.int4(bytes, 4));          // hasNulls
    assertEquals(Oid.INT4, ByteConverter.int4(bytes, 8));

    Integer[][] roundTrip = (Integer[][]) decodeBinary(bytes, Integer.class);
    assertArrayEquals(input[0], roundTrip[0]);
    assertArrayEquals(input[1], roundTrip[1]);
  }

  @Test
  void multiDim_text_emitsNestedBraces() throws SQLException {
    assertEquals("{{1,2},{3,4}}", encodeText(new int[][]{{1, 2}, {3, 4}}));
  }

  @Test
  void multiDim_text_withNulls() throws SQLException {
    assertEquals("{{1,NULL},{NULL,4}}", encodeText(new Integer[][]{{1, null}, {null, 4}}));
  }

  @Test
  void multiDim_intArray_rejectsNullForPrimitiveTarget() throws SQLException {
    byte[] bytes = encodeBinary(new Integer[][]{{1, null}});
    assertThrows(SQLException.class, () -> decodeBinary(bytes, int.class));
  }

  @Test
  void multiDim_binaryRejectsJaggedArray() {
    assertThrows(SQLException.class, () -> encodeBinary(new int[][]{{1, 2}, {3}}));
  }

  @Test
  void multiDim_textRejectsJaggedArray() {
    assertThrows(SQLException.class, () -> encodeText(new int[][]{{1, 2}, {3}}));
  }

  // ---------------- text decode ----------------

  private static Object decodeText(String literal, Class<?> leafComponentType) throws SQLException {
    return MultiDimArrayText.decode(literal, leafComponentType, ',', null, LEAF);
  }

  @Test
  void decodeText_intArray_roundTrip() throws SQLException {
    int[] input = {1, -2, 3, Integer.MAX_VALUE, Integer.MIN_VALUE};
    int[] decoded = (int[]) decodeText(encodeText(input), int.class);
    assertArrayEquals(input, decoded);
  }

  @Test
  void decodeText_integerArray_withNull() throws SQLException {
    Integer[] decoded = (Integer[]) decodeText("{1,NULL,3}", Integer.class);
    assertArrayEquals(new Integer[]{1, null, 3}, decoded);
  }

  @Test
  void decodeText_intArray_rejectsNull() {
    assertThrows(SQLException.class, () -> decodeText("{1,NULL,3}", int.class));
  }

  @Test
  void decodeText_emptyArray() throws SQLException {
    assertArrayEquals(new int[]{}, (int[]) decodeText("{}", int.class));
    assertArrayEquals(new Integer[]{}, (Integer[]) decodeText("{}", Integer.class));
  }

  @Test
  void decodeText_quotedNumbersAccepted() throws SQLException {
    assertArrayEquals(new int[]{1, 2, 3}, (int[]) decodeText("{\"1\",\"2\",\"3\"}", int.class));
  }

  @Test
  void decodeText_ignoresUnquotedWhitespace() throws SQLException {
    assertArrayEquals(new int[]{1, 2, 3}, (int[]) decodeText("{ 1 , 2 , 3 }", int.class));
  }

  @Test
  void decodeText_skipsDimensionPrefix() throws SQLException {
    assertArrayEquals(new int[]{1, 2, 3}, (int[]) decodeText("[0:2]={1,2,3}", int.class));
  }

  @Test
  void decodeText_customDelimiter() throws SQLException {
    int[] decoded = (int[]) MultiDimArrayText.decode("{1;2;3}", int.class, ';', null, LEAF);
    assertArrayEquals(new int[]{1, 2, 3}, decoded);
  }

  @Test
  void decodeText_rejectsNonArrayLiteral() {
    assertThrows(SQLException.class, () -> decodeText("123", int.class));
  }

  @Test
  void decodeText_multiDim_roundTrip() throws SQLException {
    int[][] input = {{1, 2}, {3, 4}};
    int[][] decoded = (int[][]) decodeText(encodeText(input), int.class);
    assertArrayEquals(input[0], decoded[0]);
    assertArrayEquals(input[1], decoded[1]);
  }

  @Test
  void decodeText_multiDim_withNulls_roundTrip() throws SQLException {
    Integer[][] input = {{1, null}, {null, 4}};
    Integer[][] decoded = (Integer[][]) decodeText(encodeText(input), Integer.class);
    assertArrayEquals(input[0], decoded[0]);
    assertArrayEquals(input[1], decoded[1]);
  }

  @Test
  void decodeText_threeDim_roundTrip() throws SQLException {
    int[][][] input = new int[2][3][2];
    int n = 0;
    for (int x = 0; x < 2; x++) {
      for (int y = 0; y < 3; y++) {
        for (int z = 0; z < 2; z++) {
          input[x][y][z] = n++;
        }
      }
    }
    int[][][] decoded = (int[][][]) decodeText(encodeText(input), int.class);
    for (int x = 0; x < 2; x++) {
      for (int y = 0; y < 3; y++) {
        assertArrayEquals(input[x][y], decoded[x][y]);
      }
    }
  }

  @Test
  void decodeText_genericLeaf_viaElementCodec() throws SQLException {
    PgType int4Type = new PgType(
        new ObjectName("pg_catalog", "int4"),
        "integer", Oid.INT4, 'b', 'N', -1, 0, 0, 0);
    GenericArrayLeafCodec genericLeaf =
        new GenericArrayLeafCodec(int4Type, Int4Codec.INSTANCE);
    Object[] decoded =
        (Object[]) MultiDimArrayText.decode("{1,2,3}", Object.class, ',', null, genericLeaf);
    assertArrayEquals(new Object[]{1, 2, 3}, decoded);
  }

  // ---------------- registry routing ----------------

  @Test
  void registry_routes_int4Array_toSingleArrayCodec() {
    CodecRegistry registry = new CodecRegistry();
    PgType int4ArrayType = new PgType(
        new ObjectName("pg_catalog", "_int4"),
        "integer[]",
        Oid.INT4_ARRAY,
        'b', 'A', -1, Oid.INT4, 0, 0);

    // _int4 is no longer registered under a dedicated codec; the single
    // ArrayCodec handles every array type, picking the fast leaf from the
    // element codec at call time.
    assertNull(registry.getByName("_int4"));
    assertSame(ArrayCodec.INSTANCE, registry.getByOid(Oid.INT4_ARRAY, int4ArrayType));
    assertInstanceOf(ArrayElementCodec.class, registry.getByName("int4"));
  }
}
