/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

class BitCodecTest {

  private BitCodec codec;
  private PgType bitType;

  @BeforeEach
  void setUp() {
    codec = BitCodec.INSTANCE;
    bitType = new PgType(
        new ObjectName("pg_catalog", "bit"),
        "bit",
        Oid.BIT,
        'b', 'V', -1, 0, 0, 0
    );
  }

  @Test
  void decodeBinary_valid_roundTrips() throws SQLException {
    // A well-formed value (bit count + packed bits) decodes to its bit string.
    byte[] encoded = codec.encodeBinary("0101", bitType, null);
    PGobject decoded = (PGobject) codec.decodeBinary(encoded, 0, encoded.length, bitType, null);
    assertEquals("0101", decoded.getValue());
  }

  @Test
  void decodeBinary_emptyBitString_roundTrips() throws SQLException {
    // Zero bits: header only, no packed byte.
    byte[] encoded = codec.encodeBinary("", bitType, null);
    PGobject decoded = (PGobject) codec.decodeBinary(encoded, 0, encoded.length, bitType, null);
    assertEquals("", decoded.getValue());
  }

  // ==================== malformed binary wire (F3b) ====================

  // The binary form is a 4-byte bit count followed by ceil(nbits/8) packed bytes. A count read from
  // corrupt or hostile wire that does not match the bytes present must refuse with a clean
  // PSQLException, rather than drive an OutOfMemoryError on the StringBuilder allocation or an
  // ArrayIndexOutOfBoundsException while unpacking.

  @Test
  void decodeBinary_hugeBitCount_refusesCleanly() {
    // Header claims Integer.MAX_VALUE bits but carries no body: without the guard this both
    // over-allocates the StringBuilder and walks past the buffer.
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, Integer.MAX_VALUE);
    assertRefused(data);
  }

  @Test
  void decodeBinary_bitCountBeyondBody_refusesCleanly() {
    // Header claims 64 bits (8 packed bytes) but only 1 packed byte follows.
    byte[] data = new byte[5];
    ByteConverter.int4(data, 0, 64);
    assertRefused(data);
  }

  @Test
  void decodeBinary_negativeBitCount_refusesCleanly() {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, -1);
    assertRefused(data);
  }

  @Test
  void decodeBinary_shorterThanHeader_refusesCleanly() {
    // Fewer than the 4 header bytes.
    assertRefused(new byte[]{0, 0, 0});
  }

  @Test
  void decodeBinary_trailingGarbage_refusesCleanly() {
    // Header claims 4 bits (1 packed byte) but two packed bytes follow, so the length does not match.
    byte[] data = new byte[6];
    ByteConverter.int4(data, 0, 4);
    assertRefused(data);
  }

  /**
   * Asserts both binary decode entry points refuse {@code data} with a {@link PSQLState#DATA_ERROR}
   * {@link PSQLException} and never leak an unchecked exception.
   */
  private void assertRefused(byte[] data) {
    assertPathRefused("decodeBinary", () -> codec.decodeBinary(data, 0, data.length, (TypeDescriptor) bitType, (CodecContext) null));
    assertPathRefused("decodeAsString", () -> codec.decodeAsString(data, 0, data.length, (TypeDescriptor) bitType, (CodecContext) null));
  }

  private static void assertPathRefused(String path,
      org.junit.jupiter.api.function.Executable decode) {
    PSQLException e = assertThrows(PSQLException.class, decode,
        () -> "bit binary " + path + " should refuse malformed wire");
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState(),
        () -> "SQLState for bit binary " + path);
  }
}
