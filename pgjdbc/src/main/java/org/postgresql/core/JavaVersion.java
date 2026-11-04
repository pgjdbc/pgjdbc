/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

public enum JavaVersion {
  // Constants follow release order: TimestampUtils compares versions with compareTo
  v1_8,
  other;

  private static final JavaVersion RUNTIME_VERSION = from(System.getProperty("java.version"));

  /**
   * Returns the enum value for the JVM the driver runs on, which can be newer than the version the
   * driver was compiled for.
   */
  public static JavaVersion getRuntimeVersion() {
    return RUNTIME_VERSION;
  }

  /**
   * Maps a Java version string to the enum value it belongs to.
   *
   * @param version value of the {@code "java.version"} system property, such as {@code 1.8.0_452}
   *     or {@code 17.0.9}
   * @return {@link #v1_8} for a Java 8 version string, {@link #other} for every other value
   */
  public static JavaVersion from(String version) {
    if (version.startsWith("1.8")) {
      return v1_8;
    }
    return other;
  }
}
