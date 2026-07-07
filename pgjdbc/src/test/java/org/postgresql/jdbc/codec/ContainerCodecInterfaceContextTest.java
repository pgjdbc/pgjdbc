/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveDecoders;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGRange;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

/**
 * Pins the slice 2c unblock for third-party container codecs: a container codec resolves its child
 * type and codec through the {@link CodecContext} interface alone, with no downcast to the driver's
 * {@code PgCodecContext}. The context here is a standalone implementation, so a downcast in the
 * codec would fail with a {@link ClassCastException} and the test would fail.
 */
class ContainerCodecInterfaceContextTest {

  private static final PgType INT4 = new PgType(
      new ObjectName("pg_catalog", "int4"), "int4", Oid.INT4,
      'b', 'N', -1, 0, 0, 0);

  /**
   * A {@link CodecContext} that is deliberately not a {@code PgCodecContext}: child resolution is
   * answered directly (every child is {@code int4}) and the wire/policy surface delegates to a
   * connectionless test context.
   */
  private static final class InterfaceOnlyContext implements CodecContext {
    private final CodecContext delegate = TestCodecContext.create();

    @Override
    public TypeDescriptor resolveType(int oid) {
      return INT4;
    }

    @Override
    public Codec resolveCodec(int oid) {
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

  @Test
  void domainCodecResolvesBaseTypeAndCodecThroughInterface() throws SQLException {
    // CREATE DOMAIN positive_int AS integer: the codec must resolve the base type and its codec.
    PgType domain = new PgType(
        new ObjectName("public", "positive_int"), "positive_int", 99_999,
        'd', 'N', -1, 0, 0, Oid.INT4);
    Object value = DomainCodec.INSTANCE.decodeBinary(
        new byte[]{0, 0, 0, 42}, domain, new InterfaceOnlyContext());
    assertEquals(42, value);
  }

  @Test
  void domainCodecForwardsPrimitiveAccessorsToBaseType() throws SQLException {
    // A domain over int4 must decode straight to a primitive: it advertises the primitive-decoder
    // capabilities so a caller through PrimitiveDecoders takes the base int4 codec's no-box path
    // rather than boxing decodeBinary. The slice form must honour the offset off a larger buffer.
    PgType domain = new PgType(
        new ObjectName("public", "positive_int"), "positive_int", 99_999,
        'd', 'N', -1, 0, 0, Oid.INT4);
    CodecContext ctx = new InterfaceOnlyContext();
    assertInstanceOf(PrimitiveBinaryDecoder.class, DomainCodec.INSTANCE);
    assertInstanceOf(PrimitiveTextDecoder.class, DomainCodec.INSTANCE);
    assertEquals(42, PrimitiveDecoders.asInt(DomainCodec.INSTANCE, new byte[]{0, 0, 0, 42}, domain, ctx));
    assertEquals(42L, PrimitiveDecoders.asLong(DomainCodec.INSTANCE, new byte[]{0, 0, 0, 42}, domain, ctx));
    assertEquals(42.0, PrimitiveDecoders.asDouble(
        DomainCodec.INSTANCE, new byte[]{0, 0, 0, 42}, domain, ctx), 0.0);
    assertEquals(42, PrimitiveDecoders.asInt(
        DomainCodec.INSTANCE, new byte[]{9, 9, 0, 0, 0, 42}, 2, 4, domain, ctx));
    assertEquals(42, PrimitiveDecoders.asInt(DomainCodec.INSTANCE, "42", domain, ctx));
  }

  @Test
  void rangeCodecResolvesSubtypeAndCodecThroughInterface() throws SQLException {
    PgType int4range = new PgType(
        new ObjectName("pg_catalog", "int4range"), "int4range", 3904,
        'r', 'R', -1, 0, 0, 0).withRangeSubtype(Oid.INT4);
    // [1,10): lower inclusive, upper exclusive, both bounds finite.
    byte[] wire = new byte[1 + 4 + 4 + 4 + 4];
    wire[0] = 0x02;
    ByteConverter.int4(wire, 1, 4);
    ByteConverter.int4(wire, 5, 1);
    ByteConverter.int4(wire, 9, 4);
    ByteConverter.int4(wire, 13, 10);
    Object value = RangeCodec.INSTANCE.decodeBinary(wire, int4range, new InterfaceOnlyContext());
    PGRange<?> range = assertInstanceOf(PGRange.class, value);
    assertEquals(1, range.getLower());
    assertEquals(10, range.getUpper());
  }
}
