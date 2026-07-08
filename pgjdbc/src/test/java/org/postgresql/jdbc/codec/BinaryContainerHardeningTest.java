/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.util.List;

/**
 * Robustness guards for the binary container decoders — multi-dimensional arrays
 * ({@link MultiDimArrayBinary}) and composites ({@link CompositeCodec}). Counts and lengths read
 * straight from the wire (dimension count, dimension length, element length, field count, field
 * length) must be bounded against the bytes actually present before any allocation or indexed read,
 * so corrupt or hostile wire refuses with a clean {@link PSQLException} rather than an
 * {@link OutOfMemoryError} or an {@link ArrayIndexOutOfBoundsException}. These decoders have no
 * bounded fuzzer yet (that arrives with roadmap phase U4, which cannot run until this bound exists),
 * so the guard is pinned here.
 */
class BinaryContainerHardeningTest {

  // ------------------------------ array ------------------------------

  /** Builds a valid 1-D {@code int4[]} binary body for the given element values. */
  private static byte[] validInt4Array(int... values) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] w = new byte[4];
    write(out, w, 1);            // dimensions
    write(out, w, 0);            // hasNulls
    write(out, w, Oid.INT4);     // element OID
    write(out, w, values.length); // dim 0 length
    write(out, w, 1);            // dim 0 lower bound
    for (int v : values) {
      write(out, w, 4);          // element length
      write(out, w, v);          // element value
    }
    return out.toByteArray();
  }

  private static void write(ByteArrayOutputStream out, byte[] scratch, int value) {
    ByteConverter.int4(scratch, 0, value);
    out.write(scratch, 0, 4);
  }

  private static Object decodeInt4Array(byte[] data) throws SQLException {
    return MultiDimArrayBinary.decode(data, 0, data.length, Integer.class, Int4ArrayLeafCodec.INSTANCE, null);
  }

  @Test
  void array_valid_decodes() throws SQLException {
    Object decoded = decodeInt4Array(validInt4Array(7, 8, 9));
    assertArrayEquals(new Integer[]{7, 8, 9}, (Integer[]) decoded);
  }

  @Test
  void array_validEmpty_decodes() throws SQLException {
    // Zero-dimension form: header only.
    byte[] data = new byte[12];
    ByteConverter.int4(data, 8, Oid.INT4);
    Object decoded = decodeInt4Array(data);
    assertEquals(0, ((Integer[]) decoded).length);
  }

  @Test
  void array_hugeDimensionCount_refusesCleanly() {
    // dimensions = Integer.MAX_VALUE would over-allocate and exceed MAXDIM.
    byte[] data = new byte[12];
    ByteConverter.int4(data, 0, Integer.MAX_VALUE);
    ByteConverter.int4(data, 8, Oid.INT4);
    assertArrayRefused(data);
  }

  @Test
  void array_dimensionCountAboveMaxdim_refusesCleanly() {
    // Seven dimensions exceeds the server's MAXDIM of six.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] w = new byte[4];
    write(out, w, 7);          // dimensions
    write(out, w, 0);          // hasNulls
    write(out, w, Oid.INT4);   // element OID
    for (int d = 0; d < 7; d++) {
      write(out, w, 1);        // dim length
      write(out, w, 1);        // lower bound
    }
    assertArrayRefused(out.toByteArray());
  }

  @Test
  void array_negativeDimensionCount_refusesCleanly() {
    byte[] data = new byte[12];
    ByteConverter.int4(data, 0, -1);
    ByteConverter.int4(data, 8, Oid.INT4);
    assertArrayRefused(data);
  }

  @Test
  void array_hugeDimensionLength_refusesCleanly() {
    // A single dimension of Integer.MAX_VALUE elements but an empty body: the element count exceeds
    // the bytes that remain, so the Array.newInstance allocation is refused before it is attempted.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] w = new byte[4];
    write(out, w, 1);                 // dimensions
    write(out, w, 0);                 // hasNulls
    write(out, w, Oid.INT4);          // element OID
    write(out, w, Integer.MAX_VALUE); // dim 0 length
    write(out, w, 1);                 // dim 0 lower bound
    assertArrayRefused(out.toByteArray());
  }

  @Test
  void array_zeroInnerDimensionHugeOuter_refusesCleanly() {
    // A zero inner dimension collapses the element product to 0, so a product-only bound passes, but
    // Array.newInstance still allocates the huge outer spine (dimLengths[0] references) before the zero
    // shrinks anything -- an OutOfMemoryError. The partial-product bound refuses it.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] w = new byte[4];
    write(out, w, 2);          // dimensions
    write(out, w, 0);          // hasNulls
    write(out, w, Oid.INT4);   // element OID
    write(out, w, 84_215_045); // dim 0 length (huge outer spine)
    write(out, w, 1);          // dim 0 lower bound
    write(out, w, 0);          // dim 1 length (zero collapses the product)
    write(out, w, 1);          // dim 1 lower bound
    assertArrayRefused(out.toByteArray());
  }

  @Test
  void array_negativeDimensionLength_refusesCleanly() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] w = new byte[4];
    write(out, w, 1);        // dimensions
    write(out, w, 0);        // hasNulls
    write(out, w, Oid.INT4); // element OID
    write(out, w, -1);       // dim 0 length
    write(out, w, 1);        // dim 0 lower bound
    assertArrayRefused(out.toByteArray());
  }

  @Test
  void array_truncatedHeader_refusesCleanly() {
    // Fewer bytes than the fixed 12-byte header.
    assertArrayRefused(new byte[]{0, 0, 0, 1, 0, 0});
  }

  @Test
  void array_truncatedElementBody_refusesCleanly() {
    // Header promises two elements but the body stops after the first.
    byte[] full = validInt4Array(7, 8);
    byte[] truncated = new byte[full.length - 4];
    System.arraycopy(full, 0, truncated, 0, truncated.length);
    assertArrayRefused(truncated);
  }

  private static void assertArrayRefused(byte[] data) {
    PSQLException e = assertThrows(PSQLException.class, () -> decodeInt4Array(data),
        "malformed binary array should refuse cleanly");
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState(),
        "SQLState for malformed binary array");
  }

  // ---------------------------- composite ----------------------------

  /** Builds a valid composite binary body with one int4 field of the given value. */
  private static byte[] validSingleInt4Composite(int value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] w = new byte[4];
    write(out, w, 1);        // field count
    write(out, w, Oid.INT4); // field type OID
    write(out, w, 4);        // field length
    write(out, w, value);    // field value
    return out.toByteArray();
  }

  @Test
  void composite_valid_decodes() throws SQLException {
    List<CompositeCodec.DecodedField> fields =
        CompositeCodec.decodeBinaryFields(validSingleInt4Composite(42));
    assertEquals(1, fields.size());
    assertEquals(Oid.INT4, fields.get(0).getTypeOid());
    assertArrayEquals(new byte[]{0, 0, 0, 42}, fields.get(0).getData());
  }

  @Test
  void composite_hugeFieldCount_refusesCleanly() {
    // field count = Integer.MAX_VALUE with only the 4-byte count present: without the bound the
    // ArrayList allocation alone drives an OutOfMemoryError before the per-field check runs.
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, Integer.MAX_VALUE);
    assertCompositeRefused(data);
  }

  @Test
  void composite_negativeFieldCount_refusesCleanly() {
    byte[] data = new byte[4];
    ByteConverter.int4(data, 0, -1);
    assertCompositeRefused(data);
  }

  @Test
  void composite_tooShort_refusesCleanly() {
    // Fewer than the 4-byte field-count header.
    assertCompositeRefused(new byte[]{0, 0, 0});
  }

  @Test
  void composite_truncatedFieldBody_refusesCleanly() {
    // One field claiming length 4 but no value bytes follow.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] w = new byte[4];
    write(out, w, 1);        // field count
    write(out, w, Oid.INT4); // field type OID
    write(out, w, 4);        // field length, but no body
    assertCompositeRefused(out.toByteArray());
  }

  @Test
  void composite_negativeFieldLength_refusesCleanly() {
    // A field length other than -1 (SQL NULL) that is still negative.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] w = new byte[4];
    write(out, w, 1);        // field count
    write(out, w, Oid.INT4); // field type OID
    write(out, w, -2);       // invalid field length
    assertCompositeRefused(out.toByteArray());
  }

  private static void assertCompositeRefused(byte[] data) {
    PSQLException e = assertThrows(PSQLException.class,
        () -> CompositeCodec.decodeBinaryFields(data),
        "malformed binary composite should refuse cleanly");
    assertEquals(PSQLState.DATA_ERROR.getState(), e.getSQLState(),
        "SQLState for malformed binary composite");
  }
}
