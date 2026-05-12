/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link CompositeCodec}.
 */
public class CompositeCodecTest {

  @Test
  void testNeedsQuoting() {
    // Empty string needs quoting
    assertTrue(CompositeCodec.needsQuoting(""));

    // Simple values don't need quoting
    assertFalse(CompositeCodec.needsQuoting("hello"));
    assertFalse(CompositeCodec.needsQuoting("123"));
    assertFalse(CompositeCodec.needsQuoting("abc_def"));

    // Special characters need quoting
    assertTrue(CompositeCodec.needsQuoting("hello,world"));
    assertTrue(CompositeCodec.needsQuoting("(value)"));
    assertTrue(CompositeCodec.needsQuoting("with\"quote"));
    assertTrue(CompositeCodec.needsQuoting("with\\backslash"));
    assertTrue(CompositeCodec.needsQuoting("has space"));
    assertTrue(CompositeCodec.needsQuoting("has\ttab"));
    assertTrue(CompositeCodec.needsQuoting("has\nnewline"));
  }

  @Test
  void testEncodeAttributesAsText() {
    // Simple values
    assertEquals("(a,b,c)", CompositeCodec.encodeAttributesAsText(new Object[]{"a", "b", "c"}));

    // With null
    assertEquals("(a,,c)", CompositeCodec.encodeAttributesAsText(new Object[]{"a", null, "c"}));

    // Empty array
    assertEquals("()", CompositeCodec.encodeAttributesAsText(new Object[]{}));

    // Single value
    assertEquals("(value)", CompositeCodec.encodeAttributesAsText(new Object[]{"value"}));

    // Values needing quoting
    assertEquals("(\"hello,world\")", CompositeCodec.encodeAttributesAsText(new Object[]{"hello,world"}));
    assertEquals("(\"with\\\"quote\")", CompositeCodec.encodeAttributesAsText(new Object[]{"with\"quote"}));
    assertEquals("(\"with\\\\backslash\")", CompositeCodec.encodeAttributesAsText(new Object[]{"with\\backslash"}));

    // Mixed
    assertEquals("(simple,\"has,comma\",123)",
        CompositeCodec.encodeAttributesAsText(new Object[]{"simple", "has,comma", 123}));
  }

  @Test
  void testDecodeBinaryFieldsSimple() throws Exception {
    // Binary format: [4 bytes field_count] [for each: 4 bytes oid, 4 bytes len, data]
    // Let's create a simple 2-field composite
    byte[] data = new byte[4 + 8 + 4 + 8 + 5]; // count + field1 header + field2 header + "hello"
    int pos = 0;

    // Field count: 2
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 2;

    // Field 1: OID=23 (int4), len=-1 (NULL)
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 23;
    data[pos++] = (byte) 0xFF;
    data[pos++] = (byte) 0xFF;
    data[pos++] = (byte) 0xFF;
    data[pos++] = (byte) 0xFF;

    // Field 2: OID=25 (text), len=5, data="hello"
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 25;
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 5;
    data[pos++] = 'h';
    data[pos++] = 'e';
    data[pos++] = 'l';
    data[pos++] = 'l';
    data[pos++] = 'o';

    List<CompositeCodec.DecodedField> fields = CompositeCodec.decodeBinaryFields(data);
    assertEquals(2, fields.size());

    // First field is NULL
    assertEquals(23, fields.get(0).getTypeOid());
    assertTrue(fields.get(0).isNull());
    assertNull(fields.get(0).getData());

    // Second field has data
    assertEquals(25, fields.get(1).getTypeOid());
    assertFalse(fields.get(1).isNull());
    assertArrayEquals(new byte[]{'h', 'e', 'l', 'l', 'o'}, fields.get(1).getData());
  }

  @Test
  void testEncodeBinaryFields() throws Exception {
    int[] oids = {23, 25}; // int4, text
    byte[][] fieldData = {null, new byte[]{'h', 'i'}};

    byte[] result = CompositeCodec.encodeBinaryFields(oids, fieldData);

    // Decode it back
    List<CompositeCodec.DecodedField> decoded = CompositeCodec.decodeBinaryFields(result);
    assertEquals(2, decoded.size());

    assertEquals(23, decoded.get(0).getTypeOid());
    assertTrue(decoded.get(0).isNull());

    assertEquals(25, decoded.get(1).getTypeOid());
    assertArrayEquals(new byte[]{'h', 'i'}, decoded.get(1).getData());
  }

  @Test
  void testDecodeBinaryFieldsEmpty() throws Exception {
    // Empty composite: just field count = 0
    byte[] data = {0, 0, 0, 0};

    List<CompositeCodec.DecodedField> fields = CompositeCodec.decodeBinaryFields(data);
    assertEquals(0, fields.size());
  }

  @Test
  void testDecodeBinaryFieldsEmptyValue() throws Exception {
    // Composite with one field that has empty (not null) value
    byte[] data = new byte[4 + 8]; // count + field header (no data bytes since len=0)
    int pos = 0;

    // Field count: 1
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 1;

    // Field 1: OID=25, len=0 (empty string, not null)
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 25;
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 0;
    data[pos++] = 0;

    List<CompositeCodec.DecodedField> fields = CompositeCodec.decodeBinaryFields(data);
    assertEquals(1, fields.size());

    assertFalse(fields.get(0).isNull());
    assertArrayEquals(new byte[0], fields.get(0).getData());
  }
}
