/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGbytea;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * Unit tests for {@link ByteaArrayLeafCodec}, the {@code bytea} array leaf, driven
 * through the shared {@link MultiDimArrayBinary} / {@link MultiDimArrayText}
 * walkers — the path {@link ArrayCodec} takes once {@link ByteaCodec} advertises
 * the leaf via {@link ArrayElementCodec}. One element is a {@code byte[]}, so a
 * 1-D {@code bytea[]} is a {@code byte[][]} whose elements may differ in length.
 */
class ByteaArrayLeafCodecTest {

  private static final ByteaArrayLeafCodec LEAF = ByteaArrayLeafCodec.INSTANCE;
  private static final CodecContext CTX = TestCodecContext.create();

  private static byte[] encodeBinary(Object array) throws SQLException {
    return MultiDimArrayBinary.encode(array, CTX, LEAF);
  }

  private static Object decodeBinary(byte[] data) throws SQLException {
    return MultiDimArrayBinary.decode(data, byte[].class, CTX, LEAF);
  }

  private static String encodeText(Object array) throws SQLException {
    return MultiDimArrayText.encode(array, ',', CTX, LEAF);
  }

  private static Object decodeText(String literal) throws SQLException {
    return MultiDimArrayText.decode(literal, byte[].class, ',', CTX, LEAF);
  }

  /** {@code bytea[]} with differing element lengths, an empty element and a NULL. */
  private static byte[][] sample() {
    return new byte[][]{{0x01, (byte) 0xFF, 0x12}, {}, {(byte) 0xAC, (byte) 0xE4}, null};
  }

  private static void assertSampleEquals(byte[][] out) {
    assertEquals(4, out.length);
    assertArrayEquals(new byte[]{0x01, (byte) 0xFF, 0x12}, out[0]);
    assertArrayEquals(new byte[]{}, out[1]);
    assertArrayEquals(new byte[]{(byte) 0xAC, (byte) 0xE4}, out[2]);
    assertNull(out[3]);
  }

  @Test
  void byteaCodec_advertisesThisLeaf() {
    assertSame(LEAF, ByteaCodec.INSTANCE.arrayLeaf());
    assertEquals(Oid.BYTEA, LEAF.getElementOid());
    assertEquals(byte[].class, LEAF.getBoxedComponentType());
  }

  @Test
  void binary_roundTrip_jaggedWithEmptyAndNull() throws SQLException {
    byte[][] out = (byte[][]) decodeBinary(encodeBinary(sample()));
    assertSampleEquals(out);
  }

  @Test
  void binary_header_isOneDimensional() throws SQLException {
    byte[] bytes = encodeBinary(sample());
    assertEquals(1, ByteConverter.int4(bytes, 0));         // dimensions: bytea[] is 1-D
    assertEquals(1, ByteConverter.int4(bytes, 4));         // hasNulls (element 3 is null)
    assertEquals(Oid.BYTEA, ByteConverter.int4(bytes, 8)); // element OID
    assertEquals(4, ByteConverter.int4(bytes, 12));        // length
  }

  @Test
  void text_roundTrip_jaggedWithEmptyAndNull() throws SQLException {
    byte[][] out = (byte[][]) decodeText(encodeText(sample()));
    assertSampleEquals(out);
  }

  @Test
  void text_quotesAndEscapesEachElement() throws SQLException {
    // The leaf wraps each bytea's \x-hex text in quotes and doubles the backslash,
    // matching array_out; the null element is the unquoted NULL token.
    byte[] one = {0x01, (byte) 0xFF};
    String quoted = "\"" + PGbytea.toPGString(one).replace("\\", "\\\\") + "\"";
    assertEquals("{" + quoted + ",NULL}", encodeText(new byte[][]{{0x01, (byte) 0xFF}, null}));
  }

  @Test
  void text_decodesServerStyleLiteral() throws SQLException {
    // Doubled backslashes as array_out emits them: "\\x01ff" -> bytea \x01ff.
    byte[][] out = (byte[][]) decodeText("{\"\\\\x01ff\",NULL,\"\\\\x\"}");
    assertEquals(3, out.length);
    assertArrayEquals(new byte[]{0x01, (byte) 0xFF}, out[0]);
    assertNull(out[1]);
    assertArrayEquals(new byte[]{}, out[2]);
  }

  @Test
  void emptyArray_roundTrip() throws SQLException {
    assertEquals(0, ((byte[][]) decodeBinary(encodeBinary(new byte[0][]))).length);
    assertEquals("{}", encodeText(new byte[0][]));
  }

  @Test
  void twoDimensional_binaryRoundTrip() throws SQLException {
    byte[][][] in = {{{0x01}, {0x02, 0x03}}, {{}, {(byte) 0x99}}};
    byte[] bytes = encodeBinary(in);
    assertEquals(2, ByteConverter.int4(bytes, 0)); // bytea[][] is 2-D
    byte[][][] out = (byte[][][]) decodeBinary(bytes);
    assertArrayEquals(in[0][0], out[0][0]);
    assertArrayEquals(in[0][1], out[0][1]);
    assertArrayEquals(in[1][0], out[1][0]);
    assertArrayEquals(in[1][1], out[1][1]);
  }

  @Test
  void rejectsNonArray() {
    assertThrows(Exception.class, () -> encodeBinary("not-an-array"));
  }
}
