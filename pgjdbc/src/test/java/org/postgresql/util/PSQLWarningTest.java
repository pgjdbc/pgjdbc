/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;


public class PSQLWarningTest {

  @Test
  public void testPSQLLogsToDriverManagerMessage() throws Exception {
    Connection con = TestUtil.openDB();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DriverManager.setLogWriter(new PrintWriter(new OutputStreamWriter(baos, "ASCII")));

    Statement stmt = con.createStatement();
    stmt.execute("DO language plpgsql $$ BEGIN RAISE NOTICE 'test notice'; END $$;");
    assertTrue(baos.toString().contains("NOTICE: test notice"));

    stmt.close();
    con.close();
  }
}
