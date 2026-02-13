/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.File;
import java.util.Locale;

/**
 * Operating system specifics
 */
public class OSUtil {

  private static final String PG_SERVICE_CONF = "pg_service.conf";
  private static final String PGPASS = "pgpass";

  /**
   * @return true if OS is windows
   */
  public static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
  }

  /**
   *
   * @return OS specific root directory for user specific configurations
   */
  public static String getUserConfigRootDirectory() {
    if (isWindows()) {
      return System.getenv("APPDATA") + File.separator + "postgresql";
    } else {
      return System.getProperty("user.home");
    }
  }

  /**
   * @param directory directory
   * @return file
   */
  public static String getDefaultPgServiceFilename(String directory) {
    return directory + File.separator + PG_SERVICE_CONF;
  }

  /**
   * default location: `$HOME/.pg_service.conf` or `%APPDATA%\postgresql\.pg_service.conf`
   *
   * @return file
   */
  public static String getDefaultPgServiceFilename() {
    return getUserConfigRootDirectory() + File.separator + "." + PG_SERVICE_CONF;
  }

  /**
   * @param directory directory
   * @return file
   */
  public static String getDefaultPgPassFilename(String directory) {
    if (isWindows()) {
      return directory + File.separator + PGPASS + ".conf";
    } else {
      return directory + File.separator + "." + PGPASS;
    }
  }

  /**
   * default location: `$HOME/.pgpass` or `%APPDATA%\postgresql\pgpass.conf`
   *
   * @return file
   */
  public static String getDefaultPgPassFilename() {
    return getDefaultPgPassFilename(getUserConfigRootDirectory());
  }

}
