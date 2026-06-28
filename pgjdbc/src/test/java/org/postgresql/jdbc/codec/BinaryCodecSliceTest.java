/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Verifies the slice form {@code decodeBinary(byte[], offset, length, ...)}: each
 * value decodes the same whether it sits at offset 0 or embedded inside a larger
 * buffer, so container codecs can decode elements in place without
 * {@link Arrays#copyOfRange}. The type argument is unused by the fixed-width and
 * string overrides, so a single placeholder {@link PgType} is shared.
 */
class BinaryCodecSliceTest {

  private static final PgType ANY = new PgType(
      new ObjectName("pg_catalog", "int4"), "integer", Oid.INT4, 'b', 'N', -1, 0, 0, 0);
  private static final CodecContext CTX = TestCodecContext.create();

  /** Embeds {@code value} at offset 5 of a noise-filled buffer with trailing padding. */
  private static byte[] embed(byte[] value) {
    byte[] buf = new byte[5 + value.length + 3];
    Arrays.fill(buf, (byte) 0xEE); // non-zero noise to catch offset/length bugs
    System.arraycopy(value, 0, buf, 5, value.length);
    return buf;
  }

  /** Asserts that decoding {@code value} at a non-zero offset matches decoding it alone. */
  private static void assertSliceMatches(BinaryCodec codec, byte[] value) throws SQLException {
    Object whole = codec.decodeBinary(value, ANY, CTX);
    Object slice = codec.decodeBinary(embed(value), 5, value.length, ANY, CTX);
    assertEquals(whole, slice);
  }

  @Test
  void int2_sliceMatchesWhole() throws SQLException {
    byte[] v = new byte[2];
    ByteConverter.int2(v, 0, (short) -1234);
    assertSliceMatches(Int2Codec.INSTANCE, v);
    assertEquals(-1234, Int2Codec.INSTANCE.decodeBinary(embed(v), 5, 2, ANY, CTX));
  }

  @Test
  void int4_sliceMatchesWhole() throws SQLException {
    byte[] v = new byte[4];
    ByteConverter.int4(v, 0, -123456);
    assertSliceMatches(Int4Codec.INSTANCE, v);
    assertEquals(-123456, Int4Codec.INSTANCE.decodeBinary(embed(v), 5, 4, ANY, CTX));
  }

  @Test
  void int8_sliceMatchesWhole() throws SQLException {
    byte[] v = new byte[8];
    ByteConverter.int8(v, 0, -9_000_000_000L);
    assertSliceMatches(Int8Codec.INSTANCE, v);
    assertEquals(-9_000_000_000L, Int8Codec.INSTANCE.decodeBinary(embed(v), 5, 8, ANY, CTX));
  }

  @Test
  void float4_sliceMatchesWhole() throws SQLException {
    byte[] v = new byte[4];
    ByteConverter.float4(v, 0, 3.5f);
    assertSliceMatches(Float4Codec.INSTANCE, v);
  }

  @Test
  void float8_sliceMatchesWhole() throws SQLException {
    byte[] v = new byte[8];
    ByteConverter.float8(v, 0, -2.71828);
    assertSliceMatches(Float8Codec.INSTANCE, v);
  }

  @Test
  void oid_sliceMatchesWhole() throws SQLException {
    byte[] v = new byte[4];
    ByteConverter.int4(v, 0, (int) 4_000_000_000L); // > Integer.MAX_VALUE as unsigned
    assertSliceMatches(OidCodec.INSTANCE, v);
    assertEquals(4_000_000_000L, OidCodec.INSTANCE.decodeBinary(embed(v), 5, 4, ANY, CTX));
  }

  @Test
  void bool_sliceMatchesWhole() throws SQLException {
    assertEquals(true, BoolCodec.INSTANCE.decodeBinary(embed(new byte[]{1}), 5, 1, ANY, CTX));
    assertEquals(false, BoolCodec.INSTANCE.decodeBinary(embed(new byte[]{0}), 5, 1, ANY, CTX));
  }

  @Test
  void uuid_sliceMatchesWhole() throws SQLException {
    UUID uuid = new UUID(0x0011223344556677L, 0x8899aabbccddeeffL);
    byte[] v = new byte[16];
    ByteConverter.int8(v, 0, uuid.getMostSignificantBits());
    ByteConverter.int8(v, 8, uuid.getLeastSignificantBits());
    assertSliceMatches(UuidCodec.INSTANCE, v);
    assertEquals(uuid, UuidCodec.INSTANCE.decodeBinary(embed(v), 5, 16, ANY, CTX));
  }

  @Test
  void text_sliceMatchesWhole() throws SQLException {
    byte[] v = "héllo".getBytes(StandardCharsets.UTF_8);
    assertSliceMatches(TextCodecImpl.INSTANCE, v);
    assertEquals("héllo", TextCodecImpl.INSTANCE.decodeBinary(embed(v), 5, v.length, ANY, CTX));
    // varchar/bpchar/name delegate to the same slice path
    assertEquals("héllo", VarcharCodec.INSTANCE.decodeBinary(embed(v), 5, v.length, ANY, CTX));
  }

  @Test
  void numeric_sliceMatchesWhole() throws SQLException {
    byte[] v = NumericCodec.INSTANCE.encodeBinary(
        new java.math.BigDecimal("-12345.6789"), ANY, CTX);
    assertSliceMatches(NumericCodec.INSTANCE, v);
  }

  @Test
  void interval_sliceMatchesWhole() throws SQLException {
    byte[] v = IntervalCodec.INSTANCE.encodeBinary(
        new org.postgresql.util.PGInterval(1, 2, 3, 4, 5, 6.5), ANY, CTX);
    assertSliceMatches(IntervalCodec.INSTANCE, v);
  }

  @Test
  void sliceLengthMismatch_throws() {
    byte[] buf = embed(new byte[4]);
    assertThrows(SQLException.class,
        () -> Int4Codec.INSTANCE.decodeBinary(buf, 5, 3, ANY, CTX));
  }

  @Test
  void defaultSlice_copiesWindow_andDelegatesToWholeArrayForm() throws SQLException {
    // A codec that does NOT override the slice form: it must still see exactly the
    // [offset, offset + length) window via the BinaryCodec default.
    BinaryCodec stub = new BinaryCodec() {
      @Override
      public String getTypeName() {
        return "stub";
      }

      @Override
      public Class<?> getDefaultJavaType() {
        return String.class;
      }

      @Override
      public Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) {
        return new String(data, StandardCharsets.UTF_8);
      }

      @Override
      public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
        return new byte[0];
      }
    };
    byte[] v = "wörld".getBytes(StandardCharsets.UTF_8);
    assertEquals("wörld", stub.decodeBinary(embed(v), 5, v.length, ANY, CTX));
    assertEquals("wörld", stub.decodeBinary(v, 0, v.length, ANY, CTX));
  }
}
