/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.sql.SQLException;

/**
 * Enum representing the supported PostgreSQL protocol versions.
 */
public enum ProtocolVersion {
  /**
   * Protocol version 3.0
   */
  v3_0(3, 0),

  /**
   * Protocol version 3.2
   */
  v3_2(3, 2);

  private final int major;
  private final int minor;

  private static ProtocolVersion[] values = values();

  ProtocolVersion(int major, int minor) {
    this.major = major;
    this.minor = minor;
  }

  /**
   * @param major (int): The major version number of the protocol.
   * @param minor (int): The minor version number of the protocol.
   * @return A `ProtocolVersion` enum value representing the specified protocol version.
   * @throws SQLException if the requested protocol version is not supported.
   *
   *      Performs a simple validation check to ensure that only supported protocol versions are used.
   *      Currently, the PostgreSQL JDBC driver only supports protocol versions 3.0 and 3.2.
   */
  public static ProtocolVersion fromMajorMinor(int major, int minor) throws SQLException {
    for (ProtocolVersion version : values) {
      if (version.major == major && version.minor == minor) {
        return version;
      }
    }
    throw new SQLException(String.format("Invalid version number major: %d, minor: %d",
        major, minor));
  }

  /**
   * Gets the major version number.
   *
   * @return the major version number
   */
  public int getMajor() {
    return major;
  }

  /**
   * Gets the minor version number.
   *
   * @return the minor version number
   */
  public int getMinor() {
    return minor;
  }

  @Override
  public String toString() {
    return major + "." + minor;
  }
}
