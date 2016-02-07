package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class BaseTest4 {

  public enum BinaryMode {
    REGULAR, FORCE;
  }

  public enum AutoCommit {
    YES, NO;
  }

  protected Connection con;
  private BinaryMode binaryMode;

  protected void updateProperties(Properties props) {
    if (binaryMode == BinaryMode.FORCE) {
      forceBinary(props);
    }
  }

  protected void forceBinary(Properties props) {
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
  }

  public final void setBinaryMode(BinaryMode binaryMode) {
    this.binaryMode = binaryMode;
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
