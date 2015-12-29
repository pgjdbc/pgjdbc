package org.postgresql.test.jdbc2;

import java.util.Properties;

public class BatchExecuteBinaryTest extends BatchExecuteTest {
  public BatchExecuteBinaryTest(String name) {
    super(name);
  }

  @Override
  protected void updateProperties(Properties props) {
    forceBinary(props);
  }
}
