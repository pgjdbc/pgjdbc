/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.PGRange;
import org.postgresql.util.PGmultirange;
import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

/**
 * Unit tests for {@link MultirangeCodec}, run without a live connection.
 *
 * <p>The codec composes on top of {@link RangeCodec}, so it needs a {@link CodecContext} that
 * resolves the range type and its subtype. {@link StubContext} answers those lookups directly
 * ({@code int4multirange} → {@code int4range} → {@code int4}) and delegates the wire/policy surface
 * to a connectionless test context, so these tests pin the multirange framing — the {@code {...}}
 * braces, the comma separation, the empty form, and the binary count/length headers — independently
 * of a server. Live coverage, including quoted bounds and a custom subtype, lives in
 * {@code MultirangeRoundtripTest}.
 */
class MultirangeCodecTest {

  private static final int INT4MULTIRANGE_OID = 4451;
  private static final int INT4RANGE_OID = 3904;
  // A multirange whose range type cannot be resolved, to pin the explicit-error guard.
  private static final int UNRESOLVED_MULTIRANGE_OID = 99_997;

  private static final PgType INT4 = new PgType(
      new ObjectName("pg_catalog", "int4"), "int4", Oid.INT4, 'b', 'N', -1, 0, 0, 0);

  private static final PgType INT4RANGE = new PgType(
      new ObjectName("pg_catalog", "int4range"), "int4range", INT4RANGE_OID,
      'r', 'R', -1, 0, 0, 0).withRangeSubtype(Oid.INT4);

  private static final PgType INT4MULTIRANGE = new PgType(
      new ObjectName("pg_catalog", "int4multirange"), "int4multirange", INT4MULTIRANGE_OID,
      'm', 'R', -1, 0, 0, 0).withMultirangeRange(INT4RANGE_OID);

  // typtype 'm' but no range link, and the context cannot supply one either.
  private static final PgType UNRESOLVED_MULTIRANGE = new PgType(
      new ObjectName("public", "broken_multirange"), "broken_multirange", UNRESOLVED_MULTIRANGE_OID,
      'm', 'R', -1, 0, 0, 0);

  private static final CodecContext CTX = new StubContext();

  @SafeVarargs
  private static PGmultirange<Integer> multirange(PGRange<Integer>... ranges) {
    return new PGmultirange<>(ranges);
  }

  @Test
  void textRoundtrip_severalRanges() throws SQLException {
    PGmultirange<Integer> expected = multirange(
        new PGRange<>(1, 5, true, false), new PGRange<>(10, 20, true, false));
    String text = MultirangeCodec.INSTANCE.encodeText(expected, INT4MULTIRANGE, CTX);
    assertEquals("{[1,5),[10,20)}", text);
    assertEquals(expected, MultirangeCodec.INSTANCE.decodeText(text, INT4MULTIRANGE, CTX));
  }

  @Test
  void textRoundtrip_singleRange() throws SQLException {
    PGmultirange<Integer> expected = multirange(new PGRange<>(1, 5, true, false));
    assertEquals(expected, MultirangeCodec.INSTANCE.decodeText("{[1,5)}", INT4MULTIRANGE, CTX));
  }

  @Test
  void textRoundtrip_empty() throws SQLException {
    PGmultirange<Integer> empty = multirange();
    assertEquals("{}", MultirangeCodec.INSTANCE.encodeText(empty, INT4MULTIRANGE, CTX));
    assertEquals(empty, MultirangeCodec.INSTANCE.decodeText("{}", INT4MULTIRANGE, CTX));
  }

  @Test
  void textDecode_emptyInnerRangesAreDropped() throws SQLException {
    // The server normalises empty ranges out of a multirange ({empty} -> {}); the parser accepts
    // and drops them so a hand-built literal decodes the way the server would.
    assertEquals(multirange(), MultirangeCodec.INSTANCE.decodeText("{empty}", INT4MULTIRANGE, CTX));
    assertEquals(multirange(new PGRange<>(1, 2, true, false)),
        MultirangeCodec.INSTANCE.decodeText("{[1,2),empty}", INT4MULTIRANGE, CTX));
    assertEquals(multirange(new PGRange<>(5, 9, true, false)),
        MultirangeCodec.INSTANCE.decodeText("{empty,[5,9)}", INT4MULTIRANGE, CTX));
  }

  @Test
  void textDecode_boundsAreTypedFromSubtype() throws SQLException {
    Object decoded = MultirangeCodec.INSTANCE.decodeText("{[1,5)}", INT4MULTIRANGE, CTX);
    PGmultirange<?> mr = assertInstanceOf(PGmultirange.class, decoded);
    // The range subtype resolves to int4, so the bound is an Integer, not a String.
    assertInstanceOf(Integer.class, mr.getRanges().get(0).getLower());
    assertEquals(1, mr.getRanges().get(0).getLower());
  }

  @Test
  void binaryRoundtrip_severalRanges() throws SQLException {
    PGmultirange<Integer> expected = multirange(
        new PGRange<>(1, 5, true, false), new PGRange<>(10, 20, true, false));
    byte[] wire = MultirangeCodec.INSTANCE.encodeBinary(expected, INT4MULTIRANGE, CTX);
    assertEquals(expected, MultirangeCodec.INSTANCE.decodeBinary(wire, 0, wire.length, INT4MULTIRANGE, CTX));
  }

  @Test
  void binaryRoundtrip_empty() throws SQLException {
    PGmultirange<Integer> empty = multirange();
    byte[] wire = MultirangeCodec.INSTANCE.encodeBinary(empty, INT4MULTIRANGE, CTX);
    // int32 zero count, nothing else.
    assertEquals(4, wire.length);
    assertEquals(empty, MultirangeCodec.INSTANCE.decodeBinary(wire, 0, wire.length, INT4MULTIRANGE, CTX));
  }

  @Test
  void binaryDecode_typeLabelIsTheMultirangeName() throws SQLException {
    byte[] wire = MultirangeCodec.INSTANCE.encodeBinary(
        multirange(new PGRange<>(1, 5, true, false)), INT4MULTIRANGE, CTX);
    PGmultirange<?> mr = assertInstanceOf(PGmultirange.class,
        MultirangeCodec.INSTANCE.decodeBinary(wire, 0, wire.length, INT4MULTIRANGE, CTX));
    assertEquals("int4multirange", mr.getType());
  }

  @Test
  void binaryDecode_sliceMatchesWhole() throws SQLException {
    // decodeBinary must read straight off (src, srcOffset, srcLength) without copying the slice
    // into a fresh array first, so embed the wire at a non-zero offset inside noise padding.
    PGmultirange<Integer> expected = multirange(
        new PGRange<>(1, 5, true, false), new PGRange<>(10, 20, true, false));
    byte[] wire = MultirangeCodec.INSTANCE.encodeBinary(expected, INT4MULTIRANGE, CTX);
    byte[] embedded = new byte[5 + wire.length + 3];
    Arrays.fill(embedded, (byte) 0xEE);
    System.arraycopy(wire, 0, embedded, 5, wire.length);
    assertEquals(expected,
        MultirangeCodec.INSTANCE.decodeBinary(embedded, 5, wire.length, INT4MULTIRANGE, CTX));
  }

  @Test
  void unresolvableRangeFailsClearly() {
    // typtype 'm' must surface a clear error rather than silently mis-decoding when the range
    // type cannot be resolved.
    byte[] wire = {0, 0, 0, 0};
    PSQLException ex = assertThrows(PSQLException.class,
        () -> MultirangeCodec.INSTANCE.decodeBinary(wire, 0, wire.length, (TypeDescriptor) UNRESOLVED_MULTIRANGE, CTX));
    assertTrue(ex.getMessage().contains("broken_multirange"), ex.getMessage());
  }

  /**
   * A {@link CodecContext} that resolves the {@code int4multirange → int4range → int4} chain
   * directly and delegates the wire/policy surface to a connectionless test context.
   */
  private static final class StubContext implements CodecContext {
    private final CodecContext delegate = TestCodecContext.create();

    @Override
    public TypeDescriptor resolveType(int oid) {
      if (oid == INT4MULTIRANGE_OID) {
        return INT4MULTIRANGE;
      }
      if (oid == INT4RANGE_OID) {
        return INT4RANGE;
      }
      if (oid == UNRESOLVED_MULTIRANGE_OID) {
        return UNRESOLVED_MULTIRANGE;
      }
      return INT4;
    }

    @Override
    public Codec resolveCodec(int oid) {
      if (oid == INT4MULTIRANGE_OID || oid == UNRESOLVED_MULTIRANGE_OID) {
        return MultirangeCodec.INSTANCE;
      }
      if (oid == INT4RANGE_OID) {
        return RangeCodec.INSTANCE;
      }
      return Int4Codec.INSTANCE;
    }

    @Override
    public CodecContext withoutJavaTimePreferences() {
      return this;
    }

    @Override
    public Charset getCharset() {
      return delegate.getCharset();
    }

    @Override
    public boolean usesDoubleDateTime() {
      return delegate.usesDoubleDateTime();
    }

    @Override
    public TimeZone getClientTimeZone() {
      return delegate.getClientTimeZone();
    }

    @Override
    public TimeZone getDefaultTimeZone() {
      return delegate.getDefaultTimeZone();
    }

    @Override
    public @Nullable Calendar getCalendar() {
      return delegate.getCalendar();
    }

    @Override
    public boolean prefersJavaTimeForDate() {
      return delegate.prefersJavaTimeForDate();
    }

    @Override
    public boolean prefersJavaTimeForTime() {
      return delegate.prefersJavaTimeForTime();
    }

    @Override
    public boolean prefersJavaTimeForTimetz() {
      return delegate.prefersJavaTimeForTimetz();
    }

    @Override
    public boolean prefersJavaTimeForTimestamp() {
      return delegate.prefersJavaTimeForTimestamp();
    }

    @Override
    public boolean prefersJavaTimeForTimestamptz() {
      return delegate.prefersJavaTimeForTimestamptz();
    }

    @Override
    public boolean getConvertBooleanToNumeric() {
      return delegate.getConvertBooleanToNumeric();
    }

    @Override
    public Map<String, Class<?>> getTypeMap() {
      return delegate.getTypeMap();
    }

    @Override
    public @Nullable Class<?> getMappedClass(String typeName) {
      return delegate.getMappedClass(typeName);
    }
  }
}
