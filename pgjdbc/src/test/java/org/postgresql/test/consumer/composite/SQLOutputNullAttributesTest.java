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
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Verifies that every nullable {@link SQLOutput} writer accepts {@code null} and
 * serialises it as a SQL NULL attribute. The composite is built through a
 * {@link SQLData} whose {@code writeSQL} calls one writer per attribute with
 * {@code null}, then read back as a {@link Struct} where every attribute must be
 * {@code null}.
 */
@Execution(ExecutionMode.SAME_THREAD)
@ParameterizedClass
@MethodSource("data")
public class SQLOutputNullAttributesTest extends BaseTest4 {

  private static final int ATTRIBUTE_COUNT = 16;

  SQLOutputNullAttributesTest(BinaryMode binaryMode) {
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
      TestUtil.createCompositeType(conn, "sqloutput_null_leaf", "label text, weight int");
      TestUtil.createCompositeType(conn, "sqloutput_nulls",
          "v_string text,"
              + "v_nstring text,"
              + "v_ascii text,"
              + "v_char text,"
              + "v_bytes bytea,"
              + "v_binary bytea,"
              + "v_numeric numeric,"
              + "v_date date,"
              + "v_time time,"
              + "v_timestamp timestamp,"
              + "v_xml text,"
              + "v_url text,"
              + "v_array int4[],"
              + "v_struct sqloutput_null_leaf,"
              + "v_sqldata sqloutput_null_leaf,"
              + "v_object text");
      TestUtil.createTable(conn, "sqloutput_null_rows", "id int primary key, payload sqloutput_nulls");
    }
  }

  @AfterAll
  static void tearDownSchema() throws SQLException {
    try (Connection conn = TestUtil.openDB()) {
      TestUtil.dropTable(conn, "sqloutput_null_rows");
      TestUtil.dropType(conn, "sqloutput_nulls");
      TestUtil.dropType(conn, "sqloutput_null_leaf");
    }
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    try (Statement stmt = con.createStatement()) {
      stmt.execute("TRUNCATE TABLE sqloutput_null_rows");
    }
  }

  @Test
  void everyWriterAcceptsNull() throws SQLException {
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO sqloutput_null_rows (id, payload) VALUES (1, ?)")) {
      insert.setObject(1, new AllNulls());
      assertEquals(1, insert.executeUpdate());
    }

    try (PreparedStatement select = con.prepareStatement(
        "SELECT payload FROM sqloutput_null_rows WHERE id = 1");
         ResultSet rs = select.executeQuery()) {
      assertTrue(rs.next());
      Struct struct = rs.getObject(1, Struct.class);
      assertNotNull(struct, "the composite itself is non-null");
      Object[] attrs = struct.getAttributes();
      assertEquals(ATTRIBUTE_COUNT, attrs.length);
      for (int i = 0; i < attrs.length; i++) {
        assertNull(attrs[i], "attribute " + i + " must be SQL NULL");
      }
    }
  }

  /**
   * Writes {@code null} through every nullable writer on {@link SQLOutput}.
   * Read-back is performed via {@link Struct}, so {@code readSQL} is unused.
   */
  public static final class AllNulls implements SQLData {
    public AllNulls() {
    }

    @Override
    public String getSQLTypeName() {
      return "sqloutput_nulls";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      throw new UnsupportedOperationException("write-only fixture");
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(null);
      stream.writeNString(null);
      stream.writeAsciiStream(null);
      stream.writeCharacterStream(null);
      stream.writeBytes(null);
      stream.writeBinaryStream(null);
      stream.writeBigDecimal(null);
      stream.writeDate(null);
      stream.writeTime(null);
      stream.writeTimestamp(null);
      stream.writeSQLXML(null);
      stream.writeURL(null);
      stream.writeArray(null);
      stream.writeStruct(null);
      stream.writeObject((SQLData) null);
      stream.writeObject((Object) null, JDBCType.VARCHAR);
    }
  }
}
