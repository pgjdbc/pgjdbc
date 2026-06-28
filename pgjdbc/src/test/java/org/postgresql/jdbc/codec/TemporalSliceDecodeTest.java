/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * Verifies that the temporal codecs decode a value occupying a sub-range of a larger buffer
 * (a {@code byte[]} slice, as produced by array and composite element decoding) identically to
 * decoding the same bytes in a tight, offset-zero buffer.
 *
 * <p>The wire payload is wrapped in junk padding so a wrong offset or a length check against the
 * whole buffer would surface as a mismatch.</p>
 */
class TemporalSliceDecodeTest {

  private static final int LEAD = 3;
  private static final int TRAIL = 2;

  private static PgType type(String schemaless, String fullName, int oid) {
    return new PgType(new ObjectName("pg_catalog", schemaless), fullName, oid,
        'b', 'D', -1, 0, 0, 0);
  }

  /** Wraps {@code payload} in {@value LEAD}+{@value TRAIL} bytes of non-zero junk. */
  private static byte[] pad(byte[] payload) {
    byte[] buf = new byte[LEAD + payload.length + TRAIL];
    java.util.Arrays.fill(buf, (byte) 0x7f);
    System.arraycopy(payload, 0, buf, LEAD, payload.length);
    return buf;
  }

  private static void assertSliceParity(BinaryCodec codec, PgType type, byte[] payload,
      CodecContext ctx) throws SQLException {
    Object whole = codec.decodeBinary(payload, type, ctx);
    Object slice = codec.decodeBinary(pad(payload), LEAD, payload.length, type, ctx);
    assertEquals(whole, slice, codec.getTypeName() + " slice decode must match whole-buffer decode");
  }

  private static byte[] int8(long micros) {
    byte[] b = new byte[8];
    ByteConverter.int8(b, 0, micros);
    return b;
  }

  private static byte[] int4(int v) {
    byte[] b = new byte[4];
    ByteConverter.int4(b, 0, v);
    return b;
  }

  @Test
  void dateSliceParity() throws SQLException {
    PgType t = type("date", "date", 1082);
    // days since 2000-01-01
    byte[] payload = int4(8800);
    assertSliceParity(DateCodec.INSTANCE, t, payload, TestCodecContext.create());
    assertSliceParity(DateCodec.INSTANCE, t, payload,
        TestCodecContext.create(true, false, false, false, false));
  }

  @Test
  void timeSliceParity() throws SQLException {
    PgType t = type("time", "time without time zone", 1083);
    // microseconds since midnight
    byte[] payload = int8(45_000_000_000L);
    assertSliceParity(TimeCodec.INSTANCE, t, payload, TestCodecContext.create());
    assertSliceParity(TimeCodec.INSTANCE, t, payload,
        TestCodecContext.create(false, true, false, false, false));
  }

  @Test
  void timetzSliceParity() throws SQLException {
    PgType t = type("timetz", "time with time zone", 1266);
    // 12 bytes: int8 micros since midnight + int4 zone offset (seconds, postgres sign)
    byte[] payload = new byte[12];
    ByteConverter.int8(payload, 0, 45_000_000_000L);
    ByteConverter.int4(payload, 8, -3600);
    assertSliceParity(TimetzCodec.INSTANCE, t, payload, TestCodecContext.create());
    assertSliceParity(TimetzCodec.INSTANCE, t, payload,
        TestCodecContext.create(false, false, true, false, false));
  }

  @Test
  void timestampSliceParity() throws SQLException {
    PgType t = type("timestamp", "timestamp without time zone", 1114);
    // microseconds since 2000-01-01
    byte[] payload = int8(760_000_000_000_000L);
    assertSliceParity(TimestampCodec.INSTANCE, t, payload, TestCodecContext.create());
    assertSliceParity(TimestampCodec.INSTANCE, t, payload,
        TestCodecContext.create(false, false, false, true, false));
  }

  @Test
  void timestamptzSliceParity() throws SQLException {
    PgType t = type("timestamptz", "timestamp with time zone", 1184);
    byte[] payload = int8(760_000_000_000_000L);
    assertSliceParity(TimestamptzCodec.INSTANCE, t, payload, TestCodecContext.create());
    assertSliceParity(TimestamptzCodec.INSTANCE, t, payload,
        TestCodecContext.create(false, false, false, false, true));
  }
}
