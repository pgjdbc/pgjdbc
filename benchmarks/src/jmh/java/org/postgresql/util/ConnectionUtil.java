/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import java.util.Properties;

public class ConnectionUtil {
  /**
   * @return the Postgresql username
   */
  public static String getUser() {
    return TestUtil.getUser();
  }

  /**
   * @return the user's password
   */
  public static String getPassword() {
    return TestUtil.getPassword();
  }

  /**
   * @return the test server
   */
  public static String getServer() {
    return TestUtil.getServer();
  }

  /**
   * @return the test port
   */
  public static int getPort() {
    return TestUtil.getPort();
  }

  /**
   * @return the Test database
   */
  public static String getDatabase() {
    return TestUtil.getDatabase();
  }

  /**
   * @return connection url to server
   */
  public static String getURL() {
    return TestUtil.getURL();
  }

  /**
   * @return merged with default property list
   */
  public static Properties getProperties() {
    Properties properties = new Properties(System.getProperties());

    PGProperty.USER.set(properties, getUser());
    PGProperty.PASSWORD.set(properties, getPassword());
    PGProperty.PG_PORT.set(properties, getPort());
    properties.setProperty("database", getDatabase());
    properties.setProperty("server", getServer());

    return properties;
  }
}
