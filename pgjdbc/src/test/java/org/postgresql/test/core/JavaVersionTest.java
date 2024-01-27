/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.JavaVersion;

import org.junit.jupiter.api.Test;

class JavaVersionTest {
  @Test
  void getRuntimeVersion() {
    String currentVersion = System.getProperty("java.version");
    String msg = "java.version = " + currentVersion + ", JavaVersion.getRuntimeVersion() = "
        + JavaVersion.getRuntimeVersion();
    System.out.println(msg);
    if (currentVersion.startsWith("1.8")) {
      assertEquals(JavaVersion.v1_8, JavaVersion.getRuntimeVersion(), msg);
    }
  }
}
