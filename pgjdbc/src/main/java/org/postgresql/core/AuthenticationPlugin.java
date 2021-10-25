/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.PSQLException;

import java.util.Properties;

public interface AuthenticationPlugin {

  byte[] getEncodedPassword(Properties info) throws PSQLException, PSQLException;

}
