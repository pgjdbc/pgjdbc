/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.sql.SQLException;

/**
 * Unit tests for the streaming codec path that exist without an active
 * database connection. Integration coverage (composite-element arrays in
 * binary + text mode, with quotes / backslashes / nulls in nested values)
 * lives in {@code NestedStructArrayRoundtripTest}.
 */
class StreamingCodecTest {

  private PgType int4Type;

  @BeforeEach
  void setUp() {
    int4Type = new PgType(
        new ObjectName("pg_catalog", "int4"),
        "integer",
        Oid.INT4,
        'b', 'N', -1, 0, 0, 0);
  }

  // ---------------- Streaming vs materializing form agree ----------------

  @Test
  void int4_stringFormMatchesStreamingText() throws SQLException {
    // Int4Codec implements both the String-returning encodeText and the streaming
    // Appendable form; they must produce identical output.
    String viaString = Int4Codec.INSTANCE.encodeText(42, int4Type, null);
    StringBuilder sb = new StringBuilder();
    try {
      Int4Codec.INSTANCE.encodeText(42, int4Type, null, sb);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    assertEquals(viaString, sb.toString());
    assertEquals("42", viaString);
  }

  @Test
  void int4_byteArrayFormMatchesStreamingBinary() throws SQLException, IOException {
    // The byte[]-returning encodeBinary and the streaming sink form must produce identical bytes.
    byte[] viaArray = Int4Codec.INSTANCE.encodeBinary(42, int4Type, null);
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    Int4Codec.INSTANCE.encodeBinary(42, int4Type, null, out);
    assertArrayEquals(viaArray, out.toByteArray());
  }

  // ---------------- BackpatchByteArrayOutputStream ----------------

  @Test
  void backpatch_reserveThenPatch_writesAtRecordedPosition() {
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    assertTrue(out instanceof BackpatchingBinarySink);
    out.write(0xAB); // 1 byte prefix
    int slot = out.reserveInt32();
    assertEquals(5, out.position());
    out.write(0xCD); // 1 byte payload after slot
    out.setInt32At(slot, 0x12345678);
    byte[] bytes = out.toByteArray();
    assertEquals(6, bytes.length);
    assertEquals((byte) 0xAB, bytes[0]);
    assertEquals((byte) 0x12, bytes[1]);
    assertEquals((byte) 0x34, bytes[2]);
    assertEquals((byte) 0x56, bytes[3]);
    assertEquals((byte) 0x78, bytes[4]);
    assertEquals((byte) 0xCD, bytes[5]);
  }

  @Test
  void backpatch_writeInt32_appendsAtCurrentPosition() {
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    out.write(0xAB);
    out.writeInt32(0x12345678);
    out.write(0xCD);
    byte[] bytes = out.toByteArray();
    assertEquals(6, bytes.length);
    assertEquals((byte) 0xAB, bytes[0]);
    assertEquals((byte) 0x12, bytes[1]);
    assertEquals((byte) 0x34, bytes[2]);
    assertEquals((byte) 0x56, bytes[3]);
    assertEquals((byte) 0x78, bytes[4]);
    assertEquals((byte) 0xCD, bytes[5]);
  }

  // ---------------- EscapingAppendable ----------------

  @Test
  void escapingAppendable_quotesAndBackslashesGetBackslashPrefix() throws IOException {
    StringWriter sink = new StringWriter();
    EscapingAppendable esc = new EscapingAppendable(sink);
    esc.append("say \"hi\" with \\ slash");
    assertEquals("say \\\"hi\\\" with \\\\ slash", sink.toString());
  }

  @Test
  void escapingAppendable_subsequenceRespectsRange() throws IOException {
    StringWriter sink = new StringWriter();
    EscapingAppendable esc = new EscapingAppendable(sink);
    esc.append("XX\"YY", 2, 3); // only the quote
    assertEquals("\\\"", sink.toString());
  }

  @Test
  void escapingAppendable_arrayElementQuotesProtectCompositeSyntax() throws IOException {
    StringWriter sink = new StringWriter();
    sink.append('"');
    EscapingAppendable esc = new EscapingAppendable(sink);
    esc.append("(a,\"b\")");
    sink.append('"');
    assertEquals("\"(a,\\\"b\\\")\"", sink.toString());
  }

  @Test
  void escapingAppendable_layersForArrayOfCompositeWithNestedArray() throws IOException {
    StringWriter sink = new StringWriter();

    // Outer array quotes its composite element and applies array-level escaping.
    sink.append('"');
    EscapingAppendable arrayElementEscaper = new EscapingAppendable(sink);

    // Composite writes a quoted field into the already array-escaped sink.
    arrayElementEscaper.append('(');
    arrayElementEscaper.append('"');

    // Nested array field writes through a second escaping layer. Quotes and
    // backslashes now receive both composite-field and array-element escaping.
    EscapingAppendable compositeFieldEscaper = new EscapingAppendable(arrayElementEscaper);
    compositeFieldEscaper.append("{\"a\\\"b\"}");

    arrayElementEscaper.append('"');
    arrayElementEscaper.append(')');
    sink.append('"');

    assertEquals("\"(\\\"{\\\\\\\"a\\\\\\\\\\\\\\\"b\\\\\\\"}\\\")\"", sink.toString());
  }

  // ---------------- INT4 array leaf via streaming ----------------

  @Test
  void int4ArrayLeaf_streamingText_equalsNonStreaming() throws SQLException, IOException {
    Integer[] input = {1, null, -7, Integer.MAX_VALUE};
    String viaString = MultiDimArrayText.encode(input, ',', null, Int4ArrayLeafCodec.INSTANCE);
    StringBuilder sb = new StringBuilder();
    MultiDimArrayText.encode(input, ',', sb, null, Int4ArrayLeafCodec.INSTANCE);
    assertEquals(viaString, sb.toString());
    assertEquals("{1,NULL,-7,2147483647}", viaString);
  }

  @Test
  void int4ArrayLeaf_streamingBinary_equalsNonStreaming() throws SQLException, IOException {
    int[] input = {7, -42, 0, 1};
    byte[] viaArray = MultiDimArrayBinary.encode(input, null, Int4ArrayLeafCodec.INSTANCE);
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    MultiDimArrayBinary.encode(input, out, null, Int4ArrayLeafCodec.INSTANCE);
    assertArrayEquals(viaArray, out.toByteArray());
  }

  // ---------------- Interface inheritance ----------------

  @Test
  void scalarCodecs_implementStreamingInterfaces() {
    assertTrue(Int4Codec.INSTANCE instanceof StreamingTextCodec,
        "Int4Codec should opt into StreamingTextCodec");
    assertTrue(Int4Codec.INSTANCE instanceof StreamingBinaryCodec,
        "Int4Codec should opt into StreamingBinaryCodec");
    assertTrue(CompositeCodec.INSTANCE instanceof StreamingTextCodec,
        "CompositeCodec should opt into StreamingTextCodec");
    assertTrue(CompositeCodec.INSTANCE instanceof StreamingBinaryCodec,
        "CompositeCodec should opt into StreamingBinaryCodec");
  }

  @Test
  void textFamily_doesNotStream() {
    // The String-natural leaves (text, varchar, bpchar, name, "char") deliberately do NOT stream: a String
    // must be materialised into charset bytes before it is written either way, so a streaming encoder saves
    // nothing over the byte[]/String form (unlike a fixed-width primitive). As a container element the whole
    // family encodes through the non-streaming path. Locked in here so it is not re-added by reflex.
    for (Codec codec : new Codec[]{TextCodec.INSTANCE, VarcharCodec.INSTANCE, BpcharCodec.INSTANCE,
        NameCodec.INSTANCE, CharCodec.INSTANCE}) {
      assertFalse(codec instanceof StreamingTextCodec,
          () -> codec.getTypeName() + " must not stream text");
      assertFalse(codec instanceof StreamingBinaryCodec,
          () -> codec.getTypeName() + " must not stream binary");
    }
  }

  @Test
  void arrayCodecs_implementStreamingInterfaces() {
    assertTrue(ArrayCodec.INSTANCE instanceof StreamingTextCodec,
        "ArrayCodec should opt into StreamingTextCodec");
    assertTrue(ArrayCodec.INSTANCE instanceof StreamingBinaryCodec,
        "ArrayCodec should opt into StreamingBinaryCodec");
    assertTrue(Int4Codec.INSTANCE instanceof ArrayElementCodec,
        "Int4Codec should advertise an array fast-leaf via ArrayElementCodec");
  }

  @Test
  void int4ArrayLeaf_streamingBinaryHeaderMatchesNonStreaming() throws SQLException, IOException {
    Integer[] input = {10, 20, 30};
    byte[] viaArray = MultiDimArrayBinary.encode(input, null, Int4ArrayLeafCodec.INSTANCE);
    BackpatchByteArrayOutputStream out = new BackpatchByteArrayOutputStream();
    MultiDimArrayBinary.encode(input, out, null, Int4ArrayLeafCodec.INSTANCE);
    byte[] streamed = out.toByteArray();
    assertNotNull(viaArray);
    assertArrayEquals(viaArray, streamed);
  }
}
