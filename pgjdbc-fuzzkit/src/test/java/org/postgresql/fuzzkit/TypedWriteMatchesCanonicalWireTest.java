/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;
import org.postgresql.fuzzkit.coercion.WriteCoercions.Method;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgSQLOutputBinary;
import org.postgresql.jdbc.PgSQLOutputText;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.SQLOutput;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * The byte-equivalence safety net for phase E's removal of the {@code TYPED}/{@code OBJECT} read paths.
 * The reader fuzzer now feeds the reader only the canonical wire, on the assumption -- established by
 * research O1 -- that a driver <em>write</em> presents field bytes identical to the canonical codec on
 * the diagonal, so the dropped write-then-read cells were redundant. This test pins that assumption: for
 * every diagonal coercion scalar and both wire formats, the field bytes a typed {@code PgSQLOutput}
 * write produces must equal {@code Codecs.encode(value, scalar(oid), ctx, format)}.
 *
 * <p>The comparison is on <em>field</em> bytes, so the composite framing is stripped: rather than
 * running the value through {@code Codecs.encode(SQLData, composite, ...)} -- which wraps the field in
 * the composite header (binary) or the {@code (...)} text form -- the write is driven straight through a
 * {@link PgSQLOutputBinary} / {@link PgSQLOutputText} built on the single-field composite, and the field
 * bytes are read back from {@code getAttributeValues().get(0)} before any framing runs. That is exactly
 * the field value the reader adapter is handed on the canonical path.
 *
 * <p>Two axes of scalar are covered:
 *
 * <ul>
 *   <li>the eight diagonal typed-writer scalars ({@code int4}, {@code int8}, {@code numeric},
 *       {@code text}, {@code bool}, {@code date}, {@code time}, {@code timestamp}) are written through
 *       their descriptor's {@code typedWriter} ({@code writeInt}, {@code writeString}, ...);</li>
 *   <li>the two object-axis scalars ({@code timetz}, {@code timestamptz}, which have no typed
 *       {@code Offset} writer) are written through {@code writeObject(value, jdbcType)}.</li>
 * </ul>
 *
 * <p>Values are fixed, deterministic examples of each scalar's {@code naturalClass} -- not random --
 * so a failure is reproducible. If the typed field bytes ever diverge from the canonical wire, O1's
 * "no coverage lost" assumption no longer holds for that type and the narrowing would be unsafe; this
 * test turns that drift into a red build rather than a silent gap.
 */
class TypedWriteMatchesCanonicalWireTest {

  /** One deterministic sample value per scalar {@code naturalClass}, chosen to encode legally. */
  private static final Map<Class<?>, Object> SAMPLES = samples();

  private static Map<Class<?>, Object> samples() {
    // A TreeMap on class name keeps the map order stable, though lookups are by exact class.
    Map<Class<?>, Object> map = new TreeMap<>((a, b) -> a.getName().compareTo(b.getName()));
    map.put(Integer.class, 1_234_567);
    map.put(Long.class, 9_876_543_210L);
    map.put(BigDecimal.class, new BigDecimal("12345.6789"));
    map.put(String.class, "sample text é");
    map.put(Boolean.class, Boolean.TRUE);
    map.put(Date.class, Date.valueOf(LocalDate.of(2020, 1, 2)));
    map.put(Time.class, Time.valueOf(LocalTime.of(10, 15, 30)));
    map.put(Timestamp.class,
        Timestamp.valueOf(LocalDateTime.of(2020, 1, 2, 10, 15, 30, 123_456_000)));
    map.put(OffsetTime.class, OffsetTime.of(LocalTime.of(10, 15, 30), ZoneOffset.ofHours(5)));
    map.put(OffsetDateTime.class,
        OffsetDateTime.of(LocalDateTime.of(2020, 1, 2, 10, 15, 30), ZoneOffset.ofHours(5)));
    return map;
  }

  @Test
  void typedFieldBytesMatchCanonicalWire() throws SQLException {
    for (ScalarDescriptor descriptor : PgTypeDescriptors.coercionScalars()) {
      Object value = SAMPLES.get(descriptor.naturalClass());
      assertEquals(true, value != null,
          () -> "no sample value for naturalClass " + descriptor.naturalClass().getName()
              + " (OID " + descriptor.oid() + ")");
      for (Format format : Format.values()) {
        assertFieldBytesMatch(descriptor, value, format);
      }
    }
  }

  private static void assertFieldBytesMatch(ScalarDescriptor descriptor, Object value, Format format)
      throws SQLException {
    int oid = descriptor.oid();
    PgType comp = FuzzComposites.singleField(oid);
    PgCodecContext ctx = (PgCodecContext) PgCodecContext.offlineBuilder()
        .type(comp)
        .timeZone(TimeZone.getDefault())
        .build();

    // The canonical wire the reader is handed: the field's own codec, same scalar PgType shape the
    // reader fuzzer uses.
    RawValue canonical = Codecs.encode(value, scalar(oid), ctx, format);

    // The typed-write field bytes, with the composite framing stripped: the write is driven straight
    // through the format's PgSQLOutput, and the single field's buffer is read back before framing.
    Object typedField = typedFieldValue(descriptor, value, comp, ctx, format);

    if (format == Format.TEXT) {
      assertEquals(canonical.asString(StandardCharsets.UTF_8), (String) typedField,
          () -> writerLabel(descriptor) + " text field bytes must equal the canonical wire for OID "
              + oid);
    } else {
      assertArrayEquals(canonical.toByteArray(), (byte[]) typedField,
          () -> writerLabel(descriptor) + " binary field bytes must equal the canonical wire for OID "
              + oid);
    }
  }

  /**
   * Drives one write into the single-field composite through the format's {@link SQLOutput} and returns
   * the single field's buffer (a {@code String} for text, a {@code byte[]} for binary) before the
   * composite framing runs. A diagonal scalar uses its descriptor's {@code typedWriter}; an object-axis
   * scalar ({@code timetz}/{@code timestamptz}) uses {@code writeObject(value, jdbcType)}.
   */
  private static Object typedFieldValue(ScalarDescriptor descriptor, Object value, PgType comp,
      PgCodecContext ctx, Format format) throws SQLException {
    Method typedWriter = descriptor.typedWriter();
    if (format == Format.TEXT) {
      PgSQLOutputText out = new PgSQLOutputText(comp, ctx);
      writeField(out, descriptor, typedWriter, value);
      List<String> fields = out.getAttributeValues();
      assertEquals(1, fields.size(), "one field written");
      return fields.get(0);
    }
    PgSQLOutputBinary out = new PgSQLOutputBinary(comp, ctx);
    writeField(out, descriptor, typedWriter, value);
    List<byte[]> fields = out.getAttributeValues();
    assertEquals(1, fields.size(), "one field written");
    return fields.get(0);
  }

  private static void writeField(SQLOutput out, ScalarDescriptor descriptor, Method typedWriter,
      Object value) throws SQLException {
    if (typedWriter == null) {
      // The object axis (timetz/timestamptz): the generic writeObject with the descriptor's JDBCType,
      // the same call the driver write path takes for a type with no typed Offset writer.
      out.writeObject(value, descriptor.jdbcType());
    } else {
      SqlOutputWriterBinding.of(typedWriter).write(out, value);
    }
  }

  private static String writerLabel(ScalarDescriptor descriptor) {
    Method typedWriter = descriptor.typedWriter();
    return typedWriter == null ? "writeObject" : SqlOutputWriterBinding.of(typedWriter).label();
  }

  /** The scalar {@link PgType} the reader fuzzer's canonical path resolves (a bare raw-OID base type). */
  private static PgType scalar(int oid) {
    return new PgType(new ObjectName("pg_catalog", "t" + oid), "t" + oid, oid, 'b', 'N', -1, 0, 0, 0);
  }
}
