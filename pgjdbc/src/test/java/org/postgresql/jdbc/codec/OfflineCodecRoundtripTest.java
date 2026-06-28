/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Round-trips values through the public offline (connectionless) codec surface: build a
 * {@link CodecContext} with {@link PgCodecContext#offlineBuilder()}, encode with {@link Codecs#encode}
 * to a {@link RawValue}, and decode it back with {@link Codecs#decode} — all with no
 * {@link java.sql.Connection}. Scalar and temporal types round-trip in both wire formats; container
 * types report a clear error pending offline container support.
 */
class OfflineCodecRoundtripTest {

  // ---- built-in scalar and temporal descriptors -----------------------------
  // Built-in OIDs are pinned in CodecRegistry, so resolveCodec finds the codec from the OID alone;
  // these descriptors need not be registered with the offline builder. typcategory mirrors the
  // catalog but does not affect resolution for a pinned scalar.

  private static final PgType INT2 = base("int2", "smallint", Oid.INT2, 'N');
  private static final PgType INT4 = base("int4", "integer", Oid.INT4, 'N');
  private static final PgType INT8 = base("int8", "bigint", Oid.INT8, 'N');
  private static final PgType FLOAT4 = base("float4", "real", Oid.FLOAT4, 'N');
  private static final PgType FLOAT8 = base("float8", "double precision", Oid.FLOAT8, 'N');
  private static final PgType NUMERIC = base("numeric", "numeric", Oid.NUMERIC, 'N');
  private static final PgType BOOL = base("bool", "boolean", Oid.BOOL, 'B');
  private static final PgType TEXT = base("text", "text", Oid.TEXT, 'S');
  private static final PgType VARCHAR = base("varchar", "character varying", Oid.VARCHAR, 'S');
  private static final PgType BYTEA = base("bytea", "bytea", Oid.BYTEA, 'U');
  private static final PgType UUID_T = base("uuid", "uuid", Oid.UUID, 'U');

  private static final PgType DATE = base("date", "date", Oid.DATE, 'D');
  private static final PgType TIME = base("time", "time without time zone", Oid.TIME, 'D');
  private static final PgType TIMETZ = base("timetz", "time with time zone", Oid.TIMETZ, 'D');
  private static final PgType TIMESTAMP =
      base("timestamp", "timestamp without time zone", Oid.TIMESTAMP, 'D');
  private static final PgType TIMESTAMPTZ =
      base("timestamptz", "timestamp with time zone", Oid.TIMESTAMPTZ, 'D');

  // A built-in array type. Its codec resolves offline through the driver's built-in catalog, so the
  // builder need not carry it; the descriptor's typelem drives element decoding.
  private static final PgType INT4_ARRAY = new PgType(
      new ObjectName("pg_catalog", "_int4"), "integer[]", Oid.INT4_ARRAY, 'b', 'A', -1,
      Oid.INT4, 0, 0);
  // A user composite OID with no built-in or registered descriptor, for the unregistered-type case.
  private static final int COMPOSITE_OID = 99_999;

  private static PgType base(String name, String fullName, int oid, char typcategory) {
    return new PgType(new ObjectName("pg_catalog", name), fullName, oid, 'b', typcategory, -1, 0,
        0, 0);
  }

  private static CodecContext offline() {
    return PgCodecContext.offlineBuilder().build();
  }

  private static <T> T roundtrip(Object value, TypeDescriptor type, CodecContext ctx, Format format,
      Class<T> targetClass) throws SQLException {
    RawValue encoded = Codecs.encode(value, type, ctx, format);
    assertEquals(format, encoded.getFormat());
    T decoded = Codecs.decode(encoded, type, ctx, targetClass);
    assertNotNull(decoded, "round-trip decoded a present value, not null");
    return decoded;
  }

  @Test
  void scalarsRoundtripInBothFormats() throws SQLException {
    CodecContext ctx = offline();
    for (Format format : Format.values()) {
      assertEquals(Short.valueOf((short) 7),
          roundtrip((short) 7, INT2, ctx, format, Short.class), "int2 " + format);
      assertEquals(Integer.valueOf(42),
          roundtrip(42, INT4, ctx, format, Integer.class), "int4 " + format);
      assertEquals(Long.valueOf(9_000_000_000L),
          roundtrip(9_000_000_000L, INT8, ctx, format, Long.class), "int8 " + format);
      assertEquals(Float.valueOf(3.5f),
          roundtrip(3.5f, FLOAT4, ctx, format, Float.class), "float4 " + format);
      assertEquals(Double.valueOf(12345.5d),
          roundtrip(12345.5d, FLOAT8, ctx, format, Double.class), "float8 " + format);
      assertEquals(new BigDecimal("12345.6789"),
          roundtrip(new BigDecimal("12345.6789"), NUMERIC, ctx, format, BigDecimal.class),
          "numeric " + format);
      assertEquals(Boolean.TRUE,
          roundtrip(true, BOOL, ctx, format, Boolean.class), "bool true " + format);
      assertEquals(Boolean.FALSE,
          roundtrip(false, BOOL, ctx, format, Boolean.class), "bool false " + format);
      assertEquals("hello, world",
          roundtrip("hello, world", TEXT, ctx, format, String.class), "text " + format);
      assertEquals("a varchar value",
          roundtrip("a varchar value", VARCHAR, ctx, format, String.class), "varchar " + format);
      UUID uuid = UUID.fromString("0fa3b9c4-1234-4567-89ab-0123456789ab");
      assertEquals(uuid, roundtrip(uuid, UUID_T, ctx, format, UUID.class), "uuid " + format);
      byte[] bytes = {0, 1, 2, (byte) 0xFE, (byte) 0xFF};
      assertArrayEquals(bytes,
          roundtrip(bytes, BYTEA, ctx, format, byte[].class), "bytea " + format);
    }
  }

  @Test
  void temporalRoundtripsInBothFormats() throws SQLException {
    CodecContext ctx = offline();
    for (Format format : Format.values()) {
      LocalDate date = LocalDate.of(2026, 6, 29);
      assertEquals(date, roundtrip(date, DATE, ctx, format, LocalDate.class), "date " + format);

      LocalTime time = LocalTime.of(16, 21, 50, 123456000);
      assertEquals(time, roundtrip(time, TIME, ctx, format, LocalTime.class), "time " + format);

      OffsetTime timetz = OffsetTime.of(16, 21, 50, 123456000, ZoneOffset.ofHoursMinutes(3, 30));
      assertEquals(timetz, roundtrip(timetz, TIMETZ, ctx, format, OffsetTime.class),
          "timetz " + format);

      LocalDateTime timestamp = LocalDateTime.of(2023, 9, 5, 16, 21, 50, 123456000);
      assertEquals(timestamp, roundtrip(timestamp, TIMESTAMP, ctx, format, LocalDateTime.class),
          "timestamp " + format);

      OffsetDateTime timestamptz =
          OffsetDateTime.of(2023, 9, 5, 16, 21, 50, 123456000, ZoneOffset.ofHours(-8));
      OffsetDateTime back = roundtrip(timestamptz, TIMESTAMPTZ, ctx, format, OffsetDateTime.class);
      assertEquals(timestamptz.toInstant(), back.toInstant(), "timestamptz " + format);
    }
  }

  @Test
  void offlineBuilderProducesConnectionlessContext() {
    CodecContext ctx = PgCodecContext.offlineBuilder()
        .charset(UTF_8)
        .timeZone(TimeZone.getTimeZone("UTC"))
        .integerDateTimes(true)
        .build();
    assertEquals(UTF_8, ctx.getCharset());
    assertFalse(ctx.usesDoubleDateTime(), "integerDateTimes(true) means no float8 datetimes");
    assertEquals(TimeZone.getTimeZone("UTC"), ctx.getClientTimeZone());
  }

  @Test
  void arrayDecodeOfflineProducesJavaArray() throws SQLException {
    CodecContext ctx = offline();
    Integer[] values = {1, 2, 3};
    RawValue raw = Codecs.encode(values, INT4_ARRAY, ctx, Format.BINARY);
    // A typed array target decodes element-for-element with no connection.
    assertArrayEquals(values, Codecs.decode(raw, INT4_ARRAY, ctx, Integer[].class));
    // Object.class also yields a Java array offline (a connection-bound context gives a PgArray).
    assertArrayEquals(values, (Integer[]) Codecs.decode(raw, INT4_ARRAY, ctx, Object.class));
  }

  @Test
  void arrayDecodeOfflineToSqlArrayReportsClearError() {
    CodecContext ctx = offline();
    // A java.sql.Array needs connection-bound lazy operations, so an explicit Array target reports a
    // clear error offline rather than handing back a Java array of a different type.
    PSQLException ex = assertThrows(PSQLException.class,
        () -> Codecs.decode(RawValue.binary(new byte[0]), INT4_ARRAY, ctx, Array.class));
    assertEquals(PSQLState.NOT_IMPLEMENTED.getState(), ex.getSQLState(),
        "offline java.sql.Array decode should report 'feature not supported'");
  }

  @Test
  void unregisteredOfflineTypeReportsClearError() {
    // resolveType has no descriptor for an unregistered child OID and must say so rather than NPE.
    CodecContext ctx = offline();
    PSQLException ex = assertThrows(PSQLException.class, () -> ctx.resolveType(COMPOSITE_OID));
    assertEquals(PSQLState.INVALID_PARAMETER_TYPE.getState(), ex.getSQLState());
  }

  @Test
  void rawValueWrapsBorrowedSliceAndCopiesOnDemand() {
    byte[] buffer = {10, 20, 30, 40, 50};
    RawValue slice = RawValue.of(Format.BINARY, buffer, 1, 3);
    assertEquals(Format.BINARY, slice.getFormat());
    assertEquals(3, slice.getLength());
    assertArrayEquals(new byte[]{20, 30, 40}, slice.toByteArray());

    // toByteArray copies: mutating the copy leaves the borrowed buffer untouched.
    byte[] copy = slice.toByteArray();
    copy[0] = 99;
    assertEquals(20, buffer[1]);

    RawValue text = RawValue.text("café".getBytes(UTF_8));
    assertEquals("café", text.asString(UTF_8));
    assertEquals(RawValue.binary(new byte[]{1, 2, 3}), RawValue.binary(new byte[]{1, 2, 3}));
  }
}
