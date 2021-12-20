/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGProperty;
import org.postgresql.plugin.AuthenticationPlugin;
import org.postgresql.plugin.AuthenticationRequestType;
import org.postgresql.util.GT;
import org.postgresql.util.ObjectFactory;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AuthenticationPluginManager {
  private static final Logger LOGGER = Logger.getLogger(AuthenticationPluginManager.class.getName());

  private AuthenticationPluginManager() {
  }

  /**
   * If a password is requested by the server during connection initiation, this
   * method will be invoked to supply the password. This method will only be
   * invoked if the server actually requests a password, e.g. trust authentication
   * will skip it entirely.
   *
   * @param type The authentication type that is being requested
   * @param info The connection properties for the connection
   * @return The password to use for authentication or null if none is available
   * @throws PSQLException Throws a PSQLException if the plugin class cannot be instantiated
   */
  public static @Nullable String getPassword(AuthenticationRequestType type, Properties info) throws PSQLException {
    String authPluginClassName = PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.get(info);

    if (authPluginClassName == null || authPluginClassName.equals("")) {
      // Default auth plugin simply pulls password directly from connection properties
      return PGProperty.PASSWORD.get(info);
    }

    AuthenticationPlugin authPlugin;
    try {
      authPlugin = (AuthenticationPlugin) ObjectFactory.instantiate(authPluginClassName, info,
          false, null);
    } catch (Exception ex) {
      LOGGER.log(Level.FINE, "Unable to load Authentication Plugin " + ex.toString());
      throw new PSQLException(ex.getMessage(), PSQLState.UNEXPECTED_ERROR);
    }
    return authPlugin.getPassword(type);
  }

  /**
   * Helper that wraps getPassword(...), checks that it is not-null, and encodes
   * it as a byte array. Used by internal code paths that require an encoded password that may be an
   * empty string, but not null.
   *
   * @param type The authentication type that is being requested
   * @param info The connection properties for the connection
   * @return The password to use for authentication encoded as a byte array
   * @throws PSQLException Throws a PSQLException if the plugin class cannot be instantiated or if the retrieved password is null.
   */
  public static byte[] getEncodedPassword(AuthenticationRequestType type, Properties info) throws PSQLException {
    String password = getPassword(type, info);

    if (password == null) {
      throw new PSQLException(
          GT.tr("The server requested password-based authentication, but no password was provided."),
          PSQLState.CONNECTION_REJECTED);
    }

    return password.getBytes(StandardCharsets.UTF_8);
  }
}
