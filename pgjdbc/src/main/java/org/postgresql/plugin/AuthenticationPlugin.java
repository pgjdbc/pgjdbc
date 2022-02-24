/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.plugin;

import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;

public interface AuthenticationPlugin {

  /**
   * Callback method to provide the password to use for authentication.
   *
   * <p>Implementers can also check the authentication type to ensure that the
   * authentication handshake is using a specific authentication method (e.g. SASL)
   * or avoiding a specific one (e.g. cleartext).</p>
   *
   * <p>For security reasons, the driver will wipe the contents of the array returned
   * by this method after it has been used for authentication.</p>
   *
   * <p><b>Implementers must provide a new array each time this method is invoked as
   * the previous contents will have been wiped.</b></p>
   *
   * @param type The authentication method that the server is requesting
   * @return The password to use or null if no password is available
   * @throws PSQLException if something goes wrong supplying the password
   */
  char @Nullable [] getPassword(AuthenticationRequestType type) throws PSQLException;

}
