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
 * Unit tests for {@link BoolArrayLeafCodec}, the {@code bool} array fast leaf.
 * Wire forms mirror {@link BoolCodec}: a single byte in binary and {@code t}/{@code f}
 * in text; the boxed component type is {@code Boolean}, matching the legacy
 * {@code getArray()} return type for bool[].
 */
class BoolArrayLeafCodecTest {

  private static final BoolArrayLeafCodec LEAF = BoolArrayLeafCodec.INSTANCE;

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
  void boolCodec_advertisesThisLeaf() {
    assertSame(LEAF, BoolCodec.INSTANCE.arrayLeaf());
    assertEquals(Oid.BOOL, LEAF.getElementOid());
    assertEquals(boolean.class, LEAF.getPrimitiveComponentType());
    assertEquals(Boolean.class, LEAF.getBoxedComponentType());
  }

  @Test
  void encodeBinary_booleanArray_writesPackedHeader() throws SQLException {
    boolean[] input = {true, false, true};
    byte[] bytes = encodeBinary(input);

    assertEquals(20 + (4 + 1) * input.length, bytes.length);
    assertEquals(1, ByteConverter.int4(bytes, 0));         // dimensions
    assertEquals(0, ByteConverter.int4(bytes, 4));         // hasNulls
    assertEquals(Oid.BOOL, ByteConverter.int4(bytes, 8));  // element OID
    assertEquals(3, ByteConverter.int4(bytes, 12));        // length
    assertEquals(1, ByteConverter.int4(bytes, 20));        // first element length
    assertEquals(1, bytes[24]);                            // true
    assertEquals(0, bytes[29]);                            // false
  }

  @Test
  void decodeBinary_booleanArray_packedRoundTrip() throws SQLException {
    boolean[] input = {true, false, true, false};
    boolean[] roundTrip = (boolean[]) decodeBinary(encodeBinary(input), boolean.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_BooleanArrayWithNull_roundTrip() throws SQLException {
    Boolean[] input = {true, null, false};
    Boolean[] roundTrip = (Boolean[]) decodeBinary(encodeBinary(input), Boolean.class);
    assertArrayEquals(input, roundTrip);
  }

  @Test
  void decodeBinary_booleanArray_rejectsNull() throws SQLException {
    byte[] withNull = encodeBinary(new Boolean[]{true, null});
    assertThrows(SQLException.class, () -> decodeBinary(withNull, boolean.class));
  }

  @Test
  void decodeBinary_emptyArray() throws SQLException {
    Boolean[] decoded = (Boolean[]) decodeBinary(encodeBinary(new Boolean[]{}), Boolean.class);
    assertArrayEquals(new Boolean[]{}, decoded);
  }

  @Test
  void text_booleanArray_roundTrip() throws SQLException {
    assertEquals("{t,f,t}", encodeText(new boolean[]{true, false, true}));
    boolean[] decoded = (boolean[]) decodeText("{t,f,t}", boolean.class);
    assertArrayEquals(new boolean[]{true, false, true}, decoded);
  }

  @Test
  void text_BooleanArrayWithNull_roundTrip() throws SQLException {
    assertEquals("{t,NULL,f}", encodeText(new Boolean[]{true, null, false}));
    Boolean[] decoded = (Boolean[]) decodeText("{t,NULL,f}", Boolean.class);
    assertArrayEquals(new Boolean[]{true, null, false}, decoded);
  }

  @Test
  void text_acceptsAlternateBooleanSpellings() throws SQLException {
    // BooleanTypeUtil tolerates the spellings PostgreSQL accepts as input.
    assertArrayEquals(new Boolean[]{true, false},
        (Boolean[]) decodeText("{true,false}", Boolean.class));
  }

  @Test
  void multiDim_binaryAndText_roundTrip() throws SQLException {
    boolean[][] input = {{true, false}, {false, true}};
    boolean[][] viaBinary = (boolean[][]) decodeBinary(encodeBinary(input), boolean.class);
    assertArrayEquals(input[0], viaBinary[0]);
    assertArrayEquals(input[1], viaBinary[1]);

    assertEquals("{{t,f},{f,t}}", encodeText(input));
    boolean[][] viaText = (boolean[][]) decodeText("{{t,f},{f,t}}", boolean.class);
    assertArrayEquals(input[0], viaText[0]);
    assertArrayEquals(input[1], viaText[1]);
  }

  @Test
  void decodeBinary_nullSlotsPreserved() throws SQLException {
    Boolean[] decoded = (Boolean[]) decodeBinary(
        encodeBinary(new Boolean[]{null, true, null}), Boolean.class);
    assertNull(decoded[0]);
    assertEquals(Boolean.TRUE, decoded[1]);
    assertNull(decoded[2]);
  }

  @Test
  void registry_bool_isArrayElementCodec() {
    CodecRegistry registry = new CodecRegistry();
    assertInstanceOf(ArrayElementCodec.class, registry.getByName("bool"));
  }
}
