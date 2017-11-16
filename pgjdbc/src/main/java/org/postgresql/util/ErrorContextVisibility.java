/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

/**
 * Property that handles {@link ServerErrorMessage} context display mode. This mode controls whether
 * the CONTEXT field is included in messages. The NEVER mode never includes CONTEXT, while ALWAYS
 * always includes it if available. In ERRORS mode (the default), CONTEXT fields are included only
 * for error messages, not for notices and warnings.
 *
 * @see org.postgresql.PGProperty#ERROR_CONTEXT_VISIBILITY
 *
 * @author jsolorzano
 */
public enum ErrorContextVisibility {

  NEVER("never"),
  ERRORS("errors"),
  ALWAYS("always");

  private final String value;

  ErrorContextVisibility(String value) {
    this.value = value;
  }

  public static ErrorContextVisibility of(String visibility) {
    for (ErrorContextVisibility errorContextVisibility : values()) {
      if (errorContextVisibility.value.equals(visibility)) {
        return errorContextVisibility;
      }
    }
    return ERRORS;
  }

  public String value() {
    return value;
  }
}
