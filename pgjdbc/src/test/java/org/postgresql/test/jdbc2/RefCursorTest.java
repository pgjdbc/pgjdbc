/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * RefCursor ResultSet tests. This test case is basically the same as the ResultSet test case.
 *
 * <p>For backwards compatibility reasons we verify that ref cursors can be
 * registered with both {@link Types#OTHER} and {@link Types#REF_CURSOR}.</p>
 *
 * @author Nic Ferrier (nferrier@tapsellferrier.co.uk)
 */
public class RefCursorTest extends BaseTest4 {

  // before Java 7 there was no official ref cursor support in jdbc
  private boolean supportsRefCursor;
  // the rests are compiled with Java 6 and 7 as well so referencing
  // Types#REF_CURSOR directly would result in a compile error
  private int refCursorType;

  @Override
  public void setUp() throws Exception {
    // this is the same as the ResultSet setup.
    super.setUp();
    Statement stmt = con.createStatement();

    TestUtil.createTable(con, "testrs", "id integer primary key");

    stmt.executeUpdate("INSERT INTO testrs VALUES (1)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (2)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (3)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (4)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (6)");
    stmt.executeUpdate("INSERT INTO testrs VALUES (9)");


    // Create the functions.
    stmt.execute("CREATE OR REPLACE FUNCTION testspg__getRefcursor () RETURNS refcursor AS '"
        + "declare v_resset refcursor; begin open v_resset for select id from testrs order by id; "
        + "return v_resset; end;' LANGUAGE plpgsql;");
    stmt.execute("CREATE OR REPLACE FUNCTION testspg__getEmptyRefcursor () RETURNS refcursor AS '"
        + "declare v_resset refcursor; begin open v_resset for select id from testrs where id < 1 order by id; "
        + "return v_resset; end;' LANGUAGE plpgsql;");
    stmt.close();
    con.setAutoCommit(false);

    // if the public field Types#REF_CURSOR is present we're Java 8 or later
    try {
      Field refCursorFiled = Types.class.getDeclaredField("REF_CURSOR");
      refCursorType = (Integer) refCursorFiled.get(null);
      supportsRefCursor = true;
    } catch (NoSuchFieldException e) {
      supportsRefCursor = false;
    }
  }

  private void assumeRefCursorSupported() {
    Assume.assumeTrue(this.supportsRefCursor);
  }

  @Override
  public void tearDown() throws SQLException {
    con.setAutoCommit(true);
    Statement stmt = con.createStatement();
    stmt.execute("drop FUNCTION testspg__getRefcursor ();");
    stmt.execute("drop FUNCTION testspg__getEmptyRefcursor ();");
    TestUtil.dropTable(con, "testrs");
    super.tearDown();
  }

  @Test
  public void testResultOther() throws SQLException {
    assumeCallableStatementsSupported();

    testResult(Types.OTHER);
  }

  @Test
  public void testResultRefCursor() throws SQLException {
    assumeCallableStatementsSupported();
    assumeRefCursorSupported();

    testResult(this.refCursorType);
  }


  private void testResult(int outParameterSqlType) throws SQLException {
    CallableStatement call = con.prepareCall("{ ? = call testspg__getRefcursor () }");
    call.registerOutParameter(1, outParameterSqlType);
    call.execute();
    ResultSet rs = (ResultSet) call.getObject(1);

    assertTrue(rs.next());
    assertTrue(rs.getInt(1) == 1);

    assertTrue(rs.next());
    assertTrue(rs.getInt(1) == 2);

    assertTrue(rs.next());
    assertTrue(rs.getInt(1) == 3);

    assertTrue(rs.next());
    assertTrue(rs.getInt(1) == 4);

    assertTrue(rs.next());
    assertTrue(rs.getInt(1) == 6);

    assertTrue(rs.next());
    assertTrue(rs.getInt(1) == 9);

    assertTrue(!rs.next());
    rs.close();

    call.close();
  }


  @Test
  public void testEmptyResultOther() throws SQLException {
    assumeCallableStatementsSupported();

    testEmptyResult(Types.OTHER);
  }

  @Test
  public void testEmptyResultRefCursor() throws SQLException {
    assumeCallableStatementsSupported();
    assumeRefCursorSupported();

    testEmptyResult(this.refCursorType);
  }

  private void testEmptyResult(int outParameterSqlType) throws SQLException {
    CallableStatement call = con.prepareCall("{ ? = call testspg__getEmptyRefcursor () }");
    call.registerOutParameter(1, outParameterSqlType);
    call.execute();

    ResultSet rs = (ResultSet) call.getObject(1);
    assertTrue(!rs.next());
    rs.close();

    call.close();
  }

  @Test
  public void testMetaDataOther() throws SQLException {
    assumeCallableStatementsSupported();

    testMetaData(Types.OTHER);
  }

  @Test
  public void testMetaDataRefCursor() throws SQLException {
    assumeCallableStatementsSupported();
    assumeRefCursorSupported();

    testMetaData(this.refCursorType);
  }

  private void testMetaData(int outParameterSqlType) throws SQLException {
    CallableStatement call = con.prepareCall("{ ? = call testspg__getRefcursor () }");
    call.registerOutParameter(1, outParameterSqlType);
    call.execute();

    ResultSet rs = (ResultSet) call.getObject(1);
    ResultSetMetaData rsmd = rs.getMetaData();
    assertNotNull(rsmd);
    assertEquals(1, rsmd.getColumnCount());
    assertEquals(Types.INTEGER, rsmd.getColumnType(1));
    assertEquals("int4", rsmd.getColumnTypeName(1));
    rs.close();

    call.close();
  }

  @Test
  public void testResultTypeOther() throws SQLException {
    assumeCallableStatementsSupported();

    testResultType(Types.OTHER);
  }

  @Test
  public void testResultTypeRefCursor() throws SQLException {
    assumeCallableStatementsSupported();
    assumeRefCursorSupported();

    testResultType(this.refCursorType);
  }

  private void testResultType(int outParameterSqlType) throws SQLException {
    CallableStatement call = con.prepareCall("{ ? = call testspg__getRefcursor () }",
            ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    call.registerOutParameter(1, outParameterSqlType);
    call.execute();
    ResultSet rs = (ResultSet) call.getObject(1);

    assertEquals(rs.getType(), ResultSet.TYPE_SCROLL_INSENSITIVE);
    assertEquals(rs.getConcurrency(), ResultSet.CONCUR_READ_ONLY);

    assertTrue(rs.last());
    assertEquals(6, rs.getRow());
    rs.close();
    call.close();
  }

}
