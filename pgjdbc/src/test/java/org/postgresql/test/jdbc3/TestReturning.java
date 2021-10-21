/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class TestReturning extends BaseTest4 {

  public enum ColumnsReturned {
    Id("Id"),
    id("id"),
    ID("*"),
    QUOTED("\"Id\""),
    NO();

    final String[] columns;
    ColumnsReturned(String... columns) {
      this.columns =  columns;
    }

    public int columnsReturned() {
      if (columns.length == 1 && columns[0].charAt(0) == '*') {
        return 100500; // does not matter much, the meaning is "every possible column"
      }
      return columns.length;
    }

    public String[] getColumnNames() {
      if (columnsReturned() == 0) {
        return new String[]{};
      }

      return columns;
    }
  }

  static String []returningOptions = {"true", "false"};

  @Parameterized.Parameters(name = "returningInQuery = {0}, quoteReturning = {1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (ColumnsReturned columsReturned : ColumnsReturned.values()) {
      for (String q : returningOptions) {
        ids.add(new Object[]{columsReturned, q});
      }
    }
    return ids;
  }

  private final ColumnsReturned columnsReturned;
  private final String quoteReturning;

  public TestReturning(ColumnsReturned columnsReturned, String quoteReturning) throws Exception {
    this.columnsReturned = columnsReturned;
    this.quoteReturning = quoteReturning;
  }

  protected void updateProperties(Properties props) {
    PGProperty.QUOTE_RETURNING_IDENTIFIERS.set(props, quoteReturning);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "genkeys", "\"Id\" serial, b varchar(5), c int");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "genkeys");
    super.tearDown();
  }

  private void testGeneratedKeys(Connection conn, String sql, String[] columnNames,  boolean exceptionExpected) throws SQLException {

    try (PreparedStatement stmt = conn.prepareStatement(sql, columnNames)) {
      stmt.execute();
      ResultSet rs = stmt.getGeneratedKeys();
      assertNotNull(rs);
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    } catch (SQLException e) {
      if ( !exceptionExpected ) {
        fail("error getting column names: " + e.getMessage());
      }
    }
  }

  @Test
  public void testMixedCase() throws SQLException {

    String insertSql = "INSERT INTO genkeys (b,c) VALUES ('hello', 1)";

    testGeneratedKeys(con, insertSql, new String[] {"Id"},  quoteReturning.equals("false") );
    testGeneratedKeys(con, insertSql, new String[] {"id"},  true);
    testGeneratedKeys(con, insertSql, new String[] {"ID"},  true);
    testGeneratedKeys(con, insertSql, new String[] {"\"Id\""}, quoteReturning.equals("true"));
    testGeneratedKeys(con, insertSql, new String[] {"bad"},  true);
  }
}
