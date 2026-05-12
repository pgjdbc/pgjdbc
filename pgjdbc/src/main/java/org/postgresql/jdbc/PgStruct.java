/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.codec.CompositeCodec;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.Arrays;
import java.util.Map;

/**
 * Implementation of {@link Struct} for PostgreSQL composite types.
 *
 * <p>This class represents a PostgreSQL composite type (struct) value.
 * It can be created via {@link java.sql.Connection#createStruct(String, Object[])}
 * or returned from ResultSet when reading composite type columns.</p>
 */
public class PgStruct implements Struct {

  private final String typeName;
  private final Object[] attributes;
  private final @Nullable BaseConnection connection;

  /**
   * Creates a new PgStruct with the given type name and attributes.
   *
   * @param typeName the SQL type name of the struct
   * @param attributes the attribute values
   */
  public PgStruct(String typeName, Object[] attributes) {
    this(typeName, attributes, null);
  }

  /**
   * Creates a new PgStruct with the given type name, attributes, and connection.
   *
   * @param typeName the SQL type name of the struct
   * @param attributes the attribute values
   * @param connection the connection (used for type mapping)
   */
  public PgStruct(String typeName, Object[] attributes, @Nullable BaseConnection connection) {
    this.typeName = typeName;
    this.attributes = attributes.clone();
    this.connection = connection;
  }

  @Override
  public String getSQLTypeName() throws SQLException {
    return typeName;
  }

  @Override
  public Object[] getAttributes() throws SQLException {
    return attributes.clone();
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object[] getAttributes(@Nullable Map<String, Class<?>> map) throws SQLException {
    if (map == null || map.isEmpty()) {
      return getAttributes();
    }

    // Apply type mapping to nested types in the attributes
    // Note: The map entry for this struct's own type (if present) is ignored here
    // because getAttributes() returns the attribute values, not a converted struct.
    Object[] result = new Object[attributes.length];
    for (int i = 0; i < attributes.length; i++) {
      result[i] = convertAttribute(attributes[i], map);
    }
    return result;
  }

  /**
   * Converts an attribute value according to the type map.
   */
  @SuppressWarnings("unchecked")
  private Object convertAttribute(@Nullable Object attr, Map<String, Class<?>> map) throws SQLException {
    if (attr == null) {
      return null;
    }

    if (attr instanceof Struct) {
      Struct nestedStruct = (Struct) attr;
      String nestedTypeName = nestedStruct.getSQLTypeName();
      Class<?> nestedClass = map.get(nestedTypeName);

      if (nestedClass != null && SQLData.class.isAssignableFrom(nestedClass)) {
        if (connection == null) {
          throw new PSQLException(
              GT.tr("Cannot convert nested struct to SQLData without connection context"),
              PSQLState.OBJECT_NOT_IN_STATE);
        }
        // Convert nested struct to SQLData using CompositeCodec.
        PgType pgType = connection.getTypeInfo().getPgTypeByPgName(nestedTypeName);
        Object[] nestedAttrs = nestedStruct.getAttributes();
        CodecContext ctx = connection.getCodecContext();
        String textValue = CompositeCodec.encodeAttributesAsText(nestedAttrs, pgType, ctx);
        return CompositeCodec.INSTANCE.decodeTextAs(
            textValue, pgType, (Class<? extends SQLData>) nestedClass, ctx);
      }
      // If no mapping, recursively convert nested struct's attributes
      if (nestedStruct instanceof PgStruct) {
        // The nested struct will handle its own attribute conversion
        return new PgStruct(nestedTypeName, nestedStruct.getAttributes(map), connection);
      }
      return attr;
    }

    if (attr instanceof java.sql.Array) {
      // Arrays may contain structs that need conversion
      java.sql.Array array = (java.sql.Array) attr;
      Object arrayObj = array.getArray(map);
      if (arrayObj instanceof Object[]) {
        Object[] elements = (Object[]) arrayObj;
        Object[] converted = new Object[elements.length];
        boolean anyConverted = false;
        for (int i = 0; i < elements.length; i++) {
          converted[i] = convertAttribute(elements[i], map);
          if (converted[i] != elements[i]) {
            anyConverted = true;
          }
        }
        if (anyConverted) {
          return converted;
        }
      }
      return arrayObj;
    }

    return attr;
  }

  @Override
  public String toString() {
    return "PgStruct{typeName='" + typeName + "', attributes=" + Arrays.toString(attributes) + "}";
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PgStruct)) {
      return false;
    }
    PgStruct pgStruct = (PgStruct) o;
    return typeName.equals(pgStruct.typeName) && Arrays.equals(attributes, pgStruct.attributes);
  }

  @Override
  public int hashCode() {
    int result = typeName.hashCode();
    result = 31 * result + Arrays.hashCode(attributes);
    return result;
  }
}
