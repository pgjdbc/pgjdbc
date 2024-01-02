/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.postgresql.core.ServerVersion;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ServerVersionTest {
  @Test
  void versionIncreases() {
    ServerVersion prev = null;
    for (ServerVersion serverVersion : ServerVersion.values()) {
      if (prev != null) {
        Assertions.assertTrue(prev.getVersionNum() < serverVersion.getVersionNum(),
            prev + " should be less than " + serverVersion);
      }
      prev = serverVersion;
    }
  }

  @Test
  void versions() {
    Assertions.assertEquals(ServerVersion.v12.getVersionNum(), ServerVersion.from("12.0").getVersionNum());
    Assertions.assertEquals(120004, ServerVersion.from("12.4").getVersionNum());
    Assertions.assertEquals(ServerVersion.v11.getVersionNum(), ServerVersion.from("11.0").getVersionNum());
    Assertions.assertEquals(110006, ServerVersion.from("11.6").getVersionNum());
    Assertions.assertEquals(ServerVersion.v10.getVersionNum(), ServerVersion.from("10.0").getVersionNum());
    Assertions.assertTrue(ServerVersion.v9_6.getVersionNum() < ServerVersion.from("9.6.4").getVersionNum());
  }
}
