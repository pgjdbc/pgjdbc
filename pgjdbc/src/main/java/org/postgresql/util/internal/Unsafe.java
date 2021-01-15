/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

/**
 * This class is excluded from {@code forbidden-apis} check.
 * @see <a href="https://github.com/policeman-tools/forbidden-apis/issues/82">Extend
 * suppression configuration to be more fine grained</a>
 */
public class Unsafe {
  public static boolean credentialCacheExists() {
    try {
      @SuppressWarnings({"nullness"})
      sun.security.krb5.Credentials credentials =
          sun.security.krb5.Credentials.acquireTGTFromCache(null, null);
      return credentials != null;
    } catch (Exception ex) {
      return false;
    }
  }
}
