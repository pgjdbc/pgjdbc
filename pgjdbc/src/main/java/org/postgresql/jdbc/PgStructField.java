/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

public class PgStructField {
  private final String name;
  private final String typeName;
  private final int oid;

  public PgStructField(String name, String typeName, int dataType) {
    this.name = name;
    this.typeName = typeName;
    this.oid = dataType;
  }

  public String getName() {
    return name;
  }

  public String getTypeName() {
    return typeName;
  }

  public int getOID() {
    return oid;
  }
}
