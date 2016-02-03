package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class BaseTest4 {
  protected Connection con;

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
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }
}
