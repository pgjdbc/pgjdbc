/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import org.postgresql.PGProperty;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Masks credentials in a JDBC URL, so that the URL can be logged or put into an error message.
 *
 * <p>The masking knows only the syntax of the {@code jdbc:postgresql:} URL, so callers must not
 * pass a URL meant for another driver.</p>
 */
public final class JdbcUrlMasker {

  private static final String MASKED_VALUE = "***";
  private static final Set<String> SENSITIVE_NAMES = new HashSet<>();

  static {
    for (PGProperty property : PGProperty.values()) {
      if (property.isSensitive()) {
        SENSITIVE_NAMES.add(property.getName().toLowerCase(Locale.ROOT));
      }
    }
  }

  private JdbcUrlMasker() {
    // prevent instantiation of static helper class
  }

  /**
   * Replaces the value of every {@link PGProperty#isSensitive() sensitive} parameter in the query
   * string with {@code ***}, matching the name in any case: the driver ignores {@code Password=},
   * but its value is still a password. An empty value is kept, as {@code password=} discloses
   * nothing and turns off the {@code .pgpass} lookup. The rest of the URL is returned as is.
   *
   * @param url JDBC URL, possibly carrying credentials in its query string
   * @return the URL with the values of its sensitive parameters masked
   */
  public static String maskSensitiveValues(String url) {
    int qPos = url.indexOf('?');
    if (qPos == -1) {
      return url;
    }
    StringBuilder result = new StringBuilder(url.length());
    result.append(url, 0, qPos + 1);
    // Split on "&" as Driver.parseURL does, but keep empty parameters, so the URL is rebuilt as is
    String[] args = url.substring(qPos + 1).split("&", -1);
    for (int i = 0; i < args.length; i++) {
      if (i > 0) {
        result.append('&');
      }
      String arg = args[i];
      int eqPos = arg.indexOf('=');
      if (eqPos == -1) {
        result.append(arg);
        continue;
      }
      String name = arg.substring(0, eqPos);
      if (SENSITIVE_NAMES.contains(name.toLowerCase(Locale.ROOT)) && eqPos < arg.length() - 1) {
        result.append(name).append('=').append(MASKED_VALUE);
      } else {
        result.append(arg);
      }
    }
    return result.toString();
  }
}
