/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

/**
 * Round-trips each temporal codec's binary encoder through its own binary decoder, so the wire
 * layout the encoder writes is exactly what the (server-validated) decoder reads back. The decoders
 * are already exercised against a live server, so a matching round-trip pins the encoders to the
 * same PostgreSQL binary format.
 */
class TemporalBinaryRoundtripTest {

  private static PgType type(String schemaless, String fullName, int oid) {
    return new PgType(new ObjectName("pg_catalog", schemaless), fullName, oid, 'b', 'D', -1, 0, 0, 0);
  }

  private static Object roundtrip(BinaryCodec codec, PgType type, Object value, CodecContext ctx)
      throws SQLException {
    return codec.decodeBinary(codec.encodeBinary(value, type, ctx), type, ctx);
  }

  @Test
  void timeLocalTime() throws SQLException {
    PgType t = type("time", "time without time zone", 1083);
    CodecContext ctx = TestCodecContext.create(false, true, false, false, false);
    LocalTime v = LocalTime.of(16, 21, 50, 123456000);
    assertEquals(v, roundtrip(TimeCodec.INSTANCE, t, v, ctx));
  }

  @Test
  void timeMidnightAndMax() throws SQLException {
    PgType t = type("time", "time without time zone", 1083);
    CodecContext ctx = TestCodecContext.create(false, true, false, false, false);
    assertEquals(LocalTime.MIDNIGHT, roundtrip(TimeCodec.INSTANCE, t, LocalTime.MIDNIGHT, ctx));
    assertEquals(LocalTime.MAX, roundtrip(TimeCodec.INSTANCE, t, LocalTime.MAX, ctx));
  }

  @Test
  void timeSqlTime() throws SQLException {
    // java.sql.Time decodes/encodes in the JVM default zone; same zone on both sides round-trips.
    PgType t = type("time", "time without time zone", 1083);
    CodecContext ctx = TestCodecContext.create();
    Time v = Time.valueOf("16:21:50");
    assertEquals(v, roundtrip(TimeCodec.INSTANCE, t, v, ctx));
  }

  @Test
  void timetzOffsetTime() throws SQLException {
    PgType t = type("timetz", "time with time zone", 1266);
    CodecContext ctx = TestCodecContext.create(false, false, true, false, false);
    OffsetTime v = OffsetTime.of(16, 21, 50, 123456000, ZoneOffset.ofHoursMinutes(3, 30));
    assertEquals(v, roundtrip(TimetzCodec.INSTANCE, t, v, ctx));
  }

  @Test
  void timetzNegativeOffset() throws SQLException {
    PgType t = type("timetz", "time with time zone", 1266);
    CodecContext ctx = TestCodecContext.create(false, false, true, false, false);
    OffsetTime v = OffsetTime.of(1, 2, 3, 0, ZoneOffset.ofHours(-8));
    assertEquals(v, roundtrip(TimetzCodec.INSTANCE, t, v, ctx));
  }

  @Test
  void timestampLocalDateTime() throws SQLException {
    PgType t = type("timestamp", "timestamp without time zone", 1114);
    CodecContext ctx = TestCodecContext.create(false, false, false, true, false);
    LocalDateTime v = LocalDateTime.of(2023, 9, 5, 16, 21, 50, 123456000);
    assertEquals(v, roundtrip(TimestampCodec.INSTANCE, t, v, ctx));
  }

  @Test
  void timestampBeforePgEpoch() throws SQLException {
    PgType t = type("timestamp", "timestamp without time zone", 1114);
    CodecContext ctx = TestCodecContext.create(false, false, false, true, false);
    LocalDateTime v = LocalDateTime.of(1970, 1, 1, 0, 0, 0, 0);
    assertEquals(v, roundtrip(TimestampCodec.INSTANCE, t, v, ctx));
  }

  @Test
  void timestampSqlTimestamp() throws SQLException {
    PgType t = type("timestamp", "timestamp without time zone", 1114);
    CodecContext ctx = TestCodecContext.create();
    Timestamp v = Timestamp.valueOf("2023-09-05 16:21:50.123456");
    assertEquals(v, roundtrip(TimestampCodec.INSTANCE, t, v, ctx));
  }

  @Test
  void timestamptzOffsetDateTime() throws SQLException {
    PgType t = type("timestamptz", "timestamp with time zone", 1184);
    CodecContext ctx = TestCodecContext.create(false, false, false, false, true);
    OffsetDateTime v = OffsetDateTime.of(2023, 9, 5, 16, 21, 50, 123456000, ZoneOffset.ofHours(-8));
    Object back = roundtrip(TimestamptzCodec.INSTANCE, t, v, ctx);
    assertEquals(v.toInstant(), ((OffsetDateTime) back).toInstant());
  }

  @Test
  void timestamptzInstant() throws SQLException {
    PgType t = type("timestamptz", "timestamp with time zone", 1184);
    CodecContext ctx = TestCodecContext.create(false, false, false, false, true);
    Instant v = Instant.ofEpochSecond(1_693_931_310L, 123456000);
    Object back = roundtrip(TimestamptzCodec.INSTANCE, t, v, ctx);
    assertEquals(v, ((OffsetDateTime) back).toInstant());
  }

  // The generic array leaf (GenericArrayLeafCodec) walks one binary array slice, writing each
  // element through the scalar codec's encodeBinary and reading it back through readLeaf. These
  // round-trips confirm a binary temporal array decodes element-for-element through readLeaf, the
  // same path a binary-received temporal array would take.

  @Test
  void timeArrayViaGenericLeaf() throws SQLException {
    PgType t = type("time", "time without time zone", 1083);
    CodecContext ctx = TestCodecContext.create();
    GenericArrayLeafCodec leaf =
        new GenericArrayLeafCodec(t, TimeCodec.INSTANCE);
    Time[] in = {Time.valueOf("12:34:56"), Time.valueOf("03:30:25")};
    byte[] wire = MultiDimArrayBinary.encode(in, ctx, leaf);
    assertArrayEquals(in, (Time[]) MultiDimArrayBinary.decode(wire, Time.class, ctx, leaf));
  }

  @Test
  void timestampArrayViaGenericLeaf() throws SQLException {
    PgType t = type("timestamp", "timestamp without time zone", 1114);
    CodecContext ctx = TestCodecContext.create();
    GenericArrayLeafCodec leaf =
        new GenericArrayLeafCodec(t, TimestampCodec.INSTANCE);
    Timestamp[] in = {
        Timestamp.valueOf("2023-09-05 16:21:50.123456"),
        Timestamp.valueOf("2012-01-01 13:02:03"),
    };
    byte[] wire = MultiDimArrayBinary.encode(in, ctx, leaf);
    assertArrayEquals(in, (Timestamp[]) MultiDimArrayBinary.decode(wire, Timestamp.class, ctx, leaf));
  }

  @Test
  void timeArrayWithNullViaGenericLeaf() throws SQLException {
    PgType t = type("time", "time without time zone", 1083);
    CodecContext ctx = TestCodecContext.create();
    GenericArrayLeafCodec leaf =
        new GenericArrayLeafCodec(t, TimeCodec.INSTANCE);
    Time[] in = {Time.valueOf("12:34:56"), null};
    byte[] wire = MultiDimArrayBinary.encode(in, ctx, leaf);
    assertArrayEquals(in, (Time[]) MultiDimArrayBinary.decode(wire, Time.class, ctx, leaf));
  }
}
