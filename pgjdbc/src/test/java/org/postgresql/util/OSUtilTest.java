/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.resource.Resources;

import java.io.File;

@ExtendWith(SystemStubsExtension.class)
class OSUtilTest {

  @Test
  void getUserConfigRootDirectory() throws Exception {
    // windows
    Resources.with(new EnvironmentVariables("APPDATA", "C:\\Users\\realuser\\AppData\\Roaming"),
        new SystemProperties("os.name", "Windows 10")).execute(() -> {
          String result = OSUtil.getUserConfigRootDirectory();
          assertEquals("C:\\Users\\realuser\\AppData\\Roaming" + File.separator + "postgresql", result);
        }
    );
    // linux
    Resources.with(new SystemProperties("os.name", "Linux", "user.home", "/home/realuser")).execute(() -> {
          String result = OSUtil.getUserConfigRootDirectory();
          assertEquals("/home/realuser", result);
        }
    );
  }
}
