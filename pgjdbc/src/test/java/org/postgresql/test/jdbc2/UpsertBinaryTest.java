/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import java.util.Properties;

/**
 * Tests {@code INSERT .. ON CONFLICT} in binary mode.
 */
public class UpsertBinaryTest extends UpsertTest {
  public UpsertBinaryTest(String name) {
    super(name);
  }

  @Override
  protected void updateProperties(Properties props) {
    forceBinary(props);
  }
}
