/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.Version;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;

@RunWith(Parameterized.class)
public class ServerVersionParseTest {

  private final String versionString;
  private final int versionNum;
  private final String rejectReason;

  public ServerVersionParseTest(String versionString, int versionNum, String rejectReason) {
    this.versionString = versionString;
    this.versionNum = versionNum;
    this.rejectReason = rejectReason;
  }

  @Parameterized.Parameters(name = "str = {0}, expected = {1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        /* 4 part version tests */
        {"7.4.0.0", 70400, null},
        {"9.0.0.0", 90000, null},
        {"9.0.1.0", 90001, null},
        {"9.2.1.0", 90201, null},
        {"7.4.0", 70400, null},
        {"9.0.0", 90000, null},
        {"9.0.1", 90001, null},
        {"9.2.1", 90201, null},
        /* Major only */
        {"7.4", 70400, null},
        {"9.0", 90000, null},
        {"9.2", 90200, null},
        {"9.6", 90600, null},
        {"10", 100000, null},
        {"11", 110000, null},
        {"12", 120000, null},
        /* Multidigit */
        {"9.4.10", 90410, null},
        {"9.20.10", 92010, null},
        /* After 10 */
        {"10.1", 100001, null},
        {"10.10", 100010, null},
        {"11.1", 110001, null},
        {"123.20", 1230020, null},
        /* Fail cases */
        {"9.20.100", -1, "Should've rejected three-digit minor version"},
        {"9.100.10", -1, "Should've rejected three-digit second part of major version"},
        {"10.100.10", -1, "10+ version should have 2 components only"},
        {"12345.1", -1, "Too big version number"},
        /* Preparsed */
        {"90104", 90104, null},
        {"090104", 90104, null},
        {"070400", 70400, null},
        {"100004", 100004, null},
        {"10000", 10000, null},
        /* --with-extra-version or beta/devel tags */
        {"9.4devel", 90400, null},
        {"9.4beta1", 90400, null},
        {"10devel", 100000, null},
        {"10beta1", 100000, null},
        {"10.1devel", 100001, null},
        {"10.1beta1", 100001, null},
        {"9.4.1bobs", 90401, null},
        {"9.4.1bobspatched9.4", 90401, null},
        {"9.4.1-bobs-patched-postgres-v2.2", 90401, null},

    });
  }

  @Test
  public void run() {
    try {
      Version version = ServerVersion.from(versionString);
      if (rejectReason == null) {
        Assert.assertEquals("Parsing " + versionString, versionNum, version.getVersionNum());
      } else {
        Assert.fail("Should fail to parse " + versionString + ", " + rejectReason);
      }
    } catch (NumberFormatException e) {
      if (rejectReason != null) {
        return;
      }
      throw e;
    }
  }

}
