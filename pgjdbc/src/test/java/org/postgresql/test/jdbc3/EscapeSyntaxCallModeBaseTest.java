/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import java.sql.SQLException;
import java.sql.Statement;

public class EscapeSyntaxCallModeBaseTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    Statement stmt = con.createStatement();
    stmt.execute(
        "CREATE OR REPLACE FUNCTION myiofunc(a INOUT int, b OUT int) AS 'BEGIN b := a; a := 1; END;' LANGUAGE plpgsql");
    stmt.execute(
        "CREATE OR REPLACE FUNCTION mysumfunc(a int, b int) returns int AS 'BEGIN return a + b; END;' LANGUAGE plpgsql");
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      stmt.execute(
          "CREATE OR REPLACE PROCEDURE myioproc(a INOUT int, b INOUT int) AS 'BEGIN b := a; a := 1; END;' LANGUAGE plpgsql");
    }
  }

  @Override
  public void tearDown() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("drop function myiofunc(a INOUT int, b OUT int) ");
    stmt.execute("drop function mysumfunc(a int, b int) ");
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v11)) {
      stmt.execute("drop procedure myioproc(a INOUT int, b INOUT int) ");
    }
    stmt.close();
    super.tearDown();
  }

}
