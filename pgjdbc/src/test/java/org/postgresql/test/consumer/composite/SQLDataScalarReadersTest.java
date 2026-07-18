/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Reads a composite into a {@link SQLData} whose {@code readSQL} exercises the
 * full span of scalar {@link SQLInput} readers ({@code readInt}, {@code readLong},
 * {@code readShort}, {@code readString}, {@code readBoolean}, {@code readByte},
 * {@code readFloat}, {@code readDouble}, {@code readBigDecimal}, {@code readBytes},
 * {@code readDate}, {@code readTime}, {@code readTimestamp}) in a single row.
 *
 * <p>Two rows are covered: one with populated values, and one whose attributes are
 * all SQL NULL. The NULL row pins the primitive readers to their zero defaults
 * (with {@link SQLInput#wasNull()} set) and the reference readers to {@code null},
 * complementing {@link SQLInputNullAttributesTest}, which covers only the
 * reference-returning readers.</p>
 *
 * <p>Ported from the {@code SQLDataTest} fixtures contributed by Ken Southerland in
 * <a href="https://github.com/pgjdbc/pgjdbc/pull/3396">pgjdbc/pgjdbc#3396</a>.</p>
 */
@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
public class SQLDataScalarReadersTest extends BaseTest4 {

  SQLDataScalarReadersTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @BeforeAll
  static void setUpSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB()) {
      // Attribute order must match the readSQL() call order in Scalars.
      TestUtil.createCompositeType(conn, "sqldata_scalars",
          "c_int int4,"
              + "c_long int8,"
              + "c_short int2,"
              + "c_string text,"
              + "c_bool boolean,"
              + "c_byte int2,"
              + "c_float float4,"
              + "c_double float8,"
              + "c_bigdecimal numeric,"
              + "c_bytes bytea,"
              + "c_date date,"
              + "c_time time,"
              + "c_timestamp timestamp");
      TestUtil.createTable(conn, "sqldata_scalar_rows", "id int primary key, payload sqldata_scalars");
    }
  }

  @AfterAll
  static void tearDownSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB()) {
      TestUtil.dropTable(conn, "sqldata_scalar_rows");
      TestUtil.dropType(conn, "sqldata_scalars");
    }
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("TRUNCATE TABLE sqldata_scalar_rows");
    }
  }

  @Test
  void everyScalarReaderReturnsThePopulatedValue() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("INSERT INTO sqldata_scalar_rows (id, payload) VALUES (1, ROW("
          + "42,"
          + "4300000000,"
          + "44,"
          + "'hello',"
          + "true,"
          + "7,"
          + "42.5,"
          + "65.25,"
          + "78.94,"
          + "'\\x736f6d65',"
          + "DATE '2024-10-10',"
          + "TIME '14:12:35',"
          + "TIMESTAMP '2024-10-10 14:12:35'"
          + ")::sqldata_scalars)");
    }

    try (PreparedStatement select = con.prepareStatement(
        "SELECT payload FROM sqldata_scalar_rows WHERE id = 1");
         ResultSet rs = select.executeQuery()) {
      assertTrue(rs.next());
      Scalars actual = rs.getObject(1, Scalars.class);
      assertNotNull(actual);
      assertEquals(42, actual.intValue);
      assertEquals(4_300_000_000L, actual.longValue);
      assertEquals((short) 44, actual.shortValue);
      assertEquals("hello", actual.stringValue);
      assertTrue(actual.boolValue);
      assertEquals((byte) 7, actual.byteValue);
      assertEquals(42.5f, actual.floatValue);
      assertEquals(65.25d, actual.doubleValue);
      assertEquals(new BigDecimal("78.94"), actual.bigDecimalValue);
      assertArrayEquals("some".getBytes(US_ASCII), actual.bytesValue);
      assertEquals("2024-10-10", actual.dateValue.toString());
      assertEquals("14:12:35", actual.timeValue.toString());
      assertEquals("2024-10-10 14:12:35.0", actual.timestampValue.toString());
    }
  }

  @Test
  void allNullRowYieldsPrimitiveDefaultsAndNullReferences() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("INSERT INTO sqldata_scalar_rows (id, payload) VALUES (2, ROW("
          + "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL"
          + ")::sqldata_scalars)");
    }

    try (PreparedStatement select = con.prepareStatement(
        "SELECT payload FROM sqldata_scalar_rows WHERE id = 2");
         ResultSet rs = select.executeQuery()) {
      assertTrue(rs.next());
      Scalars actual = rs.getObject(1, Scalars.class);
      assertNotNull(actual, "the composite itself is non-null");
      assertEquals(0, actual.intValue);
      assertEquals(0L, actual.longValue);
      assertEquals((short) 0, actual.shortValue);
      assertNull(actual.stringValue);
      assertFalse(actual.boolValue);
      assertEquals((byte) 0, actual.byteValue);
      assertEquals(0.0f, actual.floatValue);
      assertEquals(0.0d, actual.doubleValue);
      assertNull(actual.bigDecimalValue);
      assertNull(actual.bytesValue);
      assertNull(actual.dateValue);
      assertNull(actual.timeValue);
      assertNull(actual.timestampValue);
    }
  }

  /**
   * Reads every attribute through the matching scalar {@link SQLInput} reader.
   * The write path is not exercised, so {@code writeSQL} is intentionally
   * unsupported.
   */
  public static final class Scalars implements SQLData {
    int intValue;
    long longValue;
    short shortValue;
    String stringValue;
    boolean boolValue;
    byte byteValue;
    float floatValue;
    double doubleValue;
    BigDecimal bigDecimalValue;
    byte[] bytesValue;
    Date dateValue;
    Time timeValue;
    Timestamp timestampValue;

    public Scalars() {
    }

    @Override
    public String getSQLTypeName() {
      return "sqldata_scalars";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      intValue = stream.readInt();
      longValue = stream.readLong();
      shortValue = stream.readShort();
      stringValue = stream.readString();
      boolValue = stream.readBoolean();
      byteValue = stream.readByte();
      floatValue = stream.readFloat();
      doubleValue = stream.readDouble();
      bigDecimalValue = stream.readBigDecimal();
      bytesValue = stream.readBytes();
      dateValue = stream.readDate();
      timeValue = stream.readTime();
      timestampValue = stream.readTimestamp();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      throw new UnsupportedOperationException("read-only fixture");
    }
  }
}
