/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

public interface AuthenticationPlugin {
  byte[] getEncodedPassword(String userName, String password);
}
