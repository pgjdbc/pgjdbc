package org.postgresql.test;

import org.postgresql.test.jdbc2.CursorFetchTest;

import java.util.Properties;

public class CursorFetchBinaryTest extends CursorFetchTest {
  public CursorFetchBinaryTest(String name) {
    super(name);
  }

  @Override
  protected void updateProperties(Properties props) {
    forceBinary(props);
  }
}
