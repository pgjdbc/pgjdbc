/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
package org.postgresql.jdbc;

import org.postgresql.types.CompositeType;
import org.postgresql.types.Type;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import java.nio.charset.StandardCharsets;

import java.sql.Struct;
import java.sql.SQLException;

import java.util.Map;

public abstract class PGStruct implements Struct {

  protected PgConnection context;
  protected Type[] attributeTypes;
  protected CompositeType typeName;

  PGStruct(CompositeType typeName, Object[] attributeTypes) {
    // context = null;
    super();
    this.typeName = typeName;
    this.attributeTypes = (Type[]) attributeTypes;
  }

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

  public byte[] toBytes() throws SQLException {
    try {
      ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
      DataOutputStream dataStream = new DataOutputStream(byteStream);

      for (Object attribute : attributeTypes) {
        // Convert each attribute to bytes and write to the data stream
        byte[] attributeBytes = convertAttributeToBytes(attribute);
        dataStream.writeInt(attributeBytes.length);
        dataStream.write(attributeBytes);
      }

      dataStream.flush();
      dataStream.close();

      return byteStream.toByteArray();
    } catch (Exception e) {
      throw new SQLException("Error converting struct to bytes", e);
    }
  }

  private byte[] convertAttributeToBytes(Object attribute) throws IOException {
    if (attribute instanceof String) {
      String strAttribute = (String) attribute;
      return strAttribute.getBytes(StandardCharsets.UTF_8);
    }
    // Handle other attribute types as per your requirements

    throw new IllegalArgumentException("Unsupported attribute type: " + attribute.getClass().getName());
  }
}
