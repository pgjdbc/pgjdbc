/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.util.Objects;

public final class PgAttribute {
  private final String name;
  private final String typeName;
  private final int oid;

  public PgAttribute(String name, String typeName, int oid) {
    this.name = name;
    this.typeName = typeName;
    this.oid = oid;
  }

  public String name() {
    return name;
  }

  public String typeName() {
    return typeName;
  }

  public int oid() {
    return oid;
  }

  @Override
  public String toString() {
    return "PgAttribute{"
        + "name='" + name + '\''
        + ", typeName='" + typeName + '\''
        + ", oid=" + oid
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PgAttribute)) {
      return false;
    }
    PgAttribute that = (PgAttribute) o;
    return oid == that.oid && Objects.equals(name, that.name) && Objects.equals(typeName, that.typeName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, typeName, oid);
  }
}
