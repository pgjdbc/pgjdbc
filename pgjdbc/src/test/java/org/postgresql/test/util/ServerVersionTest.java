/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.postgresql.core.ServerVersion;

import org.junit.Assert;
import org.junit.Test;

public class ServerVersionTest {
  @Test
  public void versionIncreases() {
    ServerVersion prev = null;
    for (ServerVersion serverVersion : ServerVersion.values()) {
      if (prev != null) {
        Assert.assertTrue(prev + " should be less than " + serverVersion,
            prev.getVersionNum() < serverVersion.getVersionNum());
      }
      prev = serverVersion;
    }
  }

  @Test
  public void testVersions() {
    Assert.assertEquals(ServerVersion.v12.getVersionNum(), ServerVersion.from("12.0").getVersionNum());
    Assert.assertEquals(120004, ServerVersion.from("12.4").getVersionNum());
    Assert.assertEquals(ServerVersion.v11.getVersionNum(), ServerVersion.from("11.0").getVersionNum());
    Assert.assertEquals(110006, ServerVersion.from("11.6").getVersionNum());
    Assert.assertEquals(ServerVersion.v10.getVersionNum(), ServerVersion.from("10.0").getVersionNum());
    Assert.assertTrue(ServerVersion.v9_6.getVersionNum() < ServerVersion.from("9.6.4").getVersionNum());
  }
}
