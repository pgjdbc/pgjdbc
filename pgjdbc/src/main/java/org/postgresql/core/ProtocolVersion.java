/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

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
   * Protocol version 3.0
   */
  public static final ProtocolVersion V_3_0 = new ProtocolVersion(3, 0);

  /**
   * Protocol version 3.2
   */
  public static final ProtocolVersion V_3_2 = new ProtocolVersion(3, 2);

}
