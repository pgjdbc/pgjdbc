package org.postgresql.test.jdbc4;

import java.util.Properties;

public class ArrayBinaryTest extends ArrayTest {
  public ArrayBinaryTest(String name) {
    super(name);
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    forceBinary(props);
  }
}
