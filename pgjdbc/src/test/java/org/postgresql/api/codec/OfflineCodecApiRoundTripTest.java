/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.jdbc.OfflineCodecs;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

/**
 * Pins the canonical offline usage down: a caller reaches into {@code org.postgresql.jdbc} once for
 * the entry point {@link OfflineCodecs}, then encodes and decodes through
 * {@code org.postgresql.api.codec} types alone.
 *
 * <p>Every other declared type lives in the public {@code api.codec} package:
 * {@link CodecContextBuilder}, {@link CodecContext}, {@link CodecLookup}, {@link Codecs},
 * {@link TypeDescriptor}, {@link RawValue}, {@link Format}. If the offline round-trip ever needs
 * another driver-internal type, this test stops compiling against the api surface, which is the
 * regression it guards.</p>
 */
class OfflineCodecApiRoundTripTest {

  /** {@code pg_catalog.int4}. Hard-coded to keep the test off {@code org.postgresql.core.Oid}. */
  private static final int INT4_OID = 23;

  @Test
  void roundTripsInt4InBothFormats() throws SQLException {
    CodecContextBuilder builder = OfflineCodecs.builder();
    CodecContext ctx = builder.build();
    TypeDescriptor int4 = ctx.resolveType(INT4_OID);

    for (Format format : Format.values()) {
      RawValue encoded = Codecs.encode(42, int4, ctx, format);
      Integer decoded = Codecs.decode(encoded, int4, ctx, Integer.class);
      assertEquals(Integer.valueOf(42), decoded, "int4 round-trip in " + format);
    }
  }

  @Test
  void sharesADefaultRegistryAcrossContexts() throws SQLException {
    CodecLookup registry = OfflineCodecs.defaultRegistry();
    CodecContext ctx = OfflineCodecs.builder().registry(registry).build();
    TypeDescriptor int4 = ctx.resolveType(INT4_OID);

    assertNotNull(registry.getByOid(INT4_OID, int4), "int4 resolves to a codec");

    RawValue encoded = Codecs.encode(42, int4, ctx, Format.BINARY);
    assertEquals(Integer.valueOf(42), Codecs.decode(encoded, int4, ctx, Integer.class));
  }

  @Test
  void rejectsForeignRegistryWithClearError() {
    // A CodecLookup the driver did not build cannot drive resolution, so the builder rejects it with a
    // message that points back at the factory rather than leaking a raw ClassCastException.
    CodecLookup foreign = new UnsupportedCodecLookup();

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> OfflineCodecs.builder().registry(foreign));
    assertTrue(ex.getMessage().contains("OfflineCodecs.defaultRegistry()"),
        "message points at the factory: " + ex.getMessage());
  }

  /** A {@link CodecLookup} not produced by the driver; every call is unreachable in these tests. */
  private static final class UnsupportedCodecLookup implements CodecLookup {
    @Override
    public Codec getByName(String typeName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Codec getByOid(int oid, TypeDescriptor type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Codec getByClass(Class<?> javaClass) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Codec findCodecFor(Class<?> javaClass) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BinaryCodec getBinaryCodec(int oid, TypeDescriptor type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TextCodec getTextCodec(int oid, TypeDescriptor type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BinaryCodec getBinaryCodec(int oid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TextCodec getTextCodec(int oid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean canDecodeBinary(int oid, TypeDescriptor type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean canDecodeText(int oid, TypeDescriptor type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<Integer, Codec> builtinCodecsByOid() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasCodecForName(String typeName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasCodecForClass(Class<?> javaClass) {
      throw new UnsupportedOperationException();
    }
  }
}
