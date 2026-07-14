/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgSQLInputText;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.Time;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

/**
 * Hand-picked round-trip examples, run as plain {@code @Test}s rather than through the fuzzed matrix of
 * {@link JqfCoercionRoundTripFuzzTest}. Useful for pinning a specific scenario or reproducing a case.
 */
class CoercionRoundTripExampleTest {

  /**
   * Write a {@code time} literal through {@code writeString} and read it back through {@code readTime}.
   * This is an off-diagonal write (String into a {@code time} attribute), so
   * {@link CoercionRoundTripSupport#run} only asserts that neither leg leaks and both outcomes match the
   * registries; the value assertion below additionally pins that the time survives in text format.
   */
  @Test
  void writeStringTimeReadTime() throws SQLException {
    CoercionRoundTripSupport.run(new CoercionRoundTripCase(PgTypeDescriptors.scalar(Oid.TIME),
        SqlOutputWriterBinding.WRITE_STRING, "10:15:00", SqlInputReader.READ_TIME, null));

    assertEquals(Time.valueOf("10:15:00"), readBackAsTime("10:15:00"),
        "writeString(time literal) -> readTime should preserve the time in text format");
  }

  /**
   * Write an {@code OffsetTime} into a {@code timetz} attribute through {@code writeObject} and read it
   * back through {@code readObject(OffsetTime.class)}. {@code timetz} has no typed {@code Offset}
   * writer/reader, so this is its identity round-trip on the object axis; because it is an identity pair,
   * {@link CoercionRoundTripSupport#run} asserts value fidelity -- {@code timetz} keeps the offset, so
   * the {@code OffsetTime} survives exactly.
   */
  @Test
  void writeTimetzReadOffsetTime() throws SQLException {
    CoercionRoundTripSupport.run(new CoercionRoundTripCase(PgTypeDescriptors.scalar(Oid.TIMETZ),
        SqlOutputWriterBinding.WRITE_OBJECT_AS, OffsetTime.of(LocalTime.of(10, 15, 30), ZoneOffset.ofHours(5)),
        SqlInputReader.READ_OBJECT_AS, OffsetTime.class));
  }

  /**
   * Write an {@code OffsetDateTime} into a {@code timestamptz} attribute through {@code writeObject} and
   * read it back through {@code readObject(OffsetDateTime.class)}. {@code timestamptz} is an instant, so
   * the read-back offset is the session zone rather than the written {@code +05:00}; the identity-pair
   * fidelity therefore compares the moment (see {@code SAME_INSTANT}), which is preserved.
   */
  @Test
  void writeTimestamptzReadOffsetDateTime() throws SQLException {
    CoercionRoundTripSupport.run(new CoercionRoundTripCase(PgTypeDescriptors.scalar(Oid.TIMESTAMPTZ),
        SqlOutputWriterBinding.WRITE_OBJECT_AS,
        OffsetDateTime.of(LocalDateTime.of(2020, 1, 2, 10, 15, 30), ZoneOffset.ofHours(5)),
        SqlInputReader.READ_OBJECT_AS, OffsetDateTime.class));
  }

  /** Encodes a time literal into a single-field {@code time} composite (text) and reads it via readTime. */
  private static Object readBackAsTime(String literal) throws SQLException {
    PgType comp = FuzzComposites.singleField(Oid.TIME);
    PgCodecContext ctx = (PgCodecContext) OfflineCodecs.builder()
        .type(comp)
        .timeZone(TimeZone.getDefault())
        .build();
    RawValue wire = Codecs.encode(
        new WriteOracle.WriteProbe(SqlOutputWriterBinding.WRITE_STRING, literal), comp, ctx, Format.TEXT);
    SQLInput in = new PgSQLInputText(wire.asString(StandardCharsets.UTF_8), comp, ctx);
    return SqlInputReader.READ_TIME.read(in, Object.class);
  }
}
