/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * Pins that {@link DomainCodec} forwards the value {@code (offset, length)} to its base codec, not
 * {@code (0, data.length)}. A domain shares its base type's wire form and does nothing but delegate, so a
 * value that sits at a non-zero offset in a larger buffer -- a composite field, an array element, a
 * {@code SQLInput} slice -- must reach the base codec at that offset. Dropping the offset made the base
 * read the wrong bytes (or fail the base's length check), a defect that only bites a domain value decoded
 * from a slice, never one decoded from its own whole buffer.
 *
 * <p>The offline int4 domain is built inline (a domain has no pinned OID in the driver) so it routes to
 * {@code DomainCodec} by {@code typtype='d'} and forwards to the built-in int4 base codec without a
 * connection.
 */
class DomainCodecOffsetTest {

  private static final PgType INT4_DOMAIN =
      new PgType(new ObjectName("public", "int4_domain"), "public.int4_domain",
          90_301, 'd', 'N', -1, 0, 0, Oid.INT4);

  @Test
  void decodeBinaryAsForwardsTheValueOffset() throws SQLException {
    CodecContext ctx = OfflineCodecs.builder().type(INT4_DOMAIN).build();

    byte[] wire = new byte[4];
    ByteConverter.int4(wire, 0, 42);
    // The same 4-byte int4 wire placed after a 3-byte canary, decoded from offset 3. A codec that reads
    // from index 0 sees the 0xFF canary; one that keeps the offset but forces length to data.length reads 7
    // bytes and trips int4's length check. Only forwarding (offset, length) verbatim reads the value.
    byte[] shifted = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, wire[0], wire[1], wire[2], wire[3]};

    Integer atZero = DomainCodec.INSTANCE.decodeBinaryAs(wire, 0, wire.length, INT4_DOMAIN, Integer.class, ctx);
    Integer atOffset =
        DomainCodec.INSTANCE.decodeBinaryAs(shifted, 3, wire.length, INT4_DOMAIN, Integer.class, ctx);

    assertEquals(Integer.valueOf(42), atZero, "int4 domain decodeBinaryAs from offset 0");
    assertEquals(atZero, atOffset,
        "DomainCodec.decodeBinaryAs must forward the value offset to the base codec, not reset it to 0");
  }
}
