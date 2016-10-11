/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

public enum AutoSave {
  NEVER,
  ALWAYS,
  CONSERVATIVE;

  private final String value;

  AutoSave() {
    value = this.name().toLowerCase();
  }

  public String value() {
    return value;
  }

  public static AutoSave of(String value) {
    return valueOf(value.toUpperCase());
  }
}
