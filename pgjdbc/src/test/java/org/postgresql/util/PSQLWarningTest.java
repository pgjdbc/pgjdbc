/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.sql.DriverManager;

public class PSQLWarningTest {

  @Test
  public void testPSQLLogsToDriverManagerMessage() throws UnsupportedEncodingException  {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DriverManager.setLogWriter(new PrintWriter(new OutputStreamWriter(baos, "ASCII")));

    ServerErrorMessage warnMsg = new ServerErrorMessage(
        "SNOTICE\u0000C42P06\u0000Mschema \"customschema\" already exists, skipping\u0000Fschemacmds.c\u0000L100\u0000RCreateSchemaCommand\u0000\u0000");
    PSQLWarning warning = new PSQLWarning(warnMsg);
    assertEquals("SQLWarning: reason(NOTICE: schema \"customschema\" already exists, skipping) SQLState(42P06)\n", baos.toString());
  }
}
