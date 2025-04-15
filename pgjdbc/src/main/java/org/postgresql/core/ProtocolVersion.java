package org.postgresql.core;

import java.util.Objects;

public class ProtocolVersion {
  private final int major;
  private final int minor;

  private ProtocolVersion() {
    // not meant to be instantiated
    major=0;
    minor=0;
  }

  public ProtocolVersion (int major, int minor) {
    this.major = major;
    this.minor = minor;
  }

  public ProtocolVersion(int protocol) {
    this.minor = protocol & 0xff;
    this.major = (protocol >> 16 ) & 0xff;
  }

  @Override
  public String toString() {
    return ""+major+"."+minor;
  }

  @Override
  public boolean equals(Object o) {
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
}
