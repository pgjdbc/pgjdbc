package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class BaseTest extends TestCase {
  protected Connection con;

  public BaseTest(String name) {
    super(name);

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

  protected void setUp() throws Exception {
    Properties props = new Properties();
    updateProperties(props);
    con = TestUtil.openDB(props);
  }

  protected void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }
}
