/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class QuietOutPutTest extends BaseTest4 {
  private final int setBefore;
  private final int setAfter;
  private final Operation operation;
  private final BatchMode batchMode;

  enum Operation {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
  }

  enum BatchMode {
    YES,
    NO,
  }

  public QuietOutPutTest(BinaryMode binaryMode, int setBefore, int setAfter,
      Operation operation, BatchMode batchMode) {
    setBinaryMode(binaryMode);
    this.setBefore = setBefore;
    this.setAfter = setAfter;
    this.operation = operation;
    this.batchMode = batchMode;
  }

  @Parameterized.Parameters(name = "binary = {0}, setBefore = {1}, setAfter = {2}, operation = "
      + "{3}, batchMode = {4}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      for (int setBefore : new int[]{0, 1, 2}) {
        for (int setAfter : new int[]{0, 1, 2}) {
          for (Operation operation : Operation.values()) {
            for (BatchMode batchMode : BatchMode.values()) {
              if (batchMode == BatchMode.YES && operation == Operation.SELECT) {
                continue;
              }
              ids.add(new Object[]{binaryMode, setBefore, setAfter, operation, batchMode});
            }
          }
        }
      }
    }
    return ids;
  }

  @BeforeClass
  public static void createTestTable() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "inttable", "a int, b int");
    }
  }

  @AfterClass
  public static void dropTestTable() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "inttable");
    }
  }

  @Override
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("quietOutput", "true");
    updateProperties(props);
    con = TestUtil.openDB(props);
    PGConnection pg = con.unwrap(PGConnection.class);
    preferQueryMode = pg == null ? PreferQueryMode.EXTENDED : pg.getPreferQueryMode();
    try (Statement st = con.createStatement()) {
      st.execute("DELETE FROM inttable");
    }
  }

  @Test
  public void testPreparedStatement() throws SQLException {
    StringBuilder sb = new StringBuilder();
    addSetSearchPath(sb, setBefore);
    switch (operation) {
      case SELECT:
        sb.append("SELECT * FROM inttable WHERE a>? AND a<? order by a");
        break;
      case INSERT:
        sb.append("INSERT INTO inttable VALUES (?, 1)");
        break;
      case UPDATE:
        sb.append("UPDATE inttable SET b=b where a=?");
        break;
      case DELETE:
        sb.append("DELETE FROM inttable WHERE a=?");
        break;
    }
    sb.append(';');
    addSetSearchPath(sb, setAfter);
    // Remove the trailing ;
    sb.setLength(sb.length() - 1);

    if (operation != Operation.INSERT) {
      // Other operations require data to process
      try (PreparedStatement pstmt = con.prepareStatement("insert into inttable values (1, 1), "
          + "(2, 1)")) {
        pstmt.execute();
      }
    }

    // Actual test
    try (PreparedStatement pstmt = con.prepareStatement(sb.toString())) {
      switch (operation) {
        case SELECT:
          pstmt.setInt(1, 0);
          pstmt.setInt(2, 5);
          try (ResultSet rs = pstmt.executeQuery();) {
            assertEquals(
                "pstmt.executeQuery() results",
                "1,1\n2,1",
                TestUtil.join(TestUtil.resultSetToLines(rs))
            );
          }
          break;
        case INSERT:
        case UPDATE:
        case DELETE:
          pstmt.setInt(1, 1);
          if (batchMode == BatchMode.NO) {
            assertEquals(
                "pstmt.executeUpdate()",
                1,
                pstmt.executeUpdate()
            );
          } else {
            pstmt.addBatch();
            pstmt.setInt(1, 2);
            pstmt.addBatch();
            assertEquals(
                "pstmt.executeBatch()",
                "[1, 1]",
                Arrays.toString(pstmt.executeBatch())
            );
          }
          break;
      }
    }
  }

  @Test
  public void testStatement() throws SQLException {
    StringBuilder sb = new StringBuilder();
    addSetSearchPath(sb, setBefore);
    switch (operation) {
      case SELECT:
        sb.append("SELECT * FROM inttable WHERE a>0 AND a<5 order by a");
        break;
      case INSERT:
        sb.append("INSERT INTO inttable VALUES (1, 1)");
        break;
      case UPDATE:
        sb.append("UPDATE inttable SET b=b where a=1");
        break;
      case DELETE:
        sb.append("DELETE FROM inttable WHERE a=1");
        break;
    }
    sb.append(';');
    addSetSearchPath(sb, setAfter);
    // Remove the trailing ;
    sb.setLength(sb.length() - 1);

    if (operation != Operation.INSERT) {
      // Other operations require data to process
      try (PreparedStatement pstmt = con.prepareStatement("insert into inttable values (1, 1), "
          + "(2, 1)")) {
        pstmt.execute();
      }
    }

    // Actual test
    try (Statement stmt = con.createStatement()) {
      switch (operation) {
        case SELECT:
          try (ResultSet rs = stmt.executeQuery(sb.toString())) {
            assertEquals(
                "stmt.executeQuery() results",
                "1,1\n2,1",
                TestUtil.join(TestUtil.resultSetToLines(rs))
            );
          }
          break;
        case INSERT:
        case UPDATE:
        case DELETE:
          if (batchMode == BatchMode.NO) {
            assertEquals(
                "stmt.executeUpdate()",
                1,
                stmt.executeUpdate(sb.toString())
            );
          } else {
            stmt.addBatch(sb.toString());
            assertEquals(
                "stmt.executeBatch()",
                "[1]",
                Arrays.toString(stmt.executeBatch())
            );
          }
          break;
      }
    }
  }

  private void addSetSearchPath(StringBuilder sb, int numberOfCommands) {
    for (int i = 0; i < numberOfCommands; i++) {
      sb.append("SET search_path = 'public';");
    }
  }
}
