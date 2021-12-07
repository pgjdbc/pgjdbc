/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

public class KerberosTicket {

  static {
    Configuration.setConfiguration(new CustomKrbConfig());
  }

  private static final String CONFIG_ITEM_NAME = "ticketCache";
  private static final String KRBLOGIN_MODULE = "com.sun.security.auth.module.Krb5LoginModule";

  static class CustomKrbConfig extends Configuration {

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

  public static boolean credentialCacheExists() {
    LoginContext lc = null;
    try {
      lc = new LoginContext(CONFIG_ITEM_NAME, new CallbackHandler() {

        @Override
        public void handle(Callback[] callbacks)
            throws IOException, UnsupportedCallbackException {
          // config has doNotPrompt, so it should never happen
          throw new RuntimeException("Should not happen!");
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
