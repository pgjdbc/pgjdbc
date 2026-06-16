/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Verifies that every reference-returning {@link SQLInput} reader surfaces a SQL
 * NULL attribute as {@code null} (and sets {@link SQLInput#wasNull()}), rather
 * than substituting an empty value. The composite is read back through a
 * {@link SQLData} whose {@code readSQL} calls one reader per all-NULL attribute.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
public class SQLInputNullAttributesTest extends BaseTest4 {

  SQLInputNullAttributesTest(BinaryMode binaryMode) {
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
      TestUtil.createCompositeType(conn, "sqlinput_nulls",
          "v_string text,"
              + "v_ascii text,"
              + "v_char text,"
              + "v_bytes bytea,"
              + "v_binary bytea,"
              + "v_numeric numeric,"
              + "v_date date,"
              + "v_time time,"
              + "v_timestamp timestamp,"
              + "v_xml text,"
              + "v_object text,"
              + "v_array int4[]");
      TestUtil.createTable(conn, "sqlinput_null_rows", "id int primary key, payload sqlinput_nulls");
    }
  }

  @AfterAll
  static void tearDownSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB()) {
      TestUtil.dropTable(conn, "sqlinput_null_rows");
      TestUtil.dropType(conn, "sqlinput_nulls");
    }
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("TRUNCATE TABLE sqlinput_null_rows");
    }
  }

  @Test
  void everyReaderReturnsNullForSqlNull() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      // ROW() with all NULLs, cast to the composite type so the column is a
      // fully-NULL struct (not a NULL struct).
      stmt.execute("INSERT INTO sqlinput_null_rows (id, payload) VALUES (1,"
          + " ROW(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
          + "::sqlinput_nulls)");
    }

    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("sqlinput_nulls", AllNulls.class);

    try (PreparedStatement select = con.prepareStatement(
        "SELECT payload FROM sqlinput_null_rows WHERE id = 1");
         ResultSet rs = select.executeQuery()) {
      assertTrue(rs.next());
      AllNulls actual = (AllNulls) rs.getObject(1, typeMap);
      assertNotNull(actual, "the composite itself is non-null");
      assertEquals(12, actual.readerCount, "every attribute should have been read");
    }
  }

  /**
   * Reads each attribute through a different reference-returning reader and
   * asserts the result is {@code null} with {@code wasNull()} set.
   */
  public static final class AllNulls implements SQLData {
    int readerCount;

    public AllNulls() {
    }

    @Override
    public String getSQLTypeName() {
      return "sqlinput_nulls";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      assertNullAttribute("readString", stream, stream.readString());
      assertNullAttribute("readAsciiStream", stream, stream.readAsciiStream());
      assertNullAttribute("readCharacterStream", stream, stream.readCharacterStream());
      assertNullAttribute("readBytes", stream, stream.readBytes());
      assertNullAttribute("readBinaryStream", stream, stream.readBinaryStream());
      assertNullAttribute("readBigDecimal", stream, stream.readBigDecimal());
      assertNullAttribute("readDate", stream, stream.readDate());
      assertNullAttribute("readTime", stream, stream.readTime());
      assertNullAttribute("readTimestamp", stream, stream.readTimestamp());
      assertNullAttribute("readSQLXML", stream, stream.readSQLXML());
      assertNullAttribute("readObject", stream, stream.readObject());
      assertNullAttribute("readArray", stream, stream.readArray());
    }

    private void assertNullAttribute(String reader, SQLInput stream, Object value)
        throws SQLException {
      assertNull(value, reader + " must return null for a SQL NULL attribute");
      assertTrue(stream.wasNull(), reader + " must set wasNull() for a SQL NULL attribute");
      readerCount++;
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      throw new UnsupportedOperationException("read-only fixture");
    }
  }
}
