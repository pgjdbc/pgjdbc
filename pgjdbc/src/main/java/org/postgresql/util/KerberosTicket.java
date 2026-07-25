/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

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

  private static final String CONFIG_ITEM_NAME = "ticketCache";
  private static final String KRBLOGIN_MODULE = "com.sun.security.auth.module.Krb5LoginModule";

  static class CustomKrbConfig extends Configuration {

    @SuppressWarnings("nullness")
    @Override
    public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
      if (CONFIG_ITEM_NAME.equals(name)) {
        Map<String, String> options = new HashMap<>();
        options.put("refreshKrb5Config", String.valueOf(false));
        options.put("useTicketCache", String.valueOf(true));
        options.put("doNotPrompt", String.valueOf(true));
        options.put("useKeyTab", String.valueOf(true));
        options.put("isInitiator", String.valueOf(false));
        options.put("renewTGT", String.valueOf(false));
        options.put("debug", String.valueOf(false));
        return new AppConfigurationEntry[]{
            new AppConfigurationEntry(KRBLOGIN_MODULE,
                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options)};
      }
      return null;
    }

  }

  public static boolean credentialCacheExists(Properties info) {
    LoginContext lc = null;

    // in the event that the user has specified a jaas.conf file then we want to remember it
    Configuration existingConfiguration = Configuration.getConfiguration();
    Configuration.setConfiguration(new CustomKrbConfig());

    try {
      lc = new LoginContext(CONFIG_ITEM_NAME, new CallbackHandler() {

        @Override
        public void handle(Callback[] callbacks)
            throws IOException, UnsupportedCallbackException {
          // if the user has not configured jaasLogin correctly this can happen
          throw new RuntimeException("This is an error, you should set doNotPrompt to false in jaas.config");
        }
      });
      lc.login();
    } catch (LoginException e) {
      // restore saved configuration
      if (existingConfiguration != null ) {
        Configuration.setConfiguration(existingConfiguration);
      }
      return false;
    }
    // restore saved configuration
    if (existingConfiguration != null ) {
      Configuration.setConfiguration(existingConfiguration);
    }
    Subject sub = lc.getSubject();
    return sub != null;
  }
}
