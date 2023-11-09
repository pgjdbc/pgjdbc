/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

/**
 *
 * @see org.postgresql.PGProperty#PLACEHOLDER_STYLE
 */
public enum PlaceholderStyle {
  ANY("any"),
  NAMED("named"),
  NATIVE("native"),
  NONE("none");

  private final String value;

  PlaceholderStyle(String value) {
    this.value = value;
  }

  public static PlaceholderStyle of(String mode) {
    for (PlaceholderStyle placeholderStyle : values()) {
      if (placeholderStyle.value.equals(mode)) {
        return placeholderStyle;
      }
    }
    return NONE;
  }

  public boolean placeholderStyleIsAccepted(PlaceholderStyle setting) {
    return setting == PlaceholderStyle.ANY || setting == this;
  }

  public String value() {
    return value;
  }
}
