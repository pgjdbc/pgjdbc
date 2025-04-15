/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

/**
 * Utility class providing static instances of commonly used PostgreSQL protocol versions.
 */
public final class ProtocolVersions {

  /**
   * Protocol version 3.0
   */
  public static final ProtocolVersion VERSION_3_0 = new ProtocolVersion(3, 0);

  /**
   * Protocol version 3.2
   */
  public static final ProtocolVersion VERSION_3_2 = new ProtocolVersion(3, 2);

  // Private constructor to prevent instantiation
  private ProtocolVersions() {
    // Not meant to be instantiated
  }
}
