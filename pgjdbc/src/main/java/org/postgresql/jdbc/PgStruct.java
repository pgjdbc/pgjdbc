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
public class PgStruct extends org.postgresql.util.PGobject implements Struct {

  private final String typeName;
  private final @Nullable Object[] attributes;
  private final @Nullable BaseConnection connection;

  /**
   * Creates a new PgStruct with the given type name and attributes.
   *
   * @param typeName the SQL type name of the struct
   * @param attributes the attribute values
   */
  public PgStruct(String typeName, @Nullable Object[] attributes) {
    this(typeName, attributes, null);
  }

  /**
   * Creates a new PgStruct with the given type name, attributes, and connection.
   *
   * @param typeName the SQL type name of the struct
   * @param attributes the attribute values
   * @param connection the connection (used for type mapping)
   */
  @SuppressWarnings("method.invocation")
  public PgStruct(String typeName, @Nullable Object[] attributes, @Nullable BaseConnection connection) {
    this.typeName = typeName;
    this.attributes = attributes.clone();
    this.connection = connection;
    // Also satisfy the PGobject contract — callers that expect getObject(int)
    // to return a typed PGobject (the legacy contract) get a non-null type
    // string back, and value is left null since the composite text
    // representation isn't materialized here. PGobject.setType is final and
    // only assigns a field, so the partially-initialized 'this' is fine
    // (matches the PGmoney constructor pattern).
    setType(typeName);
  }

  @Override
  public String getSQLTypeName() throws SQLException {
    return typeName;
  }

  @Override
  @SuppressWarnings("override.return")
  public @Nullable Object[] getAttributes() throws SQLException {
    return attributes.clone();
  }

  @Override
  @SuppressWarnings({"unchecked", "override.return"})
  public @Nullable Object[] getAttributes(@Nullable Map<String, Class<?>> map) throws SQLException {
    if (map == null || map.isEmpty()) {
      return getAttributes();
    }

    // Apply type mapping to nested types in the attributes
    // Note: The map entry for this struct's own type (if present) is ignored here
    // because getAttributes() returns the attribute values, not a converted struct.
    @Nullable Object[] result = new @Nullable Object[attributes.length];
    for (int i = 0; i < attributes.length; i++) {
      result[i] = convertAttribute(attributes[i], map);
    }
    return result;
  }

  @Override
  public @Nullable String getValue() {
    String value = super.getValue();
    if (value != null || attributes == null || connection == null) {
      return value;
    }
    try {
      PgType pgType = connection.getTypeInfo().getPgTypeByPgName(typeName);
      CodecContext ctx = connection.getCodecContext();
      String text = CompositeCodec.encodeAttributesAsText(attributes, pgType, ctx);
      super.setValue(text);
      return text;
    } catch (SQLException e) {
      return value;
    }
  }

  /**
   * Converts an attribute value according to the type map.
   */
  @SuppressWarnings("unchecked")
  private @Nullable Object convertAttribute(@Nullable Object attr, Map<String, Class<?>> map) throws SQLException {
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
        @Nullable Object[] converted = new @Nullable Object[elements.length];
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
    String value = getValue();
    return value != null ? value
        : "PgStruct{typeName='" + typeName + "', attributes=" + Arrays.toString(attributes) + "}";
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
