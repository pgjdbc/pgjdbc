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
    }
  }
}
