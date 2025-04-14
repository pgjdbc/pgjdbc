package org.postgresql.core;

public class ProtocolVersion {
  private final int major;
  private final int minor;

  public ProtocolVersion (int major, int minor) {
    this.major = major;
    this.minor = minor;
  }
  public ProtocolVersion(int protocol) {
    this.minor = protocol & 0xff;
    this.major = (protocol >> 16 ) & 0xff;
  }
  public boolean checkVersion(int major, int minor) {
    return this.major == major && this.minor == minor;
  }
}
