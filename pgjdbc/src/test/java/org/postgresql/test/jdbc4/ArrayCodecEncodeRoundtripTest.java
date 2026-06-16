/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

/**
 * End-to-end round-trips for reference-array parameters whose element type has no primitive fast
 * leaf (String[], UUID[], BigDecimal[], the temporal types). These now encode through the element
 * codec via the shared array walker rather than the legacy {@code ArrayEncoding} native encoders;
 * running under {@code binaryMode=FORCE} exercises the binary array wire format, which the server
 * must accept for the round-trip to succeed.
 */
@ParameterizedClass
@MethodSource("data")
public class ArrayCodecEncodeRoundtripTest extends BaseTest4 {

  private Connection conn;

  public ArrayCodecEncodeRoundtripTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    conn = con;
  }

  private void roundTrip(String elementType, Object[] values) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::" + elementType + "[]")) {
      ps.setArray(1, conn.createArrayOf(elementType, values));
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        Object[] out = (Object[]) rs.getArray(1).getArray();
        assertArrayEquals(values, out);
      }
    }
  }

  @Test
  public void stringArray() throws SQLException {
    // Covers delimiter, embedded quote/backslash, empty string and the literal "NULL" token.
    roundTrip("text", new String[]{"a", "b,c", "d\"e\\f", "", "NULL"});
  }

  @Test
  public void uuidArray() throws SQLException {
    roundTrip("uuid", new UUID[]{
        UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
        UUID.fromString("00000000-0000-0000-0000-000000000001")});
  }

  @Test
  public void numericArray() throws SQLException {
    roundTrip("numeric", new BigDecimal[]{new BigDecimal("1.50"), new BigDecimal("-99.99")});
  }

  @Test
  public void timestampArray() throws SQLException {
    roundTrip("timestamp", new Timestamp[]{
        Timestamp.valueOf("2024-01-01 12:34:56"),
        Timestamp.valueOf("1999-12-31 23:59:59")});
  }

  @Test
  public void dateArray() throws SQLException {
    roundTrip("date", new Date[]{Date.valueOf("2024-01-01"), Date.valueOf("1999-12-31")});
  }

  @Test
  public void timeArray() throws SQLException {
    roundTrip("time", new Time[]{Time.valueOf("12:34:56"), Time.valueOf("03:30:25")});
  }

  /**
   * Binds a primitive Java array to an element type with no fast leaf ({@code numeric}). These used
   * to reach the legacy {@code ArrayEncoding} fallback; they now box each element and encode through
   * {@code NumericCodec} via the generic array leaf.
   */
  private void primitiveToNumeric(Object primitiveArray, BigDecimal[] expected) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::numeric[]")) {
      ps.setArray(1, conn.unwrap(PgConnection.class).createArrayOf("numeric", primitiveArray));
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        Object[] out = (Object[]) rs.getArray(1).getArray();
        assertEquals(expected.length, out.length);
        for (int i = 0; i < expected.length; i++) {
          BigDecimal actual = (BigDecimal) out[i];
          assertEquals(0, expected[i].compareTo(actual), "element " + i + " = " + actual);
        }
      }
    }
  }

  @Test
  public void intArrayToNumeric() throws SQLException {
    primitiveToNumeric(new int[]{1, 2, 3},
        new BigDecimal[]{new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3")});
  }

  @Test
  public void longArrayToNumeric() throws SQLException {
    primitiveToNumeric(new long[]{10L, -20L},
        new BigDecimal[]{new BigDecimal("10"), new BigDecimal("-20")});
  }

  @Test
  public void doubleArrayToNumeric() throws SQLException {
    primitiveToNumeric(new double[]{1.5, -2.25},
        new BigDecimal[]{new BigDecimal("1.5"), new BigDecimal("-2.25")});
  }

  /**
   * A {@code boolean[]} bound to {@code bit[]} must encode each element as {@code "1"}/{@code "0"}
   * (via {@code BitCodec}), not {@code "true"}/{@code "false"}. {@code bit[]} decodes to
   * {@code PGobject[]} (the bit string of each element).
   */
  @Test
  public void booleanArrayToBit() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::bit[]")) {
      ps.setArray(1, conn.unwrap(PgConnection.class).createArrayOf("bit", new boolean[]{true, false, true}));
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        Object[] out = (Object[]) rs.getArray(1).getArray();
        assertArrayEquals(new String[]{"1", "0", "1"},
            new String[]{((PGobject) out[0]).getValue(), ((PGobject) out[1]).getValue(),
                ((PGobject) out[2]).getValue()});
      }
    }
  }
}
