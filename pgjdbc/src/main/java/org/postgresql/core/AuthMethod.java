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

  public static @Nullable EnumSet<AuthMethod> parseRequireAuth(@Nullable String requireAuth) throws PSQLException {
    if (requireAuth == null) {
      return null;
    }

    EnumSet<AuthMethod> allowedMethods;
    EnumSet<AuthMethod> seenMethods = EnumSet.noneOf(AuthMethod.class);
    String[] methods = requireAuth.split(",");
    boolean isDisallowMode = methods.length > 0 && methods[0].trim().startsWith("!");

    allowedMethods = isDisallowMode ? EnumSet.allOf(AuthMethod.class) : EnumSet.noneOf(AuthMethod.class);

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
    return allowedMethods.isEmpty()?null:allowedMethods;
  }

  public static void checkAuth(@Nullable EnumSet<AuthMethod> allowedMethods, AuthMethod authMethod) throws PSQLException {
    if (allowedMethods == null) {
      return;
    }
    if (!allowedMethods.contains(authMethod)) {
      throw new PSQLException(GT.tr("Authentication method is not allowed by requireAuth"), PSQLState.CONNECTION_REJECTED);
    }
  }
}
