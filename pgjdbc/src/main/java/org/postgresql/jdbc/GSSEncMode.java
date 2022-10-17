/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.PGProperty;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.util.Properties;

public enum GSSEncMode {

  /**
   * Do not use encrypted connections.
   */
  DISABLE("disable"),

  /**
   * Start with non-encrypted connection, then try encrypted one.
   */
  ALLOW("allow"),

  /**
   * Start with encrypted connection, fallback to non-encrypted (default).
   */
  PREFER("prefer"),

  /**
   * Ensure connection is encrypted.
   */
  REQUIRE("require");

  private static final GSSEncMode[] VALUES = values();

  public final String value;

  GSSEncMode(String value) {
    this.value = value;
  }

  public boolean requireEncryption() {
    return this.compareTo(REQUIRE) >= 0;
  }

  public static GSSEncMode of(Properties info) throws PSQLException {
    String gssEncMode = PGProperty.GSS_ENC_MODE.get(info);
    // If gssEncMode is not set, fallback to allow
    if (gssEncMode == null) {
      return ALLOW;
    }

    for (GSSEncMode mode : VALUES) {
      if (mode.value.equalsIgnoreCase(gssEncMode)) {
        return mode;
      }
    }
    throw new PSQLException(GT.tr("Invalid gssEncMode value: {0}", gssEncMode),
        PSQLState.CONNECTION_UNABLE_TO_CONNECT);
  }

}
