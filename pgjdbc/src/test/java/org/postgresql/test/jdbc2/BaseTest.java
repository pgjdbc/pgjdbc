/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class BaseTest {
  protected Connection con;
  protected PreferQueryMode preferQueryMode;

  public BaseTest() {
    try {
      new org.postgresql.Driver();
    } catch (Exception ex) {
      /* ignore */
    }
  }

  protected void updateProperties(Properties props) {
  }

  protected void forceBinary(Properties props) {
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
  }

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    updateProperties(props);
    con = TestUtil.openDB(props);
    PGConnection pg = con.unwrap(PGConnection.class);
    preferQueryMode = pg == null ? PreferQueryMode.EXTENDED : pg.getPreferQueryMode();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  public void assumeByteaSupported() {
    Assume.assumeTrue("bytea is not supported in simple protocol execution mode",
        preferQueryMode.compareTo(PreferQueryMode.EXTENDED) >= 0);
  }
}
