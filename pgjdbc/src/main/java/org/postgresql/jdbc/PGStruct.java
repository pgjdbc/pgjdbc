/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
package org.postgresql.jdbc;

import org.postgresql.types.CompositeType;
import org.postgresql.types.Type;

import java.sql.Struct;
import java.sql.SQLException;

import java.util.Map;

public class PGStruct implements Struct {

  protected PgConnection context;
  protected Type[] attributeTypes;
  protected CompositeType typeName;

  PGStruct(PgConnection context, CompositeType typeName, Object[] attributeTypes) {
    this.context = context;
    this.typeName = typeName;
    this.attributeTypes = (Type[]) attributeTypes;
  }

  public Type[] getAttributeTypes() {
    return attributeTypes;
  }

  public String getSQLTypeName() {
    return String.valueOf(typeName);
  }

  public Object[] getAttributes() throws SQLException {
    return attributeTypes;
  }

  public Object[] getAttributes(Map<String, Class<?>> typeMap) throws SQLException {
    try {
      // Convert the map of attributes to an array
      Object[] attributes = typeMap.values().toArray();
      // Return the array of attributes
      return attributes;
    }
    catch (Exception e) {
      throw new SQLException(e);
    }
  }

  // Does this need to be included?
  // public Object[] getAttributes(Context context) throws IOException;
}
