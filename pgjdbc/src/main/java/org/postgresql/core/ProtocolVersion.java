/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Objects;

public class ProtocolVersion {
  private final int major;
  private final int minor;

  private ProtocolVersion() {
    // not meant to be instantiated
    major = 0;
    minor = 0;
  }

  public ProtocolVersion(int major, int minor) {
    this.major = major;
    this.minor = minor;
  }

  public ProtocolVersion(int protocol) {
    this.minor = protocol & 0xff;
    this.major = (protocol >> 16 ) & 0xff;
  }

  @Override
  public String toString() {
    return "" + major + "." + minor;
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ProtocolVersion that = (ProtocolVersion) o;
    return major == that.major && minor == that.minor;
  }

  @Override
  public int hashCode() {
    return Objects.hash(major, minor);
  }

  /**
   * @param major (int): The major version number of the protocol.
   * @param minor (int): The minor version number of the protocol.
   * @return A `ProtocolVersion` instance representing the specified protocol version.
   * @throws SQLException
   *
   *      Performs a simple validation check to ensure that only supported protocol versions are used.
   *      Currently, the PostgreSQL JDBC driver only supports protocol versions 3.0 and 3.2.
   *      When a supported version is requested, the method returns the corresponding
   *      pre-defined constant (`V_3_0` or `V_3_2`) rather than creating a new instance,
   *      following the flyweight pattern.
   */
  public static ProtocolVersion from(int major, int minor) throws SQLException {
    // we only support version 3.0 and 3.2
    if ( major == 3 ) {
      switch (minor) {
        case 0:
          return V_3_0;
        case 2:
          return V_3_2;
      }
    }
    throw new SQLException(String.format("Invalid version number major: %d, minor: %d",
        major, minor));
  }

  /**
   * Protocol version 3.0
   */
  public static final ProtocolVersion V_3_0 = new ProtocolVersion(3, 0);

  /**
   * Protocol version 3.2
   */
  public static final ProtocolVersion V_3_2 = new ProtocolVersion(3, 2);

}
