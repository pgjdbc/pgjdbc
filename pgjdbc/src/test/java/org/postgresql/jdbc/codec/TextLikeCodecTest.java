/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PGUnknownBinary;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class TextLikeCodecTest {

  private TextLikeCodec codec;
  private PgType refcursorType;
  private CodecContext ctx;
  private byte[] abcBytes;

  @BeforeEach
  void setUp() {
    codec = TextLikeCodec.INSTANCE;
    // A text-send type: refcursor. The codec only reads the type name, so a minimal PgType suffices.
    refcursorType = new PgType(
        new ObjectName("pg_catalog", "refcursor"), "refcursor", 1790, 'b', 'U', -1, 0, 0, 0);
    ctx = TestCodecContext.create();
    abcBytes = "abc".getBytes(ctx.getCharset());
  }

  @Test
  void readsBothTextAndBinary() {
    // Unlike FallbackCodec (binary-read off), the text-send binary wire is the charset text, so this
    // codec reads both -- which is what makes its types eligible for binary receive.
    assertTrue(codec.supportsBinaryRead());
    assertTrue(codec.supportsTextRead());
  }

  @Test
  void getDefaultJavaType() {
    assertEquals(PGobject.class, codec.getDefaultJavaType());
  }

  @Test
  void decodeText_returnsPGobjectWithTypeName() throws SQLException {
    Object result = codec.decodeText("abc", refcursorType, ctx);

    assertInstanceOf(PGobject.class, result);
    PGobject obj = (PGobject) result;
    assertEquals("refcursor", obj.getType());
    assertEquals("abc", obj.getValue());
  }

  @Test
  void decodeBinary_decodesCharsetTextIntoPGobject() throws SQLException {
    Object result = codec.decodeBinary(abcBytes, 0, abcBytes.length, refcursorType, ctx);

    assertInstanceOf(PGobject.class, result);
    PGobject obj = (PGobject) result;
    assertEquals("refcursor", obj.getType());
    assertEquals("abc", obj.getValue());
  }

  @Test
  void decodeBinaryAs_targetClasses() throws SQLException {
    assertInstanceOf(PGobject.class, codec.decodeBinaryAs(abcBytes, 0, abcBytes.length, refcursorType, Object.class, ctx));
    assertInstanceOf(PGobject.class, codec.decodeBinaryAs(abcBytes, 0, abcBytes.length, refcursorType, PGobject.class, ctx));
    assertEquals("abc", codec.decodeBinaryAs(abcBytes, 0, abcBytes.length, refcursorType, String.class, ctx));
    assertArrayEquals(abcBytes, codec.decodeBinaryAs(abcBytes, 0, abcBytes.length, refcursorType, byte[].class, ctx));
    // This codec is text, not raw binary: it does not produce PGUnknownBinary.
    assertThrows(PSQLException.class,
        () -> codec.decodeBinaryAs(abcBytes, 0, abcBytes.length, (TypeDescriptor) refcursorType, PGUnknownBinary.class, ctx));
  }

  @Test
  void decodeTextAs_targetClasses() throws SQLException {
    assertInstanceOf(PGobject.class, codec.decodeTextAs("abc", refcursorType, Object.class, ctx));
    assertInstanceOf(PGobject.class, codec.decodeTextAs("abc", refcursorType, PGobject.class, ctx));
    assertEquals("abc", codec.decodeTextAs("abc", refcursorType, String.class, ctx));
    // The text input is already the value; its charset bytes mirror decodeBinaryAs(byte[]).
    assertArrayEquals(abcBytes, codec.decodeTextAs("abc", refcursorType, byte[].class, ctx));
    assertThrows(PSQLException.class,
        () -> codec.decodeTextAs("abc", refcursorType, PGUnknownBinary.class, ctx));
  }

  @Test
  void encode_acceptsStringAndMatchingPGobject() throws SQLException {
    assertTrue(codec.canEncodeBinary("abc", refcursorType, ctx));
    assertArrayEquals(abcBytes, codec.encodeBinary("abc", refcursorType, ctx));
    assertEquals("abc", codec.encodeText("abc", refcursorType, ctx));

    PGobject matching = new PGobject();
    matching.setType("refcursor");
    matching.setValue("abc");
    assertTrue(codec.canEncodeBinary(matching, refcursorType, ctx));
    assertArrayEquals(abcBytes, codec.encodeBinary(matching, refcursorType, ctx));
    assertEquals("abc", codec.encodeText(matching, refcursorType, ctx));
  }

  @Test
  void encode_nullValuedMatchingPGobjectIsEmptyString() throws SQLException {
    PGobject matchingNull = new PGobject();
    matchingNull.setType("refcursor");
    // value left null
    assertTrue(codec.canEncodeBinary(matchingNull, refcursorType, ctx));
    assertEquals("", codec.encodeText(matchingNull, refcursorType, ctx));
    assertArrayEquals(new byte[0], codec.encodeBinary(matchingNull, refcursorType, ctx));
  }

  @Test
  void encode_rejectsMismatchedTypePGobject() throws SQLException {
    // A PGobject of another type is not encodable by this type's codec (matching type only). A
    // type-less PGobject is not tested here: PGobject.getType() throws when unset, and such a value
    // never reaches a codec, since codec selection routes a PGobject by its (set) type name.
    PGobject otherType = new PGobject();
    otherType.setType("json");
    otherType.setValue("abc");
    assertFalse(codec.canEncodeBinary(otherType, refcursorType, ctx));
    assertThrows(PSQLException.class, () -> codec.encodeText(otherType, refcursorType, ctx));
    assertThrows(PSQLException.class, () -> codec.encodeBinary(otherType, refcursorType, ctx));
  }
}
