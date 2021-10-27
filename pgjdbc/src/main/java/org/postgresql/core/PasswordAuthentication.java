/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGProperty;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class PasswordAuthentication implements AuthenticationPlugin {

  public PasswordAuthentication(Properties info) {
    // ignore we don't need it.
  }

  @Override
  public byte[] getEncodedPassword(Properties info) throws PSQLException {
    String password = PGProperty.PASSWORD.getSetString(info);

    if (password == null) {
      throw new PSQLException(
          GT.tr(
              "The server requested password-based authentication, but no password was provided."),
          PSQLState.CONNECTION_REJECTED);
    }

    return password.getBytes(StandardCharsets.UTF_8);
  }
}
