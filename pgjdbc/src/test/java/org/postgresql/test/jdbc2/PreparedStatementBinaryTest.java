package org.postgresql.test.jdbc2;

import java.util.Properties;

public class PreparedStatementBinaryTest extends PreparedStatementTest {
  public PreparedStatementBinaryTest(String name) {
    super(name);
  }

  @Override
  protected void updateProperties(Properties props) {
    forceBinary(props);
  }
}
