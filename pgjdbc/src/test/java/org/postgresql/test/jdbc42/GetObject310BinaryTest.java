/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import java.util.Properties;

public class GetObject310BinaryTest extends GetObject310Test {

  public GetObject310BinaryTest(String name) {
    super(name);
  }

  @Override
  protected void updateProperties(Properties props) {
    forceBinary(props);
  }

}
