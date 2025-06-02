/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.sspi;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

/*
* These tests require a working SSPI authentication setup
* in the database server that allows the executing user
* to authenticate as the "sspiusername" in the build
* configuration.
*/
class SSPITest {

  /*
   * SSPI only exists on Windows.
   */
  @BeforeAll
  static void checkPlatform() {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    assumeTrue(
        os.contains("windows"),
        "SSPI not supported on this platform, current os.name is " + os);
  }

  /*
   * Tests that SSPI login succeeds and a query can be run.
   */
  @Test
  @Disabled
  void authorized() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getSSPIUser());

    Connection con = TestUtil.openDB(props);

    Statement stmt = con.createStatement();
    stmt.executeQuery("SELECT 1");

    TestUtil.closeDB(con);
  }

  /*
   * Tests that SSPI login fails with an unknown/unauthorized
   * user name.
   */
  @Test
  void unauthorized() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, "invalid" + TestUtil.getSSPIUser());

    try {
      Connection con = TestUtil.openDB(props);
      TestUtil.closeDB(con);
      fail("Expected a PSQLException");
    } catch (PSQLException e) {
      assertThat(e.getSQLState(), is(PSQLState.INVALID_PASSWORD.getState()));
    }
  }

}
