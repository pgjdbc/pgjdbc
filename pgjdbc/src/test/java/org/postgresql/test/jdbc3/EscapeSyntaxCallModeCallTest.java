/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.EscapeSyntaxCallMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.Test;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;

public class EscapeSyntaxCallModeCallTest extends EscapeSyntaxCallModeBaseTest {

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.ESCAPE_SYNTAX_CALL_MODE.set(props, EscapeSyntaxCallMode.CALL.value());
  }

  @Test
  public void testInvokeFunction() throws Throwable {
    // escapeSyntaxCallMode=call will cause a CALL statement to be used for the JDBC escape call
    // syntax used below. "myiofunc" is a function, so the attempted invocation should fail.
    PSQLState expected = PSQLState.WRONG_OBJECT_TYPE;
    assumeCallableStatementsSupported();
    assumeMinimumServerVersion(ServerVersion.v11);

    CallableStatement cs = con.prepareCall("{ call myiofunc(?,?) }");
    cs.registerOutParameter(1, Types.INTEGER);
    cs.registerOutParameter(2, Types.INTEGER);
    cs.setInt(1, 10);
    try {
      cs.execute();
      fail("Should throw an exception");
    } catch (SQLException ex) {
      assertEquals(expected.getState(),ex.getSQLState());
    }
  }

  @Test
  public void testInvokeFunctionHavingReturnParameter() throws Throwable {
    // escapeSyntaxCallMode=call will cause a CALL statement to be used for the JDBC escape call
    // syntax used below. "mysumfunc" is a function, so the attempted invocation should fail.

    //version 14 changes this to undefined function
    PSQLState expected = PSQLState.WRONG_OBJECT_TYPE;

    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v14)) {
      expected = PSQLState.UNDEFINED_FUNCTION;
    }

    assumeCallableStatementsSupported();
    assumeMinimumServerVersion(ServerVersion.v11);
    CallableStatement cs = con.prepareCall("{ ? = call mysumfunc(?,?) }");
    cs.registerOutParameter(1, Types.INTEGER);
    cs.setInt(2, 10);
    cs.setInt(3, 20);
    try {
      cs.execute();
      fail("Should throw an exception");
    } catch (SQLException ex) {
      assertEquals(expected.getState(), ex.getSQLState());
    }
  }

  @Test
  public void testInvokeProcedure() throws Throwable {
    // escapeSyntaxCallMode=call will cause a CALL statement to be used for the JDBC escape call
    // syntax used below. "myioproc" is a procedure, so the invocation should succeed.
    assumeCallableStatementsSupported();
    assumeMinimumServerVersion(ServerVersion.v11);
    CallableStatement cs = con.prepareCall("{call myioproc(?,?)}");
    cs.registerOutParameter(1, Types.INTEGER);
    cs.registerOutParameter(2, Types.INTEGER);
    cs.setInt(1, 10);
    cs.setInt(2, 20);
    cs.execute();
    // Expected output: a==1 (param 1), b==10 (param 2)
    int a = cs.getInt(1);
    int b = cs.getInt(2);
    assertTrue("Expected myioproc() to return output parameter values 1,10 but returned " + a + "," + b, (a == 1 && b == 10));
  }

}
