/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.hamcrest.core.StringStartsWith;
import org.junit.Assert;
import org.junit.Test;

import java.sql.Statement;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Logger;

/**
 * Test JDBC extension API for server reported parameter status messages.
 *
 * <p>This test covers client interface for server ParameterStatus messages
 * (GUC_REPORT) parameters via PGConnection.getParameterStatuses() and
 * PGConnection.getParameterStatus().</p>
 */
public class ParameterStatusTest extends BaseTest4 {

  private final TimeZone tzPlus0800 = TimeZone.getTimeZone("GMT+8:00");
  private final Logger logger = Logger.getLogger(ParameterStatusTest.class.getName());

  @Override
  public void tearDown() {
    TimeZone.setDefault(null);
  }

  @Test
  public void expectedInitialParameters() throws Exception {
    TimeZone.setDefault(tzPlus0800);
    con = TestUtil.openDB();

    Map<String,String> params = ((PGConnection)con).getParameterStatuses();

    // PgJDBC forces the following parameters
    Assert.assertEquals("UTF8", params.get("client_encoding"));
    Assert.assertNotNull(params.get("DateStyle"));
    Assert.assertThat(params.get("DateStyle"), StringStartsWith.startsWith("ISO"));

    // PgJDBC sets TimeZone via Java's TimeZone.getDefault()
    // Pg reports POSIX timezones which are negated, so:
    Assert.assertEquals("GMT-08:00", params.get("TimeZone"));

    // Must be reported. All these exist in 8.2 or above, and we don't bother
    // with test coverage older than that.
    Assert.assertNotNull(params.get("integer_datetimes"));
    Assert.assertNotNull(params.get("is_superuser"));
    Assert.assertNotNull(params.get("server_encoding"));
    Assert.assertNotNull(params.get("server_version"));
    Assert.assertNotNull(params.get("session_authorization"));
    Assert.assertNotNull(params.get("standard_conforming_strings"));

    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      Assert.assertNotNull(params.get("IntervalStyle"));
    } else {
      Assert.assertNull(params.get("IntervalStyle"));
    }

    // TestUtil forces "ApplicationName=Driver Tests"
    // if application_name is supported (9.0 or newer)
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      Assert.assertEquals("Driver Tests", params.get("application_name"));
    } else {
      Assert.assertNull(params.get("application_name"));
    }

    // Not reported
    Assert.assertNull(params.get("nonexistent"));
    Assert.assertNull(params.get("enable_hashjoin"));

    TestUtil.closeDB(con);
  }

  @Test
  public void reportUpdatedParameters() throws Exception {
    con = TestUtil.openDB();

    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      /* This test uses application_name which was added in 9.0 */
      return;
    }

    con.setAutoCommit(false);
    Statement stmt = con.createStatement();

    stmt.executeUpdate("SET application_name = 'pgjdbc_ParameterStatusTest2';");
    stmt.close();

    // Parameter status should be reported before the ReadyForQuery so we will
    // have already processed it
    Assert.assertEquals("pgjdbc_ParameterStatusTest2", ((PGConnection)con).getParameterStatus("application_name"));

    TestUtil.closeDB(con);
  }

  // Run a txn-level SET then a txn-level SET LOCAL so we can make sure we keep
  // track of the right GUC value at each point.
  private void transactionalParametersCommon() throws Exception {
    Statement stmt = con.createStatement();

    // Initial value assigned by TestUtil
    Assert.assertEquals("Driver Tests", ((PGConnection)con).getParameterStatus("application_name"));

    // PgJDBC begins an explicit txn here due to autocommit=off so the effect
    // should be lost on rollback but retained on commit per the docs.
    stmt.executeUpdate("SET application_name = 'pgjdbc_ParameterStatusTestTxn';");
    Assert.assertEquals("pgjdbc_ParameterStatusTestTxn", ((PGConnection)con).getParameterStatus("application_name"));

    // SET LOCAL is always txn scoped so the effect here will always be
    // unwound on txn end.
    stmt.executeUpdate("SET LOCAL application_name = 'pgjdbc_ParameterStatusTestLocal';");
    Assert.assertEquals("pgjdbc_ParameterStatusTestLocal", ((PGConnection)con).getParameterStatus("application_name"));

    stmt.close();
  }

  @Test
  public void transactionalParametersRollback() throws Exception {
    con = TestUtil.openDB();

    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      /* This test uses application_name which was added in 9.0 */
      return;
    }

    con.setAutoCommit(false);

    transactionalParametersCommon();

    // SET unwinds on ROLLBACK
    con.rollback();

    Assert.assertEquals("Driver Tests", ((PGConnection)con).getParameterStatus("application_name"));

    TestUtil.closeDB(con);
  }

  @Test
  public void transactionalParametersCommit() throws Exception {
    con = TestUtil.openDB();

    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      /* This test uses application_name which was added in 9.0 */
      return;
    }

    con.setAutoCommit(false);

    transactionalParametersCommon();

    // SET is retained on commit but SET LOCAL is unwound
    con.commit();

    Assert.assertEquals("pgjdbc_ParameterStatusTestTxn", ((PGConnection)con).getParameterStatus("application_name"));

    TestUtil.closeDB(con);
  }

  @Test
  public void transactionalParametersAutocommit() throws Exception {
    con = TestUtil.openDB();

    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      /* This test uses application_name which was added in 9.0 */
      return;
    }

    con.setAutoCommit(true);
    Statement stmt = con.createStatement();

    // A SET LOCAL in autocommit should have no visible effect as we report the reset value too
    Assert.assertEquals("Driver Tests", ((PGConnection)con).getParameterStatus("application_name"));
    stmt.executeUpdate("SET LOCAL application_name = 'pgjdbc_ParameterStatusTestLocal';");
    Assert.assertEquals("Driver Tests", ((PGConnection)con).getParameterStatus("application_name"));

    stmt.close();
    TestUtil.closeDB(con);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void parameterMapReadOnly() throws Exception {
    try {
      con = TestUtil.openDB();
      Map params = ((PGConnection)con).getParameterStatuses();
      params.put("DateStyle", "invalid");
      Assert.fail("Attempt to write to exposed parameters map must throw");
    } finally {
      TestUtil.closeDB(con);
    }
  }

  @Test
  public void parameterMapIsView() throws Exception {
    con = TestUtil.openDB();

    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      /* This test uses application_name which was added in 9.0 */
      return;
    }

    Map params = ((PGConnection)con).getParameterStatuses();

    Statement stmt = con.createStatement();

    Assert.assertEquals("Driver Tests", params.get("application_name"));
    stmt.executeUpdate("SET application_name = 'pgjdbc_paramstatus_view';");
    Assert.assertEquals("pgjdbc_paramstatus_view", params.get("application_name"));

    stmt.close();
    TestUtil.closeDB(con);
  }

}
