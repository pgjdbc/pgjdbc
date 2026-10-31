/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.EnumSet;

/**
 * Enumeration of PostgreSQL authentication methods.
 */
public enum AuthMethod {
  NONE, PASSWORD, MD5, GSS, SSPI, SCRAM_SHA_256;

  public static AuthMethod fromString(String method) throws PSQLException {
    switch (method) {
      case "none": return NONE;
      case "password": return PASSWORD;
      case "md5": return MD5;
      case "gss": return GSS;
      case "sspi": return SSPI;
      case "scram-sha-256": return SCRAM_SHA_256;
      default: throw new PSQLException(GT.tr("Invalid authentication method: {0}", method), PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  /**
   * Parses a {@code requireAuth} value into the authentication methods the
   * server may request.
   *
   * @param requireAuth comma-separated method names, either all plain or all
   *     prefixed with {@code !}
   * @return {@code null} when {@code requireAuth} is {@code null}, and
   *     {@link #checkAuth} allows every method for {@code null}; otherwise the
   *     allowed methods, empty when the value excludes every method
   * @throws PSQLException with {@link PSQLState#INVALID_PARAMETER_VALUE} if
   *     the value mixes the two forms, names a method twice, names an unknown
   *     method, or is written without a method
   */
  public static @Nullable EnumSet<AuthMethod> parseRequireAuth(@Nullable String requireAuth) throws PSQLException {
    if (requireAuth == null) {
      return null;
    }

    EnumSet<AuthMethod> seenMethods = EnumSet.noneOf(AuthMethod.class);
    String[] methods = requireAuth.split(",");
    // split drops empty strings at the end, so "," and ",," give an empty
    // array. A value without a method in it names neither methods to allow
    // nor methods to exclude. It is a mistake, not a request to refuse every
    // method, so it fails as an invalid value.
    if (methods.length == 0) {
      throw new PSQLException(
          GT.tr("Invalid authentication method: {0}", requireAuth),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    boolean isDisallowMode = methods[0].trim().startsWith("!");

    EnumSet<AuthMethod> allowedMethods = isDisallowMode
        ? EnumSet.allOf(AuthMethod.class)
        : EnumSet.noneOf(AuthMethod.class);

    for (String method : methods) {
      method = method.trim();
      boolean isNegative = method.startsWith("!");

      if (isNegative != isDisallowMode) {
        throw new PSQLException(GT.tr("requireAuth cannot mix positive and negative authentication methods"), PSQLState.INVALID_PARAMETER_VALUE);
      }

      AuthMethod authMethod = fromString(isNegative ? method.substring(1) : method);
      if (!seenMethods.add(authMethod)) {
        throw new PSQLException(GT.tr("requireAuth contains duplicate authentication method"), PSQLState.INVALID_PARAMETER_VALUE);
      }

      if (isDisallowMode) {
        allowedMethods.remove(authMethod);
      } else {
        allowedMethods.add(authMethod);
      }
    }
    // Return an empty set, not null. checkAuth allows every method for null,
    // so null here would let the server pick any method.
    return allowedMethods;
  }

  /**
   * Refuses an authentication method that {@code requireAuth} does not allow.
   *
   * @param allowedMethods the methods to allow, or {@code null} to allow
   *     every method; an empty set refuses every method
   * @param authMethod the method the server asked for
   * @throws PSQLException with {@link PSQLState#CONNECTION_REJECTED} if the
   *     method is not allowed
   */
  public static void checkAuth(@Nullable EnumSet<AuthMethod> allowedMethods, AuthMethod authMethod) throws PSQLException {
    if (allowedMethods == null) {
      return;
    }
    if (!allowedMethods.contains(authMethod)) {
      throw new PSQLException(GT.tr("Authentication method is not allowed by requireAuth"), PSQLState.CONNECTION_REJECTED);
    }
  }

  /**
   * Refuses an AuthenticationOk that {@code requireAuth} does not allow. Every
   * request the server sent was checked by {@link #checkAuth} as it arrived,
   * so what is left is a server that lets the client in without a finished
   * exchange: it requested nothing, or it sent AuthenticationOk before the
   * exchange it requested was complete. libpq makes the same check on
   * AUTH_REQ_OK in {@code check_expected_areq}.
   *
   * @param allowedMethods the methods to allow, or {@code null} to allow
   *     every method; an empty set refuses every method
   * @param requested whether the server sent an authentication request
   * @param finished whether the client finished the exchange it was asked for
   * @param gssEncrypted whether the connection is GSS-encrypted
   * @throws PSQLException with {@link PSQLState#CONNECTION_REJECTED} if the
   *     connection is not allowed
   */
  public static void checkAuthCompleted(@Nullable EnumSet<AuthMethod> allowedMethods,
      boolean requested, boolean finished, boolean gssEncrypted) throws PSQLException {
    if (allowedMethods == null || finished) {
      return;
    }
    // As in libpq, this also accepts an exchange of another method that the
    // server started and did not finish.
    if (allowedMethods.contains(NONE) || (gssEncrypted && allowedMethods.contains(GSS))) {
      return;
    }
    if (requested) {
      throw new PSQLException(
          GT.tr("The server accepted the connection before the authentication exchange was "
              + "complete, which requireAuth does not allow."),
          PSQLState.CONNECTION_REJECTED);
    }
    if (gssEncrypted) {
      throw new PSQLException(
          GT.tr("The server accepted the GSS-encrypted connection without requesting further "
              + "authentication, which requireAuth does not allow. Allow ''gss'' in requireAuth "
              + "to accept the Kerberos authentication done while setting up GSS encryption, or "
              + "set gssEncMode to disable to authenticate without it."),
          PSQLState.CONNECTION_REJECTED);
    }
    throw new PSQLException(
        GT.tr("The server accepted the connection without requesting authentication, which "
            + "requireAuth does not allow. Check the pg_hba.conf entry the server matched, or "
            + "allow ''none'' in requireAuth to accept an unauthenticated connection."),
        PSQLState.CONNECTION_REJECTED);
  }
}
