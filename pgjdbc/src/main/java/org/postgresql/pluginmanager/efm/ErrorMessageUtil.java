/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

/**
 * Utility class constructing messages for null arguments.
 */
public class ErrorMessageUtil {

  /**
   * Return a message indicating the given {@code paramName} is null.
   *
   * @param paramName The name of the parameter that is null.
   * @return a message the parameter passed to the caller method is null.
   */
  public static String getMessage(String paramName) {
    return String.format("Parameter ''%s'' must not be null", paramName);
  }
}
