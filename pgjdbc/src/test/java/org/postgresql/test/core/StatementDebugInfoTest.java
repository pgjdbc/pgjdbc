/*
 * Copyright (c) 2016, PostgreSQL Global Development Group See the LICENSE file in the project root
 * for more information.
 */

package org.postgresql.test.core;

import org.postgresql.PGProperty;
import org.postgresql.PGStatement;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

/**
 * Debuggers and loggers need access to statements raw SQL, prepared statement parameter types and
 * values. This test makes sure all this info is available.
 *
 * @author Christopher Deckers (chrriis@gmail.com)
 *
 */
@RunWith(Parameterized.class)
public class StatementDebugInfoTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "DebugInfoTable",
        "Col1      INTEGER             NOT NULL,"
            + "Col2      DATE                    NULL,"
            + "Col3      DOUBLE PRECISION        NULL,"
            + "Col4      SMALLINT            NOT NULL,"
            + "Col5      NUMERIC (10)            NULL,"
            + "Col6      TIMESTAMP               NULL,"
            + "Col7      TEXT                NOT NULL,"
            + "Col8      BOOLEAN                 NULL");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "DebugInfoTable");
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.REWRITE_BATCHED_INSERTS.set(props, insertRewrite);
  }

  private boolean insertRewrite;

  public StatementDebugInfoTest(boolean insertRewrite) {
    this.insertRewrite = insertRewrite;
  }

  @Parameterized.Parameters(name = "insertRewrite = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (boolean insertRewrite : new boolean[]{false, true}) {
      ids.add(new Object[]{insertRewrite});
    }
    return ids;
  }

  @Test
  public void testParameterInfo() throws Throwable {
    Connection conn = con;
    try {
      Timestamp ts = new Timestamp(2016 - 1900, 1 - 1, 31, 0, 0, 0, 0);
      Date d = new Date(2016 - 1900, 1 - 1, 31);
      String insert =
          "INSERT INTO DebugInfoTable (Col1,Col2,Col3,Col4,Col5,Col6,Col7,Col8) VALUES (?,?,?,?,?,?,?,?)";
      PreparedStatement pst = conn.prepareStatement(insert);
      pst.setInt(1, 123);
      pst.setDate(2, d);
      Assert.assertEquals(8, ((PGStatement) pst).getParameterCount());
      Assert.assertEquals(
          Arrays.toString(new int[]{Types.INTEGER, Types.DATE, Types.NULL, Types.NULL, Types.NULL,
              Types.NULL, Types.NULL, Types.NULL}),
          Arrays.toString(((PGStatement) pst).getParameterTypes()));
      Assert.assertEquals(true, ((PGStatement) pst).isParameterBound(1));
      Assert.assertEquals(true, ((PGStatement) pst).isParameterBound(2));
      Assert.assertEquals(false, ((PGStatement) pst).isParameterBound(3));
      pst.setNull(2, Types.DATE);
      pst.setDouble(3, 12.3);
      Assert.assertEquals(
          Arrays.toString(
              new int[]{Types.INTEGER, Types.NULL, Types.DOUBLE, Types.NULL, Types.NULL, Types.NULL,
                  Types.NULL, Types.NULL}),
          Arrays.toString(((PGStatement) pst).getParameterTypes()));
      Assert.assertEquals(true, ((PGStatement) pst).isParameterBound(1));
      Assert.assertEquals(true, ((PGStatement) pst).isParameterBound(2));
      Assert.assertEquals(true, ((PGStatement) pst).isParameterBound(3));
      pst.setDate(2, d);
      pst.setDouble(3, 2.3);
      pst.setShort(4, (short) 4);
      pst.setBigDecimal(5, BigDecimal.TEN);
      pst.setTimestamp(6, ts);
      pst.setString(7, "abc");
      pst.setObject(8, true);
      for (int i = 0; i < 8; i++) {
        Assert.assertEquals("Parameter " + (i + 1) + " must be bound", true,
            ((PGStatement) pst).isParameterBound(i + 1));
      }
      Assert.assertEquals(
          Arrays.toString(new int[]{Types.INTEGER, Types.DATE, Types.DOUBLE, Types.SMALLINT,
              Types.NUMERIC, Types.TIMESTAMP, Types.VARCHAR, Types.BOOLEAN}),
          Arrays.toString(((PGStatement) pst).getParameterTypes()));
      pst.addBatch();
      pst.setInt(1, 345);
      pst.setDate(2, d);
      pst.setDouble(3, 4.5);
      pst.setShort(4, (short) 8);
      pst.setBigDecimal(5, BigDecimal.ONE);
      pst.setTimestamp(6, ts);
      pst.setString(7, "def");
      pst.setObject(8, false);
      pst.addBatch();
      for (int i = 0; i < 8; i++) {
        Assert.assertEquals("Parameter " + (i + 1) + " must be bound", true,
            ((PGStatement) pst).isParameterBound(i + 1));
      }
      Assert.assertEquals(
          Arrays.toString(new int[]{Types.INTEGER, Types.DATE, Types.DOUBLE, Types.SMALLINT,
              Types.NUMERIC, Types.TIMESTAMP, Types.VARCHAR, Types.BOOLEAN}),
          Arrays.toString(((PGStatement) pst).getParameterTypes()));
      pst.executeBatch();
      pst.clearParameters();
      Assert.assertEquals(
          Arrays.toString(new int[]{Types.NULL, Types.NULL, Types.NULL, Types.NULL, Types.NULL,
              Types.NULL, Types.NULL, Types.NULL}),
          Arrays.toString(((PGStatement) pst).getParameterTypes()));
      Assert.assertEquals(false, ((PGStatement) pst).isParameterBound(1));
    } catch (SQLException e) {
      e.printStackTrace();
      SQLException nextException = e.getNextException();
      if (nextException != null) {
        System.err.println("Next Exception:");
        nextException.printStackTrace();
      }
      throw e;
    }
  }

  @Test
  public void testStatementInfo() throws Throwable {
    Connection conn = con;
    try {
      String insert =
          "INSERT /* with comments */ INTO DebugInfoTable (Col1,Col2,Col3,Col4,Col5,Col6,Col7,Col8) VALUES (1,'2016-12-31',2.3,4,10,'2016-12-31 12:34:56.789','abc',CAST(1 AS BOOLEAN)) --- line comments";
      Statement st = conn.createStatement();
      // Statement is not executed, so it returns a standard Object toString.
      Assert.assertEquals(st.getClass().getName() + "@" + Integer.toHexString(st.hashCode()),
          ((PGStatement) st).toString());
      st.executeUpdate(insert);
      Assert.assertEquals(insert, ((PGStatement) st).toString());
      Assert.assertEquals(insert, ((PGStatement) st).toPreparedString());
    } catch (SQLException e) {
      e.printStackTrace();
      SQLException nextException = e.getNextException();
      if (nextException != null) {
        System.err.println("Next Exception:");
        nextException.printStackTrace();
      }
      throw e;
    }
  }

  @Test
  public void testPreparedStatementInfo() throws Throwable {
    Connection conn = con;
    try {
      Timestamp ts = new Timestamp(2016 - 1900, 1 - 1, 31, 0, 0, 0, 0);
      Date d = new Date(2016 - 1900, 1 - 1, 31);
      String insert =
          "INSERT /* with comments */ INTO DebugInfoTable (Col1,Col2,Col3,Col4,Col5,Col6,Col7,Col8) VALUES (?,?,?,?,?,?,?,?) --- line comments";
      PreparedStatement pst = conn.prepareStatement(insert);
      Assert.assertEquals(insert, pst.toString());
      pst.setInt(1, 123);
      pst.setDate(2, d);
      pst.setDouble(3, 2.3);
      pst.setShort(4, (short) 4);
      pst.setBigDecimal(5, BigDecimal.TEN);
      pst.setTimestamp(6, ts);
      pst.setString(7, "abc");
      pst.setObject(8, true);
      pst.addBatch();
      Assert.assertEquals(insert, pst.toString());
      pst.setNull(2, Types.DATE);
      pst.setNull(6, Types.TIMESTAMP);
      Assert.assertEquals(
          "INSERT /* with comments */ INTO DebugInfoTable (Col1,Col2,Col3,Col4,Col5,Col6,Col7,Col8) VALUES (123,NULL,2.3,4,'10',NULL,'abc','TRUE') --- line comments",
          ((PGStatement) pst).toPreparedString());
      pst.executeBatch();
      Assert.assertEquals(insert, pst.toString());
      Assert.assertEquals(
          "INSERT /* with comments */ INTO DebugInfoTable (Col1,Col2,Col3,Col4,Col5,Col6,Col7,Col8) VALUES (123,NULL,2.3,4,'10',NULL,'abc','TRUE') --- line comments",
          ((PGStatement) pst).toPreparedString());
      pst.clearParameters();
      Assert.assertEquals(insert, pst.toString());
      Assert.assertEquals(
          "INSERT /* with comments */ INTO DebugInfoTable (Col1,Col2,Col3,Col4,Col5,Col6,Col7,Col8) VALUES (?,?,?,?,?,?,?,?) --- line comments",
          ((PGStatement) pst).toPreparedString());
    } catch (SQLException e) {
      e.printStackTrace();
      SQLException nextException = e.getNextException();
      if (nextException != null) {
        System.err.println("Next Exception:");
        nextException.printStackTrace();
      }
      throw e;
    }
  }

}
