/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGProperty;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

public class KerberosTicket {

  private static final String CONFIG_ITEM_NAME = "pgjdbc";
  private static final String KRBLOGIN_MODULE = "com.sun.security.auth.module.Krb5LoginModule";

  static class CustomKrbConfig extends Configuration {

    @SuppressWarnings("nullness")
    @Override
    public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
      if (CONFIG_ITEM_NAME.equals(name)) {
        Map<String, String> options = new HashMap<>();
        options.put("refreshKrb5Config", Boolean.FALSE.toString());
        options.put("useTicketCache", Boolean.TRUE.toString());
        options.put("doNotPrompt", Boolean.TRUE.toString());
        options.put("useKeyTab", Boolean.TRUE.toString());
        options.put("isInitiator", Boolean.FALSE.toString());
        options.put("renewTGT", Boolean.FALSE.toString());
        options.put("debug", Boolean.FALSE.toString());
        return new AppConfigurationEntry[] {
            new AppConfigurationEntry(KRBLOGIN_MODULE,
                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options) };
      }
      return null;
    }

  }

  public static boolean credentialCacheExists( Properties info ) {
    LoginContext lc = null;

    /* check to see if the user has created a valid jaas.config file */
    String jaasApplicationName = PGProperty.JAAS_APPLICATION_NAME.getSetString(info);
    if ( jaasApplicationName != null ) {
      Configuration configuration = Configuration.getConfiguration();
      AppConfigurationEntry[] entries = configuration.getAppConfigurationEntry(jaasApplicationName);

      /* is there an entry for this name ? */
      if ( entries == null ) {
        jaasApplicationName = PGProperty.JAAS_APPLICATION_NAME.getDefaultValue();
        Configuration.setConfiguration(new CustomKrbConfig());
      } else {
        Configuration.setConfiguration(new CustomKrbConfig());
      }
    } else {
      jaasApplicationName = PGProperty.JAAS_APPLICATION_NAME.getDefaultValue();
      Configuration.setConfiguration(new CustomKrbConfig());
    }

    try {
      lc = new LoginContext(castNonNull(jaasApplicationName), new CallbackHandler() {

        @Override
        public void handle(Callback[] callbacks)
            throws IOException, UnsupportedCallbackException {
          // if the user has not configured jaasLogin correctly this can happen
          throw new RuntimeException("This is an error, you should set doNotPrompt to false in jaas.config");
        }

      });
      lc.login();
    } catch (LoginException e) {
      return false;
    }
    Subject sub = lc.getSubject();
    return sub != null;
  }
}
