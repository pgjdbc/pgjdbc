/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGConnection;
import org.postgresql.PGPreparedStatement;
import org.postgresql.PGProperty;
import org.postgresql.jdbc.PlaceholderStyle;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MixedParameterMultiStatementTest extends BaseTest5 {
  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    PGProperty.REWRITE_BATCHED_INSERTS.set(props, true);
    TestUtil.createTable(con, "testbatch", "pk SERIAL, col1 VARCHAR, col2 INTEGER");
  }

  @AfterEach
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testbatch");
    super.tearDown();
  }

  protected List<String> getResult() throws SQLException {
    List<String> result = new ArrayList<>();
    try (Statement statement = con.createStatement()) {
      statement.execute("SELECT * FROM testbatch ORDER BY pk");
      try (ResultSet rs = statement.getResultSet()) {
        while (rs.next()) {
          result.add(rs.getString("col1") + "," + rs.getString("col2"));
        }
      }
    }
    return result;
  }

  @Test
  void regularMultiStatement() throws SQLException {
    String sql = ""
        + "INSERT INTO testbatch( col1, col2 ) VALUES ($1, $2), ($1, $2);"
        + "INSERT INTO testbatch( col1, col2 ) VALUES (?, ?);"
        + "INSERT INTO testbatch( col1, col2 ) VALUES (:a, :b), (:a, :b), (:a, :b)";

    final PGPreparedStatement ps =
        con.unwrap(PGConnection.class)
            .prepareStatement(sql, PlaceholderStyle.ANY)
            .unwrap(PGPreparedStatement.class);

    ps.setString(1, "111");
    ps.setInt(2, 222);
    ps.setString(3, "333");
    ps.setInt(4, 444);
    ps.setString(5, "555");
    ps.setInt(6, 666);

    Assertions.assertEquals(""
            + "INSERT INTO testbatch( col1, col2 ) VALUES ('111', 222), ('111', 222);"
            + "INSERT INTO testbatch( col1, col2 ) VALUES ('333', 444);"
            + "INSERT INTO testbatch( col1, col2 ) VALUES ('555', 666), ('555', 666), ('555', 666)",
        ps.toString());

    Assertions.assertEquals(2, ps.executeUpdate());
    Assertions.assertFalse(ps.getMoreResults());
    Assertions.assertEquals(1, ps.getUpdateCount());
    Assertions.assertFalse(ps.getMoreResults());
    Assertions.assertEquals(3, ps.getUpdateCount());

    Assertions.assertEquals("[111,222, 111,222, 333,444, 555,666, 555,666, 555,666]",
        getResult().toString());
  }

  @Test
  void batchMultiStatement() throws SQLException {
    String sql = ""
        + "INSERT INTO testbatch( col1, col2 ) VALUES ($1, $2), ($1, $2);"
        + "INSERT INTO testbatch( col1, col2 ) VALUES (?, ?);"
        + "INSERT INTO testbatch( col1, col2 ) VALUES (:a, :b), (:a, :b), (:a, :b)";

    final PGPreparedStatement ps =
        con.unwrap(PGConnection.class)
            .prepareStatement(sql, PlaceholderStyle.ANY)
            .unwrap(PGPreparedStatement.class);

    ps.setString(1, "11");
    ps.setInt(2, 22);
    ps.setString(3, "33");
    ps.setInt(4, 44);
    ps.setString(5, "55");
    ps.setInt(6, 66);

    Assertions.assertEquals(""
            + "INSERT INTO testbatch( col1, col2 ) VALUES (('11'), ('22'::int4)), (('11'), "
            + "('22'::int4));"
            + "INSERT INTO testbatch( col1, col2 ) VALUES (('33'), ('44'::int4));"
            + "INSERT INTO testbatch( col1, col2 ) VALUES (('55'), ('66'::int4)), (('55'), "
            + "('66'::int4)), (('55'), ('66'::int4))",
        ps.toString());

    ps.addBatch();

    ps.setString(1, "1");
    ps.setInt(2, 2);
    ps.setString(3, "3");
    ps.setInt(4, 4);
    ps.setString(5, "5");
    ps.setInt(6, 6);

    Assertions.assertEquals(""
            + "INSERT INTO testbatch( col1, col2 ) VALUES (('1'), ('2'::int4)), (('1'), "
            + "('2'::int4));"
            + "INSERT INTO testbatch( col1, col2 ) VALUES (('3'), ('4'::int4));"
            + "INSERT INTO testbatch( col1, col2 ) VALUES (('5'), ('6'::int4)), (('5'), "
            + "('6'::int4)), (('5'), ('6'::int4))",
        ps.toString());

    ps.addBatch();

    Assertions.assertEquals("[6, 6]", Arrays.toString(ps.executeBatch()));
    Assertions.assertEquals(
        "[11,22, 11,22, 33,44, 55,66, 55,66, 55,66, 1,2, 1,2, 3,4, 5,6, 5,6, 5,6]",
        getResult().toString());
  }
}
