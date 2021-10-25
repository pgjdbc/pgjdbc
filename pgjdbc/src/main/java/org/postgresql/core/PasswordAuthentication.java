/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.nio.charset.StandardCharsets;

public class PasswordAuthentication implements AuthenticationPlugin {

  @Override
  public byte[] getEncodedPassword(String userName, String password) {
    return password.getBytes(StandardCharsets.UTF_8);
  }
}
