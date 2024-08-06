/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.util.Arrays;
import java.util.Objects;

public final class PgStructDescriptor {

  private final String sqlTypeName;
  private final PgAttribute[] pgAttributes;

  public PgStructDescriptor(String sqlTypeName, PgAttribute[] fields) {
    this.sqlTypeName = sqlTypeName;
    this.pgAttributes = fields;
  }

  public String sqlTypeName() {
    return sqlTypeName;
  }

  public PgAttribute[] pgAttributes() {
    return pgAttributes;
  }

  @Override
  public String toString() {
    return "PgStructDescriptor{"
        + "sqlTypeName='" + sqlTypeName + '\''
        + ", pgAttributes=" + Arrays.toString(pgAttributes)
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    PgStructDescriptor that = (PgStructDescriptor) o;
    return Objects.equals(sqlTypeName, that.sqlTypeName) && Objects.deepEquals(pgAttributes, that.pgAttributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sqlTypeName, Arrays.hashCode(pgAttributes));
  }
}
