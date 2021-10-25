/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGProperty;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AuthenticationPluginManager {

  private static final Logger LOGGER = Logger.getLogger(AuthenticationPluginManager.class.getName());

  public static AuthenticationPlugin getAuthenticationPlugin(Properties info) throws Exception {
    String authenticationClassName = PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.getSetString(info);

    if ( authenticationClassName == null ) {
      return new PasswordAuthentication();
    } else {
      try {
        return (AuthenticationPlugin) Class.forName(authenticationClassName).getDeclaredConstructor().newInstance();
      } catch (Exception ex ) {
        LOGGER.log(Level.FINE, "Unable to load Authentication Plugin" + ex.getMessage() );
        throw ex;
      }
    }
  }
}
