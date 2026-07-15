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

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
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

  /** {@code pg_catalog.numeric}. Hard-coded to keep the test off {@code org.postgresql.core.Oid}. */
  private static final int NUMERIC_OID = 1700;

  /** {@code numeric(2,-2)}: precision 2 in the high 16 bits, scale -2 sign-extended below, + VARHDRSZ. */
  private static final int NUMERIC_2_NEG2 = ((2 << 16) | (-2 & 0xffff)) + 4;

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
  void suppliesColumnTypmodThroughTheApiSurface() throws SQLException {
    // Offline there is no RowDescription, so a caller supplies a column/attribute modifier through the
    // descriptor. Both the convenience overload and the wither report it; the plain descriptor has none.
    CodecContext ctx = OfflineCodecs.builder().build();

    assertEquals(NUMERIC_2_NEG2, ctx.resolveType(NUMERIC_OID, NUMERIC_2_NEG2).getTypmod(),
        "resolveType(oid, typmod) reports the modifier");
    assertEquals(NUMERIC_2_NEG2, ctx.resolveType(NUMERIC_OID).withTypmod(NUMERIC_2_NEG2).getTypmod(),
        "withTypmod reports the modifier");
    assertEquals(-1, ctx.resolveType(NUMERIC_OID).getTypmod(), "no modifier by default");
  }

  @Test
  void withTypmodWrapsAForeignDescriptor() {
    // A descriptor the driver did not build gets the modifier through the default delegating wrapper,
    // so the offline lever works for third-party descriptors, not only the driver's own PgType.
    TypeDescriptor foreign = new MinimalDescriptor();
    TypeDescriptor stamped = foreign.withTypmod(42);

    assertEquals(42, stamped.getTypmod(), "wrapper reports the modifier");
    assertEquals(foreign.getOid(), stamped.getOid(), "wrapper delegates other properties");
    assertEquals(-1, foreign.getTypmod(), "the original descriptor is unchanged");
    assertEquals(7, stamped.withTypmod(7).getTypmod(), "restamping replaces rather than nests");
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

  /** A minimal {@link TypeDescriptor} not built by the driver, to exercise the default withTypmod wrapper. */
  private static final class MinimalDescriptor implements TypeDescriptor {
    @Override
    public int getOid() {
      return NUMERIC_OID;
    }

    @Override
    public ObjectName getTypeName() {
      return new ObjectName() {
        @Override
        public @Nullable String getNamespace() {
          return "pg_catalog";
        }

        @Override
        public String getName() {
          return "numeric";
        }
      };
    }

    @Override
    public String getFullName() {
      return "numeric";
    }

    @Override
    public int getTyptypmod() {
      return -1;
    }

    @Override
    public int getTypelem() {
      return 0;
    }

    @Override
    public int getArrayOid() {
      return 0;
    }

    @Override
    public int getTypbasetype() {
      return 0;
    }

    @Override
    public int getRangeSubtype() {
      return 0;
    }

    @Override
    public int getMultirangeRange() {
      return 0;
    }

    @Override
    public char getTyptype() {
      return 'b';
    }

    @Override
    public char getTypcategory() {
      return 'N';
    }

    @Override
    public char getDelimiter() {
      return ',';
    }

    @Override
    public @Nullable List<? extends PGField> getFields() {
      return null;
    }
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
    public BinaryCodec getBinaryCodec(int oid, TypeDescriptor type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TextCodec getTextCodec(int oid, TypeDescriptor type) {
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
  }
}
