/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import org.postgresql.PGProperty;
import org.postgresql.util.PGPropertyUtil;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Masks credentials in a JDBC URL, so that the URL can be logged or put into an error message.
 *
 * <p>The methods know only the syntax of the {@code jdbc:postgresql:} URL, so callers must not
 * pass them a URL meant for another driver.</p>
 */
public final class JdbcUrlMasker {

  private static final String URL_PREFIX = "jdbc:postgresql:";
  private static final String AUTHORITY_PREFIX = URL_PREFIX + "//";
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
   * nothing and turns off the {@code .pgpass} lookup. The rest of the URL is returned as is, so
   * this suits only a URL without {@code user:password@}, such as the one a data source builds. Use
   * {@link #maskUserSuppliedUrl(String)} for a URL as the user wrote it.
   *
   * @param url JDBC URL without {@code user:password@}
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

  /**
   * Masks a URL as the user wrote it, accepted or not. It may hold {@code user:password@} even when
   * {@code Driver.parseURL} accepts it: as in libpq, a password with a raw {@code /}, as in
   * {@code user:1234/word@host}, reads as a port and a database name.
   *
   * <p>Everything after {@code jdbc:postgresql:} or {@code jdbc:postgresql://} up to the {@code @}
   * that may end a password is replaced with {@code ***}: the one {@link #findUserInfoEnd(String)}
   * finds, or else the last {@code @}, when an {@code @} is before the first {@code ?}, in a
   * parameter name, or in the value of an unknown parameter. So a raw {@code @} in a database name
   * masks the host too, while {@code user=name@server} is kept. A password that ends with a known
   * parameter, as in {@code user:pass/word?user=b@host}, is not masked. Sensitive values are masked
   * as {@link #maskSensitiveValues(String)} does.</p>
   *
   * @param url JDBC URL as the user wrote it
   * @return the URL with everything that may be a credential masked
   */
  public static String maskUserSuppliedUrl(String url) {
    // Cut before masking values: a "?" in the password may put that "@" inside a masked value
    int userInfoEnd = findUserInfoEnd(url);
    if (userInfoEnd != -1) {
      return AUTHORITY_PREFIX + MASKED_VALUE + maskSensitiveValues(url.substring(userInfoEnd));
    }
    // Mask values before and after the cut: a password may follow the last "@" in the query string,
    // and a "?" in the password may have started the query string too early
    String masked = maskSensitiveValues(url);
    if (!masked.startsWith(URL_PREFIX)) {
      return masked;
    }
    int userInfoStart = masked.startsWith(AUTHORITY_PREFIX)
        ? AUTHORITY_PREFIX.length()
        : URL_PREFIX.length();
    int at = masked.lastIndexOf('@');
    if (at < userInfoStart || !mayEndUserInfo(masked)) {
      return masked;
    }
    return masked.substring(0, userInfoStart) + MASKED_VALUE
        + maskSensitiveValues(masked.substring(at));
  }

  /**
   * Returns the position of the {@code @} that ends {@code user:password@} in a
   * {@code jdbc:postgresql://} URL, or {@code -1}. As in libpq, that is an {@code @} before the
   * first {@code /}, even after a {@code ?}; the last one is taken, as the password may hold
   * {@code @}.
   *
   * @param url JDBC URL as the user wrote it
   * @return the position of the {@code @}, or {@code -1}
   */
  public static int findUserInfoEnd(String url) {
    if (!url.startsWith(AUTHORITY_PREFIX)) {
      return -1;
    }
    int slash = url.indexOf('/', AUTHORITY_PREFIX.length());
    int at = url.lastIndexOf('@', (slash == -1 ? url.length() : slash) - 1);
    return at < AUTHORITY_PREFIX.length() ? -1 : at;
  }

  /**
   * Returns whether an {@code @} in the URL may end a {@code user:password@} part: one before the
   * first {@code ?}, in a parameter name, or in the value of a parameter the driver does not know.
   */
  private static boolean mayEndUserInfo(String url) {
    int qPos = url.indexOf('?');
    if (qPos == -1 || url.lastIndexOf('@', qPos) != -1) {
      return true;
    }
    for (String arg : url.substring(qPos + 1).split("&")) {
      int eqPos = arg.indexOf('=');
      String name = eqPos == -1 ? arg : arg.substring(0, eqPos);
      if (name.indexOf('@') != -1) {
        return true;
      }
      if (eqPos != -1 && arg.indexOf('@', eqPos) != -1
          && PGProperty.forName(PGPropertyUtil.translatePGServiceToPGProperty(name)) == null) {
        return true;
      }
    }
    return false;
  }
}
